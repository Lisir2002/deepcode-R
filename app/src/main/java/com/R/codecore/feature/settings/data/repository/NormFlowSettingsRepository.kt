package com.R.codecore.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「规范流程」运行时机制统一开关（D1-7，对齐 norm-chain-design.md §3.5）：
 *
 * - **总开关** [normFlowEnabledFlow]（默认开）：关闭即 3.1-3.3 三机制整体停用（step 前注入块 / guard 链均不生效）；
 * - **子开关** [stepInjectEnabledFlow]（默认开）：step 前注入纪律（8 Source 注入块）单独可关；
 * - **子开关** [toolGuardEnabledFlow]（默认开）：guard 链 + 文件观察单独可关；
 * - **子开关** [reasoningBudgetEnabledFlow]（D2-2，默认开）：推理预算——开启时透传每会话思考强度
 *   （reasoningEffort）给 provider，关闭则禁用推理参数。**默认开以保持现状**：现有每会话
 *   reasoningEffort 默认 MEDIUM（推理本就开启），若按 norm-chain §3.7「默认 off」落地会关闭既有推理，
 *   破坏「默认 off 不改变现有行为」验收，故以「不改变现有行为」为准（正确性优先，AGENTS.md）。
 * - **子开关** [usageCardEnabledFlow]（D2-4，默认开）：每回合用量卡片（本回合增量 + 会话累计，仅 token）。
 * - **子开关** [sopSummaryEnabledFlow]（D4-3，默认开）：SOP 清单摘要常驻注入单独可关
 *   （完整正文经 loadSop 工具按需取用，不受此开关影响）。
 * - **子开关** [playbookAutoEnabledFlow]（D5-pa，默认开）：Playbook 自动触发——控制模型自主
 *   调用 `playbook_start` 工具启动剧本；`/playbook` 斜杠命令显式入口不受此开关影响。
 * - **子开关** [idleConvergeEnabledFlow]（D2-1，默认关）：空转软收敛——连续 [IDLE_CONVERGE_ROUNDS]
 *   轮无实质产出（未写文件/未执行命令/未读到新信息）时自动结束回合。默认关：研究/浏览类请求
 *   （websearch / browser 等不在实质产出集合）会被误伤收敛，故默认不启用，由用户按需开启。
 *
 * 第一批只落三个字段（对齐设计 §4 第一批「总开关 + step_inject/tool_guard 子开关」）；
 * SOP 摘要（sop_summary）/ Playbook 自动触发（playbook_auto）子开关随 D4/D5 批次补入本文件；
 * 推理预算 / 用量卡片开关随 D2 批次补入本文件。
 *
 * 读取位置：workflow 组装注入块 / 挂 guard 链统一读本 repository（settings DataStore），
 * 设置页「规范流程」分组读写同源。DataStore 用法与 [KeepaliveSettingsRepository] 一致。
 */
@Singleton
class NormFlowSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val NORM_FLOW_ENABLED_KEY = booleanPreferencesKey("norm_flow_enabled")
        val STEP_INJECT_ENABLED_KEY = booleanPreferencesKey("step_inject_enabled")
        val TOOL_GUARD_ENABLED_KEY = booleanPreferencesKey("tool_guard_enabled")
        val REASONING_BUDGET_ENABLED_KEY = booleanPreferencesKey("reasoning_budget_enabled")
        val USAGE_CARD_ENABLED_KEY = booleanPreferencesKey("usage_card_enabled")
        val SOP_SUMMARY_ENABLED_KEY = booleanPreferencesKey("sop_summary_enabled")
        val PLAYBOOK_AUTO_ENABLED_KEY = booleanPreferencesKey("playbook_auto_enabled")
        val IDLE_CONVERGE_ENABLED_KEY = booleanPreferencesKey("idle_converge_enabled")
    }

    /** 总开关流；未设置时回退 true（默认开）。 */
    val normFlowEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[NORM_FLOW_ENABLED_KEY] ?: true }

    /** step 前注入纪律子开关流；未设置时回退 true（默认开）。 */
    val stepInjectEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[STEP_INJECT_ENABLED_KEY] ?: true }

    /** guard 链 + 文件观察子开关流；未设置时回退 true（默认开）。 */
    val toolGuardEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[TOOL_GUARD_ENABLED_KEY] ?: true }

    /** 推理预算子开关流（D2-2）；未设置时回退 true（默认开，保持现有推理行为）。 */
    val reasoningBudgetEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[REASONING_BUDGET_ENABLED_KEY] ?: true }

    /** 用量卡片子开关流（D2-4）；未设置时回退 true（默认开）。 */
    val usageCardEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[USAGE_CARD_ENABLED_KEY] ?: true }

    /** SOP 清单摘要子开关流（D4-3）；未设置时回退 true（默认开）。 */
    val sopSummaryEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[SOP_SUMMARY_ENABLED_KEY] ?: true }

    /** Playbook 自动触发子开关流（D5-pa）；未设置时回退 true（默认开）。 */
    val playbookAutoEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[PLAYBOOK_AUTO_ENABLED_KEY] ?: true }

    /** 空转软收敛子开关流（D2-1）；未设置时回退 false（默认关，避免研究/浏览类请求被误伤收敛）。 */
    val idleConvergeEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[IDLE_CONVERGE_ENABLED_KEY] ?: false }

    suspend fun setNormFlowEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[NORM_FLOW_ENABLED_KEY] = enabled }
    }

    suspend fun setStepInjectEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[STEP_INJECT_ENABLED_KEY] = enabled }
    }

    suspend fun setToolGuardEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[TOOL_GUARD_ENABLED_KEY] = enabled }
    }

    suspend fun setReasoningBudgetEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[REASONING_BUDGET_ENABLED_KEY] = enabled }
    }

    suspend fun setUsageCardEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[USAGE_CARD_ENABLED_KEY] = enabled }
    }

    suspend fun setSopSummaryEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[SOP_SUMMARY_ENABLED_KEY] = enabled }
    }

    suspend fun setPlaybookAutoEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[PLAYBOOK_AUTO_ENABLED_KEY] = enabled }
    }

    suspend fun setIdleConvergeEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[IDLE_CONVERGE_ENABLED_KEY] = enabled }
    }

    /**
     * 组合判定：step 前注入纪律是否生效（总开关 && 子开关）。
     * workflow 每轮 CallLlm 前读取；DataStore 命中内存缓存，开销可忽略。
     */
    suspend fun isStepInjectActive(): Boolean = normFlowEnabledFlow.first() && stepInjectEnabledFlow.first()

    /**
     * 组合判定：guard 链（含文件观察）是否生效（总开关 && 子开关）。
     * workflow 工具执行前读取；DataStore 命中内存缓存，开销可忽略。
     */
    suspend fun isToolGuardActive(): Boolean = normFlowEnabledFlow.first() && toolGuardEnabledFlow.first()

    /**
     * 组合判定：推理预算（D2-2）是否生效（总开关 && 子开关）。
     * 开启时透传每会话思考强度给 provider；关闭则禁用推理参数。
     */
    suspend fun isReasoningBudgetActive(): Boolean = normFlowEnabledFlow.first() && reasoningBudgetEnabledFlow.first()

    /**
     * 组合判定：用量卡片（D2-4）是否生效（总开关 && 子开关）。
     * workflow 回合结束是否聚合轨迹并推送用量事件。
     */
    suspend fun isUsageCardActive(): Boolean = normFlowEnabledFlow.first() && usageCardEnabledFlow.first()

    /**
     * 组合判定：SOP 清单摘要（D4-3）是否生效（总开关 && 子开关）。
     * SystemPromptProvider step 前注入 SOP 摘要前读取；loadSop 工具取正文不受此开关影响。
     */
    suspend fun isSopSummaryActive(): Boolean = normFlowEnabledFlow.first() && sopSummaryEnabledFlow.first()

    /**
     * 组合判定：Playbook 自动触发（D5-pa）是否生效（总开关 && 子开关）。
     * PlaybookStartTool 工具入口读取；`/playbook` 斜杠命令显式入口不受此开关影响（直接走
     * [com.R.codecore.feature.agent.domain.playbook.PlaybookExecutor.start]，不经工具）。
     */
    suspend fun isPlaybookAutoActive(): Boolean = normFlowEnabledFlow.first() && playbookAutoEnabledFlow.first()

    /**
     * 组合判定：空转软收敛（D2-1）是否生效（总开关 && 子开关）。
     * workflow 每轮 CallLlm 前读取；关闭时即使累计轮数达标也不强制收敛。
     */
    suspend fun isIdleConvergeActive(): Boolean = normFlowEnabledFlow.first() && idleConvergeEnabledFlow.first()
}
