package com.core.deepcode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.core.deepcode.core.security.CredentialEncryptor
import com.core.deepcode.core.security.OperationResult
import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.domain.zth.ZthPerformanceClass
import com.core.deepcode.feature.agent.domain.zth.ZthPresetTier
import com.core.deepcode.feature.settings.data.repository.ZthTierRepository
import com.core.deepcode.feature.workspace.domain.RemoteAuditAction
import com.core.deepcode.feature.workspace.domain.RemoteAuditCategory
import com.core.deepcode.feature.workspace.domain.repository.RemoteAuditLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    val successMessage: String? = null,
    // ZTH 三字段（Phase 3.4）：默认值仅用于 UI 初始帧；真实值由 tierFlow 组合覆盖
    val zthTier: ZthPresetTier = ZthPresetTier.BALANCED,
    val zthPerfClass: ZthPerformanceClass = ZthPerformanceClass.HIGH_END,
    val zthSwipeEnabled: Boolean = true
)

@HiltViewModel
class SecuritySettingsViewModel @Inject constructor(
    private val encryptor: CredentialEncryptor,
    private val auditLogRepo: RemoteAuditLogRepository,
    private val zthTierRepository: ZthTierRepository
) : ViewModel() {

    // BaseState（凭据/轮换部分）+ ZTH StateFlow 三字段 → 合成一个统一 SecurityUiState
    private val _baseState = MutableStateFlow(SecurityUiState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SecurityUiState> =
        combine(
            _baseState,
            zthTierRepository.tierFlow,
            zthTierRepository.perfClassFlow,
            zthTierRepository.swipeEnabledFlow
        ) { base, tier, perf, swipe ->
            base.copy(zthTier = tier, zthPerfClass = perf, zthSwipeEnabled = swipe)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SecurityUiState()
        )

    init {
        loadState()
    }

    private fun loadState() {
        viewModelScope.launch {
            try {
                val state = encryptor.encryptionState()
                if (state != null) {
                    val cur = _baseState.value
                    _baseState.value = cur.copy(
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
            _baseState.value = _baseState.value.copy(loading = true, error = null, successMessage = null)
            val result = encryptor.setBiometricRequired(required)
            when (result) {
                is OperationResult.Success -> {
                    _baseState.value = _baseState.value.copy(
                        biometricRequired = required,
                        loading = false,
                        successMessage = "生物识别保护已${if (required) "开启" else "关闭"}"
                    )
                    loadState()
                }
                is OperationResult.Failure -> {
                    _baseState.value = _baseState.value.copy(
                        loading = false,
                        error = "切换失败: ${result.error.message}"
                    )
                }
            }
        }
    }

    fun rotateDek() {
        viewModelScope.launch {
            _baseState.value = _baseState.value.copy(rotating = true, error = null, successMessage = null)
            val result = encryptor.scheduleRotateDek()
            when (result) {
                is OperationResult.Success -> {
                    _baseState.value = _baseState.value.copy(
                        rotating = false,
                        successMessage = "凭据密钥轮换完成（版本 ${result.data.rotationCounter}）"
                    )
                    loadState()
                }
                is OperationResult.Failure -> {
                    _baseState.value = _baseState.value.copy(
                        rotating = false,
                        error = "轮换失败: ${result.error.message}"
                    )
                }
            }
        }
    }

    fun emergencyReset(host: String, port: Int, username: String, password: String) {
        viewModelScope.launch {
            _baseState.value = _baseState.value.copy(resetting = true, error = null, successMessage = null)
            try {
                val report = encryptor.emergencyResetMasterKey()
                FileLogger.i("SecurityVM", "紧急重置完成")
                auditLogRepo.append(
                    category = RemoteAuditCategory.SECURITY,
                    action = RemoteAuditAction.EMERGENCY_RESET_MASTERKEY,
                    success = true,
                    message = "通过 SSH 验证（$host:$port）执行紧急重置"
                )
                _baseState.value = _baseState.value.copy(
                    resetting = false,
                    successMessage = "主密钥已重置，请重新录入各远程连接的密码"
                )
                loadState()
            } catch (e: Exception) {
                _baseState.value = _baseState.value.copy(
                    resetting = false,
                    error = "重置失败: ${e.message}"
                )
            }
        }
    }

    fun clearMessages() {
        _baseState.value = _baseState.value.copy(error = null, successMessage = null)
    }

    // ── ZTH 档位三 setter（Phase 3.4）──────────────────────────────────
    fun setZthTier(tier: ZthPresetTier) {
        viewModelScope.launch {
            zthTierRepository.setTier(tier)
            _baseState.value = _baseState.value.copy(successMessage = "ZTH 档位已设置为：$tier")
        }
    }
    fun setZthPerf(cls: ZthPerformanceClass) {
        viewModelScope.launch {
            zthTierRepository.setPerformanceClass(cls)
            _baseState.value = _baseState.value.copy(successMessage = "ZTH 性能等级已设置为：${cls.name}")
        }
    }
    fun setZthSwipe(enabled: Boolean) {
        viewModelScope.launch {
            val current = uiState.value.zthTier
            zthTierRepository.setSwipeEnabled(enabled, current)
            val actual = if (current.tier >= 2 && !enabled) {
                _baseState.value = _baseState.value.copy(error = "档位 BALANCED/STRICT：必须启用滑动确认（C.4.8），已阻止关闭。")
                true
            } else {
                _baseState.value = _baseState.value.copy(successMessage = "ZTH 滑动确认：${if (enabled) "开" else "关"}（仅档位 MINIMAL 以下允许关闭）")
                enabled
            }
            _baseState.value = _baseState.value.copy(zthSwipeEnabled = actual)
        }
    }
}