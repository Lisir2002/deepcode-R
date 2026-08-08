package com.deep.rcode.feature.terminal.presentation.component

import com.deep.rcode.feature.terminal.domain.TerminalTab
import java.util.LinkedList
import kotlin.math.max
import kotlin.math.min

/**
 * 字符级输入追踪（B 方案，高精度）。
 *
 *  记录当前行「提示符长度」与「用户实际输入字节序列」，支持：
 *    - 按字节写入（用户输入）、按字节追加（终端输出）
 *    - 解析控制字符（\b 退格、\r 回车、\n 换行、^C Ctrl-C 中断、^D EOF、^U 擦除当前行、^W 擦除前一个词、方向键 \u001b[D/C）
 *    - 计算"选中区是否完全属于用户可剪切区域"：
 *        1. 选择必须是单行
 *        2. 选择起点列 >= 提示符长度
 *        3. 选择终点行 == 当前光标行
 *        4. 选择字节范围必须恰好落在已记录的输入缓冲区内（或更短）
 *    - 执行剪切动作（返回要删除的字节数，供上层发 \x7f 退格）
 *
 *  为什么要记录字节数？因为 terminal 列宽度和实际 UTF-8 字节未必一致（宽字符/全角），
 *  但删除动作是按字节退格的（每个 \x7f 删掉最后一个用户写进 pty 的字节）。
 */
class TextInputTracker {

    // 当前行提示符长度（列数），>=0。提示符本身不可剪切。
    var promptColLen: Int = DEFAULT_PROMPT_COL_LEN
        private set

    // 当前行内"用户已输入"字节（只包含用户发出的字符，不含 shell 回显的提示符输出）。
    private val userInputBytes = LinkedList<Byte>()

    // 当前行内已记录"用户输入"对应的列宽度（因为一个 UTF-8 序列可能占 0/1/2 列）。
    private var userInputCols: Int = 0

    // 当前光标所在行（相对于屏幕顶部为 0）。当用户的写入光标和终端输出光标行不一致时，
    // 判定为"程序输出"，此时该行视为系统输出，整行不可剪切。
    var cursorRow: Int = -1
        private set

    // 当前"视觉输入列"起点（= promptColLen + userInputCols）。
    // 用它判断"新到来的输出字节是否回显到用户刚输入的位置"（避免回显导致重复计数）。
    val inputStartCol: Int get() = promptColLen
    val inputEndCol: Int get() = promptColLen + userInputCols

    // 上次处理 \n / \r / ^C / ^D / 方向键到行尾 后遇到的提示符正则匹配锚点：
    // 当检测到"用户在新行输入字符前 shell 已输出一段提示符"时，我们通过对齐光标列重算 promptColLen。
    private var pendingAnchorCol: Int = -1

    // 用户最近一次按下的方向键 / 删除键等"非字节输入控制序列"要被扣除，避免把它们算入输入。
    fun reset() {
        promptColLen = DEFAULT_PROMPT_COL_LEN
        userInputBytes.clear()
        userInputCols = 0
        cursorRow = -1
        pendingAnchorCol = -1
    }

    /**
     * 对某个 Tab 绑定追踪器（懒加载单例，Tab 级生命周期）。
     */
    companion object {
        const val DEFAULT_PROMPT_COL_LEN = 24  // > root@localhost:~/xxx# 粗略长度，检测到真实提示符后会修正

        private val registry = mutableMapOf<String, TextInputTracker>()

        fun forTab(tab: TerminalTab): TextInputTracker =
            registry.getOrPut(tab.id) { TextInputTracker() }

        fun onTabClosed(tabId: String) { registry.remove(tabId) }
    }

    // ────────────── 事件入口 ──────────────────────────────────────

    /** 终端发生了一次输出（shell/程序把字节写给终端）。
     *  @param newCursorRow 输出后的屏幕光标行
     *  @param newCursorCol 输出后的屏幕光标列
     *  @param displayedRowColChanged 是否真的有字符被打印到屏幕（非纯控制）
     */
    fun onTerminalOutput(newCursorRow: Int, newCursorCol: Int, displayedRowColChanged: Boolean) {
        // 光标行切换 → 进入新行：如果旧行还在等提示符锚点则丢弃当前行输入缓冲
        if (newCursorRow != cursorRow) {
            if (displayedRowColChanged && cursorRow != -1 && newCursorCol > 0) {
                // shell 在新行开头先输出提示符：记录 prompt 列起点
                pendingAnchorCol = newCursorCol
            }
            // 用户进入了新行，之前的 userInputBytes 属于上一行（命令已执行/退出）→ 重置单行输入态
            userInputBytes.clear()
            userInputCols = 0
            promptColLen = newCursorCol.coerceAtLeast(DEFAULT_PROMPT_COL_LEN)
            cursorRow = newCursorRow
            return
        }
        // 同行：光标列往前挪了（shell 正在输出提示符，用户还没输入）
        if (pendingAnchorCol == -1 && userInputBytes.isEmpty() && newCursorCol > promptColLen) {
            promptColLen = newCursorCol
        }
        cursorRow = newCursorRow
    }

    /** 用户向 pty 写入原始字节（键盘 / 粘贴 / ExtraKeys 发出的字节）。
     *  @param utf8Bytes 用户要写的原始字节
     *  @param cursorRowBefore 当前光标行（写入前）
     *  @param cursorColBefore 当前光标列（写入前）
     */
    fun onUserBytes(
        utf8Bytes: ByteArray,
        cursorRowBefore: Int,
        cursorColBefore: Int
    ) {
        if (cursorRowBefore != this.cursorRow) {
            // 光标行切换：丢弃旧行追踪态，重新锚定 prompt
            this.cursorRow = cursorRowBefore
            userInputBytes.clear()
            userInputCols = 0
            promptColLen = cursorColBefore.coerceAtLeast(DEFAULT_PROMPT_COL_LEN)
        }
        for (b in utf8Bytes) {
            val v = b.toInt() and 0xFF
            when {
                // \r（回车，回到行首）
                v == 0x0D -> {
                    userInputBytes.clear()
                    userInputCols = 0
                }
                // \n（换行，命令被执行，进入下一行）
                v == 0x0A -> {
                    userInputBytes.clear()
                    userInputCols = 0
                    // promptColLen 留给 onTerminalOutput 下次重算
                }
                // DEL / BS（退格：0x7F / 0x08）
                v == 0x7F || v == 0x08 -> {
                    if (userInputBytes.isNotEmpty()) {
                        // 正确处理 UTF-8：从后往前找到字符头，整字删掉
                        var removed = 1
                        while (removed < userInputBytes.size) {
                            val last = userInputBytes[userInputBytes.size - removed].toInt() and 0xFF
                            if (last and 0xC0 != 0x80) break // 非延续字节：字符头
                            removed++
                        }
                        repeat(removed) { userInputBytes.removeLast() }
                        // 视觉列减 1（简化：中文字符按 2 列处理——但没记录宽度时最多减 2）
                        userInputCols = max(0, userInputCols - 1)
                    }
                }
                // ^C（中断，当前行丢弃）
                v == 0x03 -> {
                    userInputBytes.clear()
                    userInputCols = 0
                }
                // ^U（擦除当前行输入）
                v == 0x15 -> {
                    userInputBytes.clear()
                    userInputCols = 0
                }
                // ^W（擦除前一个词）
                v == 0x17 -> {
                    while (userInputBytes.isNotEmpty()) {
                        val b2 = userInputBytes.removeLast()
                        val c = (b2.toInt() and 0xFF).toChar()
                        if (c.isWhitespace() || c in " /;|&(){}[]<>\"'") break
                    }
                    userInputCols = max(0, userInputCols - 1)
                }
                // ESC 控制序列开头：原样记录，后续交给 onTerminalOutput 判定
                v == 0x1B -> {
                    // ESC 序列只记 1 个字节起始，后续 append 会继续（方向键等是 \u001b[XD / \u001b[XC）
                    // 方向键：如果用户移动光标导致列位变化，光标列会在下一次 onTerminalOutput 更新
                    userInputBytes.add(b)
                }
                // 普通 ASCII / UTF-8 延续字节
                else -> {
                    userInputBytes.add(b)
                    // 粗略估算列宽度：ASCII=1，中文等高位字节按字符头判断 2 列
                    if (v and 0xC0 != 0x80) {
                        val isWide = when {
                            v >= 0xE4 && v <= 0xE9 -> true  // 中日韩 UTF-8 3字节起始
                            v >= 0xC0 && v <= 0xDF -> false // 2字节拉丁文/希腊文等
                            v >= 0xF0 && v <= 0xF4 -> true  // 4字节 emoji
                            v < 0x20 || v == 0x7F -> 0      // 控制字符不计列
                            else -> v >= 0x80
                        }
                        if (isWide !is Boolean && isWide == 0) {
                            // no-op
                        } else if (isWide == true) {
                            userInputCols += 2
                        } else {
                            userInputCols += 1
                        }
                    }
                }
            }
        }
    }

    /** 光标列/行更新（onScreenUpdated 之后同步一次），主要用于更新 promptColLen 与 cursorRow。
     *  @param lastDisplayedChange 最近一次输出到屏幕时光标从 (oldCol,oldRow)→(newCol,newRow) 是否有实际字符写入。
     */
    fun syncCursor(newRow: Int, newCol: Int, lastDisplayedChange: Boolean) {
        onTerminalOutput(newRow, newCol, lastDisplayedChange)
    }

    // ────────────── 业务查询 ──────────────────────────────────────

    data class SelectionSpan(
        val startRow: Int, val startCol: Int,
        val endRow: Int, val endCol: Int,
        val selectedText: String
    )

    /** 判断选中区是否属于「纯用户输入、可剪切」范围。
     *
     *  返回：可剪切字节数（> 0 表示可剪切，值就是要发多少个 \x7f 退格字节）；
     *        0 表示不能剪切（包含系统输出内容或跨行）。
     */
    fun cutEligibleBytes(span: SelectionSpan, emulatorCols: Int): Int {
        // 1) 必须单行
        if (span.startRow != span.endRow) return 0
        // 2) 必须和当前光标行一致（不允许对已输出/执行过的旧行剪切）
        if (span.startRow != cursorRow) return 0
        // 3) 选择起点列必须 >= promptColLen（提示符之后）
        if (span.startCol < promptColLen) return 0
        // 4) 选择终点列必须 <= promptColLen + userInputCols
        if (span.endCol > promptColLen + max(1, userInputCols)) return 0
        // 5) 字节到列的反向换算：从 (startCol - promptColLen) 到 (endCol - promptColLen)
        //    用 userInputBytes 里 UTF-8 序列做一次列计数
        val wantStartColOff = span.startCol - promptColLen
        val wantEndColOff = span.endCol - promptColLen
        if (wantEndColOff <= wantStartColOff) return 0
        return countByteRangeForColRange(wantStartColOff, wantEndColOff)
    }

    /** 在 userInputBytes 中定位要想覆盖列 [startColOff..endColOff) 需要从末尾回退多少个字节。
     *  因为退格 (\x7f) 是从末尾往前删的，这里直接返回 bytesBackCount。
     */
    private fun countByteRangeForColRange(startColOff: Int, endColOff: Int): Int {
        var bytesFromStart = 0
        var colsFromStart = 0
        var cutEndBytes = -1
        var cutStartBytes = -1
        while (bytesFromStart < userInputBytes.size) {
            if (colsFromStart >= startColOff && cutStartBytes == -1) cutStartBytes = bytesFromStart
            if (colsFromStart >= endColOff && cutEndBytes == -1) cutEndBytes = bytesFromStart
            val b = userInputBytes[bytesFromStart].toInt() and 0xFF
            var stepBytes = 1
            var stepCols = 1
            when {
                b and 0xC0 == 0x80 -> {
                    // UTF-8 延续字节：跳过，不新增列
                    stepCols = 0
                }
                b >= 0xF0 -> { stepBytes = 4; stepCols = 2 }  // 4字节emoji，宽 2
                b >= 0xE0 -> {
                    stepBytes = 3
                    // CJK（0xE4-0xE9 / 0xEF 部分）占 2 列；其余 3 字节区域（希腊附/阿拉伯等）占 1
                    stepCols = if (b in 0xE4..0xE9 || b == 0xE3 || b == 0xEA || b == 0xEB || b == 0xEC || b == 0xED || b == 0xEE) 2 else 1
                }
                b >= 0xC0 -> { stepBytes = 2; stepCols = 1 } // 2 字节 = 1 列
                b < 0x20 || b == 0x7F -> stepCols = 0       // 控制字符
            }
            bytesFromStart += stepBytes
            colsFromStart += stepCols
        }
        if (cutStartBytes == -1) cutStartBytes = userInputBytes.size
        if (cutEndBytes == -1) cutEndBytes = userInputBytes.size
        // 剪切从末尾删：返回 cutEndBytes..cutStartBytes 区间的大小即字节数
        return max(0, cutEndBytes - cutStartBytes)
    }

    /** 工具：取当前用户输入的字节快照（调试或回显校验）。 */
    fun inputBytesSnapshot(): ByteArray = userInputBytes.toByteArray()

    /** 工具：当前提示符列长度（调试用）。 */
    fun debugInfo(): String = "prompt=$promptColLen inputCols=$userInputCols row=$cursorRow bytes=${userInputBytes.size}"
}
