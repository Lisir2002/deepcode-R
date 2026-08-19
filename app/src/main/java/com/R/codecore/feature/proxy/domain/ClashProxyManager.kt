package com.R.codecore.feature.proxy.domain

import android.content.Context
import android.os.Build
import com.R.codecore.core.util.FileLogger
import java.io.IOException
import com.R.codecore.feature.proxy.data.ProxySettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.yaml.snakeyaml.Yaml
import javax.inject.Inject
import javax.inject.Singleton

/** 当前代理运行状态（供 UI 与工具 status 共用）。 */
data class ProxyRuntimeState(
    val enabled: Boolean = false,
    val mode: String = "rule",
    val activeProfileId: String? = null,
    val mixedHost: String = "127.0.0.1",
    val mixedPort: Int = ClashProxyManager.MIXED_PORT,
    val controllerHost: String = "127.0.0.1",
    val controllerPort: Int = ClashProxyManager.CONTROLLER_PORT,
    /** 控制器最近一次是否连得上 mihomo。 */
    val controllerReachable: Boolean = false,
)

/** 单个代理节点（解析自 Clash 配置，仅展示用）。 */
data class ProxyNodeInfo(
    val name: String,
    val type: String,
    val server: String,
    val port: Int,
)

/** 一份 Clash 配置的解析概览（供预检 / 节点列表展示）。 */
data class ClashConfigSummary(
    val ok: Boolean,
    val nodes: List<ProxyNodeInfo> = emptyList(),
    val groups: List<String> = emptyList(),
    val providerCount: Int = 0,
    val error: String? = null,
)

/** mihomo /proxies 运行态中的一个分组（对齐 Clash 分组树：类型/选中项/成员/健康检查延迟）。 */
data class ProxyGroupInfo(
    val name: String,
    val type: String,
    val now: String?,
    val all: List<String>,
    val delay: Long?,
)

/** /proxies 全量快照（分组树 + 节点数），UI 与工具共用。 */
data class ClashProxiesSnapshot(
    val groups: List<ProxyGroupInfo>,
    val nodeCount: Int,
)

/** /traffic WS 推流的一条速率（字节/秒），实时流量展示用。 */
data class ProxyTraffic(
    val up: Long,
    val down: Long,
)

/**
 * 容器内 mihomo 代理引擎管理器（生命周期仿 [com.R.codecore.feature.agent.domain.bridge.RcbBridge]）。
 *
 * 职责分层（《网络代理设计 v1.0》§2.3 / §5）：
 *  - **配置合成**：订阅/手动 YAML → `synthesizeConfig()` 叠加**固定覆盖块**（mixed-port 7890、
 *    external-controller 127.0.0.1:9090 + secret、allow-lan false、mode rule、DIRECT 兜底），并剥掉
 *    源配置里的危险键（listen/port 等），产物写 `filesDir/rcodecore/proxy/config.yaml`。
 *  - **env 注入**：`exportContainerEnv()` 供 [LinuxContainerEngine.buildContainerEnv] 并入，让容器内
 *    进程的 http/https/all_proxy 指向 127.0.0.1:7890（容器与宿主共享 loopback，同一 mihomo 实例）。
 *  - **控制面**：`controllerRequest()` 打 mihomo external-controller REST（secret 鉴权），供
 *    `status/list_proxies/select/latency` 用；连不上即视为未运行。
 *
 * 说明：mihomo 二进制本身放入容器并在容器内拉起属于容器初始化层（根侧），本管理器只负责
 * 状态机、配置产物与 env/clc control 面；容器内新终端会因 env 注入直接吃到代理。
 */
@Singleton
class ClashProxyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
    private val repository: ProxySettingsRepository,
    private val routeHolder: ProxyRouteHolder,
) {
    companion object {
        private const val TAG = "ClashProxyManager"
        const val MIXED_PORT = 7890
        const val CONTROLLER_PORT = 9090
        const val CONTROLLER_HOST = "127.0.0.1"
        private const val CONFIG_DIR = "proxy"
        private const val CONFIG_FILE = "config.yaml"
        private const val SECRET_FILE = "secret"

        /** 被覆盖块接管、需从源配置剥离的顶层键（避免与 fixed override 冲突或被恶意夹带）。 */
        val OVERRIDDEN_KEYS = listOf(
            "mixed-port", "port", "socks-port", "redir-port",
            "tproxy-port", "external-controller", "external-ui",
            "secret", "allow-lan", "bind-address", "mode"
        )

        /** /proxies 中被视为「分组」的 type：mihomo 五类策略组 + 内置直达/拒绝等（对齐 Clash 分组树）。 */
        val GROUP_TYPES = setOf(
            "Selector", "URLTest", "Fallback", "LoadBalance", "Relay",
            "Direct", "Reject", "RejectDrop", "Compatible", "Pass"
        )

        /**
         * 拉取订阅用的 User-Agent。实测该订阅商（nginx）按 UA 白名单放行：
         * 非 Clash 系 UA（如通用浏览器/curl/自研 UA）直接回 406 Not Acceptable（HTML 错误页），
         * Clash 系 UA（clash.meta / ClashMetaForAndroid/...）才回 200 + 完整 YAML。
         * 故伪装成 mihomo 自身默认订阅 UA，保证订阅在程序内与 Clash 表现一致。
         */
        const val SUBSCRIPTION_USER_AGENT = "clash.meta"

        /**
         * mihomo 内核（Clash Meta）发布版本与二进制资产。
         *
         * 选择 **android 构建**（与 ClashMetaForAndroid 同源）：可直接作为 App 子进程运行，
         * 绑定 127.0.0.1:7890 —— App 网络栈与容器进程共享 loopback，同一实例两侧共用。
         * SHA256 为对应 `.gz` 的官方校验和（发布页 sha256sums 同值），版本固定避免运行时
         * 探测 GitHub API 引入不确定性与降级风险；旧版本资产长期保留，故固定版本安全。
         *
         * 资产命名：arm64 → `mihomo-android-arm64-v8-…`；x86_64 → `mihomo-android-amd64-…`
         * （`-amd64-v1/-v2/-v3` 只是 **linux** 构建的 CPU 档位命名，android 构建统一用 `-amd64-`）。
         */
        const val MIHOMO_VERSION = "v1.19.13"
        const val MIHOMO_BASE_URL = "https://github.com/MetaCubeX/mihomo/releases/download/$MIHOMO_VERSION/"
        const val MIHOMO_ARM64_ASSET = "mihomo-android-arm64-v8-$MIHOMO_VERSION.gz"
        const val MIHOMO_ARM64_GZ_SHA256 = "c896cbe91344124da0c8e0b93d77a11fae53fc16f49b1b8cd238b5008e336e5b"
        const val MIHOMO_AMD64_ASSET = "mihomo-android-amd64-$MIHOMO_VERSION.gz"
        const val MIHOMO_AMD64_GZ_SHA256 = "f930e62c24f6f6ae18790282963d47eadaeed61346a8d869ca899acdb8c7cf29"
    }

    private val _state = MutableStateFlow(ProxyRuntimeState())
    val state: StateFlow<ProxyRuntimeState> = _state.asStateFlow()

    /** 串行化内核的启动/停止，避免 on() 与启动时自动恢复并发双拉。 */
    private val kernelMutex = Mutex()

    /** 当前 mihomo 内核子进程（App 子进程，绑定 127.0.0.1:7890；App 进程存活则内核存活）。 */
    @Volatile
    private var mihomoProcess: Process? = null

    /**
     * 下载 mihomo 二进制用的**直连** OkHttp：强制 Proxy.NO_PROXY 覆盖共享 client 的 ProxySelector。
     * 内核二进制属于基础设施，必须绕过代理自举（代理未起/代理本身被墙都不能成为下载失败原因）。
     */
    private val directClient: OkHttpClient by lazy {
        okHttp.newBuilder().proxy(java.net.Proxy.NO_PROXY).build()
    }

    /** env 注入/控制器读取的安全鉴权令牌（app 运行时生成并落盘，跨进程一致）。 */
    @Volatile
    private var secret: String = ""

    /** 全局开关缓存：buildContainerEnv 是同步方法，不能在其中挂起读 DataStore，故用 flow 预热。 */
    @Volatile
    private var enabledCache: Boolean = false

    init {
        secret = loadOrCreateSecret()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            // 首帧：上次若为启用态，先拉起内核、确认控制面就绪，再同步开关。
            // 顺序不可反——先置 enabled 会让共享 OkHttp 把流量打进还没人监听的 7890（对应
            // ModelMetadataService 那类 `Failed to connect to /127.0.0.1:7890`），必须内核先起。
            val initiallyEnabled = repository.isProxyEnabled()
            if (initiallyEnabled) {
                // 内核以 -f config.yaml 启动，配置缺失会瞬时退出（code=1）——先兜底重建再拉起。
                val cfgFile = java.io.File(configDir(), CONFIG_FILE)
                if (!cfgFile.isFile) {
                    val rebuilt = rebuildConfigFromActiveProfile()
                    FileLogger.i(
                        TAG,
                        if (rebuilt) "启动时重写丢失的 config.yaml"
                        else "config.yaml 缺失且无法从活跃 profile 重建，跳过自动恢复"
                    )
                }
                val ok = ensureKernelRunning(restart = false)
                enabledCache = ok
                _state.update { it.copy(enabled = ok) }
                routeHolder.update(ok, "127.0.0.1:$MIXED_PORT")
                FileLogger.i(
                    TAG,
                    if (ok) "启动时自动恢复 mihomo 内核成功"
                    else "启动时自动恢复 mihomo 内核失败（保持代理关闭，避免把网络流量打进未监听的端口）"
                )
            } else {
                routeHolder.update(false, "127.0.0.1:$MIXED_PORT")
            }
            // 之后的开关变化继续由 flow 驱动
            repository.proxyEnabledFlow.drop(1).collect { e ->
                enabledCache = e
                _state.update { it.copy(enabled = e) }
                // 同步给 App 网络层的路由开关写位，让共享 OkHttp 的 ProxySelector 感知（§4.2）
                routeHolder.update(e, "127.0.0.1:$MIXED_PORT")
            }
        }
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            repository.activeProfileIdFlow.collect { id ->
                _state.update { it.copy(activeProfileId = id) }
            }
        }
    }

    /** 供容器/上层读取的启用态（同步、非挂起）。 */
    fun isEnabled(): Boolean = enabledCache

    /** 控制器地址 "127.0.0.1:port"。 */
    fun controllerAddress(): String = "$CONTROLLER_HOST:$CONTROLLER_PORT"

    fun controllerSecret(): String = secret

    private fun configDir(): java.io.File =
        java.io.File(java.io.File(context.filesDir, "rcodecore"), CONFIG_DIR)

    private fun loadOrCreateSecret(): String {
        return try {
            val dir = configDir().apply { mkdirs() }
            val f = java.io.File(dir, SECRET_FILE)
            if (f.exists()) {
                f.readText().trim().ifBlank { newSecret(f) }
            } else {
                newSecret(f)
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取代理 secret 失败: ${e.message}")
            ""
        }
    }

    private fun newSecret(f: java.io.File): String {
        val pool = "abcdefghijklmnopqrstuvwxyz0123456789"
        val s = (0 until 24).joinToString("") { pool[kotlin.random.Random.nextInt(pool.length)].toString() }
        runCatching { f.writeText(s) }
        FileLogger.i(TAG, "已生成代理 control secret")
        return s
    }

    /**
     * 合成 mihomo 配置：固定覆盖块头 + 清洗后的源配置体。
     *
     * 用真实 YAML 解析而非逐行正则清洗：旧实现按「行首命中 OVERRIDDEN_KEYS 就删整行」，
     * 会把**块式**代理节点里的嵌套键（如 `    port: 443`、`mode:`、`secret:`）误删，导致
     * mihomo 加载配置 FATAL 秒退（code=1，且 log-level 静默时无任何日志可查）。
     * 改为解析成 Map 后仅移除顶层危险键再 dump 回 YAML，嵌套结构不受损。
     *
     * 源不是 YAML 映射（订阅回 HTML/裸文本等）时，退化为仅 DIRECT 兜底的空配置并告警，
     * 避免 mihomo 因配置不可解析而秒退。
     */
    fun synthesizeConfig(sourceYaml: String): String {
        val clean = LinkedHashMap<Any?, Any?>()
        try {
            val root = Yaml().load<Any?>(sourceYaml)
            if (root is Map<*, *>) {
                root.forEach { (k, v) ->
                    val key = k?.toString() ?: return@forEach
                    if (key !in OVERRIDDEN_KEYS) clean[key] = v
                }
            } else {
                FileLogger.w(
                    TAG,
                    "订阅源不是 YAML 映射（实际为 ${root?.javaClass?.simpleName ?: "null"}），仅生成 DIRECT 兜底配置"
                )
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "订阅源 YAML 解析失败（${e.message}），仅生成 DIRECT 兜底配置")
        }
        if (clean["rules"] == null) clean["rules"] = listOf("MATCH,DIRECT")
        val body = Yaml().dump(clean)
        return buildString {
            appendLine("mixed-port: $MIXED_PORT")
            appendLine("allow-lan: false")
            appendLine("mode: rule")
            // info 而非 silent：内核启动期 FATAL/错误必须落到 mihomo.log，否则秒退原因完全不可见。
            appendLine("log-level: info")
            appendLine("external-controller: $CONTROLLER_HOST:$CONTROLLER_PORT")
            appendLine("secret: \"$secret\"")
            appendLine()
            append(body.trimEnd('\n'))
            appendLine()
        }
    }

    /** 订阅 URL 全文抓取（拉取远端订阅 YAML）。失败返回 null。 */
    suspend fun fetchSubscriptionYaml(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", SUBSCRIPTION_USER_AGENT)
                .get().build()
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful || resp.body == null) return@use null
                resp.body!!.string()
            }
        }.getOrNull()
    }

    /**
     * 用 SnakeYAML 解析 Clash 配置，统计内联节点 / 分组 / proxy-provider。
     *
     * 修复「解析 OK 但节点 0」：旧实现用正则 `^\s*-\s+name:` 只匹配块式 `- name: …`；
     * 现实订阅多为**流式** `- {name: …, type: ss, …}` 或直接走 **proxy-provider**（动态节点池），
     * 正则都数不出来，导致节点数恒为 0。改为真实 YAML 解析后块式/流式/provider 一并对齐 Clash。
     */
    fun parseClashConfig(yaml: String): ClashConfigSummary {
        return try {
            val root = Yaml().load<Any?>(yaml)
            if (root !is Map<*, *>) {
                return ClashConfigSummary(ok = false, error = "不是合法的 YAML 映射")
            }
            val proxies = root["proxies"] as? List<*> ?: emptyList<Any?>()
            val groups = root["proxy-groups"] as? List<*> ?: emptyList<Any?>()
            val providers = root["proxy-providers"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val nodes = proxies.mapNotNull { item ->
                if (item !is Map<*, *>) return@mapNotNull null
                val name = item["name"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ProxyNodeInfo(
                    name = name,
                    type = item["type"]?.toString() ?: "unknown",
                    server = item["server"]?.toString() ?: "",
                    port = (item["port"] as? Number)?.toInt()
                        ?: item["port"]?.toString()?.toIntOrNull() ?: 0,
                )
            }
            val groupNames = groups.mapNotNull { (it as? Map<*, *>)?.get("name")?.toString() }
            ClashConfigSummary(ok = true, nodes = nodes, groups = groupNames, providerCount = providers.size)
        } catch (e: Exception) {
            ClashConfigSummary(ok = false, error = e.message ?: "YAML 解析失败")
        }
    }

    /**
     * 单节点测速（对齐 Clash：只走内核真实出口，不做 App 直连近似）。
     *
     * 走 mihomo REST `/proxies/{node}/delay?url=generate_204&timeout=5000`，与 ClashMetaForAndroid
     * 同源：由节点在运行配置下真实访问目标 URL 得出延迟。
     *  - 成功 → 毫秒延迟；
     *  - 节点超时（mihomo 返回 504）/ 控制器不可达 → null，由调用方按「超时」展示。
     *
     * 注意：旧实现里「代理未启用时用 Socket 直连 server:port 作近似」在节点服务器几乎都在海外时
     * 必然 connect 超时，导致「测速全部超时」；且该近似测得的是 TCP 握手而非代理出口延迟，与 Clash
     * 语义不符，故移除。调用方必须先把被测配置加载进内核（[on]）再测，否则 REST 会因节点不存在报错。
     */
    suspend fun testNodeLatency(node: ProxyNodeInfo): Long? = withContext(Dispatchers.IO) {
        val encoded = urlEncode(node.name)
        val resp = controllerRequest(
            "GET",
            "/proxies/$encoded/delay?url=http://www.gstatic.com/generate_204&timeout=5000"
        )
        resp?.let { body ->
            runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(body)
                    .jsonObject["delay"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            }.getOrNull()
        }
    }

    /** 把合成配置落盘到 filesDir/rcodecore/proxy/config.yaml。 */
    suspend fun writeConfigFile(configYaml: String) = withContext(Dispatchers.IO) {
        runCatching {
            val dir = configDir().apply { mkdirs() }
            java.io.File(dir, CONFIG_FILE).writeText(configYaml, Charsets.UTF_8)
            FileLogger.i(TAG, "proxy config 已写入 ${java.io.File(dir, CONFIG_FILE).absolutePath}")
        }.onFailure { FileLogger.w(TAG, "写 proxy config 失败: ${it.message}") }
    }

    /**
     * 启动时自动恢复用：config.yaml 缺失时，从活跃 profile 重新合成并落盘（尽力而为）。
     * 订阅型需重新拉远端 YAML，网络不可达时返回 false，自动恢复跳过。
     */
    private suspend fun rebuildConfigFromActiveProfile(): Boolean {
        val activeId = repository.activeProfileIdFlow.first() ?: return false
        return try {
            val revealed = repository.revealSecret(activeId) ?: return false
            val trimmed = revealed.trim()
            val source = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                fetchSubscriptionYaml(trimmed) ?: return false
            } else {
                revealed
            }
            if (source.isBlank()) return false
            writeConfigFile(synthesizeConfig(source))
            true
        } catch (t: Throwable) {
            FileLogger.w(TAG, "启动重建 config.yaml 失败: ${t.message}")
            false
        }
    }

    /** 打 mimomo external-controller REST；成功返回 body，失败返回 null（视为未运行/网络错）。 */
    suspend fun controllerRequest(
        method: String,
        path: String,
        body: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val url = "http://$CONTROLLER_HOST:$CONTROLLER_PORT$path"
        val requestBody = body?.toRequestBody("application/json".toMediaType())
            ?: ByteArray(0).toRequestBody(null)
        val req = when (method) {
            "GET" -> Request.Builder().url(url).header("Authorization", "Bearer $secret").get().build()
            "PUT" -> Request.Builder().url(url).header("Authorization", "Bearer $secret").put(requestBody).build()
            "PATCH" -> Request.Builder().url(url).header("Authorization", "Bearer $secret").patch(requestBody).build()
            else -> return@withContext null
        }
        runCatching {
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    _state.update { it.copy(controllerReachable = true) }
                    null
                } else {
                    _state.update { it.copy(controllerReachable = true) }
                    resp.body?.string()
                }
            }
        }.getOrNull()
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * 拉取 mihomo /proxies 全量快照（分组树：类型/当前选中项/成员/健康检查延迟）。
     * 与 `network_proxy list_proxies` 共用同一 REST 数据源；未运行/不可达返回 null。
     */
    suspend fun fetchProxiesSnapshot(): ClashProxiesSnapshot? {
        val resp = controllerRequest("GET", "/proxies") ?: return null
        return runCatching {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(resp).jsonObject
            val proxies = root["proxies"]?.jsonObject ?: return null
            val groups = proxies.mapNotNull { (name, el) ->
                val obj = el.jsonObject
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                if (type !in GROUP_TYPES) return@mapNotNull null
                val now = obj["now"]?.jsonPrimitive?.contentOrNull
                val all = obj["all"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val delay = obj["history"]?.jsonArray?.lastOrNull()
                    ?.jsonObject?.get("delay")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ProxyGroupInfo(name, type, now, all, delay)
            }
            ClashProxiesSnapshot(groups = groups, nodeCount = proxies.size)
        }.getOrNull()
    }

    /**
     * 切换某分组内选中节点（对齐 Clash 点击切换）。走同一 REST：PUT /proxies/{group}。
     * 成功返回 true。
     */
    suspend fun selectProxyNode(group: String, node: String): Boolean {
        val resp = controllerRequest("PUT", "/proxies/${urlEncode(group)}", """{"name":"$node"}""")
        return resp != null
    }

    /**
     * /traffic WS 实时推流（零轮询，与 mihomo/CMA 的 flow 数据源一致）。
     * 每次 collect 建立一条 WS；collect 取消即关闭（awaitClose 里 ws.cancel()），失败自动结束流。
     */
    fun trafficFlow(): Flow<ProxyTraffic> = callbackFlow {
        val req = Request.Builder()
            .url("http://$CONTROLLER_HOST:$CONTROLLER_PORT/traffic")
            .header("Authorization", "Bearer $secret")
            .build()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val el = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
                    val up = el["up"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                    val down = el["down"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                    trySend(ProxyTraffic(up, down))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        }
        val ws = okHttp.newWebSocket(req, listener)
        awaitClose { ws.cancel() }
    }

    // ============================ mihomo 内核生命周期 ============================

    private fun mihomoDir(): java.io.File = java.io.File(configDir(), "mihomo").apply { mkdirs() }

    private fun mihomoBinary(): java.io.File = java.io.File(mihomoDir(), "mihomo")

    /** 按宿主 ABI 挑选 android 构建资产（+ 官方 SHA256）；不支持的 ABI 返回 null。 */
    private fun pickMihomoAsset(): Pair<String, String>? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        return when {
            abi.startsWith("arm64") -> MIHOMO_ARM64_ASSET to MIHOMO_ARM64_GZ_SHA256
            abi.startsWith("x86_64") -> MIHOMO_AMD64_ASSET to MIHOMO_AMD64_GZ_SHA256
            else -> null
        }
    }

    /**
     * 下载并校验 mihomo 内核二进制（首次使用触发，已存在且体积正常则跳过）。
     * 走 [directClient]（Proxy.NO_PROXY）**自举**：内核属于基础设施，代理未起/代理自身不可达
     * 都不能成为下载失败的原因，故必须绕过代理直接拉 GitHub。
     * @return null=就绪；非 null=失败原因（供调用方回显）。
     */
    private suspend fun downloadMihomoIfNeeded(): String? = withContext(Dispatchers.IO) {
        val (asset, expectedSha) = pickMihomoAsset()
            ?: return@withContext "不支持的 CPU 架构：${Build.SUPPORTED_ABIS.firstOrNull()}"
        val bin = mihomoBinary()
        if (bin.isFile && bin.length() > 1_000_000) return@withContext null
        val dir = mihomoDir()
        val gz = java.io.File(dir, asset)
        runCatching {
            val req = Request.Builder().url(MIHOMO_BASE_URL + asset).get().build()
            directClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful || resp.body == null) {
                    throw IOException("HTTP ${resp.code}")
                }
                val md = MessageDigest.getInstance("SHA-256")
                resp.body!!.byteStream().use { input ->
                    gz.outputStream().buffered().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (!hex.equals(expectedSha, ignoreCase = true)) {
                    throw IOException("SHA256 校验失败（期望 $expectedSha，实际 $hex）")
                }
            }
        }.onFailure {
            gz.delete()
            FileLogger.w(TAG, "下载 mihomo 失败: ${it.message}")
            return@withContext "下载 mihomo 失败：${it.message}"
        }
        // 校验通过 → 解压 gz
        runCatching {
            GZIPInputStream(gz.inputStream().buffered()).use { gzip ->
                bin.outputStream().buffered().use { out -> gzip.copyTo(out, 64 * 1024) }
            }
            gz.delete()
            if (!bin.setExecutable(true, false)) {
                FileLogger.w(TAG, "mihomo setExecutable 返回 false（可能仍可执行）")
            }
            FileLogger.i(TAG, "mihomo 内核就绪：${bin.absolutePath}")
        }.onFailure {
            bin.delete()
            FileLogger.w(TAG, "解压 mihomo 失败: ${it.message}")
            return@withContext "解压 mihomo 失败：${it.message}"
        }
        null
    }

    /** 拉启 mihomo 子进程（App 子进程，绑定 127.0.0.1:7890；App 进程存活则内核存活）。 */
    private fun startKernelProcess(): Boolean {
        val bin = mihomoBinary()
        if (!bin.isFile) return false
        return runCatching {
            val dir = configDir()
            val cfgFile = java.io.File(dir, CONFIG_FILE)
            // 配置必须先就绪再启动：mihomo 找不到 config.yaml 会瞬时退出（code=1）。
            // 时序上 on() 是先落盘再启内核，此处守卫主要拦「启动时自动恢复」路径的陈旧/缺失配置。
            if (!cfgFile.isFile) {
                FileLogger.w(TAG, "config.yaml 不存在（${cfgFile.absolutePath}），跳过启动 mihomo")
                return@runCatching false
            }
            // 内容校验：落盘文件必须能解析为 YAML 映射，否则 mihomo 必然 FATAL 秒退（code=1）。
            // 自动恢复路径加载的是旧会话残留的 config.yaml，可能已损坏，启前拦截并给出明确提示。
            if (!validateConfigFile(cfgFile)) {
                FileLogger.w(TAG, "config.yaml 内容非法（不可解析为 YAML 映射），跳过启动 mihomo")
                return@runCatching false
            }
            val logFile = java.io.File(dir, "mihomo.log")
            val pb = ProcessBuilder(bin.absolutePath, "-d", dir.absolutePath, "-f", cfgFile.absolutePath)
            pb.redirectErrorStream(true)
            pb.redirectOutput(logFile)
            val p = pb.start()
            mihomoProcess = p
            FileLogger.i(TAG, "mihomo 内核已启动（log=${logFile.absolutePath}）")
            // 监视退出（waitFor 阻塞一个 IO 线程，进程存活期间让渡；进程意外退出时清空句柄并告警，
            // 避免残留「伪运行」状态。不能用 Process.onExit()——Android 未提供该 Java 9 API。）
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                p.waitFor()
                if (mihomoProcess === p) {
                    mihomoProcess = null
                    FileLogger.w(TAG, "mihomo 内核进程退出 code=${p.exitValue()}（详见 mihomo.log）")
                    logKernelLogTail()
                }
            }
            true
        }.onFailure {
            FileLogger.w(TAG, "启动 mihomo 内核失败: ${it.message}")
            false
        }.getOrDefault(false)
    }

    /** 落盘 config.yaml 是否能解析为 YAML 映射（mihomo 加载前的最后一道闸，防「配损坏 → 秒退」）。 */
    private fun validateConfigFile(f: java.io.File): Boolean = runCatching {
        val root = Yaml().load<Any?>(f.readText(Charsets.UTF_8))
        root is Map<*, *>
    }.getOrDefault(false)

    /** 把 mihomo.log 尾部打进 FileLogger，便于在日志里直接看到内核报错（config 解析/端口占用/geodata 缺失等）。 */
    private fun logKernelLogTail(maxChars: Int = 4000) {
        runCatching {
            val f = java.io.File(configDir(), "mihomo.log")
            if (!f.isFile) {
                FileLogger.w(TAG, "mihomo.log 不存在，无法读取内核退出原因")
                return
            }
            val bytes = f.readBytes()
            val start = (bytes.size - maxChars).coerceAtLeast(0)
            // takeLast 会退回 List<Byte>，无 toString(Charset)；用 copyOfRange 保持 ByteArray。
            val tail = bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8)
            if (tail.isBlank()) {
                FileLogger.w(TAG, "mihomo.log 为空（log-level 静默或内核未写任何日志），无法定位退出原因")
            } else {
                FileLogger.w(TAG, "--- mihomo.log tail ---\n$tail")
            }
        }
    }

    /** 轮询 external-controller 直至可达（[retries] 次 × 500ms）；内核进程已死则立即失败。 */
    private suspend fun waitControllerReady(retries: Int): Boolean {
        repeat(retries) {
            if (mihomoProcess?.isAlive == false) return false
            if (controllerRequest("GET", "/configs") != null) return true
            delay(500)
        }
        return false
    }

    /** 在 [kernelMutex] 内确保内核运行；[restart]=true 时先停旧进程再起（加载新配置）。 */
    private suspend fun ensureKernelRunning(restart: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            kernelMutex.withLock {
                if (restart) stopKernelLocked()
                val alive = mihomoProcess?.isAlive == true
                if (!alive) {
                    // 首次使用需先自举下载并校验内核二进制（已存在则直接跳过）
                    val err = downloadMihomoIfNeeded()
                    if (err != null) {
                        FileLogger.w(TAG, err)
                        return@withLock false
                    }
                    if (!startKernelProcess()) return@withLock false
                    // 秒退保护：进程启动后瞬时退出（config 缺失/解析失败/端口占用/geodata 缺失）
                    // 直接读日志并失败返回，不再空轮询 10s 等一个已死的进程（也避免死占 kernelMutex
                    // 阻塞后续 on()）。
                    delay(300)
                    if (mihomoProcess?.isAlive != true) {
                        logKernelLogTail()
                        return@withLock false
                    }
                }
                waitControllerReady(20)
            }
        }

    private suspend fun stopKernel() = withContext(Dispatchers.IO) {
        kernelMutex.withLock { stopKernelLocked() }
    }

    private fun stopKernelLocked() {
        val p = mihomoProcess ?: return
        mihomoProcess = null
        if (!p.isAlive) return
        runCatching { p.destroy() }
        val deadline = System.currentTimeMillis() + 1500
        while (p.isAlive && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (p.isAlive) runCatching { p.destroyForcibly() }
        FileLogger.i(TAG, "mihomo 内核已停止")
    }

    /** 拉起代理：由已播种 profile（[profileId]）或临时 inline（[inlineYaml]）合成配置、落盘、拉起内核、置开关。 */
    suspend fun on(profileId: String?, inlineYaml: String?): String {
        if (profileId == null && inlineYaml == null) return "需要 profile_id 或 inline yaml"
        val source = when {
            inlineYaml != null -> inlineYaml
            else -> {
                val revealed = repository.revealSecret(profileId!!)
                    ?: return "未找到已播种 profile：$profileId"
                // 订阅型 profile 存的是订阅 URL：需先拉取远端 YAML；手动型存的就是 YAML 原文。
                val trimmed = revealed.trim()
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    fetchSubscriptionYaml(trimmed) ?: return "订阅拉取失败：$profileId"
                } else {
                    revealed
                }
            }
        }
        if (source.isBlank()) return "配置为空"
        val config = synthesizeConfig(source)
        writeConfigFile(config)
        // 内核必须实际跑起来并让控制面就绪，才能把开关置为 enabled——
        // 否则 App 流量会被 routeHolder 打进无人监听的 7890（`Failed to connect to /127.0.0.1:7890`）。
        val kernelOk = ensureKernelRunning(restart = true)
        if (!kernelOk) {
            FileLogger.w(TAG, "on(): mihomo 内核未就绪，代理保持关闭")
            return "mihomo 内核启动失败（下载或启动异常，详见日志），代理未启用"
        }
        if (profileId != null) repository.setActiveProfile(profileId)
        repository.setProxyEnabled(true)
        // 同步写位：不等 DataStore flow 的异步 emit，消除「on() 返回后立刻发请求仍走直连」的竞态窗口。
        // 后续 flow 收集器也会写同一组值（幂等）。
        enabledCache = true
        routeHolder.update(true, "127.0.0.1:$MIXED_PORT")
        _state.update { it.copy(enabled = true, activeProfileId = profileId) }
        FileLogger.i(TAG, "network_proxy ON (profile=$profileId inline=${inlineYaml != null})")
        return "ok"
    }

    /** 关闭代理：先停内核，再翻转开关（env 也随之不再注入）。 */
    suspend fun off() {
        stopKernel()
        repository.setProxyEnabled(false)
        // 同步写位（同 on()），不等 flow 异步 emit。
        enabledCache = false
        routeHolder.update(false, "127.0.0.1:$MIXED_PORT")
        _state.update { it.copy(enabled = false) }
        FileLogger.i(TAG, "network_proxy OFF")
    }

    /**
     * 容器内进程的代理环境变量。仅启用时非空；NO_PROXY 保护 loopback 与内网，避免代理接管后
     * 阻断本地服务（含 RCB_BRIDGE、容器内网、工作区同步）。
     */
    fun exportContainerEnv(): Map<String, String> {
        if (!enabledCache) return emptyMap()
        val proxy = "http://$CONTROLLER_HOST:$MIXED_PORT"
        return mapOf(
            "http_proxy" to proxy,
            "https_proxy" to proxy,
            "all_proxy" to proxy,
            "no_proxy" to "127.0.0.1,localhost,.localhost,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16",
            "CLASH_MIXED_PORT" to "$MIXED_PORT",
            "CLASH_CONTROLLER_ADDR" to controllerAddress(),
            "CLASH_CONTROLLER_TOKEN" to secret,
        )
    }
}