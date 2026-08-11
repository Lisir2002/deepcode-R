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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 *
 * ### 与 ToolPermissionPolicyEngine 现有链路的协作
 * 本工具 [permissionPolicy] 默认 **ASK**（因为文生图每次扣额度 + 耗供应商 QPS +
 * 可能产生 NSFW 内容，必须在 AI 调用前先经过 ASK 环节）。
 *   - P1 强制确认 / P5 渐进阈值：属于 T2I 内部二次 ASK（即“用户点了 ToolPermission 的
 *     放行后，T2I 子系统还可能因为渐进阈值再次弹确认框”），由 GenerateImageTool
 *     内部抛 “NEED_CONFIRM_AGAIN” 的 ToolResult.Error，UI 解析后渲染二次确认 Sheet。
 *   - P2/P3/P4 额度耗尽：直接返回 ToolResult.Error(code=DAILY_QUOTA_EXCEEDED / ...)
 *     让 AI 在 prompt 中解释“额度不足，需用户到设置页调整或明天再试”。
 *
 * ### 崩溃恢复
 * 冷启动由独立的 T2ITaskRecoveryWorker（RC69 后续迭代追加 Worker）扫描
 * T2ITaskDao.getDanglingTasks(now - 30min) 处理。本工具是同步 Agent 路径，只负责“本次
 * 会话中发起一次生成 → 落任务 → 调 provider → 标记成功/失败”，不做后台恢复。
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
    )

    /**
     * 默认策略 = ASK（文生图每次都要用户确认，避免幻觉疯狂调用打满额度；
     * 若用户在设置页关掉「T2I 全局强制确认」（P1=false），且 P2/P3/P4/P5/P6 都通过，
     * 本工具会被 ToolPermissionManager 放行，但 T2I 内部子策略仍可二次 ASK/DENY）。
     */
    override val permissionPolicy: ToolPermissionPolicy = ToolPermissionPolicy.ASK

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        // StreamingAgentTool 工作流会优先走 executeStream；这里提供非流式兜底，
        // 收集完 Flow 的最后一个 Completed 事件再返回 ToolResult。
        var last: ToolResult? = null
        executeStream(args, AgentContext.EMPTY).collect { ev ->
            if (ev is ToolStreamEvent.Completed) last = ev.result
        }
        return last ?: ToolResult.Error("Stream 没有产生 Completed 事件", code = "STREAM_INCOMPLETE")
    }

    override fun executeStream(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): Flow<ToolStreamEvent> = flow {
        val sessionId = context.sessionId
        val messageId = context.currentMessageId.orEmpty()
        val now = System.currentTimeMillis()
        val taskId = "t2i_${UUID.randomUUID().toString().replace("-","")}"

        emit(ToolStreamEvent.Progress("🔍 解析参数..."))
        val prompt = args["prompt"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: run {
                emit(ToolStreamEvent.Completed(ToolResult.Error("参数 prompt 不能为空", "EMPTY_PROMPT"))); return@flow
            }
        val negativePrompt = args["negative_prompt"]?.jsonPrimitive?.content.orEmpty()
        val width = args["width"]?.asIntSafe()?.coerceIn(128, 2048) ?: 1024
        val height = args["height"]?.asIntSafe()?.coerceIn(128, 2048) ?: 1024
        val steps = args["steps"]?.asIntSafe()?.coerceIn(1, 150) ?: 30
        val hd = args["hd"]?.jsonPrimitive?.contentOrNull()?.toBooleanStrictOrNull() ?: false
        val desiredModel = args["model"]?.jsonPrimitive?.content.orEmpty()

        // ══ 1. 路由：选当前激活的 T2I Provider + 目标 model（没激活就拿第一个 enabled 的）══
        emit(ToolStreamEvent.Progress("🎛️ 选择文生图 Provider..."))
        val activeProvider = providerDao.getActiveProviderSync()
            ?: providerDao.getEnabledProvidersOnce().sortedByDescending { it.priority }.firstOrNull()
            ?: run {
                emit(ToolStreamEvent.Completed(ToolResult.Error(
                    "未配置文生图 Provider：请到设置页「文生图」添加一个 T2I Provider（API Key + BaseUrl）。",
                    "NO_PROVIDER"
                ))); return@flow
            }

        val apiKey = credentialEncryptor.decrypt(activeProvider.encryptedApiKey).getOrElse {
            FileLogger.w(TAG, "解密 API Key 失败: ${it.message}")
            ""
        }
        if (apiKey.isBlank()) {
            emit(ToolStreamEvent.Completed(ToolResult.Error(
                "当前 T2I Provider「${activeProvider.name}」的 API Key 未正确配置或解密失败，请重新保存一次 Provider。",
                "NO_API_KEY"
            ))); return@flow
        }

        val targetModel = modelDao.getModel(activeProvider.id, desiredModel.takeIf { it.isNotBlank() }
            ?: activeProvider.defaultModelId())
            ?: modelDao.getModelsForProviderOnce(activeProvider.id).firstOrNull()
        val costPerImage = targetModel?.costPerImageTokens ?: 100

        // ══ 2. 权限策略引擎 P1~P6 ══
        emit(ToolStreamEvent.Progress("📋 评估额度与安全策略..."))
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
                // 透传“需要二次确认”的机器信号：ToolPermissionManager 已经放行了第一轮（ASK），
                // 这里是 T2I 子策略的第二轮 ASK。UI 上解析 error.code = "T2I_NEED_SECOND_CONFIRM"
                // 弹出 Sheet 显示 askReason，用户点“确认”后再调本工具（带 allow_t2i_second_confirm=true）。
                val confirmAgain = args["allow_t2i_second_confirm"]?.jsonPrimitive?.contentOrNull()
                    ?.toBooleanStrictOrNull() == true
                if (!confirmAgain) {
                    val detail = buildJsonObject {
                        put("reason", perm.askReason ?: "需要您确认是否继续生成")
                        put("tokensToDeduct", perm.tokensToDeduct)
                        put("prompt", prompt)
                        put("size", "${width}x${height}")
                        put("hd", hd)
                    }
                    emit(ToolStreamEvent.Completed(ToolResult.Error(
                        message = (perm.askReason ?: "需要二次确认"),
                        code = "T2I_NEED_SECOND_CONFIRM",
                        // TODO: partial 带 detail 给 UI 渲染确认卡
                    ))); return@flow
                }
            }
            T2IPermissionPolicyEngine.Verdict.ALLOW -> Unit
        }

        // ══ 3. 落 PENDING 任务行 + 预扣额度（写 permissionDecision / quotaDeductedTokens）══
        val outputDir = File(context.filesDir, OUTPUT_DIR_NAME)
        val endpointModeRef = EnumSafe.valueOf(
            activeProvider.endpointMode, ImageGenerator.EndpointMode.AUTO,
            tag = "GenerateImageTool.endpointMode"
        ).name
        val pending = T2ITaskEntity(
            id = taskId,
            sessionId = sessionId,
            messageId = messageId,
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

        // ══ 4. 构造 runtime + 调用 ImageGenerator ══
        emit(ToolStreamEvent.Progress("🖌️ 正在生成（$width×$height steps=$steps${if (hd) " hd" else ""}）..."))
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
            // AUTO 探测结果：如果 endpointModeRef=AUTO 但 provider 保存的仍是 AUTO，
            // 这里把探测出来的 modeUsed 持久化回写（减少后续每次探测成本）。
            if (activeProvider.endpointMode == "AUTO" && res.modeUsed != ImageGenerator.EndpointMode.AUTO) {
                runCatching { providerDao.updateEndpointMode(activeProvider.id, res.modeUsed.name) }
                    .onFailure { FileLogger.w(TAG, "写回 endpointMode 失败: ${it.message}") }
            }
            refundable = false // 成功 = 不用退款

            val mdPreview = buildPreviewMarkdown(res.imagePath, prompt)
            emit(ToolStreamEvent.Completed(ToolResult.Success(buildJsonObject {
                put("imagePath", res.imagePath)
                put("thumbnailPath", res.thumbnailPath)
                put("taskId", taskId)
                put("markdown", mdPreview)
            })))
        } catch (pe: ImageGenerator.ProviderException) {
            FileLogger.w(TAG, "Provider 异常 code=${pe.errorCode} refundable=${pe.refundable} msg=${pe.message}")
            refundable = pe.refundable
            handleFailure(taskId, pending, perm.tokensToDeduct, pe.errorCode, pe.message,
                maxRetries = 3)
            emit(ToolStreamEvent.Completed(ToolResult.Error(pe.message, pe.errorCode)))
        } catch (t: Throwable) {
            FileLogger.e(TAG, "生成时未预期异常: ${t.message}", t)
            refundable = true
            handleFailure(taskId, pending, perm.tokensToDeduct, "UNEXPECTED",
                t.message?.take(200) ?: "未知错误", maxRetries = 3)
            emit(ToolStreamEvent.Completed(ToolResult.Error(t.message?.take(200) ?: "未知错误", "UNEXPECTED")))
        } finally {
            // 非 refundable 的失败：不回加额度（例如用户 prompt 违规被供应商拦截，
            // 但供应商仍记一次 QPS 消耗，额度照扣）。refundable=true 的失败 → 把
            // quotaDeductedTokens 置 0，sum 查询时就不再统计这单（相当于退款）。
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

    // ══════════════════════════════════════════════════════════
    // 私有辅助：失败状态机 + 重试次数更新
    // ══════════════════════════════════════════════════════════
    private suspend fun handleFailure(
        taskId: String, pending: T2ITaskEntity,
        tokensDeducted: Int, code: String, msg: String,
        maxRetries: Int,
    ) {
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
    }

    private fun buildPreviewMarkdown(imagePath: String, prompt: String): String {
        return "![generated-image](file://$imagePath)\n\n*生成提示：$prompt*"
    }

    private fun JsonElement.asIntSafe(): Int? = runCatching { jsonPrimitive.int }.getOrNull()
        ?: jsonPrimitive.contentOrNull()?.toIntOrNull()

    private fun JsonElement.contentOrNull(): String? =
        runCatching { jsonPrimitive.content }.getOrNull()

    /** 兜底：activeProvider 没有 defaultModel 列（T2IProviderEntity 保留该列给 RC70 UI 扩展），
     *  这里用空字符串（= 让上层取 modelDao.firstOrNull 兜底）。 */
    private fun com.deep.rcode.feature.t2i.data.local.entity.T2IProviderEntity.defaultModelId(): String = ""
}

/** 缺省 AgentContext（流式兜底 execute 调用时）。避免对 AgentContext 的具体实现细节产生强依赖。 */
private val AgentContext.Companion.EMPTY: AgentContext
    get() = object : AgentContext {
        override val sessionId: String get() = "UNKNOWN"
        override val currentMessageId: String? get() = null
    }
