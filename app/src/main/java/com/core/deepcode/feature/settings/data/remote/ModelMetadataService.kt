package com.core.deepcode.feature.settings.data.remote

import android.content.Context
import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.datalayer.repository.AgentRepository as V2AgentRepository
import com.core.deepcode.datalayer.sqldelight.agent.Model_capability_overrides as V2ModelCapabilityOverride
import com.core.deepcode.feature.agent.data.local.entity.ModelCapabilityOverrideEntity
import com.core.deepcode.feature.proxy.domain.ClashProxyManager
import com.core.deepcode.feature.settings.data.repository.CompatibilityPolicyRepository
import com.core.deepcode.feature.settings.data.repository.DefaultPolicy
import com.core.deepcode.feature.settings.domain.model.ModelMetadata
import com.core.deepcode.feature.settings.domain.model.ProviderType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelMetadataService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    /**
     * 兼容端点默认策略 Repository（RC63 备选方案③）。
     * 仅在 resolve → findMetadata 命中失败走 default() fallback 的时候才读取，
     * catalog 已收录（MODELS_DEV 源）的模型完全不读，保证不影响整体。
     */
    private val compatibilityPolicyRepository: CompatibilityPolicyRepository,
    /**
     * 共享 OkHttp（AgentModule 注入）。以 newBuilder 派生 metadataClient：
     * 继承其 ProxySelector（代理启用时 models.dev 也走 mihomo mixed-port，而非直连），
     * 同时覆写回短超时，避免占用共享 client 的 120s 流式超时语义。
     */
    private val okHttp: OkHttpClient,
    /**
     * 代理引擎管理器：监听其状态，当代理（mihomo 内核控制面）就绪后自动补拉元数据。
     * 解决「App 启动即拉取、此时代理尚未自动恢复完成 → 直连 models.dev 超时 → 永不重试」的问题。
     */
    private val proxyManager: ClashProxyManager,
    private val v2Agent: V2AgentRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 独立协程作用域：监听代理状态，不占模型请求链路。 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化刷新，避免启动拉取与代理就绪补拉并发重复。 */
    private val refreshMutex = Mutex()

    @Volatile
    private var cached: Cache? = null

    @Volatile
    private var refreshAttemptedThisProcess = false

    /** 进程内是否已在「代理就绪」后触发过一次补拉（每个进程最多一次）。 */
    @Volatile
    private var proxyRetryFired = false

    init {
        // 代理就绪（内核控制面可达）后自动补拉元数据：覆盖两类场景——
        //  ① 启动时代理自动恢复尚未完成，首拉走直连失败；
        //  ② 用户随后手动开启代理。
        // 等代理真正可用再发请求，避免把流量打进无人监听的 7890 或直连被墙的 models.dev。
        serviceScope.launch {
            proxyManager.state.collect { state ->
                if (state.enabled && state.controllerReachable && !proxyRetryFired) {
                    proxyRetryFired = true
                    refreshInternal()
                }
            }
        }
    }

    /** models.dev 仅作元数据增强：独立短超时 client，不可达时快速失败，不占用共享的 120s 流式超时。 */
    private val metadataClient: OkHttpClient = okHttp.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(type: ProviderType, modelId: String): ModelMetadata = withContext(Dispatchers.IO) {
        val catalog = loadCatalog()
        val base = findMetadata(catalog, type, modelId) ?: default(type, modelId)
        // 决策链最后一步：应用兼容端点策略（仅 source=INFERRED 会读到；MODELS_DEV 的 catalog 模型
        // base.source==MODELS_DEV，此处跳过，保证已收录模型零影响），再叠加④单模型复选框覆盖。
        applyCompatibilityPolicies(base, type, modelId)
    }

    suspend fun resolveAll(type: ProviderType, modelIds: List<String>): Map<String, ModelMetadata> =
        withContext(Dispatchers.IO) {
            val catalog = loadCatalog()
            modelIds.associateWith { modelId ->
                val base = findMetadata(catalog, type, modelId) ?: default(type, modelId)
                applyCompatibilityPolicies(base, type, modelId)
            }
        }

    /**
     * App 启动时统一调用的异步刷新：磁盘缓存未过期（<24h）则跳过；拉取成功写入内存与磁盘缓存，
     * 失败静默（resolve 回退内置 assets 数据）。进程内只尝试一次，绝不阻塞模型请求。
     * 代理未就绪导致的失败由 [init] 中的代理状态监听在代理就绪后自动补拉。
     */
    suspend fun refreshFromNetworkIfStale() {
        if (refreshAttemptedThisProcess) return
        refreshAttemptedThisProcess = true
        refreshInternal()
    }

    /** 真正的刷新实现（可被启动拉取与代理就绪补拉重复调用）；互斥锁防止并发重复拉取。 */
    private suspend fun refreshInternal() {
        refreshMutex.withLock {
            // 已在内存缓存成功拉取过，直接跳过，避免重复拉取
            cached?.let { return@withLock }
            val diskCache = loadCatalogFromDisk()
            if (diskCache != null && isFresh(diskCache)) {
                cached = diskCache
                return@withLock
            }
            withContext(Dispatchers.IO) {
                runCatching { fetchCatalogFromNetwork() }
                    .onFailure { e ->
                        FileLogger.w(TAG, "拉取 models.dev 模型元数据失败（代理就绪后将自动重试）", e)
                    }
            }
        }
    }

    private fun isFresh(cache: Cache): Boolean =
        System.currentTimeMillis() - cache.loadedAtMs < CACHE_MAX_AGE_MS

    /** 纯只读链路：内存 → 磁盘缓存(24h 内) → 内置 assets → 空目录（由调用方回退默认值），绝不发网络请求。 */
    private fun loadCatalog(): Map<String, Map<String, ModelMetadata>> {
        cached?.let {
            return it.catalog
        }

        loadCatalogFromDisk()?.takeIf { isFresh(it) }?.let {
            cached = it
            return it.catalog
        }

        loadCatalogFromAssets()?.let {
            cached = it
            return it.catalog
        }

        return emptyMap()
    }

    private fun fetchCatalogFromNetwork() {
        runCatching {
            val request = Request.Builder()
                .url(MODELS_DEV_URL)
                .header("User-Agent", "deepcore-code")
                .get()
                .build()

            metadataClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("HTTP ${response.code}: ${body.take(200)}")
                writeCatalogCache(body)
                parseCatalog(json.parseToJsonElement(body))
            }
        }.onSuccess { catalog ->
            cached = Cache(System.currentTimeMillis(), catalog)
        }.onFailure { e ->
            FileLogger.w(TAG, "拉取 models.dev 模型元数据失败", e)
        }
    }

    private fun loadCatalogFromDisk(): Cache? {
        val file = cacheFile()
        if (!file.isFile) return null
        return runCatching {
            val body = file.readText(Charsets.UTF_8)
            val loadedAtMs = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
            Cache(loadedAtMs, parseCatalog(json.parseToJsonElement(body)))
        }.getOrNull()
    }

    private fun loadCatalogFromAssets(): Cache? = runCatching {
        val body = context.assets.open(ASSET_FILE_NAME).bufferedReader().use { it.readText() }
        Cache(0L, parseCatalog(json.parseToJsonElement(body)))
    }.getOrNull()

    private fun writeCatalogCache(body: String) {
        runCatching {
            cacheFile().writeText(body, Charsets.UTF_8)
        }
    }

    private fun cacheFile(): File = File(context.cacheDir, CACHE_FILE_NAME)

    /**
     * 目录中匹配不到模型时的兜底：
     *  - supportsVision/supportsTools/supportsReasoning 不再一刀切 false，改为用 modelId 启发式判断。
     *  这样即使用户配置了 catalog 未收录的自定义多模态模型（如 qwen-vl-max、glm-4v、自建兼容端点
     *  的 vision 模型），也不会在发送前被 StatefulAgentWorkflow.sanitizeImagesForModel() 误把图片
     *  剥空（那是「支持多模态模型但识别不到图」的根本原因）。
     *  仅当启发式完全没命中时才保守 false。
     */
    private fun default(type: ProviderType, modelId: String): ModelMetadata {
        val idLower = modelId.lowercase()
        val probablyVision =
            idLower.contains("vision") || idLower.contains("-vl") || idLower.contains("vl-") ||
            idLower.contains("image") || idLower.contains("omni") || idLower.contains("gpt-4o") ||
            idLower.contains("gpt-4.5") || idLower.contains("gemini-1.5") ||
            idLower.contains("gemini-2") || idLower.startsWith("gemini-") ||
            idLower.contains("glm-4v") || idLower.contains("glm-5v") ||
            idLower.contains("qwen-vl") || idLower.contains("qwen2-vl") ||
            idLower.contains("qwen3-vl") || idLower.contains("doubao") && idLower.contains("vision") ||
            idLower.contains("ministral-3b") || idLower.contains("pixtral") ||
            idLower.contains("claude-3-5-sonnet") || idLower.contains("claude-3-opus") ||
            idLower.contains("claude-3-haiku") || idLower.contains("claude-3.7") ||
            // 阶跃星辰 StepFun 家族：step-3.7-flash 官方文档明确「原生多模态（图+视频+工具调用）」
            // step-3.5-flash 官方文档明确纯文本不含多模态，排除。
            (idLower.startsWith("step-") && !idLower.contains("step-3.5")) ||
            idLower.startsWith("step1v") || idLower.startsWith("step2v") ||
            idLower.startsWith("step-1v") || idLower.startsWith("step-2v") ||
            idLower.startsWith("step-1.5v")
        val probablyReasoning =
            idLower.contains("reasoning") || idLower.contains("o1") || idLower.contains("o3") ||
            idLower.contains("deepseek-r") || idLower.contains("rwkv") && idLower.contains("-r") ||
            idLower.contains("qwen3-8b-a3b")
        val probablyTools =
            probablyVision || probablyReasoning || type != ProviderType.ANTHROPIC
        return ModelMetadata(
            id = modelId,
            providerId = type.name.lowercase(),
            displayName = modelId,
            contextTokens = DEFAULT_CONTEXT_TOKENS,
            inputTokens = DEFAULT_CONTEXT_TOKENS,
            outputTokens = DEFAULT_OUTPUT_TOKENS,
            supportsTools = probablyTools,
            supportsVision = probablyVision,
            supportsReasoning = probablyReasoning,
            source = ModelMetadata.Source.INFERRED,
            // 保留启发式命中详情，便于阶段 4 ④单模型覆盖 UI 的「智能预填 banner」展示来源。
            inferenceReason = ModelMetadata.InferenceReason(
                byProbablyVision = probablyVision,
                byProbablyReasoning = probablyReasoning,
                byProbablyTools = probablyTools
            )
        )
    }

    /**
     * RC63 决策链最后一步：按优先级合并兼容端点策略 & ④单模型复选框手动覆盖。
     *
     * 合并原则（严格保证不影响整体）：
     *  - MODELS_DEV 源（catalog 收录模型）：仅应用④单模型覆盖（其他任何策略不碰）。
     *    为什么 catalog 收录也要允许覆盖？因为有些 catalog（api.official.json）的信息会比
     *    用户真实接入的兼容端点滞后或漏字段，小白想手动覆盖支持 vision/tools/reasoning
     *    时必须允许覆盖，这是 RC63 「针对性修复独立出来」的真正落脚点——用户掌控一切。
     *  - INFERRED 源（自定义兼容端点 / 未收录模型）：
     *      1) 先应用「③ 兼容端点 DefaultPolicy」（STRICT / HEURISTIC / LAX / MANUAL）；
     *      2) 再应用「④ 单模型复选框覆盖」（最高优先级）。
     */
    private suspend fun applyCompatibilityPolicies(
        base: ModelMetadata,
        type: ProviderType,
        modelId: String
    ): ModelMetadata {
        val afterPolicy = if (base.source != ModelMetadata.Source.INFERRED) {
            base
        } else {
            val policy = compatibilityPolicyRepository.getDefaultPolicy()
            val (v, t, r) = when (policy) {
                DefaultPolicy.STRICT,
                DefaultPolicy.HEURISTIC -> {
                    // 严格/启发式：保持 probablyVision/Tools/Reasoning 的默认值不变（RC62d 语义）。
                    Triple(base.supportsVision, base.supportsTools, base.supportsReasoning)
                }
                DefaultPolicy.LAX -> {
                    // 宽松：INFERRED 一律三能力 true（RC62e 行为），用户手动开启。
                    Triple(true, true, true)
                }
                DefaultPolicy.MANUAL -> {
                    // 完全手动：三能力一律 false，只等用户在单模型复选框（④）手动覆盖开启。
                    Triple(false, false, false)
                }
            }
            base.copy(
                supportsVision = v,
                supportsTools = t,
                supportsReasoning = r,
                inferenceReason = base.inferenceReason?.copy(appliedPolicy = policy.name)
                    ?: ModelMetadata.InferenceReason(
                        byProbablyVision = v,
                        byProbablyTools = t,
                        byProbablyReasoning = r,
                        appliedPolicy = policy.name
                    )
            )
        }

        // 阶段 4 ④：单模型复选框覆盖（优先级最高）。MODELS_DEV 与 INFERRED 源都允许。
        val overrideRow = runCatching {
            v2Agent.getCapabilityOverride(type.name, modelId)?.toEntity()
        }.onFailure {
            FileLogger.w(TAG, "读取单模型能力覆盖失败(type=${type.name}, id=$modelId)", it)
        }.getOrNull() ?: return afterPolicy

        val override = overrideRow
        val vision = override.overrideVision ?: afterPolicy.supportsVision
        val tools = override.overrideTools ?: afterPolicy.supportsTools
        val reasoning = override.overrideReasoning ?: afterPolicy.supportsReasoning
        val originReason = afterPolicy.inferenceReason ?: ModelMetadata.InferenceReason()
        return afterPolicy.copy(
            supportsVision = vision,
            supportsTools = tools,
            supportsReasoning = reasoning,
            inferenceReason = originReason.copy(
                overrideVision = override.overrideVision,
                overrideTools = override.overrideTools,
                overrideReasoning = override.overrideReasoning
            )
        )
    }

    // —— RC63 备选方案④对外接口：设置页 UI（单选按钮 / 一键推荐）统一通过下面几个方法写入覆盖。 ——

    /** 保存（或覆盖）单个模型的手动覆盖。不想覆盖的字段传 null（维持自动决策）。 */
    suspend fun saveOverride(
        type: ProviderType,
        modelId: String,
        vision: Boolean?,
        tools: Boolean?,
        reasoning: Boolean?
    ) = withContext(Dispatchers.IO) {
        val entity = ModelCapabilityOverrideEntity(
            id = ModelCapabilityOverrideEntity.composeId(type.name, modelId),
            providerType = type.name,
            modelId = modelId,
            overrideVision = vision,
            overrideTools = tools,
            overrideReasoning = reasoning
        )
        v2Agent.upsertCapabilityOverride(
            id = entity.id,
            providerType = entity.providerType,
            modelId = entity.modelId,
            overrideVision = entity.overrideVision?.let { if (it) 1L else 0L },
            overrideTools = entity.overrideTools?.let { if (it) 1L else 0L },
            overrideReasoning = entity.overrideReasoning?.let { if (it) 1L else 0L },
            updatedAtMs = System.currentTimeMillis()
        )
    }

    /** 删除单个模型的覆盖（恢复到「启发式 + 兼容端点策略」的自动路径）。 */
    suspend fun clearOverride(type: ProviderType, modelId: String) = withContext(Dispatchers.IO) {
        v2Agent.deleteCapabilityOverride(type.name, modelId)
    }

    /** 流式观察单模型覆盖（设置页 UI 观察后实时刷新标签角标）。 */
    fun observeOverride(type: ProviderType, modelId: String): Flow<ModelCapabilityOverrideEntity?> =
        v2Agent.observeCapabilityOverride(type.name, modelId).map { list -> list.firstOrNull()?.toEntity() }

    private fun findMetadata(
        catalog: Map<String, Map<String, ModelMetadata>>,
        type: ProviderType,
        modelId: String
    ): ModelMetadata? {
        val normalized = modelId.removePrefix("models/")
        val preferredProviders = when (type) {
            ProviderType.OPENAI -> listOf(
                "openai", "openrouter", "deepseek", "groq", "xai", "mistral",
                "togetherai", "alibaba", "moonshot", "github-copilot"
            )
            ProviderType.ANTHROPIC -> listOf("anthropic", "google-vertex-anthropic")
            ProviderType.GEMINI -> listOf("google", "google-vertex")
        }

        for (provider in preferredProviders) {
            catalog[provider]?.get(normalized)?.let { return it }
        }
        return catalog.values.firstNotNullOfOrNull { models -> models[normalized] }
    }

    private fun parseCatalog(root: JsonElement): Map<String, Map<String, ModelMetadata>> {
        return root.jsonObject.mapValues { (providerId, providerEl) ->
            val models = providerEl.jsonObject["models"]?.jsonObject.orEmpty()
            models.mapValues { (_, modelEl) ->
                val model = modelEl.jsonObject
                val limit = model["limit"]?.jsonObject
                val modalities = model["modalities"]?.jsonObject
                val inputModalities = modalities?.get("input")?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.content }
                    .orEmpty()
                ModelMetadata(
                    id = model["id"]?.jsonPrimitive?.content ?: "",
                    providerId = providerId,
                    displayName = model["name"]?.jsonPrimitive?.content ?: model["id"]?.jsonPrimitive?.content.orEmpty(),
                    contextTokens = limit?.get("context")?.jsonPrimitive?.intOrNull ?: 0,
                    inputTokens = limit?.get("input")?.jsonPrimitive?.intOrNull,
                    outputTokens = limit?.get("output")?.jsonPrimitive?.intOrNull,
                    supportsTools = model["tool_call"]?.jsonPrimitive?.booleanOrNull == true,
                    supportsVision = "image" in inputModalities || "video" in inputModalities || "pdf" in inputModalities,
                    supportsReasoning = model["reasoning"]?.jsonPrimitive?.booleanOrNull == true,
                    source = ModelMetadata.Source.MODELS_DEV
                )
            }
        }
    }

    private data class Cache(
        val loadedAtMs: Long,
        val catalog: Map<String, Map<String, ModelMetadata>>
    )

    // ── V2（SQLDelight）↔ Room Entity 映射 ──────────────────────────────

    private fun V2ModelCapabilityOverride.toEntity() = ModelCapabilityOverrideEntity(
        id = id,
        providerType = provider_type,
        modelId = model_id,
        overrideVision = override_vision?.let { it != 0L },
        overrideTools = override_tools?.let { it != 0L },
        overrideReasoning = override_reasoning?.let { it != 0L },
        updatedAtMs = updated_at_ms
    )

    private companion object {
        const val TAG = "ModelMetadataService"
        const val MODELS_DEV_URL = "https://models.dev/api.json"
        const val CACHE_FILE_NAME = "models-dev-api.json"
        const val ASSET_FILE_NAME = "api.official.json"
        const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        const val DEFAULT_CONTEXT_TOKENS = 128_000
        const val DEFAULT_OUTPUT_TOKENS = 64_000
    }
}
