package com.R.codecore.feature.proxy.domain

import android.content.Context
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.proxy.data.ProxySettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    }

    private val _state = MutableStateFlow(ProxyRuntimeState())
    val state: StateFlow<ProxyRuntimeState> = _state.asStateFlow()

    /** env 注入/控制器读取的安全鉴权令牌（app 运行时生成并落盘，跨进程一致）。 */
    @Volatile
    private var secret: String = ""

    /** 全局开关缓存：buildContainerEnv 是同步方法，不能在其中挂起读 DataStore，故用 flow 预热。 */
    @Volatile
    private var enabledCache: Boolean = false

    init {
        secret = loadOrCreateSecret()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            repository.proxyEnabledFlow.collect { e ->
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
     * 剥离 [OVERRIDDEN_KEYS] 顶层键，保留源 proxies/groups/rules（无 rules 则追加 DIRECT 兜底）。
     */
    fun synthesizeConfig(sourceYaml: String): String {
        val body = sourceYaml.lines().filterNot { line ->
            val t = line.trimStart()
            t.isNotEmpty() && OVERRIDDEN_KEYS.any { t.startsWith("$it:") || t.startsWith("$it :") }
        }.joinToString("\n")
        val hasRules = Regex("^\\s*rules\\s*:").containsMatchIn(sourceYaml)
        return buildString {
            appendLine("mixed-port: $MIXED_PORT")
            appendLine("allow-lan: false")
            appendLine("mode: rule")
            appendLine("log-level: silent")
            appendLine("external-controller: $CONTROLLER_HOST:$CONTROLLER_PORT")
            appendLine("secret: \"$secret\"")
            appendLine()
            appendLine(body.trim('\n'))
            if (!hasRules) {
                appendLine()
                appendLine("rules:")
                appendLine("  - MATCH,DIRECT")
            }
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

    /** 拉起代理：由已播种 profile（[profileId]）或临时 inline（[inlineYaml]）合成配置并落盘、置开关。 */
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
        if (profileId != null) repository.setActiveProfile(profileId)
        repository.setProxyEnabled(true)
        _state.update { it.copy(enabled = true, activeProfileId = profileId) }
        FileLogger.i(TAG, "network_proxy ON (profile=$profileId inline=${inlineYaml != null})")
        return "ok"
    }

    /** 关闭代理：仅翻转开关（env 也随之不再注入）。 */
    suspend fun off() {
        repository.setProxyEnabled(false)
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