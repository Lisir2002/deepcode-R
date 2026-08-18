package com.R.codecore.feature.proxy.domain

import android.content.Context
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.proxy.data.ProxySettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
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
        private val OVERRIDDEN_KEYS = listOf(
            "mixed-port", "port", "socks-port", "redir-port",
            "tproxy-port", "external-controller", "external-ui",
            "secret", "allow-lan", "bind-address", "mode"
        )
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
            val req = Request.Builder().url(url).header("User-Agent", "rcodecore/1").get().build()
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful || resp.body == null) return@use null
                resp.body!!.string()
            }
        }.getOrNull()
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

    /** 拉起代理：由已播种 profile（[profileId]）或临时 inline（[inlineYaml]）合成配置并落盘、置开关。 */
    suspend fun on(profileId: String?, inlineYaml: String?): String {
        if (profileId == null && inlineYaml == null) return "需要 profile_id 或 inline yaml"
        val source = when {
            inlineYaml != null -> inlineYaml
            else -> repository.revealSecret(profileId!!)
                ?: return "未找到已播种 profile：$profileId"
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