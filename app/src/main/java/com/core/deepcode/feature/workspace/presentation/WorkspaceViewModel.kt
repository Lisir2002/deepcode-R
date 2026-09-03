package com.core.deepcode.feature.workspace.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.core.deepcode.feature.settings.data.repository.ExecutionModeHolder
import com.core.deepcode.feature.workspace.data.repository.WorkspaceRepository
import com.core.deepcode.feature.workspace.domain.model.Workspace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val repository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder
) : ViewModel() {

    val workspaces: StateFlow<List<Workspace>> = repository.workspaces
    val current: StateFlow<Workspace?> = repository.current

    init {
        viewModelScope.launch { runCatching { repository.initialize() } }
        // 模式切换后重新加载工作区列表（本地 File.listFiles ↔ 远程 SFTP ls）。
        // drop(1) 跳过首帧（init 已调 initialize），仅响应后续切换。
        viewModelScope.launch {
            executionModeHolder.mode.drop(1).distinctUntilChanged().collect {
                runCatching { repository.initialize() }
            }
        }
    }

    fun selectWorkspace(name: String) = viewModelScope.launch {
        runCatching { repository.selectWorkspace(name) }
    }

    fun createWorkspace(name: String, onResult: (Workspace?) -> Unit = {}) = viewModelScope.launch {
        val ws = runCatching { repository.createWorkspace(name) }.getOrNull()
        if (ws != null) runCatching { repository.selectWorkspace(ws.name) }
        onResult(ws)
    }

    fun deleteWorkspace(name: String) = viewModelScope.launch {
        runCatching { repository.deleteWorkspace(name) }
    }

    /** 重命名工作区（成功返回 true，名称非法/重名返回 false）。 */
    fun renameWorkspace(oldName: String, newName: String, onResult: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val ok = runCatching { repository.renameWorkspace(oldName, newName) }.getOrDefault(false)
        onResult(ok)
    }
}
