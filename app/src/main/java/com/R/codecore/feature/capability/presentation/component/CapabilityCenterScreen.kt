package com.R.codecore.feature.capability.presentation.component

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.R.codecore.R
import com.R.codecore.core.theme.AppEmptyState
import com.R.codecore.core.theme.AppTopAppBar
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.model.AgentMode
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.capability.presentation.AgentInfoUi
import com.R.codecore.feature.capability.presentation.CapabilityCenterViewModel
import com.R.codecore.feature.capability.presentation.ParameterUiModel
import com.R.codecore.feature.capability.presentation.ToolUiModel
import com.R.codecore.feature.settings.data.repository.ExecutionMode
import com.R.codecore.feature.settings.presentation.SkillsViewModel
import com.R.codecore.feature.settings.presentation.component.SkillsScreen
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Box
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Grid
import compose.icons.feathericons.Lock
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Zap

/** 能力中心 Tab 枚举。 */
enum class CapabilityTab(@param:StringRes val titleRes: Int) {
    TOOLS(R.string.capability_tab_tools),
    AGENT(R.string.capability_tab_agent),
    SKILLS(R.string.capability_tab_skills)
}

/**
 * 能力中心：罗列系统全部工具、Agent 能力概览与技能中心（技能管理已从设置页迁移至此）。
 */
@Composable
fun CapabilityCenterScreen(
    viewModel: CapabilityCenterViewModel,
    currentSessionMode: AgentMode,
    onNavigateBack: () -> Unit,
    onOpenSkillDetail: (String) -> Unit = {},
    onEditSkill: (String) -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(CapabilityTab.TOOLS) }
    val agentInfo by viewModel.agentInfo.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.capability_center_title),
                onNavigateBack = onNavigateBack,
                navigationIcon = FeatherIcons.ArrowLeft,
                navigationContentDescription = stringResource(R.string.common_back)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                CapabilityTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(stringResource(tab.titleRes)) }
                    )
                }
            }
            when (selectedTab) {
                CapabilityTab.TOOLS -> ToolsTab(tools = viewModel.tools)
                CapabilityTab.AGENT -> AgentTab(
                    agentInfo = agentInfo,
                    currentSessionMode = currentSessionMode
                )
                CapabilityTab.SKILLS -> {
                    val skillsViewModel: SkillsViewModel = hiltViewModel()
                    SkillsScreen(
                        viewModel = skillsViewModel,
                        onOpenSkillDetail = onOpenSkillDetail,
                        onEditSkill = onEditSkill
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// 工具 Tab
// ──────────────────────────────────────────────

@Composable
private fun ToolsTab(tools: List<ToolUiModel>) {
    if (tools.isEmpty()) {
        AppEmptyState(
            icon = FeatherIcons.Box,
            title = stringResource(R.string.capability_tools_empty),
            subtitle = stringResource(R.string.capability_tools_empty_subtitle)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        items(tools, key = { it.name }) { tool ->
            ToolCard(tool = tool)
        }
    }
}

@Composable
private fun ToolCard(tool: ToolUiModel) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(Spacing.sm))
                PermissionBadge(policy = tool.permissionPolicy)
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = tool.description.ifBlank { stringResource(R.string.capability_tool_no_description) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2
            )
            if (tool.capabilities.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    tool.capabilities.take(4).forEach { cap ->
                        CapabilityChip(capability = cap)
                    }
                    if (tool.capabilities.size > 4) {
                        Text(
                            text = "+${tool.capabilities.size - 4}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (expanded) {
                Spacer(Modifier.height(Spacing.sm))
                if (tool.capabilities.size > 4) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        tool.capabilities.drop(4).forEach { cap ->
                            CapabilityChip(capability = cap)
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
                if (tool.parameters.isEmpty()) {
                    Text(
                        text = stringResource(R.string.capability_tool_no_parameters),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.capability_tool_parameters),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    tool.parameters.forEach { param ->
                        ParameterRow(param = param)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionBadge(policy: ToolPermissionPolicy) {
    val (label, color) = when (policy) {
        ToolPermissionPolicy.AUTO_APPROVE ->
            stringResource(R.string.capability_tool_permission_auto) to Color(0xFF4CAF50)
        ToolPermissionPolicy.ASK ->
            stringResource(R.string.capability_tool_permission_ask) to Color(0xFFFF9800)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun CapabilityChip(capability: ToolCapability) {
    Text(
        text = capability.name.lowercase().replace('_', ' '),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun ParameterRow(param: ParameterUiModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = param.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = param.type,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
        if (!param.required) {
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = stringResource(R.string.capability_tool_optional),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = param.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

// ──────────────────────────────────────────────
// Agent Tab
// ──────────────────────────────────────────────

@Composable
private fun AgentTab(
    agentInfo: AgentInfoUi,
    currentSessionMode: AgentMode
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            Text(
                text = stringResource(R.string.capability_agent_runtime),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(Spacing.sm))
            AgentInfoCard(
                icon = FeatherIcons.Terminal,
                label = stringResource(R.string.capability_agent_execution_mode),
                value = when (agentInfo.executionMode) {
                    ExecutionMode.LOCAL_PROOT -> stringResource(R.string.capability_agent_execution_local)
                    ExecutionMode.REMOTE_SSH -> stringResource(R.string.capability_agent_execution_remote)
                }
            )
            Spacer(Modifier.height(Spacing.sm))
            AgentInfoCard(
                icon = FeatherIcons.Cpu,
                label = stringResource(R.string.capability_agent_active_provider),
                value = agentInfo.activeProviderName
                    ?: stringResource(R.string.capability_agent_no_provider),
                subValue = agentInfo.activeProviderModel
            )
            Spacer(Modifier.height(Spacing.sm))
            AgentInfoCard(
                icon = FeatherIcons.Grid,
                label = stringResource(R.string.capability_agent_session_mode),
                value = when (currentSessionMode) {
                    AgentMode.BUILD -> stringResource(R.string.capability_agent_mode_build)
                    AgentMode.PLAN -> stringResource(R.string.capability_agent_mode_plan)
                    AgentMode.AUTO -> stringResource(R.string.capability_agent_mode_auto)
                }
            )
        }
        item {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.capability_agent_stats),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatCard(
                    icon = FeatherIcons.Box,
                    count = agentInfo.toolCount,
                    label = stringResource(R.string.capability_agent_tool_count),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon = FeatherIcons.Zap,
                    count = agentInfo.skillCount,
                    label = stringResource(R.string.capability_agent_skill_count),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    icon = FeatherIcons.Lock,
                    count = agentInfo.permissionRuleCount,
                    label = stringResource(R.string.capability_agent_rule_count),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.capability_agent_policy),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(Spacing.sm))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(Spacing.md)) {
                    Text(
                        text = stringResource(R.string.capability_agent_policy_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentInfoCard(
    icon: ImageVector,
    label: String,
    value: String,
    subValue: String? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subValue != null && subValue.isNotBlank()) {
                    Text(
                        text = subValue,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    count: Int,
    label: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
