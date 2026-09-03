package com.R.codecore.feature.agent.domain.mcp.server

import android.content.Context
import com.R.codecore.core.util.FileLogger
import com.R.codecore.datalayer.store.KVStore
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

const val MCP_SERVER_NS = "mcp_server"
const val MCP_ENABLED_KEY = "enabled"
const val MCP_PORT_KEY = "port"
const val MCP_TOKEN_KEY = "token"
const val MCP_REQUIRE_APPROVAL_KEY = "require_approval"
const val MCP_AUTO_START_KEY = "auto_start"

/** 内置 MCP 服务器服务管理。 */
@Singleton
class McpServerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val toolRegistry: ToolRegistry,
    private val permissionManager: ToolPermissionManager,
    private val kv: KVStore,
) {
    private companion object {
        const val TAG = "McpServerManager"
        const val DEFAULT_PORT = 3000
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

    fun settingsSnapshot(): McpServerSettings = McpServerSettings(
        enabled = _isRunning.value || _autoStart.value,
        port = _port.value,
        token = _token.value,
        requireApproval = _requireApproval.value,
        autoStart = _autoStart.value
    )

    init {
        scope.launch {
            _port.value = (kv.getInt(MCP_SERVER_NS, MCP_PORT_KEY) ?: DEFAULT_PORT.toLong()).toInt()
            _requireApproval.value = kv.getBool(MCP_SERVER_NS, MCP_REQUIRE_APPROVAL_KEY) ?: true
            _autoStart.value = kv.getBool(MCP_SERVER_NS, MCP_AUTO_START_KEY) ?: false

            var savedToken = kv.getString(MCP_SERVER_NS, MCP_TOKEN_KEY)
            if (savedToken.isNullOrBlank()) {
                savedToken = McpServerSecurity.generateToken()
                kv.putString(MCP_SERVER_NS, MCP_TOKEN_KEY, savedToken)
            }
            _token.value = savedToken

            updateServerUrl()

            val auto = _autoStart.value
            val enabled = kv.getBool(MCP_SERVER_NS, MCP_ENABLED_KEY) ?: false
            if (auto || enabled) startServerInternal()
        }
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val interfaces2 = interfaces.nextElement()
                if (interfaces2.isLoopback || !interfaces2.isUp) continue
                val addresses = interfaces2.inetAddresses
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

        kv.putInt(MCP_SERVER_NS, MCP_PORT_KEY, port.toLong())
        kv.putBool(MCP_SERVER_NS, MCP_REQUIRE_APPROVAL_KEY, requireApproval)
        kv.putBool(MCP_SERVER_NS, MCP_AUTO_START_KEY, autoStart)

        if (wasRunning) startServerInternal()
    }

    suspend fun regenerateToken() = withContext(Dispatchers.IO) {
        val wasRunning = _isRunning.value
        if (wasRunning) stopServerInternal()
        val newToken = McpServerSecurity.generateToken()
        _token.value = newToken
        kv.putString(MCP_SERVER_NS, MCP_TOKEN_KEY, newToken)
        if (wasRunning) startServerInternal()
    }

    suspend fun toggleServer(): Boolean = withContext(Dispatchers.IO) {
        if (_isRunning.value) {
            stopServerInternal()
            kv.putBool(MCP_SERVER_NS, MCP_ENABLED_KEY, false)
            false
        } else {
            val ok = startServerInternal()
            if (ok) kv.putBool(MCP_SERVER_NS, MCP_ENABLED_KEY, true)
            ok
        }
    }

    suspend fun startServer(): Boolean = withContext(Dispatchers.IO) {
        if (_isRunning.value) return@withContext true
        val ok = startServerInternal()
        if (ok) kv.putBool(MCP_SERVER_NS, MCP_ENABLED_KEY, true)
        ok
    }

    suspend fun stopServer() = withContext(Dispatchers.IO) {
        if (!_isRunning.value) return@withContext
        stopServerInternal()
        kv.putBool(MCP_SERVER_NS, MCP_ENABLED_KEY, false)
    }

    private suspend fun startServerInternal(): Boolean {
        try {
            _errorMessage.value = null
            stopServerInternal()
            val adapter = AgentToolMcpAdapter(toolRegistry, permissionManager, requireApproval = _requireApproval.value)
            val settings = McpServerSettings(enabled = true, port = _port.value, token = _token.value,
                requireApproval = _requireApproval.value, autoStart = _autoStart.value)
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
            httpServer?.stop(); httpServer = null; _isRunning.value = false
            FileLogger.i(TAG, "内置 MCP 服务器已停止")
        } catch (e: Exception) {
            FileLogger.w(TAG, "停止内置 MCP 服务器出错（忽略）: ${e.message}", e)
        }
    }
}
