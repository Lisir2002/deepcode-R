package com.R.codecore.feature.agent.domain.tool.container

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.CommandEngine
import com.R.codecore.feature.agent.domain.container.CommandEvent
import com.R.codecore.feature.agent.domain.container.LinuxContainerEngine
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.StreamingAgentTool
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.agent.domain.tool.ToolResult
import com.R.codecore.feature.agent.domain.tool.ToolStreamEvent
import com.R.codecore.feature.terminal.data.bundle.TerminalBundleId
import com.R.codecore.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 在 aarch64/ARM64 手机容器里一键准备 Android APK 构建环境：
 * 1. 装 JDK (openjdk17)
 * 2. 装/下载 Android commandline-tools (sdkmanager)
 * 3. sdkmanager 安装 Platform / Build-Tools / Platform-Tools
 * 4. 应用 QEMU x86 → aarch64 wrapper（先装 QEMU_X86_TRANSLATOR bundle，再执行 wrapper 生成脚本）
 * 5. 把 ANDROID_HOME/JAVA_HOME 写入 /root/.rcodecore/env.sh，后续 Bash / terminal 登录自动 source
 *
 * 参数全部可选；不传则按 SOP 默认值执行（platforms=android-34，build-tools=34.0.0，gradle 自举）。
 * 工具幂等：已经装好的步骤会直接跳过并标记「already installed」。
 * 结果字段：
 *   { ok: boolean, arch: string, steps: [...], env: { JAVA_HOME, ANDROID_HOME }, summary: string }
 */
class EnsureAndroidEnvTool @Inject constructor(
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val containerEngine: LinuxContainerEngine,
) : AgentTool(), StreamingAgentTool {

    companion object {
        const val TAG = "EnsureAndroidEnvTool"
        /** 整体超时：jdk+sdk+wrapper 三段式，网络慢时约 15 分钟完成，上限 50 分钟。 */
        const val TIMEOUT_MS = 50 * 60 * 1000L
        /** 建议缺省的 Platform / Build-Tools / CmdlineTools 版本。 */
        const val DEFAULT_PLATFORM = "android-34"
        const val DEFAULT_BUILD_TOOLS = "34.0.0"
        const val DEFAULT_CMDLINE_TOOLS_VERSION = "11076708" // cmdline-tools 12.0
        const val ENV_SH = "/root/.rcodecore/env.sh"
    }

    override val name = "ensure_android_env"
    override val description = buildString {
        append("在当前执行环境（PRoot Linux 容器）中一键准备 Android APK 构建环境，")
        append("尤其适用于 aarch64/ARM64 手机 + QEMU 转译的场景。")
        append("自动完成：安装 JDK 17、下载 cmdline-tools (sdkmanager)、安装 Android Platform/Build-Tools/Platform-Tools、")
        append("自动将 Build-Tools 中 x86_64 二进制（aapt2/zipalign/split-select 等）包装为 qemu-x86_64 调用，")
        append("并把 ANDROID_HOME / JAVA_HOME 写入 $ENV_SH 供后续 Bash/terminal 登录自动 source。")
        append("每一步都是幂等的；如果某步失败会返回 ok=false 并在 steps[i].error 给出原因。")
        append("建议在第一次尝试构建 Android 项目前调用一次即可。")
    }
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "install_java" to ToolParameter(
            name = "install_java",
            type = ParameterType.BOOLEAN,
            description = "是否安装 openjdk17（容器内尚无 java 时自动安装，默认 true）。",
            required = false
        ),
        "install_sdk" to ToolParameter(
            name = "install_sdk",
            type = ParameterType.BOOLEAN,
            description = "是否下载 cmdline-tools 并初始化 ANDROID_HOME（默认 true）。",
            required = false
        ),
        "sdk_packages" to ToolParameter(
            name = "sdk_packages",
            type = ParameterType.ARRAY,
            description = "传给 sdkmanager 的安装包列表，默认 [\"platforms;$DEFAULT_PLATFORM\",\"build-tools;$DEFAULT_BUILD_TOOLS\",\"platform-tools\"]。",
            required = false,
            itemsSchema = mapOf("type" to "string")
        ),
        "cmdline_tools_version" to ToolParameter(
            name = "cmdline_tools_version",
            type = ParameterType.STRING,
            description = "commandlinetools-linux 号（对应 cmdline-tools 12.0 默认 $DEFAULT_CMDLINE_TOOLS_VERSION）。",
            required = false
        ),
        "apply_wrapper" to ToolParameter(
            name = "apply_wrapper",
            type = ParameterType.BOOLEAN,
            description = "在 aarch64 上是否自动安装 x86 构建转译器 bundle，并运行 rcodecore-wrap-android-buildtools（默认 true）。",
            required = false
        ),
        "write_env_sh" to ToolParameter(
            name = "write_env_sh",
            type = ParameterType.BOOLEAN,
            description = "是否把 JAVA_HOME / ANDROID_HOME / PATH 追加写入 $ENV_SH（默认 true）。",
            required = false
        ),
        "accept_licenses" to ToolParameter(
            name = "accept_licenses",
            type = ParameterType.BOOLEAN,
            description = "是否自动 yes | sdkmanager --licenses 接受 Android SDK 许可（默认 true）。",
            required = false
        ),
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val installJava = args["install_java"]?.jsonPrimitive?.booleanOrNull ?: true
            val installSdk = args["install_sdk"]?.jsonPrimitive?.booleanOrNull ?: true
            val applyWrapper = args["apply_wrapper"]?.jsonPrimitive?.booleanOrNull ?: true
            val writeEnvSh = args["write_env_sh"]?.jsonPrimitive?.booleanOrNull ?: true
            val acceptLicenses = args["accept_licenses"]?.jsonPrimitive?.booleanOrNull ?: true
            val cmdlineVer = args["cmdline_tools_version"]?.jsonPrimitive?.contentOrNull
                ?: DEFAULT_CMDLINE_TOOLS_VERSION
            val pkgsArg = (args["sdk_packages"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it.isNotBlank() }?.distinct()
            val sdkPackages = if (pkgsArg.isNullOrEmpty()) {
                listOf(
                    "platforms;$DEFAULT_PLATFORM",
                    "build-tools;$DEFAULT_BUILD_TOOLS",
                    "platform-tools"
                )
            } else pkgsArg

            val script = buildScript(
                installJava = installJava,
                installSdk = installSdk,
                applyWrapper = applyWrapper,
                writeEnvSh = writeEnvSh,
                acceptLicenses = acceptLicenses,
                cmdlineVer = cmdlineVer,
                sdkPackages = sdkPackages,
            )
            val out = commandEngine.runCommandSync(
                command = script,
                projectPath = workspaceRepository.currentPath(),
                timeoutMs = TIMEOUT_MS,
            )

            // 异步确保 QEMU 转译器 bundle 已安装（独立于容器内 apk 包）
            if (applyWrapper) {
                runCatching {
                    containerEngine.ensureInstalled()
                    if (!containerInstalledSnap(TerminalBundleId.QEMU_X86_TRANSLATOR)) {
                        runCatching { containerEngine.installBundle(TerminalBundleId.QEMU_X86_TRANSLATOR) }
                            .onFailure { FileLogger.w(TAG, "自动安装 QEMU_X86_TRANSLATOR bundle 失败：${it.message}", it) }
                    }
                }
            }

            ToolResult.Success(parseResult(out))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "ensure_android_env 失败", e)
            ToolResult.Error("prepare android env failed: ${e.message}", code = "ENV_PREP_FAILED")
        }
    }

    private suspend fun containerInstalledSnap(id: TerminalBundleId): Boolean {
        return containerEngine.isBundleInstalled(id)
    }

    /**
     * 流式执行：把 [buildScript] 产出的脚本经 [CommandEngine.runCommandStream] 逐行收集，
     * 每行若以 `[STEP|...]` 开头则实时转成 [ToolStreamEvent.Progress]（去掉 STEP 方括号标记，
     * 展示如「[java|ok] 已安装 openjdk17...」），脚本结束后聚合 [parseResult] 作为
     * [ToolStreamEvent.Completed] 结果喂回模型（与 [execute] 的最终结果一致）。
     * [execute] 作为非流式兜底保留。
     */
    override fun executeStream(
        args: Map<String, JsonElement>,
        context: com.R.codecore.feature.agent.domain.model.AgentContext
    ): Flow<ToolStreamEvent> = flow {
        val installJava = args["install_java"]?.jsonPrimitive?.booleanOrNull ?: true
        val installSdk = args["install_sdk"]?.jsonPrimitive?.booleanOrNull ?: true
        val applyWrapper = args["apply_wrapper"]?.jsonPrimitive?.booleanOrNull ?: true
        val writeEnvSh = args["write_env_sh"]?.jsonPrimitive?.booleanOrNull ?: true
        val acceptLicenses = args["accept_licenses"]?.jsonPrimitive?.booleanOrNull ?: true
        val cmdlineVer = args["cmdline_tools_version"]?.jsonPrimitive?.contentOrNull
            ?: DEFAULT_CMDLINE_TOOLS_VERSION
        val pkgsArg = (args["sdk_packages"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }?.distinct()
        val sdkPackages = if (pkgsArg.isNullOrEmpty()) {
            listOf(
                "platforms;$DEFAULT_PLATFORM",
                "build-tools;$DEFAULT_BUILD_TOOLS",
                "platform-tools"
            )
        } else pkgsArg

        val script = buildScript(
            installJava = installJava,
            installSdk = installSdk,
            applyWrapper = applyWrapper,
            writeEnvSh = writeEnvSh,
            acceptLicenses = acceptLicenses,
            cmdlineVer = cmdlineVer,
            sdkPackages = sdkPackages,
        )
        // 全量收集输出：parseResult 依赖完整内容解析 STEP/ENV/ARCH 行。
        val accumulated = StringBuilder()
        try {
            commandEngine.runCommandStream(
                command = script,
                projectPath = workspaceRepository.currentPath(),
                timeoutMs = TIMEOUT_MS,
            ).collect { event ->
                when (event) {
                    is CommandEvent.Line -> {
                        accumulated.append(event.text).append('\n')
                        stepLineToDisplay(event.text)?.let { emit(ToolStreamEvent.Progress(it)) }
                    }
                    is CommandEvent.Exit -> { /* 结束在流完成后统一聚合 */ }
                }
            }

            // 异步确保 QEMU 转译器 bundle 已安装（独立于容器内 apk 包）
            if (applyWrapper) {
                runCatching {
                    containerEngine.ensureInstalled()
                    if (!containerInstalledSnap(TerminalBundleId.QEMU_X86_TRANSLATOR)) {
                        runCatching { containerEngine.installBundle(TerminalBundleId.QEMU_X86_TRANSLATOR) }
                            .onFailure { FileLogger.w(TAG, "自动安装 QEMU_X86_TRANSLATOR bundle 失败：${it.message}", it) }
                    }
                }
            }
            emit(ToolStreamEvent.Completed(ToolResult.Success(parseResult(accumulated.toString()))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "ensure_android_env(流式) 失败", e)
            emit(ToolStreamEvent.Completed(ToolResult.Error("prepare android env failed: ${e.message}", code = "ENV_PREP_FAILED")))
        }
    }

    /**
     * 把一行 `[STEP|id|status|msg]` 转成进度展示文本（去掉 STEP 方括号标记），
     * 展示如「[java|ok] 已安装 openjdk17...」；非 STEP 行返回 null。
     */
    private fun stepLineToDisplay(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("[STEP|") || !trimmed.contains(']')) return null
        val inside = trimmed.substringAfter('[').substringBefore(']')
        val rest = trimmed.substringAfter(']').trim()
        val parts = inside.split('|', limit = 4)
        if (parts.size < 2 || parts[0] != "STEP") return null
        val id = parts.getOrElse(1) { "?" }
        val status = parts.getOrElse(2) { "unknown" }
        val msg = parts.getOrElse(3) { rest }
        return "[$id|$status] $msg"
    }

    /**
     * 把结果脚本输出解析为结构化 JSON：每一行 [STEP id|status|msg|hint]
     * 最后一行 [ENV|JAVA_HOME=x;ANDROID_HOME=y]。
     */
    private fun parseResult(out: String): JsonObject {
        val steps = mutableListOf<JsonObject>()
        var javaHome: String? = null
        var androidHome: String? = null
        var arch = ""
        var overallOk = true
        out.lineSequence().forEach { rLine ->
            val line = rLine.trim()
            if (!line.startsWith('[') || !line.contains(']')) {
                if (line.startsWith("ENV|")) {
                    val kv = line.removePrefix("ENV|").split(';').mapNotNull { seg ->
                        val (k, v) = seg.split('=', limit = 2) + listOf("", "")
                        k.trim() to v.trim()
                    }.toMap()
                    javaHome = kv["JAVA_HOME"]
                    androidHome = kv["ANDROID_HOME"]
                } else if (line.startsWith("ARCH|")) {
                    arch = line.removePrefix("ARCH|").trim()
                }
                return@forEach
            }
            val inside = line.substringAfter('[').substringBefore(']')
            val rest = line.substringAfter(']').trim()
            val parts = inside.split('|', limit = 4)
            if (parts.size >= 2 && parts[0] == "STEP") {
                val id = parts.getOrElse(1) { "?" }
                val status = parts.getOrElse(2) { "unknown" }
                val msg = parts.getOrElse(3) { rest }
                if (status == "error") overallOk = false
                steps += JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(id),
                        "status" to JsonPrimitive(status),
                        "message" to JsonPrimitive(msg),
                    )
                )
            }
        }
        val summary = buildString {
            append("steps=${steps.size} ok=$overallOk")
            if (arch.isNotBlank()) append(" arch=$arch")
            androidHome?.let { append(" ANDROID_HOME=$it") }
            javaHome?.let { append(" JAVA_HOME=$it") }
        }
        return JsonObject(
            mapOf(
                "ok" to JsonPrimitive(overallOk),
                "arch" to (if (arch.isBlank()) JsonNull else JsonPrimitive(arch)),
                "steps" to JsonArray(steps),
                "env" to JsonObject(
                    buildMap {
                        put("JAVA_HOME", javaHome?.let(::JsonPrimitive) ?: JsonNull)
                        put("ANDROID_HOME", androidHome?.let(::JsonPrimitive) ?: JsonNull)
                    }
                ),
                "summary" to JsonPrimitive(summary)
            )
        )
    }

    private fun buildScript(
        installJava: Boolean,
        installSdk: Boolean,
        applyWrapper: Boolean,
        writeEnvSh: Boolean,
        acceptLicenses: Boolean,
        cmdlineVer: String,
        sdkPackages: List<String>,
    ): String = buildString {
        append("set +e\n")
        append("mkdir -p /root/.rcodecore\n")
        // 辅助：写一条 step 行
        append("step() { echo \"[STEP|\${'$'}1|\${'$'}2|\${'$'}3]\"; }\n")
        append("step start begin '开始准备 Android 构建环境'\n")
        append("echo \"ARCH|\${'$'}(uname -m)\"\n")

        // 1) JDK 17
        if (installJava) {
            append("""
            if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -qE 'version "?1[789]|version "?2[0-9]'; then
              JAVA_HOME_GUESS="${'$'}(dirname "${'$'}(dirname "${'$'}(readlink -f "${'$'}(command -v java)")")")"
              step java ok "java 已存在：${'$'}(java -version 2>&1 | head -1) JAVA_HOME=${'$'}JAVA_HOME_GUESS"
            else
              apkMirrorAndUpdateScriptOnce 2>/dev/null || true
              apk add --no-cache openjdk17 >/tmp/jdk_install.log 2>&1
              APK_RC="${'$'}?"
              if [ "${'$'}APK_RC" -eq 0 ] && command -v java >/dev/null 2>&1; then
                JAVA_HOME_GUESS="${'$'}(dirname "${'$'}(dirname "${'$'}(readlink -f "${'$'}(command -v java)")")")"
                step java ok "已安装 openjdk17，JAVA_HOME=${'$'}JAVA_HOME_GUESS"
              else
                step java error "apk add openjdk17 失败 rc=${'$'}APK_RC。日志末 4 行：${'$'}(tail -n 4 /tmp/jdk_install.log 2>/dev/null)"
              fi
            fi
            """.trimIndent() + "\n")
        }

        // 2) ANDROID_HOME 默认路径 + 写 /root/.rcodecore/env.sh
        append("""
        SDK_ROOT="${'$'}{ANDROID_HOME:-${'$'}HOME/android-sdk}"
        mkdir -p "${'$'}SDK_ROOT"
        export ANDROID_HOME="${'$'}SDK_ROOT"
        export ANDROID_SDK_ROOT="${'$'}SDK_ROOT"
        if [ -z "${'$'}JAVA_HOME_GUESS" ] && command -v java >/dev/null 2>&1; then
          JAVA_HOME_GUESS="${'$'}(dirname "${'$'}(dirname "${'$'}(readlink -f "${'$'}(command -v java)")")")"
        fi
        : "${'$'}JAVA_HOME=${'$'}{JAVA_HOME:-${'$'}JAVA_HOME_GUESS}"
        export JAVA_HOME
        """.trimIndent() + "\n")

        // 3) cmdline-tools + sdkmanager
        if (installSdk) {
            append("""
            CMDLINE_DIR="${'$'}ANDROID_HOME/cmdline-tools/latest"
            if [ -x "${'$'}CMDLINE_DIR/bin/sdkmanager" ]; then
              step sdkmanager ok "sdkmanager 已存在：${'$'}CMDLINE_DIR"
            else
              set +e
              mkdir -p "${'$'}ANDROID_HOME/cmdline-tools"
              CDIR_TMP="/tmp/cmdline-tools.zip"
              URL="https://dl.google.com/android/repository/commandlinetools-linux-${cmdlineVer}_latest.zip"
              step sdkmanager begin "下载 cmdline-tools-linux-${cmdlineVer} …"
              rm -f "${'$'}CDIR_TMP"
              GOT=0
              if command -v curl >/dev/null 2>&1; then
                curl -fSL "${'$'}URL" -o "${'$'}CDIR_TMP" >/tmp/sdk_dl.log 2>&1 && GOT=1
              elif command -v wget >/dev/null 2>&1; then
                wget -q "${'$'}URL" -O "${'$'}CDIR_TMP" >/tmp/sdk_dl.log 2>&1 && GOT=1
              else
                step sdkmanager error "无 curl/wget，无法下载 cmdline-tools"
              fi
              if [ "${'$'}GOT" -eq 1 ] && [ -s "${'$'}CDIR_TMP" ]; then
                mkdir -p /tmp/sdk-unzip
                unzip -qo "${'$'}CDIR_TMP" -d /tmp/sdk-unzip >/tmp/sdk_unzip.log 2>&1
                if [ -d /tmp/sdk-unzip/cmdline-tools ]; then
                  rm -rf "${'$'}CMDLINE_DIR"
                  mkdir -p "${'$'}(dirname "${'$'}CMDLINE_DIR")"
                  mv /tmp/sdk-unzip/cmdline-tools "${'$'}CMDLINE_DIR"
                  if [ -x "${'$'}CMDLINE_DIR/bin/sdkmanager" ]; then
                    step sdkmanager ok "cmdline-tools 已安装：${'$'}CMDLINE_DIR"
                  else
                    step sdkmanager error "解压完成但 ${'$'}CMDLINE_DIR/bin/sdkmanager 不可执行"
                  fi
                else
                  step sdkmanager error "zip 结构异常：缺少 cmdline-tools/ 子目录"
                fi
              elif [ "${'$'}GOT" -eq 0 ]; then
                step sdkmanager error "下载 cmdline-tools 失败：${'$'}(tail -n 4 /tmp/sdk_dl.log 2>/dev/null)"
              fi
              rm -f "${'$'}CDIR_TMP"
              rm -rf /tmp/sdk-unzip
            fi
            export PATH="${'$'}CMDLINE_DIR/bin:${'$'}ANDROID_HOME/platform-tools:${'$'}ANDROID_HOME/build-tools/${sdkPackages.firstOrNull { it.startsWith("build-tools;") }?.substringAfter("build-tools;") ?: DEFAULT_BUILD_TOOLS}:${'$'}PATH"
            """.trimIndent() + "\n")
        }

        // 4) sdkmanager --licenses + install packages
        if (installSdk) {
            val pkgsJoined = sdkPackages.joinToString(" ") { "\"$it\"" }
            append("""
            if command -v sdkmanager >/dev/null 2>&1; then
              if [ "${acceptLicenses}" = "true" ]; then
                step licenses begin "自动接受 sdkmanager licenses …"
                (yes || true) | sdkmanager --licenses >/tmp/sdk_lic.log 2>&1 || true
                step licenses ok "licenses 接受完成（rc=${'$'}?）"
              fi
              step sdk_packages begin "sdkmanager 安装：${sdkPackages.joinToString(" ")}\n"
              sdkmanager --install ${pkgsJoined} >/tmp/sdk_inst.log 2>&1
              RC="${'$'}?"
              if [ "${'$'}RC" -eq 0 ]; then
                step sdk_packages ok "安装成功"
              else
                step sdk_packages error "安装失败 rc=${'$'}RC；日志末 6 行：${'$'}(tail -n 6 /tmp/sdk_inst.log 2>/dev/null)"
              fi
            else
              step sdk_packages warn "sdkmanager 不在 PATH，跳过组件安装"
            fi
            """.trimIndent() + "\n")
        }

        // 5) 应用 qemu wrapper：若存在 rcodecore-wrap-android-buildtools 则调用
        if (applyWrapper) {
            append("""
            ARCH="${'$'}(uname -m)"
            if [ "${'$'}ARCH" = "aarch64" ] || [ "${'$'}ARCH" = "arm64" ]; then
              if command -v rcodecore-wrap-android-buildtools >/dev/null 2>&1; then
                step wrapper begin "aarch64 检测到：执行 Build-Tools x86_64 wrapper 化…"
                rcodecore-wrap-android-buildtools >/tmp/wrap.log 2>&1
                RC="${'$'}?"
                if [ "${'$'}RC" -eq 0 ]; then
                  step wrapper ok "wrapper 应用完成；日志末 8 行：${'$'}(tail -n 8 /tmp/wrap.log 2>/dev/null)"
                else
                  step wrapper error "wrapper 脚本异常 rc=${'$'}RC；日志末 8 行：${'$'}(tail -n 8 /tmp/wrap.log 2>/dev/null)"
                fi
              else
                step wrapper begin "rcodecore-wrap-android-buildtools 尚未入 PATH：尝试 apk add qemu-user-static + 手工注册 wrapper…"
                apk add --no-cache qemu-user-static file >/tmp/qemu_apk.log 2>&1
                QEMU_BIN="$(command -v qemu-x86_64 2>/dev/null || find /usr -name 'qemu-x86_64*' -executable 2>/dev/null | head -1)"
                if [ -n "${'$'}QEMU_BIN" ] && [ -x "${'$'}QEMU_BIN" ]; then
                  mkdir -p /usr/local/bin
                  ln -sf "${'$'}QEMU_BIN" /usr/local/bin/qemu-x86_64
                fi
                if command -v rcodecore-wrap-android-buildtools >/dev/null 2>&1 || [ -x /usr/local/bin/rcodecore-wrap-android-buildtools ]; then
                  step wrapper ok "qemu-user-static 应急安装成功；可再次调用本工具 rerun wrapper。"
                else
                  step wrapper warn "应急 apk add 未完全就绪：请在终端 Bundle 页安装「x86 构建转译器」或重调本工具（apply_wrapper=true）。"
                fi
              fi
            else
              step wrapper skip "arch=${'$'}ARCH 非 aarch64，跳过 qemu-x86_64 wrapper 化"
            fi
            """.trimIndent() + "\n")
        }

        // 6) 写 env.sh 让后续 shell 登录自动 source
        if (writeEnvSh) {
            append("""
            ENV_FILE="$ENV_SH"
            {
              echo "# 由 ensure_android_env 自动生成（追加式）"
              echo "export JAVA_HOME=\"${'$'}JAVA_HOME\""
              echo "export ANDROID_HOME=\"${'$'}ANDROID_HOME\""
              echo "export ANDROID_SDK_ROOT=\"${'$'}ANDROID_HOME\""
              echo 'PATH="${'$'}ANDROID_HOME/cmdline-tools/latest/bin:${'$'}ANDROID_HOME/platform-tools:${'$'}PATH"'
              echo 'export PATH'
            } >> "${'$'}ENV_FILE"
            step envsh ok "环境变量已写入 ${'$'}ENV_FILE（source 生效）"
            """.trimIndent() + "\n")
        }

        // 7) 输出最终 ENV + finish 行
        append("""
        if [ -n "${'$'}JAVA_HOME" ] || [ -n "${'$'}ANDROID_HOME" ]; then
          echo "ENV|JAVA_HOME=${'$'}JAVA_HOME;ANDROID_HOME=${'$'}ANDROID_HOME"
        fi
        step finish end "ensure_android_env 完成"
        """.trimIndent() + "\n")
    }
}
