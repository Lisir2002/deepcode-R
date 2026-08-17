package com.R.codecore.feature.terminal.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.terminalDataStore by preferencesDataStore(name = "terminal_prefs")

/** 终端内容区配色：仅两套 + 跟随系统。外壳 UI（TabBar/Banner/ExtraKeys）独立走 Material 主题。 */
enum class TerminalTheme(val stableKey: String) {
    /**
     * 跟随"程序自己的主题设置"（不是系统）。
     * 具体暗/亮由 MainActivity 根据 ThemeSettingsRepository.themeModeFlow 计算后，
     * 通过 LocalAppDarkMode CompositionLocal 下发。
     *
     * 兼容注意：stableKey 仍保留 "system"，避免已安装用户 DataStore 里存的旧值失效。
     */
    FOLLOW_APP("system"),
    PURE_BLACK("pure_black"),        // 黑底白字，对比绝对
    PURE_WHITE("pure_white"),        // 白底黑字，对比绝对
    // 向后兼容读取：旧值 Dracula/Solarized 自动映射到跟随程序（避免 valueOf 抛异常）
}

/** SSH 心跳间隔枚举。 */
enum class SshHeartbeatSeconds(val seconds: Int, val display: String) {
    OFF(0, "关闭"),
    S30(30, "30 秒"),
    S60(60, "60 秒"),
    S120(120, "2 分钟"),
    S300(300, "5 分钟"),
    ;

    companion object {
        fun fromSeconds(s: Int) = entries.firstOrNull { it.seconds == s } ?: S60
    }
}

/**
 * 终端体验偏好：外观 / 键盘 & 交互 / 行为 / SSH 常用 4 分组，共 15+ 项。
 */
@Singleton
class TerminalSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        // ── G1 外观 ───────────────────────────────────
        val FONT_SIZE_SP_KEY = intPreferencesKey("font_size_sp")
        val THEME_KEY = stringPreferencesKey("theme")
        val SHOW_TAB_BAR_KEY = booleanPreferencesKey("show_tab_bar")

        // ── G2 键盘 & 交互 ────────────────────────────
        val FULL_EXTRA_KEYS_KEY = booleanPreferencesKey("full_extra_keys")
        val SCALE_GESTURE_PERSISTS_KEY = booleanPreferencesKey("scale_gesture_persists")
        val AUTO_POP_IME_ON_SWITCH_KEY = booleanPreferencesKey("auto_pop_ime_on_switch")
        val CTRL_HINT_SHOWN_KEY = booleanPreferencesKey("ctrl_hint_shown")

        // ── G3 行为 ───────────────────────────────────
        val NEW_OUTPUT_INDICATOR_KEY = booleanPreferencesKey("new_output_indicator")
        val AUTO_NEW_TAB_ON_CLOSE_LAST_KEY = booleanPreferencesKey("auto_new_tab_on_close_last")
        val KEEP_SESSION_WHEN_LEAVE_KEY = booleanPreferencesKey("keep_session_when_leave")
        val PASTE_AS_PLAIN_TEXT_KEY = booleanPreferencesKey("paste_as_plain_text")
        val FIRST_RUN_BANNER_DISMISSED_KEY = booleanPreferencesKey("first_run_banner_dismissed")

        // ── G4 SSH 常用 ───────────────────────────────
        val SSH_AUTO_RECONNECT_KEY = booleanPreferencesKey("ssh_auto_reconnect")
        val SSH_HEARTBEAT_SECONDS_KEY = intPreferencesKey("ssh_heartbeat_seconds")
        val SSH_KEEPALIVE_KEY = booleanPreferencesKey("ssh_keepalive")

        // ── 旧键（仍保留读路径） ──────────────────────
        val LAST_CWD_KEY = stringPreferencesKey("last_cwd")
    }

    // ───────────────── G1 外观 ─────────────────────────────────────

    /** 字号档位（SP）；默认 12sp。 */
    val fontSizeFlow: Flow<Int> = context.terminalDataStore.data.map {
        it[FONT_SIZE_SP_KEY] ?: 12
    }

    /** 终端内容区配色：跟随程序 / 黑底白字 / 白底黑字。默认跟随程序。
     *  对历史旧值 DRACULA_DARK / SOLARIZED_LIGHT 做兼容映射，避免 valueOf 抛异常。
     *  历史值 "system" 对应 FOLLOW_APP（stableKey 已保持一致，这里 valueOf 也手动兜底）。 */
    val themeFlow: Flow<TerminalTheme> = context.terminalDataStore.data.map {
        val raw = it[THEME_KEY]
        if (raw.isNullOrBlank()) TerminalTheme.FOLLOW_APP
        else runCatching { TerminalTheme.valueOf(raw) }
            .getOrElse {
                when (raw) {
                    "DRACULA_DARK" -> TerminalTheme.PURE_BLACK   // 历史暗色值 → 黑底
                    "SOLARIZED_LIGHT" -> TerminalTheme.PURE_WHITE  // 历史亮色值 → 白底
                    "system" -> TerminalTheme.FOLLOW_APP            // 历史枚举 SYSTEM → FOLLOW_APP
                    else -> TerminalTheme.FOLLOW_APP
                }
            }
    }

    /** 是否显示 Tab 栏（纯键盘流可关掉）。默认 true。 */
    val showTabBarFlow: Flow<Boolean> = context.terminalDataStore.data.map {
        it[SHOW_TAB_BAR_KEY] ?: true
    }

    // ───────────────── G2 键盘 & 交互 ──────────────────────────────

    /** true = 完整扩展键盘档，false = 简洁档。 */
    val fullExtraKeysFlow: Flow<Boolean> = context.terminalDataStore.data.map {
        it[FULL_EXTRA_KEYS_KEY] ?: false
    }

    /** 双指缩放手势是否持久化写入 fontSize（true 即改字号）。默认 true。 */
    val scaleGesturePersistsFlow: Flow<Boolean> = context.terminalDataStore.data.map {
        val v = it[SCALE_GESTURE_PERSISTS_KEY]
        if (v == null) true else v // 默认开：行为和现有一致
    }

    /** 切 Tab 后自动弹键盘。默认 true（RC14 行为）。 */
    val autoPopImeOnSwitchFlow: Flow<Boolean> = context.terminalDataStore.data.map {
        val v = it[AUTO_POP_IME_ON_SWITCH_KEY]
        if (v == null) true else v
    }

    /** Ctrl 首次用法提示是否已展示。 */
    val ctrlHintShownFlow: Flow<Boolean> = context.terminalDataStore.data.map {
        it[CTRL_HINT_SHOWN_KEY] ?: false
    }

    // ───────────────── G3 行为 ─────────────────────────────────────

    /** 后台标签新输出红点提示，默认开启。 */
    val newOutputIndicatorFlow: Flow<Boolean> = context.terminalDataStore.data.map {
        val v = it[NEW_OUTPUT_INDICATOR_KEY]
        if (v == null) true else v
    }

    /** 关闭最后一个 Tab 时自动新建空白 Tab。默认 true（RC14 行为）。 */
    val autoNewTabOnCloseLastFlow: Flow<Boolean> = context.terminalDataStore.data.map {
        val v = it[AUTO_NEW_TAB_ON_CLOSE_LAST_KEY]
        if (v == null) true else v
    }

    /** 离开终端页是否保持会话后台运行。默认 true（切回来会话还在）。 */
    val keepSessionWhenLeaveFlow: Flow<Boolean> = context.terminalDataStore.data.map {
        val v = it[KEEP_SESSION_WHEN_LEAVE_KEY]
        if (v == null) true else v
    }

    /** 剪贴板粘贴是否自动转纯文本（去掉富文本格式）。默认 true。 */
    val pasteAsPlainTextFlow: Flow<Boolean> = context.terminalDataStore.data.map {
        val v = it[PASTE_AS_PLAIN_TEXT_KEY]
        if (v == null) true else v
    }

    /**
     * 终端首进 Banner「暂不提醒」是否被用户关闭过。
     * true = 用户已经点过"暂不提醒"或者安装/初始化完成，不再显示 Banner。
     * false = 首次或未初始化时仍显示 Banner（未初始化 rootfs 时始终显示，不管此值）。
     */
    val firstRunBannerDismissedFlow: Flow<Boolean> = context.terminalDataStore.data.map {
        it[FIRST_RUN_BANNER_DISMISSED_KEY] ?: false
    }

    // ───────────────── G4 SSH 常用 ─────────────────────────────────

    /** SSH 自动重连。默认 true。 */
    val sshAutoReconnectFlow: Flow<Boolean> = context.terminalDataStore.data.map {
        val v = it[SSH_AUTO_RECONNECT_KEY]
        if (v == null) true else v
    }

    /** SSH 心跳间隔秒数。默认 60s。 */
    val sshHeartbeatSecondsFlow: Flow<Int> = context.terminalDataStore.data.map {
        it[SSH_HEARTBEAT_SECONDS_KEY] ?: SshHeartbeatSeconds.S60.seconds
    }

    /** SSH TCP KeepAlive。默认 true。 */
    val sshKeepaliveFlow: Flow<Boolean> = context.terminalDataStore.data.map {
        val v = it[SSH_KEEPALIVE_KEY]
        if (v == null) true else v
    }

    // ───────────────── 杂项 ────────────────────────────────────────

    val lastCwdFlow: Flow<String> = context.terminalDataStore.data.map {
        it[LAST_CWD_KEY] ?: ""
    }

    // ───────────────── G1 写 ───────────────────────────────────────
    suspend fun saveFontSize(sp: Int) {
        context.terminalDataStore.edit { it[FONT_SIZE_SP_KEY] = sp.coerceIn(8, 20) }
    }

    suspend fun saveTheme(theme: TerminalTheme) {
        context.terminalDataStore.edit { it[THEME_KEY] = theme.name }
    }

    suspend fun saveShowTabBar(enabled: Boolean) {
        context.terminalDataStore.edit { it[SHOW_TAB_BAR_KEY] = enabled }
    }

    // ───────────────── G2 写 ───────────────────────────────────────
    suspend fun saveFullExtraKeys(full: Boolean) {
        context.terminalDataStore.edit { it[FULL_EXTRA_KEYS_KEY] = full }
    }

    suspend fun saveScaleGesturePersists(enabled: Boolean) {
        context.terminalDataStore.edit { it[SCALE_GESTURE_PERSISTS_KEY] = enabled }
    }

    suspend fun saveAutoPopImeOnSwitch(enabled: Boolean) {
        context.terminalDataStore.edit { it[AUTO_POP_IME_ON_SWITCH_KEY] = enabled }
    }

    suspend fun markCtrlHintShown() {
        context.terminalDataStore.edit { it[CTRL_HINT_SHOWN_KEY] = true }
    }

    suspend fun resetCtrlHint() {
        context.terminalDataStore.edit { it[CTRL_HINT_SHOWN_KEY] = false }
    }

    // ───────────────── G3 写 ───────────────────────────────────────
    suspend fun saveNewOutputIndicator(enabled: Boolean) {
        context.terminalDataStore.edit { it[NEW_OUTPUT_INDICATOR_KEY] = enabled }
    }

    suspend fun saveAutoNewTabOnCloseLast(enabled: Boolean) {
        context.terminalDataStore.edit { it[AUTO_NEW_TAB_ON_CLOSE_LAST_KEY] = enabled }
    }

    suspend fun saveKeepSessionWhenLeave(enabled: Boolean) {
        context.terminalDataStore.edit { it[KEEP_SESSION_WHEN_LEAVE_KEY] = enabled }
    }

    suspend fun savePasteAsPlainText(enabled: Boolean) {
        context.terminalDataStore.edit { it[PASTE_AS_PLAIN_TEXT_KEY] = enabled }
    }

    suspend fun saveFirstRunBannerDismissed(dismissed: Boolean) {
        context.terminalDataStore.edit { it[FIRST_RUN_BANNER_DISMISSED_KEY] = dismissed }
    }

    // ───────────────── G4 写 ───────────────────────────────────────
    suspend fun saveSshAutoReconnect(enabled: Boolean) {
        context.terminalDataStore.edit { it[SSH_AUTO_RECONNECT_KEY] = enabled }
    }

    suspend fun saveSshHeartbeatSeconds(seconds: Int) {
        context.terminalDataStore.edit { it[SSH_HEARTBEAT_SECONDS_KEY] = seconds }
    }

    suspend fun saveSshKeepalive(enabled: Boolean) {
        context.terminalDataStore.edit { it[SSH_KEEPALIVE_KEY] = enabled }
    }

    suspend fun saveLastCwd(cwd: String) {
        context.terminalDataStore.edit { it[LAST_CWD_KEY] = cwd }
    }

    // ───────────────── 快照读（非 UI 场景用） ──────────────────────
    suspend fun readFontSize(): Int = fontSizeFlow.first()
    suspend fun readFullExtraKeys(): Boolean = fullExtraKeysFlow.first()
    suspend fun readNewOutputIndicator(): Boolean = newOutputIndicatorFlow.first()
    suspend fun readCtrlHintShown(): Boolean = ctrlHintShownFlow.first()
    suspend fun readAutoPopImeOnSwitch(): Boolean = autoPopImeOnSwitchFlow.first()
    suspend fun readAutoNewTabOnCloseLast(): Boolean = autoNewTabOnCloseLastFlow.first()
    suspend fun readKeepSessionWhenLeave(): Boolean = keepSessionWhenLeaveFlow.first()
    suspend fun readShowTabBar(): Boolean = showTabBarFlow.first()
    suspend fun readSshHeartbeatSeconds(): Int = sshHeartbeatSecondsFlow.first()
}

/** 字号可选档位：用户长按菜单/双指缩放后会卡在一个档位上，避免任意数导致列数抖动。 */
object TerminalFontSizes {
    val STEPS = listOf(8, 10, 11, 12, 13, 14, 15, 16, 18, 20)
    val DEFAULT = 12
}
