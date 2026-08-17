package com.R.codecore.feature.capability.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.R.codecore.feature.agent.domain.permission.PermissionRulesRepository
import com.R.codecore.feature.agent.domain.skill.Skill
import com.R.codecore.feature.agent.domain.skill.SkillStateRepository
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.agent.domain.tool.ToolRegistry
import com.R.codecore.feature.settings.data.repository.ExecutionMode
import com.R.codecore.feature.settings.data.repository.ExecutionModeHolder
import com.R.codecore.feature.settings.domain.repository.AIProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 工具 UI 模型（能力中心「工具」Tab）。 */
data class ToolUiModel(
    val name: String,
    val description: String,
    val permissionPolicy: ToolPermissionPolicy,
    val capabilities: List<ToolCapability>,
    val parameters: List<ParameterUiModel>
)

/** 工具参数 UI 模型。 */
data class ParameterUiModel(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean
)

/** Agent 概览 UI 模型（能力中心「Agent」Tab）。 */
data class AgentInfoUi(
    val executionMode: ExecutionMode,
    val activeProviderName: String?,
    val activeProviderModel: String?,
    val permissionRuleCount: Int,
    val toolCount: Int,
    val skillCount: Int
)

/**
 * 能力中心 ViewModel（RC 新增）。
 *
 * 聚合三类能力数据，供「工具 / Agent / 技能」三个 Tab 使用：
 * - 工具：来自 [ToolRegistry]（一次性快照，工具在 Agent 工作流启动时注册）。
 * - Agent：执行模式（[ExecutionModeHolder]）、激活 Provider（[AIProviderRepository]）、
 *   授权规则数（[PermissionRulesRepository]）与工具/技能计数，响应式聚合。
 * - 技能：来自 [SkillStateRepository.skillsFlow]（含启用状态，响应式）。
 */
@HiltViewModel
class CapabilityCenterViewModel @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val skillStateRepository: SkillStateRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val aiProviderRepository: AIProviderRepository,
    private val permissionRulesRepository: PermissionRulesRepository
) : ViewModel() {

    /** 全部已注册工具（含权限策略、能力、参数），按名称排序。 */
    val tools: List<ToolUiModel> = toolRegistry.getAvailableTools()
        .sortedBy { it.name.lowercase() }
        .map { it.toUiModel() }

    /** 技能列表（含启用状态，响应式）。 */
    val skills: StateFlow<List<Skill>> = skillStateRepository.skillsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Agent 概览（执行模式 / 激活 Provider / 授权规则数 / 工具与技能计数）。 */
    val agentInfo: StateFlow<AgentInfoUi> = combine(
        executionModeHolder.mode,
        aiProviderRepository.getActiveProvider(),
        permissionRulesRepository.globalRulesFlow,
        permissionRulesRepository.currentProjectRulesFlow,
        skillStateRepository.skillsFlow
    ) { mode, provider, globalRules, projectRules, skills ->
        AgentInfoUi(
            executionMode = mode,
            activeProviderName = provider?.name,
            activeProviderModel = provider?.effectiveModel,
            permissionRuleCount = globalRules.size + projectRules.size,
            toolCount = toolRegistry.getAvailableTools().size,
            skillCount = skills.size
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AgentInfoUi(
            executionMode = ExecutionMode.LOCAL_PROOT,
            activeProviderName = null,
            activeProviderModel = null,
            permissionRuleCount = 0,
            toolCount = 0,
            skillCount = 0
        )
    )
}

private fun AgentTool.toUiModel(): ToolUiModel = ToolUiModel(
    name = name,
    description = description,
    permissionPolicy = permissionPolicy,
    capabilities = capabilities.toList(),
    parameters = parameters.values.map {
        ParameterUiModel(
            name = it.name,
            type = it.type.name.lowercase(),
            description = it.description,
            required = it.required
        )
    }
)
