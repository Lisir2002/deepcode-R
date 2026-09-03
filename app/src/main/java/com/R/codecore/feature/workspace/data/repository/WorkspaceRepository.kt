package com.R.codecore.feature.workspace.data.repository

import android.content.Context
import com.R.codecore.core.util.FileLogger
import com.R.codecore.datalayer.store.KVStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.R.codecore.datalayer.repository.AgentRepository as V2AgentRepository
import com.R.codecore.feature.agent.domain.container.ConnectionState
import com.R.codecore.feature.agent.domain.container.RemoteSshConnection
import com.R.codecore.feature.settings.data.repository.ExecutionMode
import com.R.codecore.feature.settings.data.repository.ExecutionModeHolder
import com.R.codecore.feature.workspace.domain.model.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

const val WORKSPACE_NS = "workspace"
const val CURRENT_WORKSPACE_KEY = "current_workspace_name"

/**
 * 管理 App 内的"工作区/项目"。
 *
 * 当前选中的工作区名持久化在 KVStore 中，重启后保留（本地/远程共用同一份名字）。
 */
@Singleton
class WorkspaceRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection,
    private val v2Agent: V2AgentRepository,
    private val kv: KVStore,
) {
    private companion object {
        const val TAG = "WorkspaceRepository"
        const val DEFAULT_WORKSPACE = "default"
    }

    private val projectsRoot: File by lazy {
        File(context.filesDir, "projects").apply { mkdirs() }
    }

    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    private val _current = MutableStateFlow<Workspace?>(null)
    val current: StateFlow<Workspace?> = _current.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (!isLocal()) waitForConnection()
        refreshWorkspaces()

        if (isLocal() && _workspaces.value.isEmpty()) {
            createWorkspace(DEFAULT_WORKSPACE)
            refreshWorkspaces()
        }

        val savedName = kv.getString(WORKSPACE_NS, CURRENT_WORKSPACE_KEY)
        val target = _workspaces.value.firstOrNull { it.name == savedName }
            ?: _workspaces.value.firstOrNull()
        _current.value = target
        val location = if (isLocal()) projectsRoot.absolutePath else remoteSshConnection.config?.remoteWorkspacePath ?: ""
        FileLogger.i(TAG, "工作区初始化完成，当前: ${target?.name}，根目录: $location")
        if (!isLocal() && target != null) {
            remoteSshConnection.updateWorkspaceSymlink(target.path)
        }
    }

    private fun isLocal(): Boolean =
        executionModeHolder.currentMode() != ExecutionMode.REMOTE_SSH

    private suspend fun waitForConnection() {
        val state = remoteSshConnection.connectionState.first {
            it == ConnectionState.CONNECTED || it == ConnectionState.FAILED
        }
        if (state == ConnectionState.FAILED) {
            FileLogger.w(TAG, "SSH 连接失败，工作区保持空")
        }
    }

    private suspend fun refreshWorkspaces() {
        _workspaces.value = if (isLocal()) refreshLocalWorkspaces() else refreshRemoteWorkspaces()
    }

    private fun refreshLocalWorkspaces(): List<Workspace> {
        val dirs = projectsRoot.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        return dirs.map { Workspace(name = it.name, path = it.absolutePath) }
    }

    private suspend fun refreshRemoteWorkspaces(): List<Workspace> {
        val cfg = remoteSshConnection.config ?: run {
            FileLogger.w(TAG, "远程工作区列表失败：SSH 未配置")
            return emptyList()
        }
        val wsRoot = cfg.remoteWorkspacePath.trimEnd('/')
        return runCatching {
            val output = execRemote("ls -d ${wsRoot}/*/ 2>/dev/null | xargs -n1 basename 2>/dev/null")
            if (output.isBlank()) emptyList()
            else output.lines().filter { it.isNotBlank() }
                .sortedBy { it.lowercase() }
                .map { Workspace(name = it.trim(), path = "$wsRoot/${it.trim()}") }
        }.getOrElse {
            FileLogger.w(TAG, "远程工作区列表失败: $wsRoot", it)
            emptyList()
        }
    }

    private suspend fun execRemote(command: String): String =
        withContext(Dispatchers.IO) {
            val session = remoteSshConnection.startExecSession(command)
            try {
                java.io.BufferedReader(java.io.InputStreamReader(session.inputStream)).readText()
                    .also { runCatching { session.close() } }
            } catch (e: Exception) {
                runCatching { session.close() }
                throw e
            }
        }

    private suspend fun execRemoteExit(command: String): Int =
        withContext(Dispatchers.IO) {
            val session = remoteSshConnection.startExecSession(command)
            try {
                java.io.BufferedReader(java.io.InputStreamReader(session.inputStream)).readText()
                runCatching { session.close() }
                session.exitStatus ?: -1
            } catch (e: Exception) {
                runCatching { session.close() }
                -1
            }
        }

    suspend fun selectWorkspace(name: String) = withContext(Dispatchers.IO) {
        val target = _workspaces.value.firstOrNull { it.name == name } ?: return@withContext
        _current.value = target
        kv.putString(WORKSPACE_NS, CURRENT_WORKSPACE_KEY, name)
        FileLogger.i(TAG, "切换工作区: $name")
        if (!isLocal()) {
            remoteSshConnection.updateWorkspaceSymlink(target.path)
        }
    }

    suspend fun createWorkspace(rawName: String): Workspace? = withContext(Dispatchers.IO) {
        val name = sanitize(rawName)
        if (name.isEmpty()) { FileLogger.w(TAG, "新建工作区失败：名称非法 '$rawName'"); return@withContext null }
        if (isLocal()) {
            val dir = File(projectsRoot, name)
            if (dir.exists()) { FileLogger.w(TAG, "新建工作区失败：已存在 '$name'"); return@withContext null }
            if (!dir.mkdirs()) { FileLogger.e(TAG, "新建工作区失败：无法创建目录 ${dir.absolutePath}"); return@withContext null }
            refreshWorkspaces(); FileLogger.i(TAG, "新建工作区: $name")
            Workspace(name = name, path = dir.absolutePath)
        } else {
            val cfg = remoteSshConnection.config ?: return@withContext null
            val wsRoot = cfg.remoteWorkspacePath.trimEnd('/')
            val remotePath = "$wsRoot/$name"
            runCatching {
                if (execRemoteExit("test -d ${shellQuote(remotePath)}") == 0) {
                    FileLogger.w(TAG, "新建工作区失败：已存在 '$name'"); return@withContext null
                }
                execRemoteExit("mkdir -p ${shellQuote(remotePath)}")
            }.getOrElse { FileLogger.e(TAG, "远程新建工作区失败: $remotePath", it); return@withContext null }
            refreshWorkspaces(); FileLogger.i(TAG, "新建工作区(远程): $name")
            Workspace(name = name, path = remotePath)
        }
    }

    suspend fun deleteWorkspace(name: String) = withContext(Dispatchers.IO) {
        if (isLocal()) {
            File(projectsRoot, name).deleteRecursively()
        } else {
            val cfg = remoteSshConnection.config
            if (cfg != null) {
                val remotePath = "${cfg.remoteWorkspacePath.trimEnd('/')}/$name"
                runCatching { execRemoteExit("rm -rf ${shellQuote(remotePath)}") }
                    .onFailure { FileLogger.e(TAG, "远程删除工作区失败: $remotePath", it) }
            }
        }
        refreshWorkspaces()
        if (_current.value?.name == name) {
            val fallback = _workspaces.value.firstOrNull()
            _current.value = fallback
            if (fallback != null) kv.putString(WORKSPACE_NS, CURRENT_WORKSPACE_KEY, fallback.name) else kv.delete(WORKSPACE_NS, CURRENT_WORKSPACE_KEY)
        }
        FileLogger.i(TAG, "删除工作区: $name")
    }

    suspend fun renameWorkspace(oldName: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val name = sanitize(newName)
        if (name.isEmpty() || name == oldName) { FileLogger.w(TAG, "重命名工作区失败：名称非法 '$newName'"); return@withContext false }
        if (_workspaces.value.any { it.name == name }) { FileLogger.w(TAG, "重命名工作区失败：已存在 '$name'"); return@withContext false }
        val oldPath = workspacePathOf(oldName)
        val ok = if (isLocal()) {
            File(projectsRoot, oldName).renameTo(File(projectsRoot, name))
        } else {
            val cfg = remoteSshConnection.config ?: return@withContext false
            val wsRoot = cfg.remoteWorkspacePath.trimEnd('/')
            execRemoteExit("mv ${shellQuote("$wsRoot/$oldName")} ${shellQuote("$wsRoot/$name")}") == 0
        }
        if (!ok) { FileLogger.w(TAG, "重命名工作区失败: $oldName -> $name"); return@withContext false }
        refreshWorkspaces()
        val newPath = workspacePathOf(name)
        if (oldPath != null && newPath != null && oldPath != newPath) {
            v2Agent.updateWorkspacePath(oldPath, newPath)
        }
        if (_current.value?.name == oldName) {
            _current.value = _workspaces.value.firstOrNull { it.name == name }
            kv.putString(WORKSPACE_NS, CURRENT_WORKSPACE_KEY, name)
        }
        FileLogger.i(TAG, "重命名工作区: $oldName -> $name")
        true
    }

    private fun workspacePathOf(name: String): String? =
        _workspaces.value.firstOrNull { it.name == name }?.path

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun currentPath(): String {
        if (!isLocal()) {
            return _current.value?.path
                ?: remoteSshConnection.config?.remoteWorkspacePath?.takeIf { it.isNotBlank() }
                ?: "/"
        }
        return _current.value?.path ?: projectsRoot.absolutePath
    }

    private fun sanitize(raw: String): String =
        raw.trim()
            .replace(Regex("[^A-Za-z0-9 ._\\u4e00-\\u9fa5-]"), "")
            .trim()
            .take(64)
}
