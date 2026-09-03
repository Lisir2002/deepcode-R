package com.core.deepcode.feature.agent.domain.tool.container

import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.domain.container.ContainerArch
import com.core.deepcode.feature.agent.domain.container.ContainerProfile
import com.core.deepcode.feature.agent.domain.container.LinuxContainerEngine
import com.core.deepcode.feature.agent.domain.tool.AgentTool
import com.core.deepcode.feature.agent.domain.tool.ParameterType
import com.core.deepcode.feature.agent.domain.tool.StreamingAgentTool
import com.core.deepcode.feature.agent.domain.tool.ToolCapability
import com.core.deepcode.feature.agent.domain.tool.ToolParameter
import com.core.deepcode.feature.agent.domain.tool.ToolPermissionPolicy
import com.core.deepcode.feature.agent.domain.tool.ToolResult
import com.core.deepcode.feature.agent.domain.tool.ToolStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 容器架构切换工具：让 AI 按需求在 arm64（原生，快）与 x86_64（QEMU 转译，兼容官方
 * x86_64 工具链）之间自由切换，实现双容器无感切换。
 *
 * 典型用途：构建 Android 时官方 SDK 的 Build-Tools（aapt2/zipalign 等）只发 x86_64 ELF，
 * 在 arm64 容器里会报架构不兼容。切到 x86_64 容器后这些工具成为容器内"原生环境"。
 *
 * 切换由 [LinuxContainerEngine.switchToProfile] 完成：立即更新内存 + 持久化选中 + 按需
 * 安装对应架构 rootfs（首次切 x86_64 会解压 x86_64 rootfs 并部署静态 qemu 转译器），
 * 返回后新容器立即可用，无需重启。
 */
class SwitchContainerArchTool @Inject constructor(
    private val containerEngine: LinuxContainerEngine
) : AgentTool(), StreamingAgentTool {

    companion object {
        private const val TAG = "SwitchContainerArchTool"

        /** 支持的架构别名 → 内置 profile。 */
        private fun profileFor(arch: String?): ContainerProfile? = when (arch) {
            "arm64", "aarch64" -> ContainerProfile.BUILTIN_ALPINE
            "x86_64", "x86", "amd64" -> ContainerProfile.BUILTIN_ALPINE_X86
            else -> null
        }
    }

    override val name = "switch_container_arch"
    override val description = "切换本地 Linux 容器的 CPU 架构：arm64（aarch64 原生执行，快）或 x86_64（经 QEMU 静态转译执行，兼容官方只发 x86_64 的 Android SDK Build-Tools / aapt2 / zipalign 等工具链）。切换持久化保存、按需自动安装对应架构的 rootfs，返回后新容器立即可用。适合在构建 Android 项目遇到架构不兼容报错时切换。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_CONTAINER_ENV)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "arch" to ToolParameter(
            name = "arch",
            type = ParameterType.STRING,
            description = "目标容器架构：\"arm64\"（默认，原生执行，最快）或 \"x86_64\"（QEMU 转译，兼容官方 x86_64 工具链）",
            required = true,
            enum = listOf("arm64", "x86_64")
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val arch = args["arch"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            val profile = profileFor(arch)
                ?: return ToolResult.Error(
                    "不支持的容器架构: $arch（可选 arm64 / x86_64）",
                    "INVALID_ARGS"
                )
            FileLogger.i(TAG, "切换容器架构: $arch -> ${profile.id}")
            val switched = containerEngine.switchToProfile(profile.id)
            val translated = switched.arch == ContainerArch.X86_64
            FileLogger.i(TAG, "容器已切换完成: ${switched.name} (${switched.arch}, translated=$translated)")
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "arch" to JsonPrimitive(switched.arch.name.lowercase()),
                        "profileId" to JsonPrimitive(switched.id),
                        "profileName" to JsonPrimitive(switched.name),
                        "translated" to JsonPrimitive(translated),
                        "message" to JsonPrimitive(
                            "已切换到 ${switched.name}（${if (translated) "x86_64 QEMU 转译执行" else "arm64 原生执行"}）。后续命令将在此容器内执行。"
                        )
                    )
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "切换容器架构失败", e)
            ToolResult.Error("切换容器架构失败: ${e.message}", "TOOL_EXECUTION_FAILED")
        }
    }

    /**
     * 流式切换：先 emit 一条开始进度（首次切 x86_64 需解压 rootfs/部署 QEMU 转译器，耗时），
     * 然后 await [LinuxContainerEngine.switchToProfile]（suspend、无流式事件源），
     * 完成后 emit [ToolStreamEvent.Completed]（成功结果）；切换失败 emit Completed(Error)。
     * [execute] 作为非流式兜底保留，两者最终结果一致。
     */
    override fun executeStream(
        args: Map<String, JsonElement>,
        context: com.core.deepcode.feature.agent.domain.model.AgentContext
    ): Flow<ToolStreamEvent> = flow {
        val arch = args["arch"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val profile = profileFor(arch)
        if (profile == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("不支持的容器架构: $arch（可选 arm64 / x86_64）", "INVALID_ARGS")))
            return@flow
        }
        emit(ToolStreamEvent.Progress("正在切换到 $arch 容器，可能需要解压 rootfs 并部署 QEMU 转译器…"))
        try {
            FileLogger.i(TAG, "切换容器架构(流式): $arch -> ${profile.id}")
            val switched = containerEngine.switchToProfile(profile.id)
            val translated = switched.arch == ContainerArch.X86_64
            FileLogger.i(TAG, "容器已切换完成(流式): ${switched.name} (${switched.arch}, translated=$translated)")
            emit(
                ToolStreamEvent.Completed(
                    ToolResult.Success(
                        JsonObject(
                            mapOf(
                                "arch" to JsonPrimitive(switched.arch.name.lowercase()),
                                "profileId" to JsonPrimitive(switched.id),
                                "profileName" to JsonPrimitive(switched.name),
                                "translated" to JsonPrimitive(translated),
                                "message" to JsonPrimitive(
                                    "已切换到 ${switched.name}（${if (translated) "x86_64 QEMU 转译执行" else "arm64 原生执行"}）。后续命令将在此容器内执行。"
                                )
                            )
                        )
                    )
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "切换容器架构(流式)失败", e)
            emit(ToolStreamEvent.Completed(ToolResult.Error("切换容器架构失败: ${e.message}", "TOOL_EXECUTION_FAILED")))
        }
    }
}
