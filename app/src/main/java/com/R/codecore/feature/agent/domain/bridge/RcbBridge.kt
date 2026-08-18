package com.R.codecore.feature.agent.domain.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.R.codecore.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 容器 ⇄ 宿主桥（loopback TCP）：让容器内 `rcb-*` 命令把「剪贴板/URL/通知」操作交给 App 处理。
 *
 * 设计要点（见《终端增强设计 v1.1》「Topic 2 RcbBridge」，含安全评审修正）：
 *  - **loopback TCP + 握手令牌**：绑定 127.0.0.1 随机端口（外网不可达），连接首行须带 [token]
 *    （由环境变量 RCB_BRIDGE_TOKEN 注入容器），否则直接拒绝。
 *  - **能力隔离（默认最小权限）**：`share` **不支持**（防止把容器内/整卡文件经系统分享渗出）；
 *    `open_url` 需宿主侧注入 [openUrlHandler] 且默认空实现（仅记日志，交 UI 决定是否真正打开）。
 *  - **行协议 + base64 载荷**：shell 里拼 JSON 转义易错、单行又传不了多行文本，故命令行形如
 *    `cmd [base64(param)]`，文本参数一律 base64 化，天然免转义、免行数问题。
 *  - 令牌在容器进程 env 里，同容器进程可读；故对这些进程采取「最小权限 + 关键操作宿主确认」策略。
 */
@Singleton
class RcbBridge @Inject constructor(
    @ApplicationContext private val context: android.content.Context
) {
    companion object {
        private const val TAG = "RcbBridge"
        private const val HOST = "127.0.0.1"
        private const val BIN_DIR = "bin" // 容器内即 /root/.rcodecore/bin → 已注入 PATH
    }

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var port: Int = -1

    @Volatile
    private var token: String = ""

    @Volatile
    private var started = false

    /** 打开 URL 的宿主侧实现（默认不真正打开，只记日志）。回调在主线程调用。 */
    @Volatile
    var openUrlHandler: (openUrl: String) -> Unit = { url ->
        FileLogger.i(TAG, "open_url 请求（未接线宿主实现）：$url")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rand = Random(System.currentTimeMillis())

    /** 确保服务已启动并写好 rcb-* helper，返回 "127.0.0.1:port"。首次调用才真正 bind + 写脚本。 */
    fun ensureStarted(): String {
        synchronized(this) {
            if (started) return "$HOST:$port"
            token = (0 until 24).joinToString("") { "abcdefghijklmnopqrstuvwxyz0123456789"[rand.nextInt(36)].toString() }
            val ss = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(HOST, 0))
            }
            server = ss
            port = ss.localPort
            started = true
            ensureHelpers()
            val t = Thread({ acceptLoop(ss) }, "rcb-bridge-accept")
            t.isDaemon = true
            t.start()
            FileLogger.i(TAG, "RcbBridge 就绪 at $HOST:$port")
            return "$HOST:$port"
        }
    }

    fun address(): String = ensureStarted()

    fun authToken(): String {
        ensureStarted()
        return token
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (!ss.isClosed) FileLogger.w(TAG, "accept 异常: ${e.message}")
                break
            }
            Thread({ handle(socket) }, "rcb-bridge-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(socket: java.net.Socket) {
        try {
            socket.soTimeout = 20_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(socket.getOutputStream(), true)
            if (reader.readLine()?.trim() != token) {
                writer.println("err auth_failed")
                return
            }
            var line = reader.readLine()
            while (line != null) {
                writer.println(processCmd(line))
                line = reader.readLine()
            }
        } catch (_: Exception) {
            // 连接结束 / 单条命令异常不影响下一连接
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun processCmd(raw: String): String {
        val sp = raw.indexOf(' ')
        val cmd = if (sp < 0) raw else raw.substring(0, sp)
        val b64 = if (sp < 0) null else raw.substring(sp + 1)
        val arg = b64?.let { runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
        return when (cmd) {
            "clipboard_get" -> {
                val text = clipboardText()
                "ok ${Base64.getEncoder().encodeToString((text ?: "").toByteArray(Charsets.UTF_8))}"
            }
            "clipboard_set" -> if (arg == null) "err missing_text" else {
                setClipboard(arg)
                "ok"
            }
            "open_url" -> if (arg.isNullOrBlank()) "err missing_url" else {
                mainHandler.post { openUrlHandler(arg) }
                "ok"
            }
            "toast" -> {
                mainHandler.post { Toast.makeText(context, arg?.takeIf { it.isNotBlank() } ?: "(空)", Toast.LENGTH_SHORT).show() }
                "ok"
            }
            "notify" -> {
                mainHandler.post { Toast.makeText(context, arg?.takeIf { it.isNotBlank() } ?: "通知", Toast.LENGTH_LONG).show() }
                "ok"
            }
            // 安全：跨进程系统分享默认禁用（防容器内文件渗出）。
            "share" -> "err capability_disabled"
            else -> "err unknown_cmd"
        }
    }

    private fun clipboardText(): String? {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    private fun setClipboard(text: String) {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("rcb-bridge", text))
    }

    // ───────────────────────────── helper 脚本生成 ─────────────────────────────

    /** 生成 `rcb-send` 引擎与各 `rcb-*` wrapper，写入 filesDir/rcodecore/bin（容器内 /root/.rcodecore/bin）。 */
    private fun ensureHelpers() {
        runCatching {
            val binDir = java.io.File(java.io.File(context.filesDir, "rcodecore"), BIN_DIR)
            binDir.mkdirs()
            writeHelper(binDir, "rcb-send",
                """
                |#!/bin/bash
                |# rcb-send 引擎：读 env 令牌，发一条 `cmd [base64(param)]`，输出宿主编译的回复行。
                |set -u
                |CMD="\${1:-}"
                |ARG="\${2:-}"
                |ADDR="\${RCB_BRIDGE_ADDR:-127.0.0.1:0}"
                |TOKEN="\${RCB_BRIDGE_TOKEN:-}"
                |HOST="\${ADDR%%:*}"
                |PORT="\${ADDR##*:}"
                |[ -n "\$TOKEN" ] && [ "\$PORT" != 0 ] || { echo "err bridge_not_ready"; exit 1; }
                |B64=""
                |[ -n "\$ARG" ] && B64="\$(printf '%s' "\$ARG" | base64 | tr -d '\n')"
                |exec 3<>"/dev/tcp/\$HOST/\$PORT" || { echo "err connect_failed"; exit 1; }
                |printf '%s\n' "\$TOKEN" >&3
                |printf '%s\n' "\$CMD \$B64" >&3
                |IFS= read -r REPLY <&3
                |exec 3<&- 3>&-
                |printf '%s\n' "\$\{REPLY:-err no_reply}"
                |""".trimMargin())
            writeHelper(binDir, "rcb-clipboard",
                """
                |#!/bin/bash
                |# rcb-clipboard get|set [text]
                |MODE="\${1:-}"
                |case "\$MODE" in
                |  get)
                |    REPLY="\$(rcb-send clipboard_get)"
                |    case "\$REPLY" in
                |      ok\ *)
                |        printf '%s' "\${REPLY#ok }" | base64 -d
                |        echo
                |        ;;
                |      *)
                |        echo "\$REPLY" >&2
                |        exit 1
                |        ;;
                |    esac
                |    ;;
                |  set)
                |    shift
                |    rcb-send clipboard_set "\${*:-}"
                |    ;;
                |  *)
                |    echo "用法: rcb-clipboard get|set [text]" >&2
                |    exit 1
                |    ;;
                |esac
                |""".trimMargin())
            writeHelper(binDir, "rcb-open-url",
                """
                |#!/bin/bash
                |# rcb-open-url URL：让宿主 App 打开链接（需宿主侧接线，否则仅记日志）
                |[ -n "\$1" ] || { echo "用法: rcb-open-url URL" >&2; exit 1; }
                |rcb-send open_url "\$1"
                |""".trimMargin())
            writeHelper(binDir, "rcb-open",
                """
                |#!/bin/bash
                |# rcb-open PATH：把路径交给宿主（作为 URL/file 处理，需宿主接线）
                |[ -n "\$1" ] || { echo "用法: rcb-open PATH" >&2; exit 1; }
                |rcb-send open_url "\$1"
                |""".trimMargin())
            writeHelper(binDir, "rcb-toast",
                """
                |#!/bin/bash
                |# rcb-toast 文本：宿主弹短暂提示
                |shift 2>/dev/null
                |rcb-send toast "\${*:-}"
                |""".trimMargin())
            writeHelper(binDir, "rcb-notify",
                """
                |#!/bin/bash
                |# rcb-notify 文本：宿主弹较长提示（当前降级为 toast）
                |shift 2>/dev/null
                |rcb-send notify "\${*:-}"
                |""".trimMargin())
            writeHelper(binDir, "rcb-share",
                """
                |#!/bin/bash
                |# rcb-share 默认禁用（防算式文件渗出容器）；如需请宿主侧显式解除能力。
                |echo "rcb-share: capability disabled by design" >&2
                |exit 1
                |""".trimMargin())
            FileLogger.i(TAG, "rcb-* helpers 已就绪: ${binDir.absolutePath}")
        }.onFailure { FileLogger.w(TAG, "生成 rcb-* helpers 失败: ${it.message}") }
    }

    private fun writeHelper(binDir: java.io.File, name: String, script: String) {
        val f = java.io.File(binDir, name)
        f.writeText(script, Charsets.UTF_8)
        f.setExecutable(true, false)
    }
}