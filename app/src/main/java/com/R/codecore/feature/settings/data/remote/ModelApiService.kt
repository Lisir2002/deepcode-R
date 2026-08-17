package com.R.codecore.feature.settings.data.remote

import com.R.codecore.feature.agent.domain.provider.joinUrl
import com.R.codecore.feature.settings.domain.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** 测试模型连通性的结果。 */
data class ModelTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val message: String
)

/**
 * 直接通过 OkHttp 调用提供商的 REST 接口来拉取模型列表与测试连通性，
 * 复用全局 OkHttpClient，独立于聊天用的 Retrofit 适配器。
 */
@Singleton
class ModelApiService @Inject constructor(
    private val client: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 拉取提供商可用模型列表（OpenAI 兼容 / Anthropic 均为 GET /v1/models，Gemini 为 GET /v1beta/models）。
     *
     *  Failover：若 /v1(models) 返回 **404（路径不存在）**，自动探测 POST /v1/images/generations，
     *  如果后者不是 404 说明 baseUrl 指向的是「仅文生图专用代理 / StepFun step_plan/v1 这类含 v1
     *  前缀但没单独暴露 /v1/models 的网关」，就回退一份常见文生图模型名列表，避免用户看到 404
     *  误以为 Key 或网络错了（实际生成图片的路径是通的，joinUrl 会处理好去重）。
     */
    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String,
        type: ProviderType
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            if (apiKey.isBlank()) error("请先填写 API Key")

            val modelsPath = if (type == ProviderType.GEMINI) "v1beta/models" else "v1/models"
            val request = Request.Builder()
                .url(joinUrl(baseUrl, modelsPath))
                .applyAuth(apiKey, type)
                .get()
                .build()

            val (modelsCode, modelsBody) = client.newCall(request).execute().use { r ->
                r.code to r.body?.string().orEmpty()
            }

            // ========== Failover：只在明确 404（路径不存在）时走 T2I 兜底 ==========
            if (modelsCode == 404 && type != ProviderType.GEMINI && type != ProviderType.ANTHROPIC) {
                val t2iCode = probeT2IEndpoint(baseUrl, apiKey, type)
                if (t2iCode != 404) {
                    // 404 for LLM models list, but T2I images/generations IS accessible
                    //  → 这是文生图专用网关；返回常见 T2I 模型名候选（用户可在 UI 里自行再增删）
                    return@runCatching listOf(
                        // StepFun 官方图模型
                        "step-2x-large",
                        "step-image-edit-2",
                        "step-image-plus",
                        // OpenAI 兼容层常见
                        "dall-e-3",
                        "dall-e-2",
                        // 通用 SD / 自建兼容层
                        "stable-diffusion-xl",
                        "stable-diffusion-xl-1.0",
                        "flux-schnell",
                        "flux-dev",
                    ).distinct().sorted()
                }
            }

            // ========== 正常 LLM / Gemini 路径 ==========
            if (modelsCode !in 200..299) {
                error("HTTP $modelsCode: ${modelsBody.take(200)}")
            }
            val jsonObj = json.parseToJsonElement(modelsBody).jsonObject
            val data = if (type == ProviderType.GEMINI) {
                jsonObj["models"]?.jsonArray
            } else {
                jsonObj["data"]?.jsonArray
            } ?: error("响应缺少列表字段")

            data.mapNotNull {
                    it.jsonObject[if (type == ProviderType.GEMINI) "name" else "id"]?.jsonPrimitive?.contentOrNull
                }
                .map { if (type == ProviderType.GEMINI) it.removePrefix("models/") else it }
                .filter { it.isNotBlank() }
                .sorted()
        }
    }

    /** 发送一条极短请求验证 Key + 模型 + 端点是否可用，返回耗时。
     *
     *  Failover：OPENAI_COMPATIBLE 分支里如果 `/v1/chat/completions` 返回 404（路径不存在），
     *  自动 fall back 到 POST /v1/images/generations 最小文生图请求——成功即判定「文生图接口可用」。
     */
    suspend fun testModel(
        baseUrl: String,
        apiKey: String,
        type: ProviderType,
        useFullUrl: Boolean,
        useResponseApi: Boolean,
        model: String
    ): ModelTestResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            if (apiKey.isBlank()) error("请先填写 API Key")

            val (url, payload) = when (type) {
                ProviderType.ANTHROPIC -> {
                    val u = if (useFullUrl) baseUrl else joinUrl(baseUrl, "v1/messages")
                    u to """{"model":${model.jsonStr()},"max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
                }
                ProviderType.GEMINI -> {
                    val u = if (useFullUrl) {
                        baseUrl
                    } else {
                        val path = if (baseUrl.trimEnd('/').endsWith(model)) {
                            baseUrl.trimEnd('/') + ":generateContent"
                        } else {
                            joinUrl(baseUrl, "v1beta/models/$model:generateContent")
                        }
                        path
                    }
                    u to """{"contents":[{"role":"user","parts":[{"text":"hi"}]}]}"""
                }
                else -> {
                    val u = if (useFullUrl) {
                        baseUrl
                    } else {
                        joinUrl(baseUrl, "v1/chat/completions")
                    }
                    if (useResponseApi) {
                        u to """{"model":${model.jsonStr()},"input":[{"role":"user","content":"hi"}]}"""
                    } else {
                        u to """{"model":${model.jsonStr()},"max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
                    }
                }
            }

            val request = Request.Builder()
                .url(url)
                .applyAuth(apiKey, type)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val (chatCode, chatBody) = client.newCall(request).execute().use { r ->
                r.code to r.body?.string().orEmpty()
            }
            val latency = (System.nanoTime() - start) / 1_000_000

            // ========== Failover：OPENAI 兼容 → chat/completions 404 → T2I 探活 ==========
            val isOpenAICompat = type != ProviderType.ANTHROPIC && type != ProviderType.GEMINI
            if (chatCode == 404 && isOpenAICompat && !useFullUrl) {
                val t2iStart = System.nanoTime()
                val t2iCode = probeT2IEndpoint(baseUrl, apiKey, type)
                val t2iLatency = (System.nanoTime() - t2iStart) / 1_000_000
                return@withContext if (t2iCode == 404) {
                    ModelTestResult(
                        false, latency + t2iLatency,
                        "聊天接口 404 · 文生图接口也 404：请检查 baseUrl 路径结构或确认这是哪家代理。"
                    )
                } else {
                    val tip = if (t2iCode in 200..299) "连通" else "可达（HTTP $t2iCode，可能是 Key / 额度问题）"
                    ModelTestResult(
                        // 2xx 才算测速成功；401/403/429 这类虽然"通"但不算功能正常
                        success = t2iCode in 200..299,
                        latencyMs = latency + t2iLatency,
                        message = "聊天接口 404 · 已自动 fall back 文生图接口 $tip · 共 ${latency + t2iLatency}ms"
                    )
                }
            }

            if (chatCode in 200..299) {
                ModelTestResult(true, latency, "连通 · ${latency}ms")
            } else {
                ModelTestResult(false, latency, "HTTP $chatCode: ${chatBody.take(160)}")
            }
        } catch (e: Exception) {
            val latency = (System.nanoTime() - start) / 1_000_000
            ModelTestResult(false, latency, e.message ?: "请求失败")
        }
    }

    /** 发一个最小成本的 POST /v1/images/generations 探测（不消耗 token 不写文件，只取 HTTP status）。
     *  payload：size=256x256 + n=1 + 最小 prompt，让供应商侧一眼能识别为「探测请求」（多数兼容层
     *  即使 Key 错也不会拒绝连接本身：如果返回 401/403 = 路径通；返回 404 = 路径不存在）。
     */
    private fun probeT2IEndpoint(baseUrl: String, apiKey: String, type: ProviderType): Int {
        val payload = """{"model":"probe","prompt":"connection probe","n":1,"size":"256x256","response_format":"b64_json"}"""
        val req = Request.Builder()
            .url(joinUrl(baseUrl, "v1/images/generations"))
            .applyAuth(apiKey, type)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute().use { it.code }
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

    /** 转成安全的 JSON 字符串字面量（含引号、正确转义）。 */
    private fun String.jsonStr(): String = JsonPrimitive(this).toString()
}
