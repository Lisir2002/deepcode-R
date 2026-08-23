package com.R.codecore.feature.workspace.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.R.codecore.feature.workspace.data.repository.WorkspaceRepository
import com.R.codecore.feature.workspace.domain.DelegatingFileAccess
import com.R.codecore.feature.workspace.domain.FileEntry
import com.R.codecore.feature.workspace.domain.model.Workspace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 侧边栏「工作目录 → 当前工作台」的文件浏览器数据源。
 *
 * 目录导航用「相对容器路径栈」表示：根为 `~/workspace`，进入子目录即在栈尾追加目录名，
 * 对应的容器路径 = `~/workspace/` + 栈内目录名按 `/` 连接。文件条目/子目录均经
 * [DelegatingFileAccess]（按执行模式转发本地/远程实现）读取，因此本地与远程 SSH 模式行为一致。
 *
 * 切换工作区时自动复位到根目录（目录栈清空）。
 */
@HiltViewModel
class WorkspaceFileViewModel @Inject constructor(
    private val fileAccess: DelegatingFileAccess,
    private val workspaceRepository: WorkspaceRepository
) : ViewModel() {

    /** 当前选中工作区，与侧边栏「所有工作台」/聊天页共享同一数据源。 */
    val currentWorkspace: StateFlow<Workspace?> = workspaceRepository.current

    /** 当前目录的「相对容器路径栈」（从工作区根开始，不含 `~/workspace` 前缀）。 */
    private val _dirStack = MutableStateFlow<List<String>>(emptyList())
    val dirStack: StateFlow<List<String>> = _dirStack.asStateFlow()

    private val _entries = MutableStateFlow<List<FileEntry>>(emptyList())
    val entries: StateFlow<List<FileEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        // 工作区切换后复位到根目录并重列
        viewModelScope.launch {
            workspaceRepository.current.drop(1).collect {
                _dirStack.value = emptyList()
                refresh()
            }
        }
        refresh()
    }

    /** 当前目录的容器路径（如 `~/workspace` 或 `~/workspace/src`）。 */
    fun currentContainerPath(): String {
        val stack = _dirStack.value
        return if (stack.isEmpty()) "~/workspace" else "~/workspace/" + stack.joinToString("/")
    }

    /** 某条目在当前目录下的容器路径（供阅读页读取）。 */
    fun containerPathFor(entry: FileEntry): String {
        val stack = _dirStack.value + entry.name
        return "~/workspace/" + stack.joinToString("/")
    }

    /** 重新列出当前目录。 */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            val path = currentContainerPath()
            val list = runCatching { fileAccess.listFiles(path) }
                .getOrDefault(emptyList())
                .sortedWith(
                    compareBy<FileEntry> { !it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
            _entries.value = list
            _loading.value = false
        }
    }

    /** 进入子目录。 */
    fun enterDirectory(name: String) {
        _dirStack.value = _dirStack.value + name
        refresh()
    }

    /** 返回上一级；已在根目录时无操作。 */
    fun goUp() {
        if (_dirStack.value.isNotEmpty()) {
            _dirStack.value = _dirStack.value.dropLast(1)
            refresh()
        }
    }

    /** 回到工作区根目录。 */
    fun resetToRoot() {
        _dirStack.value = emptyList()
        refresh()
    }
}
