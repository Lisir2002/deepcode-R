package com.R.codecore.feature.settings.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.R.codecore.R
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Play
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Server
import compose.icons.feathericons.Square

/**
 * 内置 MCP 服务器（服务端）二级页：把本机编码后端开放给外部 MCP 客户端。
 *
 * - 顶部状态卡：运行/未运行 + 连接地址 + 启动/停止；
 * - 端口输入（保存即重启生效）；
 * - 远程调用强制审批 / App 启动自动启动 两个开关；
 * - 访问令牌展示 + 重新生成 + 复制。
 */
@Composable
internal fun McpServerSection(
    isRunning: Boolean,
    port: Int,
    token: String,
    requireApproval: Boolean,
    autoStart: Boolean,
    serverUrl: String,
    errorMessage: String?,
    onToggleServer: () -> Unit,
    onSaveConfig: (port: Int, requireApproval: Boolean, autoStart: Boolean) -> Unit,
    onRegenerateToken: () -> Unit
) {
    val context = LocalContext.current
    var portText by remember(port) { mutableStateOf(port.toString()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // ── 状态卡 ──
        item {
            Card(
                shape = RoundedCornerShape(Radius.lg),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                if (isRunning) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(Radius.md)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Server,
                            contentDescription = null,
                            tint = if (isRunning) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(Spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                if (isRunning) R.string.settings_mcp_server_running
                                else R.string.settings_mcp_server_stopped
                            ),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isRunning) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        if (isRunning && serverUrl.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = serverUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.width(Spacing.md))
                    if (isRunning) {
                        OutlinedButton(
                            onClick = onToggleServer,
                            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 8.dp)
                        ) {
                            Icon(FeatherIcons.Square, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_mcp_server_stop))
                        }
                    } else {
                        Button(
                            onClick = onToggleServer,
                            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(FeatherIcons.Play, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_mcp_server_start))
                        }
                    }
                }
            }
        }

        // ── 连接提示 ──
        item {
            Text(
                text = stringResource(R.string.settings_mcp_server_connect_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ── 错误提示 ──
        if (errorMessage != null) {
            item {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // ── 端口 + 配置 ──
        item {
            Card(
                shape = RoundedCornerShape(Radius.lg),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(Spacing.lg)) {
                    // 端口
                    Text(
                        text = stringResource(R.string.settings_mcp_server_port),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { input ->
                                portText = input.filter { it.isDigit() }.take(5)
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Button(
                            onClick = {
                                val newPort = portText.toIntOrNull()?.coerceIn(1, 65535) ?: port
                                portText = newPort.toString()
                                onSaveConfig(newPort, requireApproval, autoStart)
                            },
                            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 8.dp)
                        ) {
                            Text("保存")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 强制审批
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_mcp_server_require_approval),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_mcp_server_require_approval_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = requireApproval,
                            onCheckedChange = { onSaveConfig(port, it, autoStart) }
                        )
                    }

                    // 自动启动
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_mcp_server_auto_start),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Switch(
                            checked = autoStart,
                            onCheckedChange = { onSaveConfig(port, requireApproval, it) }
                        )
                    }
                }
            }
        }

        // ── 访问令牌 ──
        item {
            Card(
                shape = RoundedCornerShape(Radius.lg),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(Spacing.lg)) {
                    Text(
                        text = stringResource(R.string.settings_mcp_server_token),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.md))
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = token,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onRegenerateToken() }) {
                            Icon(
                                FeatherIcons.RefreshCw,
                                contentDescription = stringResource(R.string.settings_mcp_server_regenerate),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { copyText(context, token) }) {
                            Icon(
                                FeatherIcons.Copy,
                                contentDescription = stringResource(R.string.settings_mcp_server_copy),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 复制文本到系统剪贴板。 */
private fun copyText(context: Context, text: String) {
    if (text.isBlank()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("mcp-server-token", text))
}
