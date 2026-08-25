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
 * - **子开关** [toolGuardEnabledFlow]（默认开）：guard 链 + 文件观察单独可关。
 *
 * 第一批只落三个字段（对齐设计 §4 第一批「总开关 + step_inject/tool_guard 子开关」）；
 * SOP 摘要（sop_summary）/ Playbook 自动触发（playbook_auto）子开关随 D4/D5 批次补入本文件。
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
    }

    /** 总开关流；未设置时回退 true（默认开）。 */
    val normFlowEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[NORM_FLOW_ENABLED_KEY] ?: true }

    /** step 前注入纪律子开关流；未设置时回退 true（默认开）。 */
    val stepInjectEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[STEP_INJECT_ENABLED_KEY] ?: true }

    /** guard 链 + 文件观察子开关流；未设置时回退 true（默认开）。 */
    val toolGuardEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { it[TOOL_GUARD_ENABLED_KEY] ?: true }

    suspend fun setNormFlowEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[NORM_FLOW_ENABLED_KEY] = enabled }
    }

    suspend fun setStepInjectEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[STEP_INJECT_ENABLED_KEY] = enabled }
    }

    suspend fun setToolGuardEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[TOOL_GUARD_ENABLED_KEY] = enabled }
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
}
