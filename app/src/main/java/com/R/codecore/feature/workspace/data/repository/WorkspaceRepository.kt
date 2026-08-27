package com.R.codecore.feature.workspace.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.R.codecore.datalayer.DataReadMode
import com.R.codecore.datalayer.DataReadModeHolder
import com.R.codecore.core.util.FileLogger
import com.R.codecore.datalayer.repository.AgentRepository as V2AgentRepository
import com.R.codecore.feature.agent.data.local.dao.ChatSessionDao
import com.R.codecore.feature.agent.domain.container.ConnectionState
import com.R.codecore.feature.agent.domain.container.RemoteSshConnection
import com.R.codecore.feature.settings.data.repository.ExecutionMode
import com.R.codecore.feature.settings.data.repository.ExecutionModeHolder
import com.R.codecore.feature.workspace.domain.model.Workspace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管理 App 内的"工作区/项目"。
 *
 * **本地模式**：所有项目放在内部私有目录 `filesDir/projects/<name>` 下——ext4 真实路径，
 * [java.io.File] 工具与 PRoot 容器挂载都能直接使用，无需运行时存储权限，且支持 symlink。
 *
 * **远程模式**：工作区 = 远程 SSH 服务器上 `remoteWorkspacePath` 下的子文件夹。
 * 列表/新建/删除通过 SFTP 操作远程目录，[Workspace.path] 为远程绝对路径。
 *
 * 当前选中的工作区名持久化在 DataStore 中，重启后保留（本地/远程共用同一份名字）。
 */
@Singleton
class WorkspaceRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection,
    private val chatSessionDao: ChatSessionDao,
    private val v2Agent: V2AgentRepository,
    private val readMode: DataReadModeHolder,
) {
    private companion object {
        const val TAG = "WorkspaceRepository"
        const val DEFAULT_WORKSPACE = "default"
    }

    private val currentNameKey = stringPreferencesKey("current_workspace_name")

    /**
     * 所有项目的父目录，固定用内部 filesDir（app 私有 ext4）。
     *
     * 必须是 ext4：外部私有目录（getExternalFilesDir）落在 emulated/FUSE 存储，内核拒绝
     * symlink()，npm/pnpm/yarn/git 建软链时会 `EACCES symlink` 而失败。filesDir 是 ext4，
     * symlink 原生可用，所有工具链零配置即可跑。对外可见性由 DocumentsProvider 暴露，不依赖物理位置。
     */
    private val projectsRoot: File by lazy {
        File(context.filesDir, "projects").apply { mkdirs() }
    }

    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    private val _current = MutableStateFlow<Workspace?>(null)
    val current: StateFlow<Workspace?> = _current.asStateFlow()

    /** 扫描并恢复上次选中的工作区；首次启动本地模式会创建默认工作区。应在 App/ViewModel 启动时调用一次。 */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        // 远程模式：等 SSH 连接就绪后再列工作区，避免启动时序竞争
        if (!isLocal()) {
            waitForConnection()
        }
        refreshWorkspaces()

        if (isLocal() && _workspaces.value.isEmpty()) {
            createWorkspace(DEFAULT_WORKSPACE)
            refreshWorkspaces()
        }

        val savedName = context.workspaceDataStore.data.first()[currentNameKey]
        val target = _workspaces.value.firstOrNull { it.name == savedName }
            ?: _workspaces.value.firstOrNull()
        _current.value = target
        val location = if (isLocal()) projectsRoot.absolutePath else remoteSshConnection.config?.remoteWorkspacePath ?: ""
        FileLogger.i(TAG, "工作区初始化完成，当前: ${target?.name}，根目录: $location")
        // 远程模式：选中工作区后更新符号链接，让 Bash 的 ~/workspace 指向当前工作区
        if (!isLocal() && target != null) {
            remoteSshConnection.updateWorkspaceSymlink(target.path)
        }
    }

    private fun isLocal(): Boolean =
        executionModeHolder.currentMode() != ExecutionMode.REMOTE_SSH

    /** 远程模式下等待 SSH 连接就绪（最多 5 秒），避免启动时序竞争。 */
    /** 远程模式下挂起等待 SSH 连接就绪（CONNECTED）；连接失败（FAILED）则提前返回，保持空工作区。 */
    private suspend fun waitForConnection() {
        val state = remoteSshConnection.connectionState.first {
            it == ConnectionState.CONNECTED || it == ConnectionState.FAILED
        }
        if (state == ConnectionState.FAILED) {
            FileLogger.w(TAG, "SSH 连接失败，工作区保持空")
        }
    }

    /** 重新读取工作区目录列表。本地扫 projectsRoot，远程 exec ls remoteWorkspacePath。 */
    private suspend fun refreshWorkspaces() {
        _workspaces.value = if (isLocal()) refreshLocalWorkspaces() else refreshRemoteWorkspaces()
    }

    private fun refreshLocalWorkspaces(): List<Workspace> {
        val dirs = projectsRoot.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        return dirs.map { Workspace(name = it.name, path = it.absolutePath) }
    }

    /** exec 列出 remoteWorkspacePath 下的子目录作为工作区（不用 SFTP，避免 sshj Buffer bug）。 */
    private suspend fun refreshRemoteWorkspaces(): List<Workspace> {
        val cfg = remoteSshConnection.config ?: run {
            FileLogger.w(TAG, "远程工作区列表失败：SSH 未配置")
            return emptyList()
        }
        val wsRoot = cfg.remoteWorkspacePath.trimEnd('/')
        return runCatching {
            // ls -d */ 列出子目录，取基名
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

    /** 同步执行远程命令并返回 stdout（供工作区列表/新建/删除用）。 */
    private suspend fun execRemote(command: String): String =
        withContext(Dispatchers.IO) {
            val session = remoteSshConnection.startExecSession(command)
            try {
                val output = java.io.BufferedReader(java.io.InputStreamReader(session.inputStream)).readText()
                runCatching { session.close() }
                output
            } catch (e: Exception) {
                runCatching { session.close() }
                throw e
            }
        }

    /** 同步执行远程命令并返回退出码。 */
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

    /** 切换当前工作区并持久化。 */
    suspend fun selectWorkspace(name: String) = withContext(Dispatchers.IO) {
        val target = _workspaces.value.firstOrNull { it.name == name } ?: return@withContext
        _current.value = target
        context.workspaceDataStore.edit { it[currentNameKey] = name }
        FileLogger.i(TAG, "切换工作区: $name")
        // 远程模式：切换后更新符号链接指向新工作区
        if (!isLocal()) {
            remoteSshConnection.updateWorkspaceSymlink(target.path)
        }
    }

    /**
     * 新建工作区目录。名称会被清洗为安全的文件夹名。
     * 本地模式 mkdirs projectsRoot/name；远程模式 SFTP mkdirs remoteWorkspacePath/name。
     * @return 创建成功的 [Workspace]；名称非法或已存在返回 null。
     */
    suspend fun createWorkspace(rawName: String): Workspace? = withContext(Dispatchers.IO) {
        val name = sanitize(rawName)
        if (name.isEmpty()) {
            FileLogger.w(TAG, "新建工作区失败：名称非法 '$rawName'")
            return@withContext null
        }
        if (isLocal()) {
            val dir = File(projectsRoot, name)
            if (dir.exists()) {
                FileLogger.w(TAG, "新建工作区失败：已存在 '$name'")
                return@withContext null
            }
            if (!dir.mkdirs()) {
                FileLogger.e(TAG, "新建工作区失败：无法创建目录 ${dir.absolutePath}")
                return@withContext null
            }
            refreshWorkspaces()
            FileLogger.i(TAG, "新建工作区: $name")
            Workspace(name = name, path = dir.absolutePath)
        } else {
            val cfg = remoteSshConnection.config ?: return@withContext null
            val wsRoot = cfg.remoteWorkspacePath.trimEnd('/')
            val remotePath = "$wsRoot/$name"
            runCatching {
                if (execRemoteExit("test -d ${shellQuote(remotePath)}") == 0) {
                    FileLogger.w(TAG, "新建工作区失败：已存在 '$name'")
                    return@withContext null
                }
                execRemoteExit("mkdir -p ${shellQuote(remotePath)}")
            }.getOrElse {
                FileLogger.e(TAG, "远程新建工作区失败: $remotePath", it)
                return@withContext null
            }
            refreshWorkspaces()
            FileLogger.i(TAG, "新建工作区(远程): $name")
            Workspace(name = name, path = remotePath)
        }
    }

    /** 删除工作区（连同其文件）。若删的是当前工作区，则自动切到剩余的第一个。 */
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
            context.workspaceDataStore.edit { prefs ->
                if (fallback != null) prefs[currentNameKey] = fallback.name else prefs.remove(currentNameKey)
            }
        }
        FileLogger.i(TAG, "删除工作区: $name")
    }

    /**
     * 重命名工作区目录。名称经 [sanitize] 清洗；新名称非法或与其它工作区重名时返回 false。
     * 本地模式 File.renameTo，远程模式 `mv`。重命名成功后，会把绑定到该工作区的所有会话的
     * workspacePath 一并迁移到新路径（会话与工作区一对一绑定），避免会话随目录改名而丢失。
     * 若重命名的是当前工作区，同步更新持久化的选中名。
     */
    suspend fun renameWorkspace(oldName: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val name = sanitize(newName)
        if (name.isEmpty() || name == oldName) {
            FileLogger.w(TAG, "重命名工作区失败：名称非法 '$newName'")
            return@withContext false
        }
        if (_workspaces.value.any { it.name == name }) {
            FileLogger.w(TAG, "重命名工作区失败：已存在 '$name'")
            return@withContext false
        }
        // 重命名前先取旧路径（refresh 后旧名已消失，无法再反查）
        val oldPath = workspacePathOf(oldName)
        val ok = if (isLocal()) {
            File(projectsRoot, oldName).renameTo(File(projectsRoot, name))
        } else {
            val cfg = remoteSshConnection.config ?: return@withContext false
            val wsRoot = cfg.remoteWorkspacePath.trimEnd('/')
            execRemoteExit("mv ${shellQuote("$wsRoot/$oldName")} ${shellQuote("$wsRoot/$name")}") == 0
        }
        if (!ok) {
            FileLogger.w(TAG, "重命名工作区失败: $oldName -> $name")
            return@withContext false
        }
        refreshWorkspaces()
        // 会话绑定路径迁移：旧路径 → 新路径
        val newPath = workspacePathOf(name)
        if (oldPath != null && newPath != null && oldPath != newPath) {
            if (readMode.currentMode() == DataReadMode.V2) {
                v2Agent.updateWorkspacePath(oldPath, newPath)
            } else {
                chatSessionDao.updateWorkspacePath(oldPath, newPath)
            }
        }
        if (_current.value?.name == oldName) {
            _current.value = _workspaces.value.firstOrNull { it.name == name }
            context.workspaceDataStore.edit { it[currentNameKey] = name }
        }
        FileLogger.i(TAG, "重命名工作区: $oldName -> $name")
        true
    }

    /** 按工作区名推导其目录路径（本地宿主路径 / 远程绝对路径）。 */
    private fun workspacePathOf(name: String): String? {
        val ws = _workspaces.value.firstOrNull { it.name == name } ?: return null
        return ws.path
    }

    /** 单引号转义，保证 shell 命令安全。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 当前工作区的路径，供 projectRoot / 命令执行目录使用。
     * 本地模式返回宿主工作区绝对路径；远程模式返回选中工作区的远程绝对路径（命令 cd 到此）。
     * 无选中时本地回退到项目根目录，远程回退到配置的 remoteWorkspacePath。 */
    fun currentPath(): String {
        if (!isLocal()) {
            return _current.value?.path
                ?: remoteSshConnection.config?.remoteWorkspacePath?.takeIf { it.isNotBlank() }
                ?: "/"
        }
        return _current.value?.path ?: projectsRoot.absolutePath
    }

    /** 仅保留字母数字、下划线、连字符、点和空格，去掉路径分隔符等危险字符。 */
    private fun sanitize(raw: String): String =
        raw.trim()
            .replace(Regex("[^A-Za-z0-9 ._\\u4e00-\\u9fa5-]"), "")
            .trim()
            .take(64)
}
