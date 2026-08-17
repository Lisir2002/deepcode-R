package com.R.codecore.feature.settings.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.R.codecore.core.theme.Spacing

/**
 * 安全紧急通道：验证 SSH 密码通过后，允许重置 MasterKey。
 * 需要用户输入任一有效 SSH 连接的 host/port/username + 密码来证明身份。
 */
@Composable
fun SecurityEmergencyChannelSection(
    onReset: (host: String, port: Int, username: String, password: String) -> Unit,
    isResetting: Boolean = false,
    errorMessage: String? = null
) {
    var showDialog by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                text = "紧急解锁主密钥",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "当 Keystore 主密钥丢失/损坏、所有凭据无法解密时使用。" +
                    "输入任一有效 SSH 连接的认证信息验证身份，验证通过后系统将生成新主密钥，所有凭据需要重新录入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            Button(
                onClick = { showDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                enabled = !isResetting
            ) {
                Text(if (isResetting) "重置中…" else "验证身份并重置主密钥")
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showDialog && !isResetting) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("验证身份") },
            text = {
                Column {
                    Text(
                        text = "输入任一 SSH 远程连接的认证信息以验证身份：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it; localError = null },
                        label = { Text("主机") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Row {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it; localError = null },
                            label = { Text("端口") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it; localError = null },
                            label = { Text("用户名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; localError = null },
                        label = { Text("密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (localError != null) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = localError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val port = portText.toIntOrNull()
                        if (host.isBlank() || username.isBlank() || password.isBlank()) {
                            localError = "请填写所有字段"
                        } else if (port == null || port < 1 || port > 65535) {
                            localError = "端口无效"
                        } else {
                            localError = null
                            showDialog = false
                            onReset(host.trim(), port, username.trim(), password)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("验证并重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}