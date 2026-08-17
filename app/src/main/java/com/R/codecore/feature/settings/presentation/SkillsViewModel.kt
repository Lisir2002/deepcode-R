package com.R.codecore.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.skill.Skill
import com.R.codecore.feature.agent.domain.skill.SkillStateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 技能中心 ViewModel（RC74 新增）。
 */
@HiltViewModel
class SkillsViewModel @Inject constructor(
    private val skillStateRepository: SkillStateRepository
) : ViewModel() {

    private companion object {
        const val TAG = "SkillsViewModel"
    }

    /** 技能列表（含启用状态，响应式）。 */
    val skills: StateFlow<List<Skill>> = skillStateRepository.skillsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 启用/禁用技能（即时生效）。 */
    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { skillStateRepository.setEnabled(id, enabled) }
                .onFailure { e ->
                    FileLogger.e(TAG, "切换技能启用状态失败: $id", e)
                    _message.value = "切换启用状态失败: ${e.message}"
                }
        }
    }

    /** 卸载技能。 */
    fun uninstall(skill: Skill) {
        viewModelScope.launch {
            _loading.value = true
            runCatching { skillStateRepository.uninstall(skill.id) }
                .onSuccess { ok ->
                    _message.value = if (ok) "已卸载技能「${skill.name}」" else "卸载技能「${skill.name}」失败"
                }
                .onFailure { e ->
                    FileLogger.e(TAG, "卸载技能失败: ${skill.id}", e)
                    _message.value = "卸载技能失败: ${e.message}"
                }
            _loading.value = false
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
