package com.core.deepcode.feature.terminal.data.repository

import com.core.deepcode.datalayer.store.KVStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 终端内容区配色：仅两套 + 跟随系统。 */
enum class TerminalTheme(val stableKey: String) {
    FOLLOW_APP("system"),
    PURE_BLACK("pure_black"),
    PURE_WHITE("pure_white"),
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

const val TERMINAL_NS = "terminal"
const val FONT_SIZE_SP_KEY = "font_size_sp"
const val THEME_KEY = "theme"
const val SHOW_TAB_BAR_KEY = "show_tab_bar"
const val FULL_EXTRA_KEYS_KEY = "full_extra_keys"
const val SCALE_GESTURE_PERSISTS_KEY = "scale_gesture_persists"
const val AUTO_POP_IME_ON_SWITCH_KEY = "auto_pop_ime_on_switch"
const val CTRL_HINT_SHOWN_KEY = "ctrl_hint_shown"
const val NEW_OUTPUT_INDICATOR_KEY = "new_output_indicator"
const val AUTO_NEW_TAB_ON_CLOSE_LAST_KEY = "auto_new_tab_on_close_last"
const val KEEP_SESSION_WHEN_LEAVE_KEY = "keep_session_when_leave"
const val PASTE_AS_PLAIN_TEXT_KEY = "paste_as_plain_text"
const val FIRST_RUN_BANNER_DISMISSED_KEY = "first_run_banner_dismissed"
const val SSH_AUTO_RECONNECT_KEY = "ssh_auto_reconnect"
const val SSH_HEARTBEAT_SECONDS_KEY = "ssh_heartbeat_seconds"
const val SSH_KEEPALIVE_KEY = "ssh_keepalive"
const val LAST_CWD_KEY = "last_cwd"

/** 终端体验偏好：外观 / 键盘 & 交互 / 行为 / SSH 常用 4 分组，共 15+ 项。 */
@Singleton
class TerminalSettingsRepository @Inject constructor(
    private val kv: KVStore
) {
    val fontSizeFlow: Flow<Int> = kv.observeInt(TERMINAL_NS, FONT_SIZE_SP_KEY).map { (it ?: 12L).toInt() }

    val themeFlow: Flow<TerminalTheme> = kv.observeString(TERMINAL_NS, THEME_KEY).map { raw ->
        if (raw.isNullOrBlank()) TerminalTheme.FOLLOW_APP
        else runCatching { TerminalTheme.valueOf(raw) }
            .getOrElse {
                when (raw) {
                    "DRACULA_DARK" -> TerminalTheme.PURE_BLACK
                    "SOLARIZED_LIGHT" -> TerminalTheme.PURE_WHITE
                    "system" -> TerminalTheme.FOLLOW_APP
                    else -> TerminalTheme.FOLLOW_APP
                }
            }
    }

    val showTabBarFlow: Flow<Boolean> = kv.observeBool(TERMINAL_NS, SHOW_TAB_BAR_KEY).map { it ?: true }
    val fullExtraKeysFlow: Flow<Boolean> = kv.observeBool(TERMINAL_NS, FULL_EXTRA_KEYS_KEY).map { it ?: false }
    val scaleGesturePersistsFlow: Flow<Boolean> = kv.observeBool(TERMINAL_NS, SCALE_GESTURE_PERSISTS_KEY).map { it ?: true }
    val autoPopImeOnSwitchFlow: Flow<Boolean> = kv.observeBool(TERMINAL_NS, AUTO_POP_IME_ON_SWITCH_KEY).map { it ?: true }
    val ctrlHintShownFlow: Flow<Boolean> = kv.observeBool(TERMINAL_NS, CTRL_HINT_SHOWN_KEY).map { it ?: false }
    val newOutputIndicatorFlow: Flow<Boolean> = kv.observeBool(TERMINAL_NS, NEW_OUTPUT_INDICATOR_KEY).map { it ?: true }
    val autoNewTabOnCloseLastFlow: Flow<Boolean> = kv.observeBool(TERMINAL_NS, AUTO_NEW_TAB_ON_CLOSE_LAST_KEY).map { it ?: true }
    val keepSessionWhenLeaveFlow: Flow<Boolean> = kv.observeBool(TERMINAL_NS, KEEP_SESSION_WHEN_LEAVE_KEY).map { it ?: true }
    val pasteAsPlainTextFlow: Flow<Boolean> = kv.observeBool(TERMINAL_NS, PASTE_AS_PLAIN_TEXT_KEY).map { it ?: true }
    val firstRunBannerDismissedFlow: Flow<Boolean> = kv.observeBool(TERMINAL_NS, FIRST_RUN_BANNER_DISMISSED_KEY).map { it ?: false }
    val sshAutoReconnectFlow: Flow<Boolean> = kv.observeBool(TERMINAL_NS, SSH_AUTO_RECONNECT_KEY).map { it ?: true }
    val sshHeartbeatSecondsFlow: Flow<Int> = kv.observeInt(TERMINAL_NS, SSH_HEARTBEAT_SECONDS_KEY).map { (it ?: SshHeartbeatSeconds.S60.seconds.toLong()).toInt() }
    val sshKeepaliveFlow: Flow<Boolean> = kv.observeBool(TERMINAL_NS, SSH_KEEPALIVE_KEY).map { it ?: true }
    val lastCwdFlow: Flow<String> = kv.observeString(TERMINAL_NS, LAST_CWD_KEY).map { it ?: "" }

    // ── 写入 ──
    suspend fun saveFontSize(sp: Int) { kv.putInt(TERMINAL_NS, FONT_SIZE_SP_KEY, sp.coerceIn(8, 20).toLong()) }
    suspend fun saveTheme(theme: TerminalTheme) { kv.putString(TERMINAL_NS, THEME_KEY, theme.name) }
    suspend fun saveShowTabBar(enabled: Boolean) { kv.putBool(TERMINAL_NS, SHOW_TAB_BAR_KEY, enabled) }
    suspend fun saveFullExtraKeys(full: Boolean) { kv.putBool(TERMINAL_NS, FULL_EXTRA_KEYS_KEY, full) }
    suspend fun saveScaleGesturePersists(enabled: Boolean) { kv.putBool(TERMINAL_NS, SCALE_GESTURE_PERSISTS_KEY, enabled) }
    suspend fun saveAutoPopImeOnSwitch(enabled: Boolean) { kv.putBool(TERMINAL_NS, AUTO_POP_IME_ON_SWITCH_KEY, enabled) }
    suspend fun markCtrlHintShown() { kv.putBool(TERMINAL_NS, CTRL_HINT_SHOWN_KEY, true) }
    suspend fun resetCtrlHint() { kv.putBool(TERMINAL_NS, CTRL_HINT_SHOWN_KEY, false) }
    suspend fun saveNewOutputIndicator(enabled: Boolean) { kv.putBool(TERMINAL_NS, NEW_OUTPUT_INDICATOR_KEY, enabled) }
    suspend fun saveAutoNewTabOnCloseLast(enabled: Boolean) { kv.putBool(TERMINAL_NS, AUTO_NEW_TAB_ON_CLOSE_LAST_KEY, enabled) }
    suspend fun saveKeepSessionWhenLeave(enabled: Boolean) { kv.putBool(TERMINAL_NS, KEEP_SESSION_WHEN_LEAVE_KEY, enabled) }
    suspend fun savePasteAsPlainText(enabled: Boolean) { kv.putBool(TERMINAL_NS, PASTE_AS_PLAIN_TEXT_KEY, enabled) }
    suspend fun saveFirstRunBannerDismissed(dismissed: Boolean) { kv.putBool(TERMINAL_NS, FIRST_RUN_BANNER_DISMISSED_KEY, dismissed) }
    suspend fun saveSshAutoReconnect(enabled: Boolean) { kv.putBool(TERMINAL_NS, SSH_AUTO_RECONNECT_KEY, enabled) }
    suspend fun saveSshHeartbeatSeconds(seconds: Int) { kv.putInt(TERMINAL_NS, SSH_HEARTBEAT_SECONDS_KEY, seconds.toLong()) }
    suspend fun saveSshKeepalive(enabled: Boolean) { kv.putBool(TERMINAL_NS, SSH_KEEPALIVE_KEY, enabled) }
    suspend fun saveLastCwd(cwd: String) { kv.putString(TERMINAL_NS, LAST_CWD_KEY, cwd) }

    // ── 快照读 ──
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

object TerminalFontSizes {
    val STEPS = listOf(8, 10, 11, 12, 13, 14, 15, 16, 18, 20)
    val DEFAULT = 12
}
