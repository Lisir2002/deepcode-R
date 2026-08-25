package com.R.codecore.feature.agent.domain.prompt

import com.R.codecore.feature.agent.domain.input.BehaviorModeSource
import com.R.codecore.feature.agent.domain.input.GoalAdjustEventSource
import com.R.codecore.feature.agent.domain.input.GoalHintSource
import com.R.codecore.feature.agent.domain.input.GoalStaleSource
import com.R.codecore.feature.agent.domain.input.IntentAskSource
import com.R.codecore.feature.agent.domain.input.LoopAdvisorySource
import com.R.codecore.feature.agent.domain.input.PlanPendingHintSource
import com.R.codecore.feature.agent.domain.input.PlaybookStageSource
import com.R.codecore.feature.agent.domain.memory.MemoryRepository
import com.R.codecore.feature.agent.domain.memory.MemoryScope
import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.model.AgentMode
import com.R.codecore.feature.agent.domain.skill.SkillStateRepository
import com.R.codecore.feature.agent.domain.skill.SkillType
import com.R.codecore.feature.agent.domain.workflow.LoopGuardTracker
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 阶段二优化：增量式提示词更新 (SystemContext 化)
 * 拆分为独立的 Source 模块，并且为每个 Source 维护缓存和快照。
 * 对于高频变化的 WorkspaceSource，提供增量 Diff 逻辑，最大化节省冗余 Token，提升大模型 KV Cache 命中率。
 *
 * step 前注入（D1-1/2）：另维护 8 个已登记 Source 的注入链（[buildStepInjections]），
 * 由 workflow 每轮 CallLlm 前喂入动态数据（goal / plan-pending / loop tracker / stale / event）后调用，
 * 经 [StepInjectionAssembler] 做八源排序 + 注入预算裁剪（P0 永不裁），对齐 norm-chain §3.1.2。
 */
@Singleton
class SystemPromptProvider @Inject constructor(
    private val skillStateRepository: SkillStateRepository,
    private val memoryRepository: MemoryRepository,
    private val agentAssetRegistry: AgentAssetRegistry,
    // D1-1：step 前注入 8 Source 一次登记（D0 的 4 个 Source 一并纳入，含 D0-3 问判 / D0-6 行为模式 / D0-7 GoalStale + GoalAdjustEvent）
    private val goalHintSource: GoalHintSource,
    private val intentAskSource: IntentAskSource,
    private val behaviorModeSource: BehaviorModeSource,
    private val planPendingHintSource: PlanPendingHintSource,
    private val playbookStageSource: PlaybookStageSource,
    private val goalAdjustEventSource: GoalAdjustEventSource,
    private val goalStaleSource: GoalStaleSource,
    private val loopAdvisorySource: LoopAdvisorySource
) {
    // 抽象独立的 Source
    interface PromptSource {
        fun build(ctx: AgentContext): String?
    }

    /**
     * 静态规则 Source（R04 起由 [AgentAssetRegistry] 驱动）：
     * - 主 agent（currentAgentId == null）：注入 enabled 且 agent:false 的组件资产，按 mode 过滤
     *   （mode 含 "default" 恒注入；含当前模式关键字时在对应模式注入，替代旧 PlanModeSource/AutoModeSource），
     *   按 order 排序、includes 已展开。
     * - 专项 agent（currentAgentId 存在且命中）：正文整体替换为主 agent 装配结果（design 8.5）。
     * 装配结果由 registry 内部缓存（mtime + FileObserver），此处不再单独缓存。
     */
    private inner class StaticRuleSource : PromptSource {
        override fun build(ctx: AgentContext): String {
            val all = agentAssetRegistry.all()
            val currentAgent = ctx.currentAgentId?.let { id ->
                all.firstOrNull { it.enabled && it.agent && it.name == id }
            }
            if (currentAgent != null) {
                return currentAgent.body
            }
            val modeKey = when (ctx.mode) {
                AgentMode.PLAN -> "plan"
                AgentMode.AUTO -> "auto"
                else -> "default"
            }
            return all.filter {
                it.enabled && !it.agent &&
                    (it.modes.contains("default") || it.modes.contains(modeKey))
            }
                .sortedBy { it.order }
                .joinToString("\n\n") { it.body }
        }
    }

    private inner class ActiveSkillsSource : PromptSource {
        // 可见技能清单按会话不同（作用域/对话级控制），缓存需按 sessionId 区分，避免跨会话泄漏。
        @Volatile private var cached: String? = null
        @Volatile private var cachedSession: String? = null

        override fun build(ctx: AgentContext): String? {
            // 每轮实时扫描磁盘，如有新增立即生效。这里为了避免每次大体积反序列化造成开销，可以简单做内存对比。
            // 作用域严格隐藏（D7）：仅列出「当前 agent + 当前会话」可见且已启用的技能
            // （GLOBAL 且未被对话内临时禁用 / AGENT 匹配当前 agent / CONVERSATION 已显式添加）。
            val skills = try {
                skillStateRepository.listSkillsSync().let { list ->
                    skillStateRepository.filterVisibleSkillsSync(list, ctx.sessionId)
                }
            } catch (e: Exception) { return null }
            if (skills.isEmpty()) return null
            
            val list = skills.joinToString("\n") { skill ->
                val hint = when (skill.type) {
                    SkillType.PROMPT -> "（PROMPT 指令技能：用 loadSkill 取正文）"
                    SkillType.SCRIPT -> "（SCRIPT 脚本技能：loadSkill 读正文 / runSkillScript 执行）"
                    SkillType.MCP -> "（MCP 包装技能：直接调用工具 ${skill.mcpTool ?: "（未绑定）"}）"
                }
                "- ${skill.name}: ${skill.description.ifBlank { "（无描述）" }}$hint"
            }
            val newContent = "可用技能 (skills)（格式为 名称: 何时使用 + 调用方式；详见上文「技能」说明）：\n" +
                "技能正文用 `loadSkill` 加载（PROMPT/SCRIPT 均可，仅返回 SKILL.md 正文、不执行）；" +
                "脚本类（SCRIPT）技能需要实际执行时用 `runSkillScript`（执行前会征求用户确认）；" +
                "MCP 包装技能不执行、直接调用其绑定的 MCP 工具。让技能辅助你更规范、更高效地完成工作，而不是仅凭默认流程硬做。\n$list"
            
            // 同一会话内内容未变化时复用缓存快照；会话切换（或内容变化）时重建，避免跨会话串味。
            if (cachedSession != ctx.sessionId || cached != newContent) {
                cached = newContent
                cachedSession = ctx.sessionId
            }
            return cached
        }
    }

    private inner class ProjectRuleSource : PromptSource {
        @Volatile private var cached: String? = null
        private var lastModified: Long = 0
        private var lastProjectRoot: String = ""

        override fun build(ctx: AgentContext): String? {
            if (ctx.projectRoot.isBlank()) return null
            val agentsFile = File(ctx.projectRoot, AGENTS_FILE)
            val claudeFile = File(ctx.projectRoot, CLAUDE_FILE)
            val file = when {
                agentsFile.isFile && agentsFile.canRead() -> agentsFile to AGENTS_FILE
                claudeFile.isFile && claudeFile.canRead() -> claudeFile to CLAUDE_FILE
                else -> return null
            }
            
            val currentMod = file.first.lastModified()
            // 如果文件未修改且路径一致，直接返回快照基线，避免重复读取与格式化
            if (ctx.projectRoot == lastProjectRoot && currentMod == lastModified && cached != null) {
                return cached
            }
            
            val text = try { file.first.readText() } catch (e: Exception) { return null }
            if (text.isBlank()) return null
            
            val body = if (text.length > MAX_AGENTS_CHARS) {
                text.take(MAX_AGENTS_CHARS) + "\n…（${file.second} 过长，已截断）"
            } else {
                text
            }
            cached = "项目规则 (来自 ~/workspace/${file.second}，务必遵守):\n${body.trim()}"
            lastModified = currentMod
            lastProjectRoot = ctx.projectRoot
            return cached
        }
    }

    private inner class WorkspaceSource : PromptSource {
        override fun build(ctx: AgentContext): String {
            val hasWorkspace = ctx.projectRoot.isNotBlank()
            return """
                当前上下文:
                - 项目根目录: ${if (hasWorkspace) "~/workspace" else "（未选择工作区）"}
                - 当前文件: ${ctx.currentFile ?: "无"}
                - 选中的代码: ${ctx.selectedCode ?: "无"}
                - 编程语言: ${ctx.language ?: "未知"}
            """.trimIndent()
        }
    }

    private inner class CurrentTimeSource : PromptSource {
        override fun build(ctx: AgentContext): String {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
            val currentTime = java.time.ZonedDateTime.now().format(formatter)
            return "[System] 当前本地时间: $currentTime"
        }
    }

    // 会话级别的快照缓存 (Baseline & Snapshot)
    data class SessionSnapshot(
        val lastWorkspaceContext: String = ""
    )
    private val sessionSnapshots = ConcurrentHashMap<String, SessionSnapshot>()

    private inner class MemoryListSource : PromptSource {
        @Volatile private var cached: String? = null

        override fun build(ctx: AgentContext): String? {
            val memories = try { memoryRepository.listMemories(ctx.projectRoot) } catch (e: Exception) { return null }
            if (memories.isEmpty()) return null
            
            val globalMemories = memories.filter { it.scope == MemoryScope.GLOBAL }
            val projectMemories = memories.filter { it.scope == MemoryScope.PROJECT }
            
            val newContent = buildString {
                if (globalMemories.isNotEmpty()) {
                    append("全局记忆 (跨项目个人偏好，需要详情时用 memory(action=read, name=xxx, scope=global))：\n")
                    globalMemories.forEach { append("- ${it.name}: ${it.description.ifBlank { "无" }}\n") }
                }
                if (projectMemories.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("项目记忆 (当前项目专属，需要详情时用 memory(action=read, name=xxx, scope=project))：\n")
                    projectMemories.forEach { append("- ${it.name}: ${it.description.ifBlank { "无" }}\n") }
                }
            }.trimEnd()
            
            if (cached != newContent) {
                cached = newContent
            }
            return cached
        }
    }

    private val staticRuleSource = StaticRuleSource()
    private val memoryListSource = MemoryListSource()
    private val activeSkillsSource = ActiveSkillsSource()
    private val projectRuleSource = ProjectRuleSource()
    private val workspaceSource = WorkspaceSource()
    private val currentTimeSource = CurrentTimeSource()

    fun build(agentContext: AgentContext): String {
        // 1. 获取各个 Source 的基线快照。
        // PLAN/AUTO 模式提示词由 StaticRuleSource 按 mode 字段注入（R04 起），紧随静态规则之后，确保模型优先注意到模式约束
        val staticContent = staticRuleSource.build(agentContext)
        val skillsContent = activeSkillsSource.build(agentContext)
        val memoriesContent = memoryListSource.build(agentContext)
        val projectRules = projectRuleSource.build(agentContext)
        
        // 2. 增量 Diff 处理 (仅针对高频变化的 Workspace)
        val currentWorkspaceContext = workspaceSource.build(agentContext)
        val sessionId = agentContext.sessionId ?: "default"
        
        val snapshot = sessionSnapshots[sessionId] ?: SessionSnapshot()
        
        val effectiveWorkspaceContent = if (snapshot.lastWorkspaceContext == currentWorkspaceContext) {
            // 没有变化，维持基线不变，仅向大模型注入极简指令，大幅降低重复 Token 处理
            "当前上下文: [未发生变化，请参考之前的会话记忆]"
        } else {
            // 发生变化，输出完整内容并更新本次快照
            sessionSnapshots[sessionId] = snapshot.copy(lastWorkspaceContext = currentWorkspaceContext)
            currentWorkspaceContext
        }

        // 3. 组装最终提示词：把稳定不变的重头基线放最前面（享受 KV Cache），变化部分放末尾
        // PLAN/AUTO 模式约束已并入静态规则（R04 起按 mode 字段注入），无需单独追加
        return buildString {
            append(staticContent)

            skillsContent?.let {
                append("\n\n")
                append(it)
            }

            memoriesContent?.let {
                append("\n\n")
                append(it)
            }

            projectRules?.let {
                append("\n\n")
                append(it)
            }

            append("\n\n")
            append(effectiveWorkspaceContent)
            append("\n\n")
            append(currentTimeSource.build(agentContext))
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // D1-1/2 step 前注入（对齐 norm-chain §3.1.2）：
    // 8 Source 一次登记 → 每轮喂入动态数据 → buildStepInjections 组装注入块
    // （八源排序 + 注入预算裁剪，P0 永不裁）。
    // ════════════════════════════════════════════════════════════════════

    /** step 前注入源条目（含 importance 分级 + 八源固定顺序）。 */
    private data class StepSource(
        val source: PromptSource,
        val importance: StepInjectionAssembler.Importance,
        /** 同 importance 内绝对注入顺序（八源排序，理解优先）。 */
        val order: Int
    )

    /** 注入预算装配器：八源排序 + 预算裁剪（P0 永不裁）。 */
    private val stepAssembler = StepInjectionAssembler()

    /**
     * step 前注入 8 Source 一次登记（D1-1）：
     * 顺序（审计定稿，理解优先）：goal(P0) → 问判注入(P1) → 行为模式(P1) → plan-pending(P1)
     * → playbook-stage(P1) → GoalAdjustEvent(P1) → GoalStale(P2) → loop-advisory(P2)。
     */
    private val stepSources: List<StepSource> = listOf(
        StepSource(goalHintSource, StepInjectionAssembler.Importance.P0, 0),
        StepSource(intentAskSource, StepInjectionAssembler.Importance.P1, 1),
        StepSource(behaviorModeSource, StepInjectionAssembler.Importance.P1, 2),
        StepSource(planPendingHintSource, StepInjectionAssembler.Importance.P1, 3),
        StepSource(playbookStageSource, StepInjectionAssembler.Importance.P1, 4),
        StepSource(goalAdjustEventSource, StepInjectionAssembler.Importance.P1, 5),
        StepSource(goalStaleSource, StepInjectionAssembler.Importance.P2, 6),
        StepSource(loopAdvisorySource, StepInjectionAssembler.Importance.P2, 7)
    ).sortedBy { it.order }

    /** 喂入当前任务目标（workflow 每轮 CallLlm 前调用；null 清除）。 */
    fun feedGoal(sessionId: String, goalText: String?) {
        goalHintSource.feed(sessionId, goalText)
    }

    /** 喂入待批准计划选择（workflow 每轮 CallLlm 前调用；null 清除）。 */
    fun feedPlanPending(sessionId: String, pendingSelection: String?) {
        planPendingHintSource.feed(sessionId, pendingSelection)
    }

    /** 设置当次执行的循环追踪器（workflow 每次 executeEvents 开始调用）。 */
    fun setLoopTracker(tracker: LoopGuardTracker?) {
        loopAdvisorySource.setTracker(tracker)
    }

    /** 喂入目标失配检测（workflow 每次用户新输入到达时调用一次）。 */
    fun feedGoalStale(sessionId: String, goalText: String, recentUserInput: String) {
        goalStaleSource.feed(sessionId, goalText, recentUserInput)
    }

    /** 入队目标调整事件（六段式流水线 post-execute 段为关键工具白名单生成后调用）。 */
    fun enqueueGoalAdjustEvent(sessionId: String, event: com.R.codecore.feature.agent.domain.input.GoalAdjustEvent): Boolean =
        goalAdjustEventSource.enqueue(sessionId, event)

    /**
     * 组装 step 前注入块（D1-1/2）：依次 build 8 个已登记 Source → 交给 [StepInjectionAssembler]
     * 做八源排序 + 预算裁剪 → 拼接。无可注入内容时返回 null（调用方跳过追加）。
     * 单个 Source build 异常静默降级（返回 null，不阻断注入链）。
     */
    fun buildStepInjections(ctx: AgentContext): String? {
        val entries = stepSources.mapNotNull { step ->
            val text = try {
                step.source.build(ctx)
            } catch (e: Exception) {
                null
            }?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            StepInjectionAssembler.Entry(step.importance, step.order, text)
        }
        return stepAssembler.assemble(entries)
    }

    private companion object {
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val MAX_AGENTS_CHARS = 32_000
    }
}
