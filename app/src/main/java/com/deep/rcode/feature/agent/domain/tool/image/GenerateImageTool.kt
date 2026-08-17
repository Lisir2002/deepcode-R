package com.deep.rcode.feature.agent.domain.tool.image

import android.content.Context
import com.deep.rcode.core.security.CredentialEncryptor
import com.deep.rcode.core.util.EnumSafe
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.domain.model.AgentContext
import com.deep.rcode.feature.agent.domain.tool.AgentTool
import com.deep.rcode.feature.agent.domain.tool.ParameterType
import com.deep.rcode.feature.agent.domain.tool.ToolParameter
import com.deep.rcode.feature.agent.domain.tool.ToolPermissionPolicy
import com.deep.rcode.feature.agent.domain.tool.ToolResult
import com.deep.rcode.feature.agent.domain.tool.ToolStreamEvent
import com.deep.rcode.feature.agent.domain.tool.StreamingAgentTool
import com.deep.rcode.feature.t2i.data.local.dao.T2IProviderDao
import com.deep.rcode.feature.t2i.data.local.dao.T2IProviderModelDao
import com.deep.rcode.feature.t2i.data.local.dao.T2ITaskDao
import com.deep.rcode.feature.t2i.data.local.entity.T2ITaskEntity
import com.deep.rcode.feature.t2i.data.remote.ImageGenerator
import com.deep.rcode.feature.t2i.domain.permission.T2IPermissionPolicyEngine
import com.deep.rcode.feature.workspace.domain.WorkspacePathMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * T2I 文生图工具：AI 调用 `generateImage(prompt="...", width=1024, height=1024, ...)` →
 * 经过权限策略引擎 P1~P6 评估 → failover 选 provider → ImageGenerator 生成 →
 * 持久化任务行 + 图片到 filesDir/t2i_images → 返回 imagePath + 预览 Markdown。
 */
@Singleton
class GenerateImageTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providerDao: T2IProviderDao,
    private val modelDao: T2IProviderModelDao,
    private val taskDao: T2ITaskDao,
    private val permission: T2IPermissionPolicyEngine,
    private val imageGenerator: ImageGenerator,
    private val credentialEncryptor: CredentialEncryptor,
    private val pathMapper: WorkspacePathMapper,
) : AgentTool(), StreamingAgentTool {

    private companion object {
        const val TAG = "GenerateImageTool"
        const val OUTPUT_DIR_NAME = "t2i_images"
    }

    override val name: String = "generateImage"

    override val description: String = """
        根据文字描述生成一张图片（Text-to-Image）。
        参数 prompt 为必填，其余选填（默认 1024×1024、steps=30、自动选择当前激活的 T2I Provider）。
        返回生成图片的本地路径 + Markdown 预览代码，UI 会在消息气泡中内联渲染图片。
    """.trimIndent()

    override val parameters: Map<String, ToolParameter> = linkedMapOf(
        "prompt" to ToolParameter(
            "prompt", ParameterType.STRING,
            "必填：描述要生成图片的文字提示（英文/中文均可，效果因 provider 而异）。"
        ),
        "negative_prompt" to ToolParameter(
            "negative_prompt", ParameterType.STRING,
            "可选：不希望出现在图片里的内容描述（SD 系列模型支持；DALL·E 系列通常忽略）。",
            required = false
        ),
        "width" to ToolParameter(
            "width", ParameterType.INTEGER,
            "可选：图片宽度像素，默认 1024。支持的值取决于模型（如 512/768/1024/1536）。",
            required = false
        ),
        "height" to ToolParameter(
            "height", ParameterType.INTEGER,
            "可选：图片高度像素，默认 1024。",
            required = false
        ),
        "steps" to ToolParameter(
            "steps", ParameterType.INTEGER,
            "可选：扩散步数，默认 30。数值越大细节越丰富但速度越慢，SD 系列建议 20~50，DALL·E 忽略。",
            required = false
        ),
        "hd" to ToolParameter(
            "hd", ParameterType.BOOLEAN,
            "可选：是否开启高清模式（DALL·E 3 quality=hd，费用翻倍）；默认 false。",
            required = false
        ),
        "model" to ToolParameter(
            "model", ParameterType.STRING,
            "可选：指定 T2I 模型 ID（如 dall-e-3 / stable-diffusion-xl-1.0）；为空则用当前激活 T2I Provider 的默认模型。",
            required = false
        ),
        "output_path" to ToolParameter(
            "output_path", ParameterType.STRING,
            "V-3：可选：生成完成后把图片额外保存到工作区的目标路径，如 ~/workspace/assets/hero.png 或 assets/hero.png（相对路径基于工作区根）。" +
                "省略则只保存到 App 私有目录；传入后图片会同时出现在工作区，便于在项目中使用（如引用到代码/文档）。",
            required = false
        ),
    )

    override val permissionPolicy: ToolPermissionPolicy = ToolPermissionPolicy.ASK

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        var last: ToolResult? = null
        // executeStream 需要 AgentContext；execute() 作为兜底，构造一个最小上下文
        val fallbackCtx = AgentContext(
            currentFile = null, selectedCode = null, projectRoot = "",
            language = null, sessionId = null
        )
        executeStream(args, fallbackCtx).collect { ev ->
            if (ev is ToolStreamEvent.Completed) last = ev.result
        }
        return last ?: ToolResult.Error("Stream 没有产生 Completed 事件", code = "STREAM_INCOMPLETE")
    }

    override fun executeStream(
        args: Map<String, JsonElement>,
        agentCtx: AgentContext
    ): Flow<ToolStreamEvent> = flow {
        val sessionId = agentCtx.sessionId ?: "UNKNOWN_SESSION"
        val taskId = "t2i_${UUID.randomUUID().toString().replace("-", "")}"

        emit(ToolStreamEvent.Progress("解析参数..."))
        val prompt = args["prompt"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: run {
                emit(ToolStreamEvent.Completed(ToolResult.Error("参数 prompt 不能为空", "EMPTY_PROMPT"))); return@flow
            }
        val negativePrompt = args["negative_prompt"]?.asStringOrNull().orEmpty()
        val width = args["width"]?.asIntSafe()?.coerceIn(128, 2048) ?: 1024
        val height = args["height"]?.asIntSafe()?.coerceIn(128, 2048) ?: 1024
        val steps = args["steps"]?.asIntSafe()?.coerceIn(1, 150) ?: 30
        val hd = args["hd"]?.asStringOrNull()?.toBooleanStrictOrNull() ?: false
        val desiredModel = args["model"]?.asStringOrNull().orEmpty()

        emit(ToolStreamEvent.Progress("选择文生图 Provider..."))
        val activeProvider = providerDao.getActiveProviderSync()
            ?: providerDao.getEnabledProvidersOnce().sortedByDescending { it.priority }.firstOrNull()
            ?: run {
                emit(ToolStreamEvent.Completed(ToolResult.Error(
                    "未配置文生图 Provider：请到设置页「文生图」添加一个 T2I Provider（API Key + BaseUrl）。",
                    "NO_PROVIDER"
                ))); return@flow
            }

        val apiKey = try {
            credentialEncryptor.decrypt(activeProvider.encryptedApiKey)
        } catch (t: Throwable) {
            FileLogger.w(TAG, "解密 API Key 失败: ${t.message}")
            ""
        }
        if (apiKey.isBlank()) {
            emit(ToolStreamEvent.Completed(ToolResult.Error(
                "当前 T2I Provider「${activeProvider.name}」的 API Key 未正确配置或解密失败，请重新保存一次 Provider。",
                "NO_API_KEY"
            ))); return@flow
        }

        val firstModel = modelDao.getModelsForProviderOnce(activeProvider.id).firstOrNull()
        val targetModel = if (desiredModel.isNotBlank()) {
            modelDao.getModel(activeProvider.id, desiredModel) ?: firstModel
        } else {
            firstModel
        }
        val costPerImage = targetModel?.costPerImageTokens ?: 100

        emit(ToolStreamEvent.Progress("评估额度与安全策略..."))
        val perm = permission.evaluate(
            T2IPermissionPolicyEngine.Request(
                sessionId = sessionId,
                costPerImageTokens = costPerImage,
                imageCount = 1,
            )
        )
        when (perm.verdict) {
            T2IPermissionPolicyEngine.Verdict.DENY -> {
                emit(ToolStreamEvent.Completed(ToolResult.Error(
                    perm.denyMessage ?: "额度耗尽或被拒绝",
                    perm.denyCode ?: "DENIED"
                ))); return@flow
            }
            T2IPermissionPolicyEngine.Verdict.ASK -> {
                val confirmAgain = args["allow_t2i_second_confirm"]?.asStringOrNull()
                    ?.toBooleanStrictOrNull() == true
                if (!confirmAgain) {
                    val detailMsg = buildString {
                        append(perm.askReason ?: "需要您确认是否继续生成")
                        append("（本次成本 ").append(perm.tokensToDeduct).append(" tokens，")
                        append("尺寸 ").append(width).append("x").append(height)
                        if (hd) append(" HD")
                        append("）")
                    }
                    emit(ToolStreamEvent.Completed(ToolResult.Error(detailMsg, "T2I_NEED_SECOND_CONFIRM")))
                    return@flow
                }
            }
            T2IPermissionPolicyEngine.Verdict.ALLOW -> Unit
        }

        val now = System.currentTimeMillis()
        val outputDir = File(context.filesDir, OUTPUT_DIR_NAME)
        val endpointModeRef = EnumSafe.valueOf(
            activeProvider.endpointMode, ImageGenerator.EndpointMode.AUTO,
            tag = "GenerateImageTool.endpointMode"
        ).name
        val pending = T2ITaskEntity(
            id = taskId,
            sessionId = sessionId,
            messageId = "",
            prompt = prompt,
            negativePrompt = negativePrompt,
            width = width, height = height, steps = steps, hd = hd,
            providerId = activeProvider.id,
            modelId = targetModel?.modelId ?: desiredModel,
            endpointModeRef = endpointModeRef,
            status = "RUNNING",
            permissionDecision = perm.verdict.name,
            quotaDeductedTokens = perm.tokensToDeduct,
            createdAtMs = now,
            updatedAtMs = now,
        )
        taskDao.insertTask(pending)

        emit(ToolStreamEvent.Progress(buildString {
            append("正在生成（").append(width).append("x").append(height)
            append(" steps=").append(steps)
            if (hd) append(" hd")
            append("）...")
        }))
        var refundable = true
        try {
            if (imageGenerator is com.deep.rcode.feature.t2i.data.remote.OpenAiCompatibleImageGenerator) {
                imageGenerator.setRuntimeConfig(activeProvider.baseUrl, apiKey)
            }
            val res = imageGenerator.generate(
                ImageGenerator.Request(
                    prompt = prompt, negativePrompt = negativePrompt,
                    width = width, height = height, steps = steps, seed = 0, hd = hd,
                    model = targetModel?.modelId ?: desiredModel,
                    outputDir = outputDir, taskId = taskId,
                )
            )
            taskDao.markSuccess(taskId, res.imagePath, res.thumbnailPath, System.currentTimeMillis())
            if (activeProvider.endpointMode == "AUTO" && res.modeUsed != ImageGenerator.EndpointMode.AUTO) {
                runCatching { providerDao.updateEndpointMode(activeProvider.id, res.modeUsed.name) }
                    .onFailure { FileLogger.w(TAG, "写回 endpointMode 失败: ${it.message}") }
            }
            refundable = false

            // V-3：output_path 存在时把图片复制到工作区，便于 AI 在项目中使用（私有目录副本保留）。
            var workspaceCopyPath: String? = null
            var copyNote: String? = null
            val outputPathArg = args["output_path"]?.asStringOrNull()?.trim()
            if (!outputPathArg.isNullOrBlank()) {
                val copyResult = copyToWorkspace(outputPathArg, File(res.imagePath))
                if (copyResult != null) {
                    workspaceCopyPath = copyResult.first
                    copyNote = copyResult.second
                }
            }

            val mdPreview = buildPreviewMarkdown(res.imagePath, prompt)
            // V-1：重试元数据——attempts=本次成功后累计尝试次数（=retryCount+1），failures=累计失败次数
            emit(ToolStreamEvent.Completed(ToolResult.Success(buildJsonObject {
                put("imagePath", res.imagePath)
                put("thumbnailPath", res.thumbnailPath)
                put("taskId", taskId)
                put("markdown", mdPreview)
                put("attempts", pending.retryCount + 1)
                put("failures", pending.retryCount)
                // V-3：工作区副本路径（容器视角）与提示信息
                workspaceCopyPath?.let { put("outputPath", it) }
                copyNote?.let { put("note", it) }
            })))
        } catch (pe: ImageGenerator.ProviderException) {
            FileLogger.w(TAG, "Provider 异常 code=${pe.errorCode} refundable=${pe.refundable} msg=${pe.message}")
            refundable = pe.refundable
            val maxRetries = 3
            val nextRetry = handleFailure(taskId, pending, perm.tokensToDeduct, pe.errorCode, pe.message,
                maxRetries = maxRetries)
            // V-1：错误信息附带重试次数提示
            emit(ToolStreamEvent.Completed(ToolResult.Error(
                pe.message + "（第 $nextRetry/$maxRetries 次尝试失败）", pe.errorCode)))
        } catch (t: Throwable) {
            FileLogger.e(TAG, "生成时未预期异常: ${t.message}", t)
            refundable = true
            val maxRetries = 3
            val fallbackMsg = t.message?.take(200) ?: "未知错误"
            val nextRetry = handleFailure(taskId, pending, perm.tokensToDeduct, "UNEXPECTED",
                fallbackMsg, maxRetries = maxRetries)
            // V-1：错误信息附带重试次数提示
            emit(ToolStreamEvent.Completed(ToolResult.Error(
                fallbackMsg + "（第 $nextRetry/$maxRetries 次尝试失败）", "UNEXPECTED")))
        } finally {
            if (refundable && perm.tokensToDeduct > 0) {
                runCatching {
                    taskDao.setPermissionDecision(
                        taskId,
                        decision = "REFUNDED_${pending.permissionDecision}",
                        deducted = 0,
                        updatedAtMs = System.currentTimeMillis()
                    )
                }.onFailure { FileLogger.w(TAG, "写回退款失败: ${it.message}") }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 落盘失败/待重试任务行，返回本次尝试序号（nextRetry = retryCount + 1），供调用方拼接错误提示。
     */
    private suspend fun handleFailure(
        taskId: String, pending: T2ITaskEntity,
        tokensDeducted: Int, code: String, msg: String,
        maxRetries: Int,
    ): Int {
        val nextRetry = pending.retryCount + 1
        val finalStatus = if (nextRetry < maxRetries) "PENDING_RETRY" else "FAILED"
        taskDao.markFailedOrRetry(
            id = taskId,
            finalStatus = finalStatus,
            errorCode = code,
            errorMessage = msg,
            retryCount = nextRetry,
            updatedAtMs = System.currentTimeMillis()
        )
        return nextRetry
    }

    private fun buildPreviewMarkdown(imagePath: String, prompt: String): String {
        return "![generated-image](file://$imagePath)\n\n*生成提示：$prompt*"
    }

    /**
     * V-3：把生成的图片复制到工作区目标路径。
     * @return Pair(容器视角路径, 提示语)；源文件缺失或复制失败返回 null（不中断生成流程）。
     */
    private fun copyToWorkspace(outputPathArg: String, source: File): Pair<String, String>? {
        return try {
            val target = pathMapper.toHostFile(outputPathArg)
            if (target.absolutePath == source.absolutePath) return null
            if (!source.exists()) return null
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            val containerPath = pathMapper.toContainerPath(target.absolutePath)
            FileLogger.d(TAG, "V-3 复制到工作区: $containerPath")
            containerPath to "图片已同时保存到工作区 $containerPath"
        } catch (e: Exception) {
            FileLogger.w(TAG, "V-3 复制到工作区失败: ${e.message}", e)
            null
        }
    }

    private fun JsonElement.asIntSafe(): Int? = runCatching { jsonPrimitive.int }.getOrNull()
        ?: jsonPrimitive.contentOrNullSafe()?.toIntOrNull()

    private fun JsonElement.asStringOrNull(): String? = jsonPrimitive.contentOrNullSafe()

    private fun JsonElement.contentOrNullSafe(): String? =
        runCatching { jsonPrimitive.content }.getOrNull()
}
