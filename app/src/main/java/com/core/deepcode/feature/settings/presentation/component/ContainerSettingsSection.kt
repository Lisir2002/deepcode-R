package com.core.deepcode.feature.settings.presentation.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.core.theme.Radius
import com.core.deepcode.core.theme.Spacing
import com.core.deepcode.feature.agent.domain.container.ContainerArch
import com.core.deepcode.feature.agent.domain.container.ContainerProfile
import com.core.deepcode.feature.agent.domain.container.RootfsSource
import com.core.deepcode.feature.settings.data.repository.ExecutionMode
import com.core.deepcode.feature.workspace.domain.model.RemoteConnection
import com.core.deepcode.feature.workspace.domain.model.RemoteProtocol
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.core.deepcode.R

/**
 * 容器镜像二级页：列出内置与自定义 profile，单选切换；新建（本地镜像导入 tar.gz + 填启动参数，或远程 SSH 复用工作区通道）；
 * 删除自定义（本地镜像连带清理其 rootfs 目录，远程 SSH 无 rootfs）。
 *
 * 选中某个 profile 时按其 [ContainerProfile.mode] 同步切全局执行模式——本地镜像走 PRoot 容器，
 * 远程 SSH 镜像走 SSH exec/SFTP。内置 Alpine 默认本地模式。
 */
@Composable
internal fun ContainerSection(
    profiles: List<ContainerProfile>,
    activeProfileId: String,
    showAddSheetExternal: Boolean = false,
    onDismissAddSheet: () -> Unit = {},
    onSelect: (String) -> Unit,
    onSaveCustom: (ContainerProfile) -> Unit,
    onEditCustom: (ContainerProfile) -> Unit,
    onDeleteCustom: (ContainerProfile) -> Unit,
    onSwitchConfirmed: () -> Unit = {},
    onResetBuiltin: (ContainerProfile) -> Unit = {},
    remoteConnections: List<RemoteConnection> = emptyList(),
    storageShareEnabled: Boolean = false,
    onStorageShareChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var showAddSheetInternal by remember { mutableStateOf(false) }
    val showAddSheet = showAddSheetInternal || showAddSheetExternal
    var editingProfile by remember { mutableStateOf<ContainerProfile?>(null) }
    var deletingProfile by remember { mutableStateOf<ContainerProfile?>(null) }
    var pendingSwitch by remember { mutableStateOf<ContainerProfile?>(null) }
    var pendingReset by remember { mutableStateOf<ContainerProfile?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item(key = "storage_share") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.md),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(
                    1.dp,
                    if (storageShareEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.container_share_storage),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.container_share_storage_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.xs)
                        )
                    }
                    Switch(
                        checked = storageShareEnabled,
                        onCheckedChange = onStorageShareChange
                    )
                }
            }
        }

        items(profiles, key = { it.id }) { profile ->
            val active = profile.id == activeProfileId
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.md),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(
                    1.dp,
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!active) pendingSwitch = profile
                        }
                        .padding(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = profileSubtitle(context, profile, remoteConnections),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.xs)
                        )
                        if (profile.mode == ExecutionMode.LOCAL_PROOT && profile.extraBindings.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.container_bindings, profile.extraBindings.joinToString(" ")),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!profile.isBuiltin) {
                        IconButton(onClick = { editingProfile = profile }) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = stringResource(R.string.common_edit),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { deletingProfile = profile }) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        IconButton(onClick = { pendingReset = profile }) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.container_reset),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        ProfileEditSheet(
            initial = null,
            remoteConnections = remoteConnections,
            onDismiss = {
                showAddSheetInternal = false
                onDismissAddSheet()
            },
            onConfirm = { profile ->
                val id = "custom-${System.currentTimeMillis()}"
                onSaveCustom(
                    if (profile.mode == ExecutionMode.REMOTE_SSH) {
                        profile.copy(id = id, name = profile.name.ifBlank { context.getString(R.string.container_remote_ssh) })
                    } else {
                        profile.copy(id = id, name = profile.name.ifBlank { context.getString(R.string.container_custom_image) })
                    }
                )
                showAddSheetInternal = false
                onDismissAddSheet()
            }
        )
    }

    editingProfile?.let { editing ->
        ProfileEditSheet(
            initial = editing,
            remoteConnections = remoteConnections,
            onDismiss = { editingProfile = null },
            onConfirm = { profile ->
                onEditCustom(profile.copy(id = editing.id))
                editingProfile = null
            }
        )
    }

    deletingProfile?.let { deleting ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            title = { Text(stringResource(R.string.container_delete_config)) },
            text = { Text(stringResource(R.string.container_delete_confirm, deleting.name, if (deleting.mode == ExecutionMode.LOCAL_PROOT && !deleting.isBuiltin) stringResource(R.string.container_rootfs_will_be_cleared) else "")) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteCustom(deleting)
                    deletingProfile = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingProfile = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    pendingSwitch?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text(stringResource(R.string.container_switch_image)) },
            text = { Text(stringResource(R.string.container_switch_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onSwitchConfirmed()
                    onSelect(target.id)
                    pendingSwitch = null
                }) { Text(stringResource(R.string.common_switch)) }
            },
            dismissButton = { TextButton(onClick = { pendingSwitch = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    pendingReset?.let { resetting ->
        AlertDialog(
            onDismissRequest = { pendingReset = null },
            title = { Text(stringResource(R.string.container_reset_builtin)) },
            text = { Text(stringResource(R.string.container_reset_confirm, resetting.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onResetBuiltin(resetting)
                    pendingReset = null
                }) { Text(stringResource(R.string.container_reset)) }
            },
            dismissButton = { TextButton(onClick = { pendingReset = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

/** 镜像列表项副标题：按 mode 与来源类型描述。 */
private fun profileSubtitle(context: Context, profile: ContainerProfile, connections: List<RemoteConnection>): String {
    return when {
        profile.isBuiltin && profile.arch == ContainerArch.X86_64 ->
            context.getString(R.string.container_builtin_x86)
        profile.isBuiltin -> context.getString(R.string.container_builtin_auto)
        profile.mode == ExecutionMode.REMOTE_SSH -> {
            val ssh = profile.rootfsSource as? RootfsSource.RemoteSsh
            val connName = ssh?.connectionId?.let { cid -> connections.firstOrNull { it.id == cid }?.name }
            context.getString(R.string.container_remote_ssh_desc, connName ?: context.getString(R.string.container_channel_deleted), ssh?.remoteWorkspacePath ?: "")
        }
        else -> {
            val shellDesc = profile.shellPath?.ifBlank { null } ?: "/bin/sh"
            context.getString(R.string.container_imported_desc, shellDesc)
        }
    }
}

/**
 * 添加/编辑镜像的 ModalBottomSheet：顶部 SegmentedButton 切换本地镜像 / 远程 SSH。
 * 本地镜像分支：名称、shell 路径、额外绑定、额外参数、选 tar.gz 文件。
 * 远程 SSH 分支：名称、下拉选工作区已配置的 SFTP 通道、远程工作区路径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditSheet(
    initial: ContainerProfile?,
    remoteConnections: List<RemoteConnection>,
    onDismiss: () -> Unit,
    onConfirm: (ContainerProfile) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // SFTP 通道才适合 SSH exec（FTP/LOCAL 不走 sshj）
    val sshConnections = remoteConnections.filter { it.protocol == RemoteProtocol.SFTP }

    var mode by remember { mutableStateOf(initial?.mode ?: ExecutionMode.LOCAL_PROOT) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    // 本地镜像字段
    var shellPath by remember { mutableStateOf(initial?.shellPath ?: "/bin/sh") }
    var bindingsText by remember { mutableStateOf(initial?.extraBindings?.joinToString(" ") ?: "") }
    var argsText by remember { mutableStateOf(initial?.extraArgs?.joinToString(" ") ?: "") }
    val initialUri = (initial?.rootfsSource as? RootfsSource.LocalFile)?.uri
    var pickedUri by remember { mutableStateOf(initialUri) }
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pickedUri = uri.toString() }
    // 远程 SSH 字段
    val initialSsh = (initial?.rootfsSource as? RootfsSource.RemoteSsh)
    var selectedConnId by remember { mutableStateOf(initialSsh?.connectionId ?: sshConnections.firstOrNull()?.id ?: "") }
    var remotePath by remember { mutableStateOf(initialSsh?.remoteWorkspacePath ?: "") }
    var connExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (initial == null) stringResource(R.string.container_add_image) else stringResource(R.string.container_edit_image),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == ExecutionMode.LOCAL_PROOT,
                    onClick = { mode = ExecutionMode.LOCAL_PROOT },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text(stringResource(R.string.container_local_image)) }
                SegmentedButton(
                    selected = mode == ExecutionMode.REMOTE_SSH,
                    onClick = { mode = ExecutionMode.REMOTE_SSH },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text(stringResource(R.string.container_remote_ssh)) }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.common_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (mode == ExecutionMode.LOCAL_PROOT) {
                OutlinedTextField(
                    value = shellPath,
                    onValueChange = { shellPath = it },
                    label = { Text(stringResource(R.string.container_shell_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bindingsText,
                    onValueChange = { bindingsText = it },
                    label = { Text(stringResource(R.string.container_extra_bindings)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = argsText,
                    onValueChange = { argsText = it },
                    label = { Text(stringResource(R.string.container_extra_proot_args)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.size(Spacing.xs))
                TextButton(
                    onClick = { pickLauncher.launch(arrayOf("*/*")) }
                ) {
                    Text(
                        pickedUri?.let {
                            if (it == initialUri) stringResource(R.string.container_imported_click) else stringResource(R.string.container_file_selected)
                        } ?: stringResource(R.string.container_select_image_file)
                    )
                }
            } else {
                if (sshConnections.isEmpty()) {
                    Text(
                        text = stringResource(R.string.container_no_sftp_channel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = connExpanded,
                        onExpandedChange = { connExpanded = !connExpanded }
                    ) {
                        val selectedName = sshConnections.firstOrNull { it.id == selectedConnId }?.name
                            ?: stringResource(R.string.container_select_ssh_channel)
                        OutlinedTextField(
                            value = selectedName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.container_ssh_channel)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = connExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = connExpanded,
                            onDismissRequest = { connExpanded = false }
                        ) {
                            sshConnections.forEach { conn ->
                                DropdownMenuItem(
                                    text = { Text("${conn.name} (${conn.host}:${conn.port})") },
                                    onClick = {
                                        selectedConnId = conn.id
                                        if (remotePath.isBlank()) {
                                            remotePath = "/home/${conn.username}/workspace"
                                        }
                                        connExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = remotePath,
                        onValueChange = { remotePath = it },
                        label = { Text(stringResource(R.string.container_remote_workspace_path)) },
                        placeholder = { Text("/home/user/workspace") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.container_remote_workspace_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.size(Spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Spacer(Modifier.width(Spacing.sm))
                Button(
                    onClick = {
                        val profile = buildProfile(
                            mode = mode,
                            name = name,
                            shellPath = shellPath,
                            bindingsText = bindingsText,
                            argsText = argsText,
                            pickedUri = pickedUri,
                            selectedConnId = selectedConnId,
                            remotePath = remotePath
                        )
                        if (profile != null) onConfirm(profile)
                    },
                    enabled = canConfirm(mode, pickedUri, selectedConnId, sshConnections)
                ) { Text(if (initial == null) stringResource(R.string.common_add) else stringResource(R.string.common_save)) }
            }
        }
    }
}

/** 据表单状态构造 ContainerProfile；校验不通过返回 null（按钮已 disabled，此处再兜底）。 */
private fun buildProfile(
    mode: ExecutionMode,
    name: String,
    shellPath: String,
    bindingsText: String,
    argsText: String,
    pickedUri: String?,
    selectedConnId: String,
    remotePath: String
): ContainerProfile? {
    return when (mode) {
        ExecutionMode.LOCAL_PROOT -> {
            if (pickedUri == null) return null
            val bindings = bindingsText.split(' ').map { it.trim() }.filter { it.isNotEmpty() }
            val args = argsText.split(' ').map { it.trim() }.filter { it.isNotEmpty() }
            ContainerProfile(
                id = "", // 由调用方覆写
                name = name,
                rootfsSource = RootfsSource.LocalFile(pickedUri),
                shellPath = shellPath.ifBlank { null },
                extraBindings = bindings,
                extraArgs = args,
                isBuiltin = false,
                mode = ExecutionMode.LOCAL_PROOT
            )
        }

        ExecutionMode.REMOTE_SSH -> {
            if (selectedConnId.isBlank()) return null
            ContainerProfile(
                id = "", // 由调用方覆写
                name = name,
                rootfsSource = RootfsSource.RemoteSsh(selectedConnId, remotePath),
                shellPath = null,
                isBuiltin = false,
                mode = ExecutionMode.REMOTE_SSH
            )
        }
    }
}

/** 保存按钮可用条件：本地镜像需选了文件，远程 SSH 需选了通道。 */
private fun canConfirm(
    mode: ExecutionMode,
    pickedUri: String?,
    selectedConnId: String,
    sshConnections: List<RemoteConnection>
): Boolean = when (mode) {
    ExecutionMode.LOCAL_PROOT -> pickedUri != null
    ExecutionMode.REMOTE_SSH -> sshConnections.isNotEmpty() && selectedConnId.isNotBlank()
}
