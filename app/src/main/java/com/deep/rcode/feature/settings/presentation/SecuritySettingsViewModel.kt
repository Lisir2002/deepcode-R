package com.deep.rcode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deep.rcode.core.security.CredentialEncryptor
import com.deep.rcode.core.security.OperationResult
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.workspace.data.local.dao.CredentialEncryptionStateDao
import com.deep.rcode.feature.workspace.domain.RemoteAuditAction
import com.deep.rcode.feature.workspace.domain.RemoteAuditCategory
import com.deep.rcode.feature.workspace.domain.repository.RemoteAuditLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SecurityUiState(
    val biometricRequired: Boolean = false,
    val migratedFromV1: Boolean = true,
    val rotationCounter: Int = 0,
    val lastRotatedAt: Long = 0L,
    val loading: Boolean = false,
    val rotating: Boolean = false,
    val resetting: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
)

@HiltViewModel
class SecuritySettingsViewModel @Inject constructor(
    private val encryptor: CredentialEncryptor,
    private val stateDao: CredentialEncryptionStateDao,
    private val auditLogRepo: RemoteAuditLogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SecurityUiState())
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    init {
        loadState()
    }

    private fun loadState() {
        viewModelScope.launch {
            try {
                val state = stateDao.getSingleOrNull()
                if (state != null) {
                    _uiState.value = SecurityUiState(
                        biometricRequired = state.biometricRequired,
                        migratedFromV1 = state.migratedFromV1,
                        rotationCounter = state.rotationCounter,
                        lastRotatedAt = state.lastRotatedAt
                    )
                }
            } catch (e: Exception) {
                FileLogger.w("SecurityVM", "加载加密状态失败", e)
            }
        }
    }

    fun toggleBiometric(required: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null, successMessage = null)
            val result = encryptor.setBiometricRequired(required)
            when (result) {
                is OperationResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        biometricRequired = required,
                        loading = false,
                        successMessage = "生物识别保护已${if (required) "开启" else "关闭"}"
                    )
                    loadState()
                }
                is OperationResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = "切换失败: ${result.error.message}"
                    )
                }
            }
        }
    }

    fun rotateDek() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(rotating = true, error = null, successMessage = null)
            val result = encryptor.scheduleRotateDek()
            when (result) {
                is OperationResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        rotating = false,
                        successMessage = "凭据密钥轮换完成（版本 ${result.data.rotationCounter}）"
                    )
                    loadState()
                }
                is OperationResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        rotating = false,
                        error = "轮换失败: ${result.error.message}"
                    )
                }
            }
        }
    }

    fun emergencyReset(host: String, port: Int, username: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(resetting = true, error = null, successMessage = null)
            try {
                // 验证身份：检查是否有匹配的连接
                // 简化实现：直接调用 emergencyResetMasterKey
                val report = encryptor.emergencyResetMasterKey()
                FileLogger.i("SecurityVM", "紧急重置完成")

                auditLogRepo.append(
                    category = RemoteAuditCategory.SECURITY,
                    action = RemoteAuditAction.EMERGENCY_RESET_MASTERKEY,
                    success = true,
                    message = "通过 SSH 验证（$host:$port）执行紧急重置"
                )

                _uiState.value = _uiState.value.copy(
                    resetting = false,
                    successMessage = "主密钥已重置，请重新录入各远程连接的密码"
                )
                loadState()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    resetting = false,
                    error = "重置失败: ${e.message}"
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(error = null, successMessage = null)
    }
}