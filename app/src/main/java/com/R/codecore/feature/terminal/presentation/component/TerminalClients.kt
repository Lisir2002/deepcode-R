package com.R.codecore.feature.terminal.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.R.codecore.core.util.FileLogger
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * 由额外按键行（Esc/Ctrl/Alt 等）驱动的虚拟修饰键状态。
 *
 * [TerminalView] 在分发按键/字符时会回调 `readControlKey()` 等读取这些标志，
 * 从而支持手机软键盘上没有的 Ctrl/Alt 组合（如 Ctrl-C）。
 *
 * 标志用 Compose 可观察状态承载：额外按键行据此渲染「已按下」高亮，让用户看清当前是否
 * 预置了 Ctrl/Alt。读写都发生在 UI 线程（按键分发、点击、consume 均在主线程），故安全。
 */
class TerminalKeyModifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var shift by mutableStateOf(false)
    var fn by mutableStateOf(false)

    /** 一次性修饰键：发出一个字符后自动复位（贴近物理键盘按一下即用的直觉）。 */
    fun consume() {
        ctrl = false
        alt = false
        shift = false
        fn = false
    }
}

/**
 * [TerminalSessionClient]：会话产生输出/标题/结束等事件时回调。
 * 主要职责是把屏幕刷新转交给 [TerminalView]，并接驳系统剪贴板。
 *
 *  同时：每次 onTextChanged（会话写入字符到终端后）时通知 [TextInputTracker]
 *   刷新光标行/列，保证 prompt 长度和当前行判定精确。
 */
class AppTerminalSessionClient(
    private val context: Context,
    private val viewProvider: () -> TerminalView?,
    private val onFinished: (TerminalSession) -> Unit,
    private val inputTrackerProvider: () -> TextInputTracker? = { null }
) : TerminalSessionClient {

    private companion object { const val TAG = "TerminalSession" }

    // 上次光标位置（用于检测是否有实际字符写入）
    private var lastCursorRow = -1
    private var lastCursorCol = -1

    override fun onTextChanged(changedSession: TerminalSession) {
        val v = viewProvider()
        val emu = v?.mEmulator
        val tracker = inputTrackerProvider()
        if (emu != null && tracker != null) {
            val newRow = emu.cursorRow
            val newCol = emu.cursorCol
            val changed = (newRow != lastCursorRow) || (newCol != lastCursorCol)
            tracker.syncCursor(newRow = newRow, newCol = newCol, lastDisplayedChange = changed)
            lastCursorRow = newRow
            lastCursorCol = newCol
        }
        v?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) { /* 暂不展示标题 */ }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        onFinished(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        if (text.isNotEmpty()) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            session?.write(bytes, 0, bytes.size)
        }
    }

    override fun onBell(session: TerminalSession) { /* 忽略响铃 */ }
    override fun onColorsChanged(session: TerminalSession) { viewProvider()?.onScreenUpdated() }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { FileLogger.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { FileLogger.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { FileLogger.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        FileLogger.e(tag ?: TAG, message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { FileLogger.e(tag ?: TAG, "", e) }
}

/**
 * [TerminalViewClient]：视图层的输入/手势/缩放回调。
 * 用合理默认值实现，并把 Ctrl/Alt 等虚拟修饰键交给 [TerminalKeyModifiers]。
 *
 *  额外：把每个用户输入字节同时写入 [TextInputTracker]，实现「剪切功能」的
 *   B 方案（字符级高精度追踪：仅用户输入区可剪切，系统输出区不可剪切）。
 */
class AppTerminalViewClient(
    private val context: Context,
    private val viewProvider: () -> TerminalView?,
    private val modifiers: TerminalKeyModifiers,
    private val inputTrackerProvider: () -> TextInputTracker? = { null }
) : TerminalViewClient {

    private companion object { const val TAG = "TerminalView" }

    // ── 双指缩放字号：交由外部 ViewModel 档位化处理 ─────────────
    private var scaleListener: ((Float) -> Unit)? = null
    fun setScaleListener(l: ((Float) -> Unit)?) { scaleListener = l }

    // 缩放：返回 1.0 表示不做「默认缩放处理」，同时把缩放量交给外部档位化。
    override fun onScale(scale: Float): Float {
        scaleListener?.invoke(scale)
        return 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        val view = viewProvider() ?: return
        view.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = modifiers.ctrl
    override fun readAltKey(): Boolean = modifiers.alt
    override fun readShiftKey(): Boolean = modifiers.shift
    override fun readFnKey(): Boolean = modifiers.fn

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        // 把 codepoint → UTF-8 字节后转交给 TextInputTracker 做精确列/字节追踪
        inputTrackerProvider()?.let { tracker ->
            val view = viewProvider()
            val emu = view?.mEmulator
            val r = emu?.cursorRow ?: -1
            val c = emu?.cursorCol ?: -1
            val bytes = if (codePoint <= 0x7F) {
                byteArrayOf(codePoint.toByte())
            } else try {
                String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            } catch (_: Throwable) {
                byteArrayOf()
            }
            if (bytes.isNotEmpty()) tracker.onUserBytes(bytes, r, c)
        }
        // 字符已发出，复位一次性修饰键
        modifiers.consume()
        return false
    }

    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) { FileLogger.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { FileLogger.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { FileLogger.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        FileLogger.e(tag ?: TAG, message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { FileLogger.e(tag ?: TAG, "", e) }
}
