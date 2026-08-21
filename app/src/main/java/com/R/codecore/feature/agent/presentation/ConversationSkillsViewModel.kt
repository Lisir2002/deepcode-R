package com.R.codecore.feature.agent.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.data.local.entity.SkillConversationStateEntity
import com.R.codecore.feature.agent.domain.skill.Skill
import com.R.codecore.feature.agent.domain.skill.SkillScope
import com.R.codecore.feature.agent.domain.skill.SkillStateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 对话技能面板 ViewModel：对话级双向控制（D5/D10）。
 *
 * - 本对话生效：GLOBAL/AGENT 生效技能 + 已添加的 CONVERSATION 技能（可按对话临时禁用/恢复）。
 * - 可添加：未在当前对话启用的 CONVERSATION 技能（点击添加后本对话立即全面生效 D8）。
 */
@HiltViewModel
class ConversationSkillsViewModel @Inject constructor(
    private val skillStateRepository: SkillStateRepository
) : ViewModel() {

    private companion object {
        const val TAG = "ConversationSkillsViewModel"
    }

    private val _sessionId = MutableStateFlow<String?>(null)

    /** 本对话当前生效的技能（严格隐藏过滤结果）。 */
    private val _activeSkills = MutableStateFlow<List<Skill>>(emptyList())
    val activeSkills: StateFlow<List<Skill>> = _activeSkills.asStateFlow()

    /** 本对话内被临时禁用的技能（含 GLOBAL/AGENT），用于展示可恢复项。 */
    private val _disabledInConversation = MutableStateFlow<List<Skill>>(emptyList())
    val disabledInConversation: StateFlow<List<Skill>> = _disabledInConversation.asStateFlow()

    /** 可添加进本对话的 CONVERSATION 技能。 */
    private val _addableConversationSkills = MutableStateFlow<List<Skill>>(emptyList())
    val addableConversationSkills: StateFlow<List<Skill>> = _addableConversationSkills.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 加载某对话的技能面板数据。 */
    fun load(sessionId: String) {
        _sessionId.value = sessionId
        viewModelScope.launch {
            refresh()
        }
    }

    private suspend fun refresh() {
        val sessionId = _sessionId.value ?: return
        val all = runCatching { skillStateRepository.listSkills() }.getOrDefault(emptyList())
        val active = runCatching { skillStateRepository.filterVisibleSkills(all, sessionId) }.getOrDefault(emptyList())
        _activeSkills.value = active

        val convStates = runCatching { skillStateRepository.listConversationStates(sessionId) }
            .getOrDefault(emptyList())
            .associateBy { it.skillId }

        // 本对话被临时禁用的技能（enabled=false 绑定）：GLOBAL/AGENT 技能在此展示供恢复。
        _disabledInConversation.value = all.filter { skill ->
            skill.enabled && convStates[skill.id]?.enabled == false &&
                skill.scope != SkillScope.CONVERSATION
        }

        // 未添加的 CONVERSATION 技能（无绑定或绑定为 false）。
        _addableConversationSkills.value = all.filter { skill ->
            skill.enabled && skill.scope == SkillScope.CONVERSATION &&
                convStates[skill.id]?.enabled != true
        }
    }

    /** 添加 CONVERSATION 技能到当前对话（本对话立即生效）。 */
    fun addToConversation(skillId: String) {
        val sessionId = _sessionId.value ?: return
        viewModelScope.launch {
            runCatching { skillStateRepository.setConversationEnabled(skillId, sessionId, true) }
                .onFailure { FileLogger.e(TAG, "添加对话技能失败: $skillId", it) }
            refresh()
        }
    }

    /** 本对话内临时禁用技能（GLOBAL/AGENT/CONVERSATION 均可）。 */
    fun disableInConversation(skillId: String) {
        val sessionId = _sessionId.value ?: return
        viewModelScope.launch {
            runCatching { skillStateRepository.setConversationEnabled(skillId, sessionId, false) }
                .onFailure { FileLogger.e(TAG, "禁用对话技能失败: $skillId", it) }
            refresh()
        }
    }

    /** 恢复：移除本对话绑定，回到跟随声明（对 CONVERSATION 技能即恢复休眠）。 */
    fun restoreInConversation(skillId: String) {
        val sessionId = _sessionId.value ?: return
        viewModelScope.launch {
            runCatching { skillStateRepository.removeConversationBinding(skillId, sessionId) }
                .onFailure { FileLogger.e(TAG, "恢复对话技能失败: $skillId", it) }
            refresh()
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
