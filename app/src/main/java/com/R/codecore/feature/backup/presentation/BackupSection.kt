package com.R.codecore.feature.backup.presentation

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.backup.domain.BackupOptions
import compose.icons.FeatherIcons
import compose.icons.feathericons.Download
import compose.icons.feathericons.Upload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import com.R.codecore.R
import com.R.codecore.feature.backup.data.guard.SentinelVerdict
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun BackupSection(viewModel: BackupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dataSafety by viewModel.dataSafety.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 进入备份页即刷新数据保全状态（哨兵判定 + 本机自动备份信息）。
    LaunchedEffect(Unit) {
        viewModel.refreshDataSafety()
    }

    var showRestoreConfirm by remember { mutableStateOf(false) }

    var password by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingExportPassword by remember { mutableStateOf("") }
    var pendingExportOptions by remember { mutableStateOf(BackupOptions()) }
    var exportOptions by remember { mutableStateOf(BackupOptions()) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            val pw = pendingExportPassword
            val opts = pendingExportOptions
            scope.launch {
                val os = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri) }
                if (os != null) {
                    viewModel.export(pw, opts, os)
                } else {
                    Toast.makeText(context, context.getString(R.string.backup_write_failed, ""), Toast.LENGTH_LONG).show()
                    viewModel.reset()
                }
            }
        } else {
            viewModel.reset()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            pendingAction = null
            return@rememberLauncherForActivityResult
        }
        pendingImportUri = uri
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        LegacyDataRecoveryBanner()
        DataLossAlertBanner(
            verdict = dataSafety.verdict,
            hasBackup = dataSafety.backupCount > 0,
            onRestore = { showRestoreConfirm = true }
        )
        AutoBackupCard(
            lastBackupTime = dataSafety.lastBackupTime,
            backupCount = dataSafety.backupCount,
            working = dataSafety.working,
            onBackupNow = { viewModel.backupNow() }
        )
        BackupInfoCard()
        ActionCard(
            icon = FeatherIcons.Download,
            title = stringResource(R.string.backup_export_title),
            subtitle = stringResource(R.string.backup_export_subtitle),
            enabled = state !is BackupState.Working,
            onClick = { pendingAction = PendingAction.ExportOptions }
        )
        ActionCard(
            icon = FeatherIcons.Upload,
            title = stringResource(R.string.backup_import_title),
            subtitle = stringResource(R.string.backup_import_subtitle),
            enabled = state !is BackupState.Working,
            onClick = {
                pendingAction = PendingAction.Import
                importLauncher.launch(arrayOf("application/octet-stream", "application/gzip", "*/*"))
            }
        )
    }

    // 导出：先选数据范围
    if (pendingAction == PendingAction.ExportOptions) {
        ExportOptionsDialog(
            options = exportOptions,
            onOptionsChange = { exportOptions = it },
            onConfirm = {
                pendingAction = PendingAction.ExportPassword
            },
            onDismiss = {
                pendingAction = null
            }
        )
    }

    // 导出：再输口令（可留空）
    if (pendingAction == PendingAction.ExportPassword) {
        PasswordDialog(
            title = stringResource(R.string.backup_set_password),
            subtitle = stringResource(R.string.backup_password_hint),
            confirmText = stringResource(R.string.backup_export_btn),
            password = password,
            onPasswordChange = { password = it },
            onConfirm = {
                pendingExportPassword = password
                pendingExportOptions = exportOptions
                password = ""
                pendingAction = null
                exportLauncher.launch("rcodecore-backup-${System.currentTimeMillis()}.tar.gz")
            },
            onDismiss = {
                password = ""
                pendingAction = null
            }
        )
    }

    // 导入口令弹窗（SAF 选完文件后弹出）
    if (pendingAction == PendingAction.Import && pendingImportUri != null) {
        PasswordDialog(
            title = stringResource(R.string.backup_password_input),
            subtitle = stringResource(R.string.backup_password_optional_hint),
            confirmText = stringResource(R.string.backup_import_btn),
            password = password,
            onPasswordChange = { password = it },
            onConfirm = {
                val pw = password
                val uri = pendingImportUri
                password = ""
                pendingAction = null
                pendingImportUri = null
                if (uri != null) viewModel.import(uri, pw)
            },
            onDismiss = {
                password = ""
                pendingAction = null
                pendingImportUri = null
            }
        )
    }

    // 导出完成 → 提示并复位
    LaunchedEffect(state) {
        if (state is BackupState.ExportDone) {
            Toast.makeText(context, context.getString(R.string.backup_exported), Toast.LENGTH_SHORT).show()
            viewModel.reset()
        }
    }

    // 立即备份完成 → 提示
    LaunchedEffect(dataSafety.justBackedUp) {
        if (dataSafety.justBackedUp) {
            Toast.makeText(context, context.getString(R.string.backup_auto_backed_up), Toast.LENGTH_SHORT).show()
        }
    }

    // 从自动备份恢复前确认
    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text(stringResource(R.string.backup_auto_restore_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.backup_auto_restore_confirm_desc,
                        formatBackupTime(dataSafety.lastBackupTime)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    viewModel.restoreFromLatest()
                }) { Text(stringResource(R.string.backup_auto_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (state is BackupState.Working) {
        ProgressDialog()
    }

    when (state) {
        is BackupState.Error -> ResultDialog(
            title = stringResource(R.string.backup_operation_failed),
            message = (state as BackupState.Error).message,
            onDismiss = { viewModel.reset() }
        )
        is BackupState.ImportSuccess -> ResultDialog(
            title = stringResource(R.string.backup_import_done),
            message = buildImportSummary(context, (state as BackupState.ImportSuccess).stats),
            onDismiss = { viewModel.reset() }
        )
        else -> {}
    }
}

private enum class PendingAction { ExportOptions, ExportPassword, Import }

/**
 * 数据丢失 / 包名变更告警横幅（数据保全防线 D8）。
 *
 * 哨兵（DataSentinel）判定本包名下数据疑似为空（DATA_LOST）或包名被改动（PACKAGE_CHANGED）时展示，
 * 并给出「从最近自动备份恢复」入口（本机有自动备份时）。
 */
@Composable
private fun DataLossAlertBanner(
    verdict: SentinelVerdict?,
    hasBackup: Boolean,
    onRestore: () -> Unit
) {
    if (verdict != SentinelVerdict.DATA_LOST && verdict != SentinelVerdict.PACKAGE_CHANGED) return

    val isPackageChanged = verdict == SentinelVerdict.PACKAGE_CHANGED
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1F2)),
        border = BorderStroke(1.dp, Color(0xFFFECDD3))
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = stringResource(
                    if (isPackageChanged) R.string.backup_package_changed_title
                    else R.string.backup_data_lost_title
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(
                    if (isPackageChanged) R.string.backup_package_changed_desc
                    else R.string.backup_data_lost_desc
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hasBackup) {
                TextButton(onClick = onRestore) {
                    Text(stringResource(R.string.backup_auto_restore))
                }
            }
        }
    }
}

/** 本机自动备份卡片（数据保全防线 D6）：展示备份时间/份数，支持立即备份。 */
@Composable
private fun AutoBackupCard(
    lastBackupTime: Long?,
    backupCount: Int,
    working: Boolean,
    onBackupNow: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
        border = BorderStroke(1.dp, Color(0xFFBBF7D0))
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = stringResource(R.string.backup_auto_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.backup_auto_desc, AutoBackupKeepMax),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (lastBackupTime != null) {
                    stringResource(R.string.backup_auto_last, formatBackupTime(lastBackupTime))
                } else {
                    stringResource(R.string.backup_auto_none_label)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onBackupNow,
                enabled = !working
            ) {
                Text(stringResource(R.string.backup_auto_backup_now))
            }
        }
    }
}

/** 自动备份保留份数（与 AutoBackupManager.KEEP_MAX 一致）。 */
private const val AutoBackupKeepMax = 7

/** 备份时间戳格式化：yyyy-MM-dd HH:mm。 */
private fun formatBackupTime(epochMs: Long?): String {
    val ts = epochMs ?: return ""
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}

/**
 * 历史数据恢复横幅：检测到"同签名但包名不同"的旧版本应用仍安装在本机时展示。
 *
 * 背景：applicationId 三次变更（com.aicodeeditor → com.deep.rcode → com.R.codecore），
 * 每次变更是完全不同的 App，新包名全新安装 → 旧包的历史对话不会自动迁移，用户以为"历史被清空"。
 * 本横幅提示用户：在旧版本中导出备份（含聊天历史），再在本版本导入备份即可找回。
 */
@Composable
private fun LegacyDataRecoveryBanner() {
    val context = LocalContext.current
    val legacyPackage = remember { detectSameSignatureLegacyPackage(context) }
    if (legacyPackage == null) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
        border = BorderStroke(1.dp, Color(0xFFFDE68A))
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = stringResource(R.string.backup_legacy_found_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.backup_legacy_found_desc, legacyPackage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 历史遗留包名（禁止修改 applicationId 回退到这些包名，否则用户数据再次隔离）。 */
private val LEGACY_APPLICATION_IDS = listOf("com.aicodeeditor", "com.deep.rcode")

/**
 * 检测是否存在与本应用"同签名但包名不同"的旧版本应用。
 * 若存在则返回其包名；否则返回 null。
 */
private fun detectSameSignatureLegacyPackage(context: Context): String? {
    val pm = context.packageManager
    val currentSig = runCatching {
        packageSignature(pm, context.packageName)
    }.getOrNull() ?: return null
    for (pkg in LEGACY_APPLICATION_IDS) {
        if (pkg == context.packageName) continue
        val sig = runCatching { packageSignature(pm, pkg) }.getOrNull() ?: continue
        if (sig.contentEquals(currentSig)) return pkg
    }
    return null
}

private fun packageSignature(pm: PackageManager, packageName: String): ByteArray? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                ?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        }.getOrNull()
    } else {
        @Suppress("DEPRECATION")
        runCatching {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                ?.signatures?.firstOrNull()?.toByteArray()
        }.getOrNull()
    }

@Composable
private fun BackupInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(R.string.backup_section_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.backup_section_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ExportOptionsDialog(
    options: BackupOptions,
    onOptionsChange: (BackupOptions) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_select_data)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OptionRow(stringResource(R.string.common_ai_providers), options.providers) { onOptionsChange(options.copy(providers = it)) }
                OptionRow(stringResource(R.string.backup_data_git_credentials), options.gitCredentials) { onOptionsChange(options.copy(gitCredentials = it)) }
                OptionRow(stringResource(R.string.backup_data_remote), options.remoteConnections) { onOptionsChange(options.copy(remoteConnections = it)) }
                OptionRow(stringResource(R.string.backup_data_chat_history), options.chatHistory) { onOptionsChange(options.copy(chatHistory = it)) }
                OptionRow(stringResource(R.string.backup_data_mcp), options.mcpServers) { onOptionsChange(options.copy(mcpServers = it)) }
                OptionRow(stringResource(R.string.backup_data_permissions), options.permissionRules) { onOptionsChange(options.copy(permissionRules = it)) }
                OptionRow(stringResource(R.string.backup_data_app_settings), options.appSettings) { onOptionsChange(options.copy(appSettings = it)) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.backup_next)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun OptionRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PasswordDialog(
    title: String,
    subtitle: String,
    confirmText: String,
    password: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.backup_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun ProgressDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.backup_processing)) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.backup_processing_data))
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ResultDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) } }
    )
}

private fun buildImportSummary(context: android.content.Context, stats: com.R.codecore.feature.backup.domain.RestoreStats): String = buildString {
    appendLine(context.getString(R.string.backup_restored_data))
    if (stats.providers > 0) appendLine(context.getString(R.string.backup_stat_providers, stats.providers))
    if (stats.gitCredentials > 0) appendLine(context.getString(R.string.backup_stat_git_credentials, stats.gitCredentials))
    if (stats.remoteConnections > 0) appendLine(context.getString(R.string.backup_stat_remote_connections, stats.remoteConnections))
    if (stats.remoteMounts > 0) appendLine(context.getString(R.string.backup_stat_remote_mounts, stats.remoteMounts))
    if (stats.chatSessions > 0) appendLine(context.getString(R.string.backup_stat_chat_sessions, stats.chatSessions))
    if (stats.agentMessages > 0) appendLine(context.getString(R.string.backup_stat_chat_messages, stats.agentMessages))
    if (stats.todoItems > 0) appendLine(context.getString(R.string.backup_stat_todo_items, stats.todoItems))
    if (stats.mcpServers > 0) appendLine(context.getString(R.string.backup_stat_mcp_servers, stats.mcpServers))
    if (stats.globalPermissionRules > 0) appendLine(context.getString(R.string.backup_stat_permission_rules, stats.globalPermissionRules))
    append(context.getString(R.string.backup_settings_covered))
}
