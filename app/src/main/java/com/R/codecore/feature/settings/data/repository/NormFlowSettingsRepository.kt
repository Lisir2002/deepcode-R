package com.R.codecore.feature.settings.data.repository

import com.R.codecore.datalayer.store.KVStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**「规范流程」运行时机制统一开关。 */
@Singleton
class NormFlowSettingsRepository @Inject constructor(
    private val kv: KVStore
) {
    private companion object {
        const val NS = "settings"
        const val NORM_FLOW_ENABLED_KEY = "norm_flow_enabled"
        const val STEP_INJECT_ENABLED_KEY = "step_inject_enabled"
        const val TOOL_GUARD_ENABLED_KEY = "tool_guard_enabled"
        const val REASONING_BUDGET_ENABLED_KEY = "reasoning_budget_enabled"
        const val USAGE_CARD_ENABLED_KEY = "usage_card_enabled"
        const val SOP_SUMMARY_ENABLED_KEY = "sop_summary_enabled"
        const val PLAYBOOK_AUTO_ENABLED_KEY = "playbook_auto_enabled"
        const val IDLE_CONVERGE_ENABLED_KEY = "idle_converge_enabled"
    }

    val normFlowEnabledFlow: Flow<Boolean> = kv.observeBool(NS, NORM_FLOW_ENABLED_KEY).map { it ?: true }
    val stepInjectEnabledFlow: Flow<Boolean> = kv.observeBool(NS, STEP_INJECT_ENABLED_KEY).map { it ?: true }
    val toolGuardEnabledFlow: Flow<Boolean> = kv.observeBool(NS, TOOL_GUARD_ENABLED_KEY).map { it ?: true }
    val reasoningBudgetEnabledFlow: Flow<Boolean> = kv.observeBool(NS, REASONING_BUDGET_ENABLED_KEY).map { it ?: true }
    val usageCardEnabledFlow: Flow<Boolean> = kv.observeBool(NS, USAGE_CARD_ENABLED_KEY).map { it ?: true }
    val sopSummaryEnabledFlow: Flow<Boolean> = kv.observeBool(NS, SOP_SUMMARY_ENABLED_KEY).map { it ?: true }
    val playbookAutoEnabledFlow: Flow<Boolean> = kv.observeBool(NS, PLAYBOOK_AUTO_ENABLED_KEY).map { it ?: true }
    val idleConvergeEnabledFlow: Flow<Boolean> = kv.observeBool(NS, IDLE_CONVERGE_ENABLED_KEY).map { it ?: false }

    suspend fun setNormFlowEnabled(enabled: Boolean) { kv.putBool(NS, NORM_FLOW_ENABLED_KEY, enabled) }
    suspend fun setStepInjectEnabled(enabled: Boolean) { kv.putBool(NS, STEP_INJECT_ENABLED_KEY, enabled) }
    suspend fun setToolGuardEnabled(enabled: Boolean) { kv.putBool(NS, TOOL_GUARD_ENABLED_KEY, enabled) }
    suspend fun setReasoningBudgetEnabled(enabled: Boolean) { kv.putBool(NS, REASONING_BUDGET_ENABLED_KEY, enabled) }
    suspend fun setUsageCardEnabled(enabled: Boolean) { kv.putBool(NS, USAGE_CARD_ENABLED_KEY, enabled) }
    suspend fun setSopSummaryEnabled(enabled: Boolean) { kv.putBool(NS, SOP_SUMMARY_ENABLED_KEY, enabled) }
    suspend fun setPlaybookAutoEnabled(enabled: Boolean) { kv.putBool(NS, PLAYBOOK_AUTO_ENABLED_KEY, enabled) }
    suspend fun setIdleConvergeEnabled(enabled: Boolean) { kv.putBool(NS, IDLE_CONVERGE_ENABLED_KEY, enabled) }

    suspend fun isStepInjectActive(): Boolean = normFlowEnabledFlow.first() && stepInjectEnabledFlow.first()
    suspend fun isToolGuardActive(): Boolean = normFlowEnabledFlow.first() && toolGuardEnabledFlow.first()
    suspend fun isReasoningBudgetActive(): Boolean = normFlowEnabledFlow.first() && reasoningBudgetEnabledFlow.first()
    suspend fun isUsageCardActive(): Boolean = normFlowEnabledFlow.first() && usageCardEnabledFlow.first()
    suspend fun isSopSummaryActive(): Boolean = normFlowEnabledFlow.first() && sopSummaryEnabledFlow.first()
    suspend fun isPlaybookAutoActive(): Boolean = normFlowEnabledFlow.first() && playbookAutoEnabledFlow.first()
    suspend fun isIdleConvergeActive(): Boolean = normFlowEnabledFlow.first() && idleConvergeEnabledFlow.first()
}
