package com.R.codecore.feature.workspace.domain.remote.ftp

import android.content.Context
import com.R.codecore.core.util.FileLogger
import com.R.codecore.datalayer.store.KVStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

const val WORKSPACE_FTP_NS = "workspace"
const val FTP_PORT_KEY = "ftp_port"
const val FTP_USERNAME_KEY = "ftp_username"
const val FTP_PASSWORD_KEY = "ftp_password"
const val FTP_ANONYMOUS_KEY = "ftp_anonymous"
const val FTP_AUTO_START_KEY = "ftp_auto_start"

@Singleton
class FtpServerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val kv: KVStore,
) {
    private companion object {
        const val TAG = "FtpServerManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ftpServer: FtpServer? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _port = MutableStateFlow(2121)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _username = MutableStateFlow("rcodecore")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("123456")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _isAnonymous = MutableStateFlow(false)
    val isAnonymous: StateFlow<Boolean> = _isAnonymous.asStateFlow()

    private val _autoStart = MutableStateFlow(false)
    val autoStart: StateFlow<Boolean> = _autoStart.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val defaultSharedPath: String by lazy {
        File(context.filesDir, "projects").apply { mkdirs() }.absolutePath
    }

    init {
        scope.launch {
            _port.value = (kv.getInt(WORKSPACE_FTP_NS, FTP_PORT_KEY) ?: 2121L).toInt()
            _username.value = kv.getString(WORKSPACE_FTP_NS, FTP_USERNAME_KEY) ?: "rcodecore"
            _password.value = kv.getString(WORKSPACE_FTP_NS, FTP_PASSWORD_KEY) ?: "123456"
            _isAnonymous.value = kv.getBool(WORKSPACE_FTP_NS, FTP_ANONYMOUS_KEY) ?: false
            _autoStart.value = kv.getBool(WORKSPACE_FTP_NS, FTP_AUTO_START_KEY) ?: false

            updateServerUrl()
            if (_autoStart.value) startServer()
        }
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                val addresses = ni.inetAddresses
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
        _serverUrl.value = "ftp://$ip:${_port.value}"
    }

    suspend fun saveConfig(port: Int, username: String, password: String, isAnonymous: Boolean, autoStart: Boolean) = withContext(Dispatchers.IO) {
        val wasRunning = _isRunning.value
        if (wasRunning) stopServerInternal()

        _port.value = port
        _username.value = username
        _password.value = password
        _isAnonymous.value = isAnonymous
        _autoStart.value = autoStart
        updateServerUrl()

        kv.putInt(WORKSPACE_FTP_NS, FTP_PORT_KEY, port.toLong())
        kv.putString(WORKSPACE_FTP_NS, FTP_USERNAME_KEY, username)
        kv.putString(WORKSPACE_FTP_NS, FTP_PASSWORD_KEY, password)
        kv.putBool(WORKSPACE_FTP_NS, FTP_ANONYMOUS_KEY, isAnonymous)
        kv.putBool(WORKSPACE_FTP_NS, FTP_AUTO_START_KEY, autoStart)

        if (wasRunning) startServerInternal()
    }

    suspend fun toggleServer(): Boolean = withContext(Dispatchers.IO) {
        if (_isRunning.value) { stopServerInternal(); false } else startServerInternal()
    }

    suspend fun startServer(): Boolean = withContext(Dispatchers.IO) {
        if (_isRunning.value) return@withContext true
        startServerInternal()
    }

    suspend fun stopServer() = withContext(Dispatchers.IO) {
        if (!_isRunning.value) return@withContext
        stopServerInternal()
    }

    private fun startServerInternal(): Boolean {
        try {
            _errorMessage.value = null
            stopServerInternal()
            val serverFactory = FtpServerFactory()
            val listenerFactory = ListenerFactory().apply { port = _port.value }
            serverFactory.addListener("default", listenerFactory.createListener())
            val userManagerFactory = PropertiesUserManagerFactory().apply {
                file = File(context.cacheDir, "ftp_users.properties").apply { createNewFile() }
                passwordEncryptor = ClearTextPasswordEncryptor()
            }
            val userManager = userManagerFactory.createUserManager()
            val authorities = ArrayList<Authority>().apply {
                add(WritePermission()); add(ConcurrentLoginPermission(20, 20))
            }
            if (_username.value.isNotBlank()) {
                val user = BaseUser().apply {
                    name = _username.value; password = _password.value
                    homeDirectory = defaultSharedPath; this.authorities = authorities
                }
                userManager.save(user)
            }
            if (_isAnonymous.value) {
                val anonUser = BaseUser().apply {
                    name = "anonymous"; homeDirectory = defaultSharedPath; authorities = authorities
                }
                userManager.save(anonUser)
            }
            serverFactory.userManager = userManager
            val server = serverFactory.createServer()
            server.start()
            ftpServer = server
            _isRunning.value = true
            updateServerUrl()
            FileLogger.i(TAG, "内置 FTP 服务端已启动: ${_serverUrl.value}, 共享目录: $defaultSharedPath")
            return true
        } catch (e: Exception) {
            FileLogger.e(TAG, "启动内置 FTP 服务端失败: ${e.message}", e)
            _errorMessage.value = e.message ?: "启动失败，可能端口被占用"
            _isRunning.value = false
            return false
        }
    }

    private fun stopServerInternal() {
        try {
            ftpServer?.stop(); ftpServer = null; _isRunning.value = false
            FileLogger.i(TAG, "内置 FTP 服务端已停止")
        } catch (e: Exception) {
            FileLogger.e(TAG, "停止内置 FTP 服务端出错（忽略）: ${e.message}", e)
        }
    }
}
