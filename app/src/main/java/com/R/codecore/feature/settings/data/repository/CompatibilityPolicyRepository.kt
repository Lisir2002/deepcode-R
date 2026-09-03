package com.R.codecore.feature.settings.data.repository

import com.R.codecore.datalayer.store.KVStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 兼容端点默认策略枚举。持久化时存 name() 字符串；新值追加末尾即可，避免破坏存量。
 */
enum class DefaultPolicy {
    STRICT, HEURISTIC, LAX, MANUAL
}

/** viewImage 未收录模型守卫策略枚举。 */
enum class ViewImageUnknownGuardPolicy {
    FALLBACK_VISION_MODEL, FAIL_FAST
}

/** 兼容端点（自定义/未收录模型）默认策略持久化。 */
@Singleton
class CompatibilityPolicyRepository @Inject constructor(
    private val kv: KVStore
) {
    private companion object {
        const val NS = "settings"
        const val DEFAULT_POLICY_KEY = "default_policy"
        const val AUTO_DOWNGRADE_KEY = "auto_downgrade_on_send_failure"
        const val VIEWIMAGE_GUARD_KEY = "viewimage_unknown_guard_policy"
    }

    val defaultPolicyFlow: Flow<DefaultPolicy> = kv.observeString(NS, DEFAULT_POLICY_KEY).map { stored ->
        stored?.let { runCatching { DefaultPolicy.valueOf(it) }.getOrNull() }
            ?: DefaultPolicy.STRICT
    }

    val autoDowngradeOnSendFailureFlow: Flow<Boolean> = kv.observeBool(NS, AUTO_DOWNGRADE_KEY).map { it ?: true }

    val viewImageUnknownGuardPolicyFlow: Flow<ViewImageUnknownGuardPolicy> =
        kv.observeString(NS, VIEWIMAGE_GUARD_KEY).map { stored ->
            stored?.let { runCatching { ViewImageUnknownGuardPolicy.valueOf(it) }.getOrNull() }
                ?: ViewImageUnknownGuardPolicy.FALLBACK_VISION_MODEL
        }

    suspend fun getDefaultPolicy(): DefaultPolicy = defaultPolicyFlow.first()
    suspend fun isAutoDowngradeOnSendFailure(): Boolean = autoDowngradeOnSendFailureFlow.first()
    suspend fun getViewImageUnknownGuardPolicy(): ViewImageUnknownGuardPolicy = viewImageUnknownGuardPolicyFlow.first()

    suspend fun setDefaultPolicy(policy: DefaultPolicy) { kv.putString(NS, DEFAULT_POLICY_KEY, policy.name) }
    suspend fun setAutoDowngradeOnSendFailure(enabled: Boolean) { kv.putBool(NS, AUTO_DOWNGRADE_KEY, enabled) }
    suspend fun setViewImageUnknownGuardPolicy(policy: ViewImageUnknownGuardPolicy) { kv.putString(NS, VIEWIMAGE_GUARD_KEY, policy.name) }

    /** 备份快照。 */
    suspend fun snapshot(): Snapshot = Snapshot(
        defaultPolicy = getDefaultPolicy().name,
        autoDowngrade = isAutoDowngradeOnSendFailure(),
        viewImageGuard = getViewImageUnknownGuardPolicy().name
    )

    /** 从备份还原。 */
    suspend fun restore(snapshot: Snapshot) {
        setDefaultPolicy(runCatching { DefaultPolicy.valueOf(snapshot.defaultPolicy) }.getOrDefault(DefaultPolicy.STRICT))
        setAutoDowngradeOnSendFailure(snapshot.autoDowngrade)
        setViewImageUnknownGuardPolicy(
            runCatching { ViewImageUnknownGuardPolicy.valueOf(snapshot.viewImageGuard) }
                .getOrDefault(ViewImageUnknownGuardPolicy.FALLBACK_VISION_MODEL)
        )
    }

    data class Snapshot(
        val defaultPolicy: String,
        val autoDowngrade: Boolean,
        val viewImageGuard: String
    )
}
