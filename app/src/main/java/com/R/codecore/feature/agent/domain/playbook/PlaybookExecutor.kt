package com.R.codecore.feature.agent.domain.playbook

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.data.local.dao.PlaybookRunDao
import com.R.codecore.feature.agent.data.local.entity.PlaybookRunEntity
import com.R.codecore.feature.agent.data.local.entity.PlaybookRunStatus
import com.R.codecore.feature.agent.data.local.entity.PlaybookStageState
import com.R.codecore.feature.agent.data.local.entity.PlaybookStageStatus
import com.R.codecore.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.R.codecore.feature.agent.domain.tool.mode.PlanApprovalManager
import com.R.codecore.feature.settings.data.repository.NormFlowSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playbook 剧本运行状态机（D5-3，对齐 norm-chain-design.md §3.3.3 / 3.3.4 / 3.3.5 / 3.3.6）：
 *
 * 承接 [PlaybookRunEntity] 的双状态机推进：
 * - **运行级** [PlaybookRunStatus]：RUNNING / COMPLETED / ABORTED / INTERRUPTED；
 * - **阶段级** [PlaybookStageStatus]：PENDING / ACTIVE / DONE / FAILED（存于 stageStatuses JSON）。
 *
 * 核心操作（工具层 playbook_start/advance/status/abort 与 `/playbook` 命令统一走本执行器）：
 * - [start]：按名称精确匹配剧本资产，新建运行（覆盖旧非终态运行），首阶段 ACTIVE。
 * - [advance]：默认作用于本会话最近一次 RUNNING 运行；`done` 推进（当前 DONE → 下一阶段 ACTIVE，
 *   末尾阶段 → COMPLETED），`fail` 置失败中止（当前 FAILED → 运行 ABORTED）。
 * - [resume]：恢复本会话最近一次 INTERRUPTED 运行（运行置 RUNNING，当前阶段保持 ACTIVE）。
 * - [retry]：从本会话最近一次 ABORTED 运行的 FAILED 阶段恢复（该阶段置回 ACTIVE、已完成阶段保留）。
 * - [abort]：中止本会话最近一次 RUNNING 运行（置 ABORTED）。
 *
 * **审批门**（§3.3.3）：阶段 `gates=APPROVAL` 时，激活该阶段前经 [PlanApprovalManager.awaitApproval]
 * 阻塞等用户批准（与模式切换审批同链路）；REFINE（拒绝）→ 该阶段 FAILED + 运行 ABORTED；
 * `!` 标记可跳过此流程级确认（[advance] 的 [skipApproval]，由上层 D5-9 接线）。
 *
 * **完成判定护栏**（§3.3.3）：advance(done) 前若连续 N 轮无实质工具动作就声明完成，
 * 返回 advisory 提醒「确认阶段产出物」，防模型误报完成/跳步（复用 LoopGuard 思路，阈值常量 [IDLE_ROUND_THRESHOLD]）。
 * 实质动作由 workflow 侧经 [recordSubstantiveAction] / [recordIdleRound] 上报（按会话记录，playbook 每会话单活跃运行）。
 *
 * **统一开关**（§3.5，D5-pa）：本执行器为 `/playbook` 斜杠命令 + playbook 工具双入口的公共底座。
 * [start] 入口读**总开关**（norm_flow_enabled）——关闭即 Playbook 运行整体停用（含斜杠命令显式入口）；
 * 模型自主触发（playbook_start 工具）另受 playbook_auto 子开关限制，在工具层拦截（PlaybookStartTool）。
 */
@Singleton
class PlaybookExecutor @Inject constructor(
    private val playbookRunDao: PlaybookRunDao,
    private val playbookRegistry: PlaybookRegistry,
    private val planApprovalManager: PlanApprovalManager,
    private val subAgentRunner: SubAgentRunner,
    private val normFlowSettingsRepository: NormFlowSettingsRepository
) {
    private companion object {
        const val TAG = "PlaybookExecutor"

        /** 完成判定护栏：连续无实质工具动作轮数阈值（§3.3.3，复用 LoopGuard 思路）。 */
        const val IDLE_ROUND_THRESHOLD = 3
    }

    /** 阶段状态 JSON 编解码（encodeDefaults 保证 status/artifacts 默认值也序列化，往返稳定）。 */
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** 完成判定护栏内存计数：sessionId -> 连续无实质动作轮数（playbook 每会话单活跃运行）。 */
    private val idleRounds = ConcurrentHashMap<String, Int>()

    /**
     * 会话级 `!` 强制标记（D5-9，§3.3.3）：用户消息首 token `!` 时置位，
     * 下次 advance(done) 读取后消费——统一跳过流程级 approval gate，永不绕过权限系统。
     * 仅当会话存在 RUNNING 运行才置位（避免无运行残留污染后续剧本的首次推进）。
     */
    private val forceApproval = ConcurrentHashMap<String, Boolean>()

    /**
     * 用户消息首 token `!` 时置位（由 workflow executeEvents 入口解析后调用，D5-9）。
     * 无 RUNNING playbook 运行时不置位（幂等）。
     */
    suspend fun markForceApproval(sessionId: String) {
        if (playbookRunDao.getLatestRunningBySession(sessionId) != null) {
            forceApproval[sessionId] = true
            FileLogger.i(TAG, "markForceApproval: session=$sessionId（`!` 标记跳过 approval gate）")
        }
    }

    /** 读取并消费会话强制标记（advance 内部用；仅本次推进生效）。 */
    private fun consumeForceApproval(sessionId: String): Boolean =
        forceApproval.remove(sessionId) ?: false

    /**
     * 启动剧本：按名称精确匹配资产，新建运行并返回首阶段描述。
     * 若该会话存在旧非终态运行（RUNNING / INTERRUPTED），先置 ABORTED（playbook_start 覆盖旧运行）。
     * @param skipApproval 是否跳过流程级 approval gate（`!` 标记，D5-9；缺省 false 不跳过）。
     */
    suspend fun start(name: String, sessionId: String, skipApproval: Boolean = false): PlaybookOpResult {
        // D5-pa 统一开关：总开关关闭即 Playbook 运行整体停用（§3.5，含 /playbook 显式入口）。
        if (!normFlowSettingsRepository.normFlowEnabledFlow.first()) {
            return PlaybookOpResult.Error("规范流程已关闭（总开关），Playbook 不可用。请先在设置中开启「规范流程」。", "NORM_FLOW_DISABLED")
        }
        val asset = playbookRegistry.findByName(name)
            ?: return PlaybookOpResult.Error(
                message = "未找到剧本「$name」。可用剧本：\n${playbookRegistry.listSummaries()}",
                errorCode = "PLAYBOOK_NOT_FOUND"
            )
        if (asset.stages.isEmpty()) {
            return PlaybookOpResult.Error("剧本「${asset.name}」没有可执行阶段", "PLAYBOOK_NO_STAGES")
        }
        // 覆盖旧运行：会话既有 RUNNING / INTERRUPTED 运行置 ABORTED。
        playbookRunDao.getLatestBySession(sessionId)?.let { existing ->
            if (existing.statusEnum() == PlaybookRunStatus.RUNNING ||
                existing.statusEnum() == PlaybookRunStatus.INTERRUPTED
            ) {
                abortExisting(existing)
            }
        }

        val now = System.currentTimeMillis()
        val stageStates = asset.stages.mapIndexed { i, stage ->
            PlaybookStageState(
                name = stage.name,
                status = if (i == 0) PlaybookStageStatus.ACTIVE.name else PlaybookStageStatus.PENDING.name
            )
        }
        val run = PlaybookRunEntity(
            playbookRunId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            playbookName = asset.name,
            currentStageIndex = 0,
            stageStatuses = encodeStages(stageStates),
            status = PlaybookRunStatus.RUNNING.name,
            createdAtMs = now,
            updatedAtMs = now
        )
        playbookRunDao.upsert(run)
        idleRounds[sessionId] = 0
        FileLogger.i(TAG, "start: session=$sessionId playbook=${asset.name} 阶段=${asset.stages.size}")

        val first = asset.stages.first()
        // D5-6/7：首阶段激活时若声明 agents[]，同步执行真子代理隔离上下文（§3.6 阶段激活自动生成）。
        val subAgents = runStageSubAgents(sessionId, first)
        return PlaybookOpResult.Stage(
            view = stageView(asset.name, asset.stages, 0),
            message = "已启动剧本「${asset.name}」，共 ${asset.stages.size} 个阶段。\n" +
                "【当前阶段 ${1}/${asset.stages.size}】${first.name}：${first.description}" +
                stageSuffix(first) + subAgentSummary(subAgents)
        )
    }

    /**
     * 推进/中止当前阶段（§3.3.3 / §3.3.5）。
     * @param action done（当前 DONE → 推进）| fail（当前 FAILED → 运行 ABORTED）。
     * @param runId 指定运行；null 时默认作用于本会话最近一次 RUNNING 运行（模型无需管理 runId）。
     * @param artifacts 阶段 DONE 时记录的本阶段产物清单（D5-8，resume/retry 时注入对照跳过已完成操作）。
     * @param skipApproval 是否跳过流程级 approval gate（`!` 标记，D5-9）。
     */
    suspend fun advance(
        sessionId: String,
        action: String,
        runId: String? = null,
        artifacts: List<String> = emptyList(),
        skipApproval: Boolean = false
    ): PlaybookOpResult {
        val run = resolveRunning(sessionId, runId)
            ?: return PlaybookOpResult.Error(
                "当前没有正在进行的剧本运行" + if (runId != null) "（$runId）" else "，请先 playbook_start 启动",
                "PLAYBOOK_NOT_RUNNING"
            )
        val asset = playbookRegistry.findByName(run.playbookName)
            ?: return PlaybookOpResult.Error("剧本资产「${run.playbookName}」不存在", "PLAYBOOK_NOT_FOUND")
        if (run.currentStageIndex >= asset.stages.size) {
            return PlaybookOpResult.Error("运行已超出剧本阶段范围", "PLAYBOOK_STAGE_RANGE")
        }
        val stageStates = decodeStages(run.stageStatuses)
        val current = stageStates.getOrNull(run.currentStageIndex)

        // D5-9：`!` 标记跳过 approval gate——用户消息首 token `!` 置位后，本次推进消费（读后清除）。
        // 显式参数优先，其次会话强制标记；均永不绕过权限系统。
        val effectiveSkipApproval = skipApproval || consumeForceApproval(sessionId)

        return when (action.trim().lowercase()) {
            "done" -> advanceDone(sessionId, run, asset, stageStates, artifacts, effectiveSkipApproval)
            "fail" -> advanceFail(sessionId, run, asset, stageStates, current)
            else -> PlaybookOpResult.Error("未知推进动作: $action（合法值 done/fail）", "INVALID_ACTION")
        }
    }

    /** 恢复本会话最近一次 INTERRUPTED 运行（进程回收 / SessionStop 中断后继续）。 */
    suspend fun resume(sessionId: String): PlaybookOpResult {
        val run = playbookRunDao.getLatestInterruptedBySession(sessionId)
            ?: return PlaybookOpResult.Error("当前会话没有可恢复的中断运行，请先 playbook_start", "PLAYBOOK_NO_INTERRUPTED")
        val asset = playbookRegistry.findByName(run.playbookName)
            ?: return PlaybookOpResult.Error("剧本资产「${run.playbookName}}」不存在", "PLAYBOOK_NOT_FOUND")
        if (run.currentStageIndex >= asset.stages.size) {
            return PlaybookOpResult.Error("运行已超出剧本阶段范围", "PLAYBOOK_STAGE_RANGE")
        }
        val stageStates = decodeStages(run.stageStatuses)
        // 中断发生在阶段执行中：运行置 RUNNING，当前阶段保持 ACTIVE。
        val now = System.currentTimeMillis()
        playbookRunDao.upsert(run.copy(status = PlaybookRunStatus.RUNNING.name, updatedAtMs = now))
        idleRounds[sessionId] = 0
        FileLogger.i(TAG, "resume: session=$sessionId playbook=${asset.name} 阶段=${run.currentStageIndex}")

        val stage = asset.stages[run.currentStageIndex]
        // D5-6/7：恢复的阶段若声明 agents[]，同步重跑子代理（§3.6 阶段激活自动生成；产出按内容写入幂等）。
        val subAgents = runStageSubAgents(sessionId, stage)
        return PlaybookOpResult.Stage(
            view = stageView(asset.name, asset.stages, run.currentStageIndex),
            message = "已恢复剧本「${asset.name}」。\n【当前阶段 ${run.currentStageIndex + 1}/${asset.stages.size}】" +
                "${stage.name}：${stage.description}${stageSuffix(stage)}" +
                artifactsHint(stageStates, run.currentStageIndex) + subAgentSummary(subAgents)
        )
    }

    /** 从本会话最近一次 ABORTED 运行的 FAILED 阶段恢复（该阶段置回 ACTIVE，已完成阶段保留）。 */
    suspend fun retry(sessionId: String): PlaybookOpResult {
        val run = playbookRunDao.getLatestAbortedBySession(sessionId)
            ?: return PlaybookOpResult.Error("当前会话没有可重试的失败运行，请先 playbook_start", "PLAYBOOK_NO_ABORTED")
        val asset = playbookRegistry.findByName(run.playbookName)
            ?: return PlaybookOpResult.Error("剧本资产「${run.playbookName}}」不存在", "PLAYBOOK_NOT_FOUND")
        val stageStates = decodeStages(run.stageStatuses)
        // 定位 FAILED 阶段：若 currentStageIndex 指向 FAILED 则从该阶段恢复，否则回退找最近 FAILED。
        val failIndex = stageStates.indexOfFirst { it.status == PlaybookStageStatus.FAILED.name }
            .takeIf { it >= 0 } ?: run.currentStageIndex
        if (failIndex >= asset.stages.size) {
            return PlaybookOpResult.Error("运行已超出剧本阶段范围", "PLAYBOOK_STAGE_RANGE")
        }
        val restored = stageStates.mapIndexed { i, s ->
            if (i == failIndex) s.copy(status = PlaybookStageStatus.ACTIVE.name) else s
        }
        val now = System.currentTimeMillis()
        playbookRunDao.upsert(
            run.copy(
                status = PlaybookRunStatus.RUNNING.name,
                currentStageIndex = failIndex,
                stageStatuses = encodeStages(restored),
                updatedAtMs = now
            )
        )
        idleRounds[sessionId] = 0
        FileLogger.i(TAG, "retry: session=$sessionId playbook=${asset.name} 从 FAILED 阶段 $failIndex 恢复")

        val stage = asset.stages[failIndex]
        // D5-6/7：恢复的阶段若声明 agents[]，同步重跑子代理（§3.6 阶段激活自动生成）。
        val subAgents = runStageSubAgents(sessionId, stage)
        return PlaybookOpResult.Stage(
            view = stageView(asset.name, asset.stages, failIndex),
            message = "已从失败阶段恢复剧本「${asset.name}」（已完成阶段保留）。\n" +
                "【当前阶段 ${failIndex + 1}/${asset.stages.size}】${stage.name}：${stage.description}${stageSuffix(stage)}" +
                artifactsHint(restored, failIndex) + subAgentSummary(subAgents)
        )
    }

    /** 中止本会话最近一次 RUNNING 运行（置 ABORTED）。 */
    suspend fun abort(sessionId: String, runId: String? = null): PlaybookOpResult {
        val run = resolveRunning(sessionId, runId)
            ?: return PlaybookOpResult.Error("当前没有正在进行的剧本运行，无需中止", "PLAYBOOK_NOT_RUNNING")
        val now = System.currentTimeMillis()
        playbookRunDao.upsert(run.copy(status = PlaybookRunStatus.ABORTED.name, updatedAtMs = now))
        idleRounds.remove(sessionId)
        FileLogger.i(TAG, "abort: session=$sessionId playbook=${run.playbookName}")
        return PlaybookOpResult.Ok(
            message = "剧本「${run.playbookName}」已中止。可 playbook_start 从头重跑，或 playbook_retry 从失败阶段恢复。",
            playbookName = run.playbookName
        )
    }

    /**
     * 会话停止（SessionStop）时把该会话 RUNNING 运行置 INTERRUPTED（D5-8，对齐 JobEntity 语义，
     * §3.3.6 中断/恢复）。非终态但可继续：新会话需显式 playbook_resume 继续，playbook_start 覆盖旧运行。
     * 无 RUNNING 运行或已有更旧运行时空操作（幂等）。
     */
    suspend fun interrupt(sessionId: String) {
        val run = playbookRunDao.getLatestRunningBySession(sessionId) ?: return
        val now = System.currentTimeMillis()
        playbookRunDao.upsert(run.copy(status = PlaybookRunStatus.INTERRUPTED.name, updatedAtMs = now))
        idleRounds.remove(sessionId)
        FileLogger.i(TAG, "interrupt: session=$sessionId playbook=${run.playbookName}（SessionStop）")
    }

    /** 查询本会话最近一次运行的状态（playbook_status / PlaybookStageSource 用）。 */
    suspend fun status(sessionId: String): PlaybookRunEntity? =
        playbookRunDao.getLatestBySession(sessionId)

    /** 本会话最近一次运行的人类可读状态文本（/playbook status 用）。 */
    suspend fun statusText(sessionId: String): String {
        val run = playbookRunDao.getLatestBySession(sessionId)
            ?: return "当前会话没有剧本运行记录"
        val stageStates = decodeStages(run.stageStatuses)
        return buildString {
            appendLine("剧本「${run.playbookName}」 状态=${run.statusEnum().name.lowercase()}")
            if (stageStates.isEmpty()) {
                appendLine("（无阶段状态记录）")
            } else {
                stageStates.forEachIndexed { i, s ->
                    val mark = if (i == run.currentStageIndex) "→ " else "  "
                    append(mark).append("阶段 ${i + 1}. ${s.name} [${s.status.lowercase()}]")
                    if (s.artifacts.isNotEmpty()) append(" 产物: ${s.artifacts.joinToString()}")
                    appendLine()
                }
            }
        }
    }

    /** 本会话最近一次 RUNNING 运行的当前阶段视图（无运行返回 null）。 */
    suspend fun currentStageView(sessionId: String): PlaybookStageView? {
        val run = playbookRunDao.getLatestRunningBySession(sessionId) ?: return null
        val asset = playbookRegistry.findByName(run.playbookName) ?: return null
        if (run.currentStageIndex >= asset.stages.size) return null
        return stageView(asset.name, asset.stages, run.currentStageIndex)
    }

    // ── 完成判定护栏（§3.3.3）：workflow 侧按轮上报实质动作/空转 ──

    /** 记录本会话当前 Playbook 活跃运行的一轮实质工具动作（文件写/命令执行），清零空转计数。 */
    fun recordSubstantiveAction(sessionId: String) {
        idleRounds[sessionId] = 0
    }

    /** 记录本会话当前 Playbook 活跃运行的一轮无实质工具动作（空转），累计计数。 */
    fun recordIdleRound(sessionId: String) {
        idleRounds[sessionId] = (idleRounds[sessionId] ?: 0) + 1
    }

    // ── 内部推进逻辑 ──

    private suspend fun advanceDone(
        sessionId: String,
        run: PlaybookRunEntity,
        asset: PlaybookAsset,
        stageStates: List<PlaybookStageState>,
        artifacts: List<String>,
        skipApproval: Boolean
    ): PlaybookOpResult {
        val currentIndex = run.currentStageIndex
        val current = stageStates[currentIndex]
        // 完成判定护栏：连续 N 轮无实质动作就声明完成 → advisory 提醒确认产出物，不推进。
        if ((idleRounds[sessionId] ?: 0) >= IDLE_ROUND_THRESHOLD) {
            return PlaybookOpResult.Advisory(
                message = "你已连续 $IDLE_ROUND_THRESHOLD 轮没有实质工具动作（无文件写/命令执行）就声明完成" +
                    "「${current.name}」阶段。请先确认本阶段产出物是否真实完成（可读取/检查文件验证），再决定是否推进。",
                view = stageView(asset.name, asset.stages, currentIndex)
            )
        }
        val isLast = currentIndex >= asset.stages.size - 1
        if (isLast) {
            // 末尾阶段 DONE → 运行 COMPLETED。
            val finalStates = stageStates.mapIndexed { i, s ->
                when {
                    i == currentIndex -> s.copy(status = PlaybookStageStatus.DONE.name, artifacts = artifacts)
                    else -> s
                }
            }
            val now = System.currentTimeMillis()
            playbookRunDao.upsert(
                run.copy(
                    status = PlaybookRunStatus.COMPLETED.name,
                    stageStatuses = encodeStages(finalStates),
                    updatedAtMs = now
                )
            )
            idleRounds.remove(sessionId)
            FileLogger.i(TAG, "advance(done): session=$sessionId playbook=${asset.name} 全部阶段完成")
            return PlaybookOpResult.Completed(
                message = "剧本「${asset.name}」全部 ${asset.stages.size} 个阶段已完成。" +
                    artifactsSummary(artifacts)
            )
        }

        // 非末尾：当前 DONE → 下一阶段 ACTIVE。先过下一阶段 approval gate（§3.3.3）。
        val nextIndex = currentIndex + 1
        val nextStage = asset.stages[nextIndex]
        if (nextStage.gates == PlaybookGate.APPROVAL && !skipApproval) {
            val approved = awaitStageApproval(sessionId, run.playbookName, nextStage)
            if (!approved) {
                // 审批拒绝：当前 DONE，下一阶段 FAILED，运行 ABORTED。
                val rejectedStates = stageStates.mapIndexed { i, s ->
                    when {
                        i == currentIndex -> s.copy(status = PlaybookStageStatus.DONE.name, artifacts = artifacts)
                        i == nextIndex -> s.copy(status = PlaybookStageStatus.FAILED.name)
                        else -> s
                    }
                }
                val now = System.currentTimeMillis()
                playbookRunDao.upsert(
                    run.copy(
                        status = PlaybookRunStatus.ABORTED.name,
                        currentStageIndex = nextIndex,
                        stageStatuses = encodeStages(rejectedStates),
                        updatedAtMs = now
                    )
                )
                idleRounds.remove(sessionId)
                return PlaybookOpResult.Aborted(
                    message = "用户拒绝了阶段「${nextStage.name}」的审批，剧本「${run.playbookName}」已中止。" +
                        "可 playbook_start 从头重跑，或 playbook_retry 从失败阶段恢复。"
                )
            }
        }

        val advancedStates = stageStates.mapIndexed { i, s ->
            when {
                i == currentIndex -> s.copy(status = PlaybookStageStatus.DONE.name, artifacts = artifacts)
                i == nextIndex -> s.copy(status = PlaybookStageStatus.ACTIVE.name)
                else -> s
            }
        }
        val now = System.currentTimeMillis()
        playbookRunDao.upsert(
            run.copy(
                currentStageIndex = nextIndex,
                stageStatuses = encodeStages(advancedStates),
                updatedAtMs = now
            )
        )
        idleRounds[sessionId] = 0
        FileLogger.i(TAG, "advance(done): session=$sessionId playbook=${asset.name} ${current.name} → ${nextStage.name}")

        // D5-6/7：下一阶段激活时若声明 agents[]，同步执行真子代理隔离上下文（§3.6 阶段激活自动生成）。
        val subAgents = runStageSubAgents(sessionId, nextStage)
        return PlaybookOpResult.Stage(
            view = stageView(asset.name, asset.stages, nextIndex),
            message = "阶段「${current.name}」完成。\n【当前阶段 ${nextIndex + 1}/${asset.stages.size}】" +
                "${nextStage.name}：${nextStage.description}${stageSuffix(nextStage)}" +
                artifactsSummary(artifacts) + subAgentSummary(subAgents)
        )
    }

    private suspend fun advanceFail(
        sessionId: String,
        run: PlaybookRunEntity,
        asset: PlaybookAsset,
        stageStates: List<PlaybookStageState>,
        current: PlaybookStageState?
    ): PlaybookOpResult {
        val failedStates = stageStates.mapIndexed { i, s ->
            if (i == run.currentStageIndex) s.copy(status = PlaybookStageStatus.FAILED.name) else s
        }
        val now = System.currentTimeMillis()
        playbookRunDao.upsert(
            run.copy(
                status = PlaybookRunStatus.ABORTED.name,
                stageStatuses = encodeStages(failedStates),
                updatedAtMs = now
            )
        )
        idleRounds.remove(sessionId)
        FileLogger.i(TAG, "advance(fail): session=$sessionId playbook=${asset.name} 阶段 ${current?.name} 失败")
        return PlaybookOpResult.Aborted(
            message = "阶段「${current?.name ?: "（未知）"}」声明失败，剧本「${asset.name}」已中止。" +
                "可 playbook_start 从头重跑，或 playbook_retry 从失败阶段恢复。"
        )
    }

    /** approval gate：阻塞等用户批准（复用模式切换审批链路）。 */
    private suspend fun awaitStageApproval(sessionId: String, playbookName: String, stage: PlaybookStage): Boolean {
        val choice = planApprovalManager.awaitApproval(
            reason = "剧本「$playbookName」进入阶段「${stage.name}」需要你批准。\n阶段目标：${stage.description}",
            sessionId = sessionId
        )
        return choice == PlanApprovalChoice.APPROVE
    }

    private suspend fun resolveRunning(sessionId: String, runId: String?): PlaybookRunEntity? {
        if (runId != null) {
            return playbookRunDao.getById(runId)?.takeIf { it.sessionId == sessionId && it.statusEnum() == PlaybookRunStatus.RUNNING }
        }
        return playbookRunDao.getLatestRunningBySession(sessionId)
    }

    private suspend fun abortExisting(existing: PlaybookRunEntity) {
        playbookRunDao.upsert(
            existing.copy(
                status = PlaybookRunStatus.ABORTED.name,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    private fun stageView(playbookName: String, stages: List<PlaybookStage>, index: Int): PlaybookStageView {
        val stage = stages[index]
        return PlaybookStageView(
            playbookName = playbookName,
            stageIndex = index,
            stageCount = stages.size,
            stageName = stage.name,
            stageDescription = stage.description,
            gate = stage.gates,
            agents = stage.agents
        )
    }

    /** 阶段推进提示后缀：approval 门 / 子代理 / SOP 等声明，让模型知道本阶段约束。 */
    private fun stageSuffix(stage: PlaybookStage): String {
        val parts = mutableListOf<String>()
        if (stage.gates == PlaybookGate.APPROVAL) parts.add("进入前需用户批准（gates=approval）")
        if (stage.agents.isNotEmpty()) parts.add("专项子代理: ${stage.agents.joinToString()}")
        if (stage.sop.isNotEmpty()) parts.add("SOP 参考: ${stage.sop.joinToString()}")
        return if (parts.isEmpty()) "" else "\n（${parts.joinToString("；")}）"
    }

    /** 产物清单摘要（阶段 DONE 记录，D5-8）。 */
    private fun artifactsSummary(artifacts: List<String>): String {
        if (artifacts.isEmpty()) return ""
        return "\n本阶段产物：\n" + artifacts.joinToString("\n") { "- $it" }
    }

    /**
     * 阶段激活时同步执行子代理（D5-6/7，§3.6.1 阶段激活自动生成 / §3.6.3 默认同步）。
     * 仅当阶段声明 `agents[]` 非空时执行；无 agents 返回 null（不附加子代理结果）。
     * 子代理执行失败（无 provider / 调用异常）已由 [SubAgentRunner] 内聚为 FAILED 结果，不阻断阶段推进。
     */
    private suspend fun runStageSubAgents(sessionId: String, stage: PlaybookStage): SubAgentRunner.StageSubAgentResult? {
        if (stage.agents.isEmpty()) return null
        return try {
            subAgentRunner.runStage(sessionId, stage, stage.description)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "阶段子代理执行失败（不阻断阶段推进）: ${stage.name}", e)
            null
        }
    }

    /** 子代理聚合结果 → 返回消息附加文本；无结果返回空串。 */
    private fun subAgentSummary(result: SubAgentRunner.StageSubAgentResult?): String {
        if (result == null || result.agents.isEmpty()) return ""
        return "\n\n" + result.summary
    }

    /** 恢复/重试时把已完成阶段的产物清单注入，供模型对照跳过已完成操作（D5-8 幂等纪律）。 */
    private fun artifactsHint(stageStates: List<PlaybookStageState>, currentIndex: Int): String {
        val done = stageStates
            .mapIndexed { i, s -> s to i }
            .filter { (s, i) -> i < currentIndex && s.status == PlaybookStageStatus.DONE.name }
            .flatMap { (s, _) -> s.artifacts }
            .distinct()
        if (done.isEmpty()) return ""
        return "\n已完成阶段产物（供对照跳过已完成操作）：\n" + done.joinToString("\n") { "- $it" }
    }

    private fun encodeStages(stages: List<PlaybookStageState>): String = json.encodeToString(stages)

    private fun decodeStages(raw: String): List<PlaybookStageState> {
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<PlaybookStageState>>(raw) }
            .getOrElse { e ->
                FileLogger.w(TAG, "解析阶段状态失败，回退空列表: ${e.message}")
                emptyList()
            }
    }
}

/** Playbook 执行结果（工具层据此转 ToolResult.Success / ToolResult.Error）。 */
sealed class PlaybookOpResult {
    /** 用户可读的结果正文（各分支统一承载，ViewModel/命令可直接取用）。 */
    abstract val message: String

    /** 常规成功（返回当前/下一阶段视图）。 */
    data class Stage(val view: PlaybookStageView, override val message: String = "") : PlaybookOpResult()

    /** 无阶段可展示的简单成功（如 abort 完成）。 */
    data class Ok(override val message: String, val playbookName: String = "") : PlaybookOpResult()

    /** 全部阶段完成（运行 COMPLETED）。 */
    data class Completed(override val message: String) : PlaybookOpResult()

    /** 运行中止（阶段失败 / 审批拒绝 / abort）。 */
    data class Aborted(override val message: String) : PlaybookOpResult()

    /** 完成判定护栏 advisory：提醒确认阶段产出物，不推进。 */
    data class Advisory(override val message: String, val view: PlaybookStageView) : PlaybookOpResult()

    /** 失败（无状态变更）。 */
    data class Error(override val message: String, val errorCode: String = "PLAYBOOK_ERROR") : PlaybookOpResult()
}

/** Playbook 当前阶段视图（状态/注入/工具返回共用，工具层序列化为 JSON）。 */
data class PlaybookStageView(
    val playbookName: String,
    val stageIndex: Int,
    val stageCount: Int,
    val stageName: String,
    val stageDescription: String,
    val gate: PlaybookGate,
    val agents: List<String> = emptyList()
)
