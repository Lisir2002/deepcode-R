package com.R.codecore.feature.workspace.presentation.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.DocumentsContract
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.workspace.domain.model.RemoteConnection
import com.R.codecore.feature.workspace.domain.model.RemoteMount
import com.R.codecore.feature.workspace.domain.model.RemoteProtocol
import compose.icons.FeatherIcons
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.Folder
import androidx.compose.ui.res.stringResource
import com.R.codecore.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRemoteConnectionDialog(
    initialConnection: RemoteConnection? = null,
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String, String, RemoteProtocol) -> Unit,
    onTestConnection: (String, String, String, String, RemoteProtocol, (Boolean, String) -> Unit) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var name by remember(initialConnection) { mutableStateOf(initialConnection?.name ?: "") }
    var host by remember(initialConnection) { mutableStateOf(initialConnection?.host ?: "") }
    var port by remember(initialConnection) { mutableStateOf(initialConnection?.port?.toString() ?: "22") }
    var username by remember(initialConnection) { mutableStateOf(initialConnection?.username ?: "") }
    var password by remember(initialConnection) { mutableStateOf(initialConnection?.password ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var protocol by remember(initialConnection) { mutableStateOf(initialConnection?.protocol ?: RemoteProtocol.SFTP) }
    var isTesting by remember { mutableStateOf(false) }
    val isLocal = protocol == RemoteProtocol.LOCAL

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = uriToFilePath(context, uri)
            if (path != null) host = path
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (initialConnection != null) stringResource(R.string.remote_edit_connection) else stringResource(R.string.remote_add_connection),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.xs)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.remote_protocol_type), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.width(12.dp))
                FilterChip(
                    selected = protocol == RemoteProtocol.SFTP,
                    onClick = { protocol = RemoteProtocol.SFTP; port = "22" },
                    label = { Text("SFTP") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = protocol == RemoteProtocol.FTP,
                    onClick = { protocol = RemoteProtocol.FTP; port = "21" },
                    label = { Text("FTP") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = protocol == RemoteProtocol.LOCAL,
                    onClick = { protocol = RemoteProtocol.LOCAL; port = "0"; username = "local"; password = "" },
                    label = { Text(stringResource(R.string.common_local)) }
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(if (isLocal) stringResource(R.string.remote_channel_name_hint) else stringResource(R.string.remote_connection_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(if (isLocal) stringResource(R.string.remote_internal_dir) else stringResource(R.string.remote_host_address)) },
                placeholder = if (isLocal) {
                    { Text("/storage/emulated/0/RCodeCore/projects") }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = if (isLocal) {
                    {
                        IconButton(onClick = { folderPicker.launch(null) }) {
                            Icon(FeatherIcons.Folder, contentDescription = stringResource(R.string.remote_select_dir), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else null
            )
            if (!isLocal) {
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.remote_port)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text(stringResource(R.string.common_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.remote_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        val image = if (passwordVisible) FeatherIcons.Eye else FeatherIcons.EyeOff
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(image, stringResource(R.string.remote_toggle_password), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                )
            } else {
                Text(
                    text = stringResource(R.string.remote_internal_dir_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    isTesting = true
                    onTestConnection(host, port, username, password, protocol) { success, msg ->
                        isTesting = false
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isTesting && host.isNotBlank() && (isLocal || username.isNotBlank())
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (isLocal) stringResource(R.string.remote_test_dir) else stringResource(R.string.remote_test_connection))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(Spacing.sm))
                Button(
                    onClick = {
                        onAdd(name, host, port, username, password, protocol)
                    },
                    enabled = host.isNotBlank() && (isLocal || username.isNotBlank())
                ) {
                    Text(if (initialConnection != null) stringResource(R.string.common_save) else stringResource(R.string.common_add))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRemoteMountDialog(
    initialMount: RemoteMount? = null,
    connections: List<RemoteConnection>,
    workspaces: List<com.R.codecore.feature.workspace.domain.model.Workspace>,
    onDismiss: () -> Unit,
    onAdd: (String, String, String, Boolean) -> Unit,
    onListDirectories: (String, String, (Boolean, List<String>, String) -> Unit) -> Unit
) {
    var selectedConnectionId by remember(initialMount) { mutableStateOf(initialMount?.connectionId ?: connections.firstOrNull()?.id ?: "") }
    var remotePath by remember(initialMount) { mutableStateOf(initialMount?.remotePath ?: "/") }

    var selectedWorkspacePath by remember(initialMount) { mutableStateOf(initialMount?.localMountPath ?: workspaces.firstOrNull()?.path ?: "") }
    var autoConnect by remember(initialMount) { mutableStateOf(initialMount?.autoConnect ?: true) }

    var connExpanded by remember { mutableStateOf(false) }
    var wsExpanded by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
    val selectedConnection = connections.find { it.id == selectedConnectionId }
    val isLocalConnection = selectedConnection?.protocol == RemoteProtocol.LOCAL

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (initialMount != null) stringResource(R.string.remote_edit_workspace) else stringResource(R.string.remote_add_workspace),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.xs)
            )

            ExposedDropdownMenuBox(
                expanded = connExpanded,
                onExpandedChange = { connExpanded = !connExpanded }
            ) {
                val selectedName = connections.find { it.id == selectedConnectionId }?.name ?: stringResource(R.string.remote_select_channel)
                OutlinedTextField(
                    value = selectedName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.remote_link_channel)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = connExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = connExpanded,
                    onDismissRequest = { connExpanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    connections.forEach { conn ->
                        DropdownMenuItem(
                            text = { Text(conn.name) },
                            onClick = {
                                selectedConnectionId = conn.id
                                if (conn.protocol == RemoteProtocol.LOCAL && remotePath.isBlank()) {
                                    remotePath = "/"
                                }
                                connExpanded = false
                            }
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    label = { Text(if (isLocalConnection) stringResource(R.string.remote_mount_subdir) else stringResource(R.string.remote_target_dir)) },
                    placeholder = if (isLocalConnection) {
                        { Text("/") }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { showBrowser = true },
                    enabled = selectedConnectionId.isNotEmpty()
                ) {
                    Icon(FeatherIcons.Folder, contentDescription = stringResource(R.string.remote_browse_dir), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (isLocalConnection) {
                Text(
                    text = stringResource(R.string.remote_local_channel_subdir_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            ExposedDropdownMenuBox(
                expanded = wsExpanded,
                onExpandedChange = { wsExpanded = !wsExpanded }
            ) {
                val selectedWsName = workspaces.find { it.path == selectedWorkspacePath }?.name ?: stringResource(R.string.remote_select_local_workspace)
                OutlinedTextField(
                    value = selectedWsName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.remote_map_to_local)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = wsExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = wsExpanded,
                    onDismissRequest = { wsExpanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    workspaces.forEach { ws ->
                        DropdownMenuItem(
                            text = { Text(ws.name) },
                            onClick = {
                                selectedWorkspacePath = ws.path
                                wsExpanded = false
                            }
                        )
                    }
                }
            }

            if (workspaces.isEmpty()) {
                Text(stringResource(R.string.remote_no_local_workspace), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.remote_auto_connect_on_start), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.remote_auto_connect_and_sync), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoConnect, onCheckedChange = { autoConnect = it })
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(Spacing.sm))
                Button(
                    onClick = {
                        onAdd(selectedConnectionId, remotePath, selectedWorkspacePath, autoConnect)
                    },
                    enabled = selectedWorkspacePath.isNotEmpty() && selectedConnectionId.isNotEmpty()
                ) {
                    Text(if (initialMount != null) stringResource(R.string.common_save) else stringResource(R.string.remote_add_workspace))
                }
            }
        }
    }

    if (showBrowser) {
        RemoteDirectoryBrowserDialog(
            connectionId = selectedConnectionId,
            initialPath = remotePath.ifBlank { "/" },
            onPathSelected = {
                remotePath = it
                showBrowser = false
            },
            onDismiss = { showBrowser = false },
            listDirectories = onListDirectories
        )
    }
}

@Composable
fun RemoteDirectoryBrowserDialog(
    connectionId: String,
    initialPath: String,
    onPathSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    listDirectories: (String, String, (Boolean, List<String>, String) -> Unit) -> Unit
) {
    var currentPath by remember { mutableStateOf(if (initialPath.endsWith("/")) initialPath else "$initialPath/") }
    var directories by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentPath) {
        isLoading = true
        error = null
        listDirectories(connectionId, currentPath) { success, dirs, msg ->
            isLoading = false
            if (success) {
                directories = dirs.sorted()
            } else {
                error = msg
            }
        }
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_select_remote_dir)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp)) {
                Text(stringResource(R.string.remote_current_path, currentPath), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                val loadError = error
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else if (loadError != null) {
                    Text(stringResource(R.string.remote_load_failed, loadError), color = MaterialTheme.colorScheme.error)
                } else {
                    LazyColumn {
                        if (currentPath != "/") {
                            item {
                                TextButton(onClick = {
                                    val parent = currentPath.trimEnd('/').substringBeforeLast('/')
                                    currentPath = if (parent.isEmpty()) "/" else "$parent/"
                                }) {
                                    Icon(FeatherIcons.Folder, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.remote_parent_dir))
                                }
                            }
                        }
                        items(directories) { dir ->
                            TextButton(onClick = {
                                currentPath = if (currentPath == "/") "/$dir/" else "$currentPath$dir/"
                            }) {
                                Icon(FeatherIcons.Folder, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(dir)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onPathSelected(currentPath) }) {
                Text(stringResource(R.string.remote_confirm_select))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

private fun uriToFilePath(context: android.content.Context, uri: Uri): String? {
    if (DocumentsContract.isTreeUri(uri)) {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        if (docId.startsWith("primary:")) {
            val sub = docId.substringAfter("primary:", "")
            return "/storage/emulated/0/" + sub.trimStart('/')
        }
        val parts = docId.split(":")
        if (parts.size >= 2) {
            val storage = parts[0]
            val sub = parts[1]
            return "/storage/$storage/$sub"
        }
    }
    return null
}
