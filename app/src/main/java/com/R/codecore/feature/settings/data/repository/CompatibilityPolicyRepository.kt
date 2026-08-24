package com.R.codecore.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 兼容端点默认策略枚举。持久化时存 name() 字符串；新值追加末尾即可，避免破坏存量。
 *
 * —— 为什么不放在 companion object 里？——
 *   Kotlin 的 companion object 嵌套 enum 在"import XxxRepository.DefaultPolicy"这种场景下，
 *   不同 Kotlin 版本对"companion 内嵌套类的静态可见性"处理不一致：CI（Kotlin 2.1.20 配套的 AGP 8.9.3）
 *   会严格判定为"Unresolved reference 'DefaultPolicy'"，IDE（Kotlin 插件较新）反而能解析，
 *   导致本地静态校对不出来、CI 稳定挂。所以直接把两个 enum 定义成 class-level 嵌套，
 *   对外部调用者语义不变（`CompatibilityPolicyRepository.DefaultPolicy.STRICT`），但编译器解析稳定。
 */
enum class DefaultPolicy {
    /** 严格模式（默认·推荐）：与 catalog 收录模型走同一规则，supportsVision/Tools/Reasoning 仅当启发式 probablyXxx 命中为 true，否则 false。 */
    STRICT,
    /** 启发式模式（与 RC62d 等价）：行为和 STRICT 一致（默认 probablyXxx 已开启），保留用于命名。 */
    HEURISTIC,
    /** 宽松模式（与 RC62e 等价）：INFERRED 源的未收录模型一律 supportsVision/supportsTools/supportsReasoning=true。用户按需打开。 */
    LAX,
    /** 完全手动模式：supportsVision/Tools/Reasoning 一律 false，只有用户在单模型复选框（备选方案④）手动覆盖后才生效。 */
    MANUAL
}

/** viewImage 未收录模型守卫策略枚举。与 DefaultPolicy 平级定义，保证 CI 编译器可解析。 */
enum class ViewImageUnknownGuardPolicy {
    /** activeModelSupportsVision=false 时，自动回退到识图专用模型（默认·推荐） */
    FALLBACK_VISION_MODEL,
    /** activeModelSupportsVision=false 时，直接报错并提示用户去设置。 */
    FAIL_FAST
}

/**
 * 兼容端点（自定义/未收录模型）默认策略持久化。
 *
 * 对应 RC63 备选方案③：用户可以在设置里下拉切换"严格模式 / 启发式模式 / 宽松模式 / 完全手动"，
 * 还可以一键开关「发送失败自动降级（备选方案②）」、「viewImage 未收录模型守卫策略」。
 *
 * DataStore 用法与 [KeepaliveSettingsRepository] / [VisionModelSettingsRepository] 保持一致，
 * 不新增任何持久化框架，严格遵循现有架构风格。
 */
@Singleton
class CompatibilityPolicyRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private object Keys {
        val DEFAULT_POLICY = stringPreferencesKey("default_policy")
        val AUTO_DOWNGRADE_ON_SEND_FAILURE = booleanPreferencesKey("auto_downgrade_on_send_failure")
        val VIEWIMAGE_UNKNOWN_GUARD_POLICY = stringPreferencesKey("viewimage_unknown_guard_policy")
    }

    /** 默认策略流（未设置时回退 STRICT，严格不影响整体）。 */
    val defaultPolicyFlow: Flow<DefaultPolicy> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_POLICY]?.let { runCatching { DefaultPolicy.valueOf(it) }.getOrNull() }
            ?: DefaultPolicy.STRICT
    }

    /** 发送失败自动降级（备选方案②）总开关；默认开启。 */
    val autoDowngradeOnSendFailureFlow: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.AUTO_DOWNGRADE_ON_SEND_FAILURE] ?: true
    }

    /** viewImage 未收录模型守卫策略流；默认自动回退识图模型。 */
    val viewImageUnknownGuardPolicyFlow: Flow<ViewImageUnknownGuardPolicy> =
        context.settingsDataStore.data.map { prefs ->
            prefs[Keys.VIEWIMAGE_UNKNOWN_GUARD_POLICY]
                ?.let { runCatching { ViewImageUnknownGuardPolicy.valueOf(it) }.getOrNull() }
                ?: ViewImageUnknownGuardPolicy.FALLBACK_VISION_MODEL
        }

    // —— 冷读：一次性读取（非 flow），用于 resolve() 这种同步决策但挂在 suspend 里的场景。 ——

    suspend fun getDefaultPolicy(): DefaultPolicy = defaultPolicyFlow.first()

    suspend fun isAutoDowngradeOnSendFailure(): Boolean = autoDowngradeOnSendFailureFlow.first()

    suspend fun getViewImageUnknownGuardPolicy(): ViewImageUnknownGuardPolicy = viewImageUnknownGuardPolicyFlow.first()

    // —— 写入：设置页 UI 修改时调用。 ——

    suspend fun setDefaultPolicy(policy: DefaultPolicy) {
        context.settingsDataStore.edit { it[Keys.DEFAULT_POLICY] = policy.name }
    }

    suspend fun setAutoDowngradeOnSendFailure(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_DOWNGRADE_ON_SEND_FAILURE] = enabled }
    }

    suspend fun setViewImageUnknownGuardPolicy(policy: ViewImageUnknownGuardPolicy) {
        context.settingsDataStore.edit { it[Keys.VIEWIMAGE_UNKNOWN_GUARD_POLICY] = policy.name }
    }

    /** 备份快照（保留字段以便后续备份还原功能统一接入）。 */
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
