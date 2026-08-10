package com.deep.rcode.feature.settings.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.settings.presentation.SecuritySettingsViewModel

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