package com.core.deepcode.feature.t2i.data.remote

import com.core.deepcode.feature.agent.domain.provider.joinUrl
import com.core.deepcode.feature.settings.domain.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * T2I 文生图专用探测服务。与通用 LLM 的 [ModelApiService] 解耦：
 *
 * - 所有 URL 一律走 [joinUrl]，彻底避免 "step_plan/v1 + v1/images/generations = 双 v1 = 404"。
 * - 三个核心能力：
 *   1. [probeConnection] — UI 「测速」按钮直接用，返回结构化状态（连不通？Key 错？尺寸不支持？）。
 *   2. [suggestDefaultModels] — 拉模型接口 404 时提供候选模型名列表（StepFun / DALL·E / Flux / SDXL…）。
 *   3. [probeEndpointMode] — AUTO 形态探测（首次发最小成本请求，看响应体是 SYNC 直接返回图还是
 *      ASYNC 返回 task_id），结果供调用方持久化到 t2i_providers.endpointMode 缓存，避免每次生成都探测。
 */
@Singleton
class T2IModelProbeService @Inject constructor(
    private val client: OkHttpClient,
) {
    private companion object {
        /** 常见 T2I 模型名候选：用户把「只部署文生图的代理」当成 LLM Provider 添加时，
         *  GET /v1/models 必然返回 404，这时就把这份列表作为备选项填充到下拉，用户可自行增删。 */
        val COMMON_T2I_MODELS = listOf(
            // ===== StepFun 官方图模型（用户实际在用）=====
            "step-2x-large",
            "step-image-edit-2",
            "step-image-plus",
            // ===== OpenAI 原生 =====
            "dall-e-3",
            "dall-e-2",
            // ===== 自建 SD / Flux 兼容层常见 =====
            "flux-schnell",
            "flux-dev",
            "flux.1-schnell",
            "flux.1-dev",
            "stable-diffusion-xl",
            "stable-diffusion-xl-1.0",
            "sdxl-turbo",
            // ===== 国内常见 =====
            "wanx-v1",            // 阿里通义万相
            "hunyuan-image",      // 腾讯混元
            "siliconflow/flux1-dev", // 硅基流动
        ).distinct().sorted()
    }

    /** T2I 连通性探测结果（结构化，供 UI 渲染 icon + 说明）。 */
    data class T2IProbeResult(
        /** true = 2xx；false = 任何非 2xx。 */
        val ok: Boolean,
        /** 从发起请求到收到响应首字节前的毫秒。 */
        val latencyMs: Long,
        /** HTTP status（便于区分：200 全绿；401/403 = Key 错红；404 = baseUrl 路径不对黄；429 = 限流灰；其他 = 紫）。 */
        val httpStatus: Int,
        /** 给用户看的一行解释（包含具体返回码/错误消息片段）。 */
        val userMessage: String,
        /** 机器可读的原因码：OK / KEY_INVALID / PATH_404 / NSFW / RATE_LIMIT / BAD_PARAM / NETWORK_ERROR。 */
        val reasonCode: String,
    )

    // ==========================================================================
    //  1. 连通性探测（T2I 专用的「测速」——POST /v1/images/generations 最小请求）
    // ==========================================================================
    suspend fun probeConnection(
        baseUrl: String,
        apiKey: String,
        /** 传模型名，不传用 "step-2x-large"（最常用）兜底，避免模型名本身报错。 */
        probeModel: String = "step-2x-large",
        type: ProviderType = ProviderType.OPENAI,
    ): T2IProbeResult = withContext(Dispatchers.IO) {
        val startNano = System.nanoTime()
        runCatching {
            val payload = JSONObject().apply {
                put("model", probeModel)
                put("prompt", "a blue dot on white background") // 最小中性 prompt，不过 NSFW
                put("n", 1)
                put("size", "256x256")                          // 最小尺寸，省 GPU 时间
                put("response_format", "b64_json")              // 不触发文件下载链路
            }
            val endpoint = joinUrl(baseUrl, "v1/images/generations")
            val req = Request.Builder()
                .url(endpoint)
                .applyAuth(apiKey, type)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val ms = (System.nanoTime() - startNano) / 1_000_000
                val (reason, msg) = classify(resp.code, body, endpoint)
                T2IProbeResult(
                    ok = resp.isSuccessful,
                    latencyMs = ms,
                    httpStatus = resp.code,
                    userMessage = msg,
                    reasonCode = reason,
                )
            }
        }.getOrElse { t ->
            val ms = (System.nanoTime() - startNano) / 1_000_000
            T2IProbeResult(
                ok = false,
                latencyMs = ms,
                httpStatus = 0,
                userMessage = "请求失败：${t.message ?: t::class.java.simpleName}（请检查网络 / baseUrl）",
                reasonCode = "NETWORK_ERROR",
            )
        }
    }

    // ==========================================================================
    //  2. 默认模型候选列表（拉模型接口 404 时 UI 兜底用）
    // ==========================================================================
    fun suggestDefaultModels(baseUrlHint: String? = null): List<String> {
        val host = baseUrlHint.orEmpty().lowercase()
        // 针对已知供应商的轻量偏好前置（只是 UI 排序，不影响功能）
        val pinned = when {
            "stepfun" in host -> listOf("step-2x-large", "step-image-edit-2", "step-image-plus")
            "openai"  in host -> listOf("dall-e-3", "dall-e-2")
            else              -> emptyList()
        }
        return if (pinned.isEmpty()) COMMON_T2I_MODELS
        else (pinned + COMMON_T2I_MODELS).distinct()
    }

    // ==========================================================================
    //  3. AUTO 形态探测（SYNC / ASYNC 判定，结果用于持久化缓存 endpointMode）
    // ==========================================================================
    /**
     * 最小成本请求：POST /v1/images/generations。
     * - 响应体 2xx + `data[0].b64_json | url` 存在 → [ImageGenerator.EndpointMode.SYNC]
     * - 响应体 2xx + `task_id | id` 存在，无 b64_json/url → [ImageGenerator.EndpointMode.ASYNC]
     * - 非 2xx：返回 null（调用方保持 AUTO 不变，下次生成时再探）
     */
    suspend fun probeEndpointMode(
        baseUrl: String,
        apiKey: String,
        probeModel: String = "step-2x-large",
        type: ProviderType = ProviderType.OPENAI,
    ): ImageGenerator.EndpointMode? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("model", probeModel)
                put("prompt", "probe connection please ignore")
                put("n", 1)
                put("size", "256x256")
                put("response_format", "b64_json")
            }
            val req = Request.Builder()
                .url(joinUrl(baseUrl, "v1/images/generations"))
                .applyAuth(apiKey, type)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                val o = runCatching { JSONObject(body) }.getOrNull() ?: return@use null
                val data0 = o.optJSONArray("data")?.optJSONObject(0)
                val hasImage = data0 != null && (data0.has("b64_json") || data0.has("url"))
                val hasTaskId = data0 != null && data0.has("task_id") || o.has("task_id") || o.has("id")
                when {
                    hasImage -> ImageGenerator.EndpointMode.SYNC
                    hasTaskId -> ImageGenerator.EndpointMode.ASYNC
                    else -> null
                }
            }
        }.getOrNull()
    }

    // ==========================================================================
    //  内部工具
    // ==========================================================================
    private fun classify(code: Int, body: String, endpoint: String): Pair<String, String> {
        val errMsg = runCatching {
            val e = JSONObject(body).optJSONObject("error")
            e?.optString("message")?.takeIf { it.isNotBlank() } ?: body.take(120)
        }.getOrDefault(body.take(120))

        return when (code) {
            in 200..299 -> "OK" to "文生图接口连通（${code}）· 路径：$endpoint"
            401, 403    -> "KEY_INVALID" to "Key 无效/权限不足（HTTP $code）：${errMsg.take(80)}"
            404         -> "PATH_404"   to "路径不存在（HTTP 404）· 请求地址是「$endpoint」，请核对 baseUrl 是否带了错误版本前缀（例如末尾多加了 /v1）。"
            429         -> "RATE_LIMIT" to "已被限流（HTTP 429）：${errMsg.take(80)}"
            400         -> {
                if ("nsfw" in errMsg.lowercase() || "content" in errMsg.lowercase()) {
                    "NSFW" to "Prompt 命中内容审核（HTTP 400）：${errMsg.take(80)}"
                } else {
                    "BAD_PARAM" to "参数不支持（HTTP 400）：${errMsg.take(80)}· 建议换模型名或调尺寸（如 1024x1024）。"
                }
            }
            in 500..599 -> "SERVER_ERR" to "供应商 5xx（HTTP $code）：${errMsg.take(80)}· 稍后重试。"
            else        -> "UNKNOWN"    to "HTTP $code：${errMsg.take(80)}"
        }
    }

    private fun Request.Builder.applyAuth(apiKey: String, type: ProviderType): Request.Builder =
        when (type) {
            ProviderType.ANTHROPIC -> this
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
            ProviderType.GEMINI -> this
                .header("x-goog-api-key", apiKey)
            else -> this.header("Authorization", "Bearer $apiKey")
        }
}
