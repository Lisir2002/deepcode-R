package com.R.codecore.feature.settings.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.zth.ZthPerformanceClass
import com.R.codecore.feature.agent.domain.zth.ZthPresetTier
import com.R.codecore.feature.settings.presentation.SecuritySettingsViewModel

/**
 * 安全设置页：生物识别、凭据密钥轮换、紧急解锁通道。
 */
@Composable
fun SecuritySettingsScreen(
    viewModel: SecuritySettingsViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    var showBiometricConfirm by remember { mutableStateOf(false) }
    var pendingBiometricValue by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.md)
    ) {
        // ── 生物识别保护 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text(
                    text = "生物识别保护凭据",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "开启后，30 秒内首次访问 SSH 密码/私钥需验证指纹或面容。\n" +
                        "此操作会重新生成主密钥，请保持电量充足，中途不要退出应用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                Switch(
                    checked = uiState.biometricRequired,
                    onCheckedChange = { newValue ->
                        if (newValue) {
                            pendingBiometricValue = true
                            showBiometricConfirm = true
                        } else {
                            viewModel.toggleBiometric(false)
                        }
                    },
                    enabled = !uiState.loading
                )
                if (uiState.loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = Spacing.xs))
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        // ── 凭据密钥轮换 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text(
                    text = "凭据密钥轮换",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "生成新的 DEK (Data Encryption Key) 并重新加密所有凭据。" +
                        "当前轮换次数: ${uiState.rotationCounter}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                Button(
                    onClick = { viewModel.rotateDek() },
                    enabled = !uiState.rotating
                ) {
                    if (uiState.rotating) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 4.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(if (uiState.rotating) "轮换中…" else "立即轮换密钥")
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        // ── 紧急解锁通道 ──
        SecurityEmergencyChannelSection(
            onReset = { host, port, username, password ->
                viewModel.emergencyReset(host, port, username, password)
            },
            isResetting = uiState.resetting,
            errorMessage = uiState.error
        )

        Spacer(Modifier.height(Spacing.md))

        // ── ZTH 零幻觉容忍档位（C.4.2 4 档 + C.4.8 滑动确认；C.6.2 P14 纠正挂到设置安全卡片） ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text(
                    text = "ZTH 零幻觉容忍模式（幻觉零容忍 ZTH-0）",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "越严格档位越频繁弹卡用户确认；幻觉触发（ToolOutput/Plan/Skill 正文）无论档位，都先弹卡绝不自我麻痹。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))

                Text("档位（幻觉敏感度）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(Spacing.xs))
                val tiers = listOf(
                    ZthPresetTier.DISABLED to "关闭（0）—— 完全关闭 ZTH，只保留原有 PlanApproval",
                    ZthPresetTier.MINIMAL to "轻度（1）—— 8 HIGH 规则 + 幻觉计数阈值=5；允许取消卡片 / 关闭滑动确认",
                    ZthPresetTier.BALANCED to "均衡（2）—— 20 全规则 + 幻觉阈值=3；禁止取消卡片，必须滑动确认",
                    ZthPresetTier.STRICT to "严格（3）—— 30 全规则 + 幻觉阈值=2；禁止取消 + 禁止关滑动 + 自动熔断 2 次开"
                )
                Column {
                    tiers.forEach { (tier, desc) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = uiState.zthTier == tier,
                                onClick = { viewModel.setZthTier(tier) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "${tier.tier}. $tier · ${desc.substringBefore("——")}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (uiState.zthTier == tier) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(
                                    text = desc.substringAfter("——"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                Text("性能等级（低端机是否跳过 LLM 终检）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(Spacing.xs))
                val perfs = listOf(
                    ZthPerformanceClass.LOW_END_SKIP_LLM to "低端机—— 工具输出幻觉/Plan 二次审查跳过 LLM，只跑本地启发扫 + 弹卡用户",
                    ZthPerformanceClass.MID_RANGE to "中端—— 阶段 2 LLM 终检开启，预算 ≤ 10s",
                    ZthPerformanceClass.HIGH_END to "高端—— 2 阶段都跑，预算 ≤ 30s（默认）"
                )
                Column {
                    perfs.forEach { (perf, desc) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            RadioButton(selected = uiState.zthPerfClass == perf, onClick = { viewModel.setZthPerf(perf) })
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${perf.name}: $desc",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("滑动确认（SwipeToConfirm，C.4.8 方案 C）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "关闭仅允许 DISABLED / MINIMAL(0/1)；≥2 强制开。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.zthSwipeEnabled,
                        onCheckedChange = { viewModel.setZthSwipe(it) },
                        enabled = uiState.zthTier.tier <= 1
                    )
                }
                if (uiState.zthTier.tier >= 2) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = "⚠ 档位 BALANCED/STRICT（C.4.8）：必须滑动确认；设置已强制为 ON。",
                        color = Color(0xFF827717),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ── 状态消息 ──
        if (uiState.successMessage != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = uiState.successMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // ── 生物识别确认弹窗 ──
        if (showBiometricConfirm) {
            AlertDialog(
                onDismissRequest = { showBiometricConfirm = false },
                title = { Text("启用生物识别凭据保护？") },
                text = {
                    Text(
                        "开启后，30 秒内首次访问 SSH 密码/私钥需验证指纹或面容。\n\n" +
                            "⚠ 此操作会重新生成主密钥，请保持电量充足，中途不要退出应用。"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showBiometricConfirm = false
                            viewModel.toggleBiometric(true)
                        }
                    ) {
                        Text("确认启用")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBiometricConfirm = false }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}