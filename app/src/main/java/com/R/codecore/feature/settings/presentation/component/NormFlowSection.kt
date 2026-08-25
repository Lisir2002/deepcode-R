package com.R.codecore.feature.settings.presentation.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Input
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.R.codecore.R
import com.R.codecore.core.theme.AppSectionGroup
import com.R.codecore.core.theme.AppSectionHeader
import com.R.codecore.core.theme.Spacing

/**
 * 「规范流程」二级页（D1-7，对齐 norm-chain-design.md §3.5）：
 * 总开关 [normFlowEnabled]（关闭即 step 前注入 / guard 链整体停用）
 * + 子开关 [stepInjectEnabled]（step 前注入纪律）/ [toolGuardEnabled]（guard 链 + 文件观察）。
 * 默认全开；SOP 摘要 / Playbook 自动触发子开关随 D4/D5 批次追加。
 */
@Composable
internal fun NormFlowSection(
    normFlowEnabled: Boolean,
    stepInjectEnabled: Boolean,
    toolGuardEnabled: Boolean,
    onToggleNormFlow: (Boolean) -> Unit,
    onToggleStepInject: (Boolean) -> Unit,
    onToggleToolGuard: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
    ) {
        item {
            AppSectionHeader(stringResource(R.string.settings_norm_flow_group))
            AppSectionGroup {
                GroupSwitchRow(
                    icon = Icons.Rounded.FactCheck,
                    title = stringResource(R.string.settings_norm_flow_master),
                    subtitle = stringResource(R.string.settings_norm_flow_master_desc),
                    checked = normFlowEnabled,
                    onCheckedChange = onToggleNormFlow
                )
                GroupSwitchRow(
                    icon = Icons.Rounded.Input,
                    title = stringResource(R.string.settings_norm_flow_step_inject),
                    subtitle = stringResource(R.string.settings_norm_flow_step_inject_desc),
                    checked = stepInjectEnabled,
                    onCheckedChange = onToggleStepInject
                )
                GroupSwitchRow(
                    icon = Icons.Rounded.Shield,
                    title = stringResource(R.string.settings_norm_flow_tool_guard),
                    subtitle = stringResource(R.string.settings_norm_flow_tool_guard_desc),
                    checked = toolGuardEnabled,
                    onCheckedChange = onToggleToolGuard
                )
            }
            Spacer(Modifier.height(Spacing.md))
        }
    }
}
