package com.R.codecore.feature.workspace.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.R.codecore.feature.workspace.domain.DelegatingFileAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 文件阅读页 UI 状态。 */
data class FileReaderUiState(
    val loading: Boolean = false,
    val path: String = "",
    val fileName: String = "",
    val content: String = "",
    val error: String? = null
)

/**
 * 独立文件阅读页 ViewModel：读取容器路径（`~/workspace/...`）对应的文件文本内容。
 *
 * 经 [DelegatingFileAccess] 按执行模式转发本地/远程实现，本地直读宿主文件、远程走 SSH exec `cat`，
 * 与 AI 文件工具读取到的是同一批文件。
 */
@HiltViewModel
class FileReaderViewModel @Inject constructor(
    private val fileAccess: DelegatingFileAccess
) : ViewModel() {

    private val _state = MutableStateFlow(FileReaderUiState())
    val state: StateFlow<FileReaderUiState> = _state.asStateFlow()

    fun load(path: String) {
        val safePath = path.trim().ifBlank { return }
        _state.value = FileReaderUiState(loading = true, path = safePath, fileName = safePath.substringAfterLast('/'))
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { fileAccess.readFile(safePath) }
            _state.value = result.fold(
                onSuccess = { content ->
                    FileReaderUiState(
                        loading = false,
                        path = safePath,
                        fileName = safePath.substringAfterLast('/'),
                        content = content,
                        error = null
                    )
                },
                onFailure = { e ->
                    FileReaderUiState(
                        loading = false,
                        path = safePath,
                        fileName = safePath.substringAfterLast('/'),
                        error = e.message?.takeIf { it.isNotBlank() } ?: "read_error"
                    )
                }
            )
        }
    }
}
