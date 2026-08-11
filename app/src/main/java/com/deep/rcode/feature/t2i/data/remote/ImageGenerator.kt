package com.deep.rcode.feature.t2i.data.remote

import android.content.Context
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.domain.provider.joinUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * T2I 文生图 Provider 抽象接口（对齐
 * [com.deep.rcode.feature.agent.domain.provider.AIProvider] 的思路：统一一个 generate 方法，
 * 各具体 Adapter 按契约实现；但与 LLM AIProvider 接口契约完全独立，不共享任何 request/response shape）。
 *
 * ### endpointMode 语义
 * - **SYNC**  ：HTTP POST → 响应体里直接返回 1~N 张图片的 base64（或 b64_json）。
 *   例：OpenAI DALL·E 3、大多数兼容 OpenAI Images API 的自建 SD 网关。
 * - **ASYNC** ：HTTP POST → 返回远端 task_id → 再按间隔轮询 `GET /tasks/{id}` 直到
 *   `status=completed` + 返回 image_url / b64。例：阿里云百炼文生图、Stability AI 异步队列、
 *   自建 GPU 推理集群（单卡 QPS 低，走异步削峰）。
 * - **AUTO**  ：首次调用先探测（发送最小成本 1×1 prompt="probe" 且 size=最小尺寸
 *   steps=1 的请求，设置 `expectSynchronous` 标志位看响应体结构），结果写入
 *   T2IProviderDao.updateEndpointMode() 缓存；后续直接用缓存值不再探测。
 */
interface ImageGenerator {
    /**
     * 生成图片，把结果保存到本地文件（filesDir/t2i_images/），返回 [Result] 含主图 + 缩略图路径。
     *
     * @param request 所有 T2I Provider 共享的“标准化请求参数”（不含 provider 专属头 / baseUrl，
     *                这些由具体 Adapter 构造函数传入）。
     */
    suspend fun generate(request: Request): Result

    /** Provider / AgentTool 之间共享的标准化请求体（对齐 T2ITaskEntity 持久化字段）。 */
    data class Request(
        val prompt: String,
        val negativePrompt: String = "",
        val width: Int = 1024,
        val height: Int = 1024,
        val steps: Int = 30,
        val seed: Int = 0,
        val hd: Boolean = false,
        val model: String = "dall-e-3",
        /** 输出目录；由仓储/工具层传入 filesDir/t2i_images，避免本模块硬编码 context.filesDir。 */
        val outputDir: File,
        /** 本次任务 ID（用任务 ID 命名文件，方便删除/追溯）。 */
        val taskId: String,
    )

    /**
     * @param imagePath 主图文件绝对路径（PNG）。
     * @param thumbnailPath 缩略图（≤ 256×256，PNG），会话列表气泡渲染用。
     * @param modeUsed 本次实际生效的调用模式（SYNC / ASYNC），方便 AUTO 探测结果持久化回写。
     * @param remoteTaskId 远端任务 ID（ASYNC 模式下用于轮询；SYNC 模式为空串）。
     */
    data class Result(
        val imagePath: String,
        val thumbnailPath: String,
        val modeUsed: EndpointMode,
        val remoteTaskId: String = "",
    )

    enum class EndpointMode { SYNC, ASYNC, AUTO }

    /**
     * 业务失败（非 I/O / 网络崩溃）的异常——抛出该异常意味着「失败原因是 provider 返回了
     * 机器可读 error_code」，仓储层会按 errorCode 判断是否需要退款（权限策略 P4/P2/P3
     * 的 token 预扣后，非用户导致的失败必须回加额度）。
     */
    class ProviderException(
        val errorCode: String,
        override val message: String,
        cause: Throwable? = null,
        /** 是否需要退款：true = 用户没拿到图但额度被扣了（额度池回加）。 */
        val refundable: Boolean = true,
    ) : RuntimeException(message, cause)
}

/**
 * OpenAI Images API (v1/images/generations) 兼容实现。
 *
 * 绝大多数第三方 T2I 兼容网关（OneAPI、NewAPI、Astra Protocol、自建 SD OpenAI 适配层等）
 * 都实现了这个接口契约，因此这一个 Adapter 能覆盖 90%+ 的 Provider 类型（ANTHROPIC /
 * GEMINI 原生接口后续单独加 Adapter）。
 *
 * 请求/响应契约（对齐 https://platform.openai.com/docs/api-reference/images/create）：
 * ```json
 * POST {baseUrl}/v1/images/generations
 * Authorization: Bearer {apiKey}
 * {"model":"dall-e-3","prompt":"a cat","size":"1024x1024","n":1,"response_format":"b64_json"}
 * → 200 OK {"data":[{"b64_json":"...iVBORw0KGgo..."}]}
 * ```
 */
@Singleton
class OpenAiCompatibleImageGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) : ImageGenerator {

    private companion object {
        const val TAG = "OpenAiT2I"
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024 // 20MB 安全兜底（避免 OOM 写超大图）
    }

    override suspend fun generate(request: ImageGenerator.Request): ImageGenerator.Result =
        withContext(Dispatchers.IO) {
            val baseUrl = runtimeBaseUrl() // 注入后通过 setBaseUrl 动态配置（T2I Repository 路由时传入）
            val apiKey = runtimeApiKey()
            val effectiveModel = request.model.ifBlank { "dall-e-3" }
            val size = "${request.width}x${request.height}"

            val payload = JSONObject().apply {
                put("model", effectiveModel)
                put("prompt", request.prompt)
                put("size", size)
                put("n", 1)
                put("response_format", "b64_json")
                if (request.hd) put("quality", "hd")
                if (request.negativePrompt.isNotBlank()) put("negative_prompt", request.negativePrompt)
                if (request.steps > 0) put("steps", request.steps) // 非 OpenAI 原生，但大多数兼容层支持
                if (request.seed != 0) put("seed", request.seed)
            }

            val endpoint = joinUrl(baseUrl, "v1/images/generations")
            FileLogger.d(TAG, "POST $endpoint model=$effectiveModel size=$size")

            val reqBody = payload.toString().toRequestBody("application/json".toMediaType())
            val httpReq = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(reqBody)
                .build()

            val resp = runCatching { okHttpClient.newCall(httpReq).execute() }.getOrElse { t ->
                throw ImageGenerator.ProviderException(
                    "NETWORK_ERROR", "HTTP 调用失败: ${t.message}", t, refundable = true
                )
            }

            val respBodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val parsedCode = runCatching { JSONObject(respBodyStr).optJSONObject("error")?.optString("code") }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP_${resp.code}"
                val parsedMsg = runCatching { JSONObject(respBodyStr).optJSONObject("error")?.optString("message") }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: respBodyStr.take(300)
                FileLogger.w(TAG, "Provider 返回错误 code=$parsedCode http=${resp.code} msg=$parsedMsg")
                // HTTP 4xx 内容审核 / 模型拒绝等错误 —— 不退款（用户发的违规 prompt 占了供应商额度）；
                // 5xx / 429 / NETWORK_ERROR 才退款。
                val refundable = resp.code in 500..599 || resp.code == 429 || parsedCode == "NETWORK_ERROR"
                throw ImageGenerator.ProviderException(parsedCode, parsedMsg, refundable = refundable)
            }

            val b64 = runCatching {
                JSONObject(respBodyStr)
                    .getJSONArray("data")
                    .getJSONObject(0)
                    .getString("b64_json")
            }.getOrElse { t ->
                // 某些兼容层返回 url：尝试回退下载
                runCatching {
                    val url = JSONObject(respBodyStr)
                        .getJSONArray("data")
                        .getJSONObject(0)
                        .getString("url")
                    downloadUrlToBase64(url)
                }.getOrElse { _ ->
                    throw ImageGenerator.ProviderException(
                        "BAD_RESPONSE", "无法解析响应体中的 b64_json 或 url: ${t.message}"
                    )
                }
            }

            val (imagePath, thumbnailPath) = persistImage(
                b64 = b64,
                outputDir = request.outputDir,
                taskId = request.taskId,
                maxBytes = MAX_IMAGE_BYTES
            )

            ImageGenerator.Result(
                imagePath = imagePath,
                thumbnailPath = thumbnailPath,
                modeUsed = ImageGenerator.EndpointMode.SYNC,
                remoteTaskId = ""
            )
        }

    // ══ 运行时注入（T2I Repository 路由时 set，避免 ImageGenerator 直接依赖 DAO 产生循环）══
    @Volatile private var _baseUrl: String = ""
    @Volatile private var _apiKey: String = ""
    fun setRuntimeConfig(baseUrl: String, apiKey: String) {
        _baseUrl = baseUrl.trimEnd('/')
        _apiKey = apiKey
    }
    private fun runtimeBaseUrl(): String = _baseUrl
    private fun runtimeApiKey(): String = _apiKey

    private fun normalize(u: String): String = u.trimEnd('/')

    private suspend fun downloadUrlToBase64(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        val resp = okHttpClient.newCall(req).execute()
        if (!resp.isSuccessful) error("下载图片失败: HTTP ${resp.code}")
        val bytes = resp.body?.bytes() ?: error("响应体为空")
        java.util.Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * 持久化图片：写 imagePath = {outputDir}/{taskId}.png 以及缩略图 {outputDir}/{taskId}_thumb.png。
     * 缩略图通过 Bitmap.createScaledBitmap 生成（≤ 256 px 边长，避免会话列表 RecyclerView
     * 一次性 inflate 大量 1~4MB 大图触发 OOM）。
     */
    private fun persistImage(
        b64: String,
        outputDir: File,
        taskId: String,
        maxBytes: Int,
    ): Pair<String, String> {
        if (!outputDir.exists()) outputDir.mkdirs()
        val rawBytes = java.util.Base64.getDecoder().decode(b64.trim())
        if (rawBytes.size > maxBytes) {
            throw ImageGenerator.ProviderException(
                "IMAGE_TOO_LARGE", "图片 ${rawBytes.size} bytes > 上限 $maxBytes", refundable = true
            )
        }
        val imageFile = File(outputDir, "$taskId.png")
        imageFile.writeBytes(rawBytes)

        // 缩略图：RC69 第一版简化——直接与主图相同路径，等 UI 真正需要时再替换为
        //   BitmapFactory.decodeByteArray + createScaledBitmap 生成缩小版。
        //   这样避免在非 UI 模块引入 android.graphics.*（单元测试时可独立跑 Provider 层）。
        val thumbFile = File(outputDir, "${taskId}_thumb.png")
        rawBytes.inputStream().use { ins ->
            thumbFile.outputStream().use { outs ->
                ins.copyTo(outs, bufferSize = 8192)
            }
        }

        return imageFile.absolutePath to thumbFile.absolutePath
    }
}
