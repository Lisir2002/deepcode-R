package com.R.codecore.feature.agent.domain.mcp.server

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.tool.ToolPermissionManager
import com.R.codecore.feature.agent.domain.tool.ToolRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.mcpServerDataStore by preferencesDataStore(name = "mcp_server_prefs")

/**
 * 内置 MCP 服务器服务管理（对标 [com.R.codecore.feature.workspace.domain.remote.ftp.FtpServerManager]）：
 * - @Singleton + DataStore 持久化配置（enabled/port/token/requireApproval/autoStart）；
 * - 状态流：isRunning / serverUrl / errorMessage；
 * - startServer/stopServer/toggleServer：配置变更走「重建服务器」生效；
 * - autoStart 时 App 启动自动拉起。
 *
 * 安全默认值：默认关闭、启动时自动生成随机 token、远程强制审批默认开。
 */
@Singleton
class McpServerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val toolRegistry: ToolRegistry,
    private val permissionManager: ToolPermissionManager
) {
    private companion object {
        const val TAG = "McpServerManager"
        const val DEFAULT_PORT = 3000
        val ENABLED_KEY = booleanPreferencesKey("enabled")
        val PORT_KEY = intPreferencesKey("port")
        val TOKEN_KEY = stringPreferencesKey("token")
        val REQUIRE_APPROVAL_KEY = booleanPreferencesKey("require_approval")
        val AUTO_START_KEY = booleanPreferencesKey("auto_start")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var httpServer: McpHttpServer? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token.asStateFlow()

    private val _requireApproval = MutableStateFlow(true)
    val requireApproval: StateFlow<Boolean> = _requireApproval.asStateFlow()

    private val _autoStart = MutableStateFlow(false)
    val autoStart: StateFlow<Boolean> = _autoStart.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** 当前生效的配置快照（UI 展示用）。 */
    fun settingsSnapshot(): McpServerSettings = McpServerSettings(
        enabled = _isRunning.value || _autoStart.value,
        port = _port.value,
        token = _token.value,
        requireApproval = _requireApproval.value,
        autoStart = _autoStart.value
    )

    init {
        scope.launch {
            val prefs = context.mcpServerDataStore.data.first()
            _port.value = prefs[PORT_KEY] ?: DEFAULT_PORT
            _requireApproval.value = prefs[REQUIRE_APPROVAL_KEY] ?: true
            _autoStart.value = prefs[AUTO_START_KEY] ?: false

            // 首次启动生成随机 token 并持久化，保证后续重启 token 稳定。
            var token = prefs[TOKEN_KEY]
            if (token.isNullOrBlank()) {
                token = McpServerSecurity.generateToken()
                context.mcpServerDataStore.edit { it[TOKEN_KEY] = token }
            }
            _token.value = token

            updateServerUrl()

            val auto = _autoStart.value
            val enabled = prefs[ENABLED_KEY] ?: false
            if (auto || enabled) {
                startServerInternal()
            }
        }
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "获取本机 IP 失败: ${e.message}", e)
        }
        return "127.0.0.1"
    }

    private fun updateServerUrl() {
        val ip = getLocalIpAddress()
        _serverUrl.value = "http://$ip:${_port.value}/mcp"
    }

    suspend fun saveConfig(port: Int, requireApproval: Boolean, autoStart: Boolean) = withContext(Dispatchers.IO) {
        val wasRunning = _isRunning.value
        if (wasRunning) stopServerInternal()

        _port.value = port
        _requireApproval.value = requireApproval
        _autoStart.value = autoStart
        updateServerUrl()

        context.mcpServerDataStore.edit { prefs ->
            prefs[PORT_KEY] = port
            prefs[REQUIRE_APPROVAL_KEY] = requireApproval
            prefs[AUTO_START_KEY] = autoStart
        }

        if (wasRunning) startServerInternal()
    }

    /** 重新生成随机 token（重启生效；若正在运行则立即重启）。 */
    suspend fun regenerateToken() = withContext(Dispatchers.IO) {
        val wasRunning = _isRunning.value
        if (wasRunning) stopServerInternal()
        val newToken = McpServerSecurity.generateToken()
        _token.value = newToken
        context.mcpServerDataStore.edit { it[TOKEN_KEY] = newToken }
        if (wasRunning) startServerInternal()
    }

    suspend fun toggleServer(): Boolean = withContext(Dispatchers.IO) {
        if (_isRunning.value) {
            stopServerInternal()
            context.mcpServerDataStore.edit { it[ENABLED_KEY] = false }
            false
        } else {
            val ok = startServerInternal()
            if (ok) context.mcpServerDataStore.edit { it[ENABLED_KEY] = true }
            ok
        }
    }

    suspend fun startServer(): Boolean = withContext(Dispatchers.IO) {
        if (_isRunning.value) return@withContext true
        val ok = startServerInternal()
        if (ok) context.mcpServerDataStore.edit { it[ENABLED_KEY] = true }
        ok
    }

    suspend fun stopServer() = withContext(Dispatchers.IO) {
        if (!_isRunning.value) return@withContext
        stopServerInternal()
        context.mcpServerDataStore.edit { it[ENABLED_KEY] = false }
    }

    private suspend fun startServerInternal(): Boolean {
        try {
            _errorMessage.value = null
            stopServerInternal()

            val adapter = AgentToolMcpAdapter(
                toolRegistry = toolRegistry,
                permissionManager = permissionManager,
                requireApproval = _requireApproval.value
            )
            val settings = McpServerSettings(
                enabled = true,
                port = _port.value,
                token = _token.value,
                requireApproval = _requireApproval.value,
                autoStart = _autoStart.value
            )
            val server = McpHttpServer(settings = settings, session = McpServerSession(adapter))
            val ok = server.start()
            if (ok) {
                httpServer = server
                _isRunning.value = true
                updateServerUrl()
                FileLogger.i(TAG, "内置 MCP 服务器已启动: ${_serverUrl.value}, 工具数=${adapter.listTools().size}")
            }
            return ok
        } catch (e: Exception) {
            FileLogger.e(TAG, "启动内置 MCP 服务器失败: ${e.message}", e)
            _errorMessage.value = e.message ?: "启动失败，可能端口被占用"
            _isRunning.value = false
            return false
        }
    }

    private suspend fun stopServerInternal() {
        try {
            httpServer?.stop()
            httpServer = null
            _isRunning.value = false
            FileLogger.i(TAG, "内置 MCP 服务器已停止")
        } catch (e: Exception) {
            FileLogger.w(TAG, "停止内置 MCP 服务器出错（忽略）: ${e.message}", e)
        }
    }
}
