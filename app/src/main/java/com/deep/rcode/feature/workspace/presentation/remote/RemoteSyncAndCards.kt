package com.deep.rcode.feature.workspace.presentation.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deep.rcode.feature.workspace.domain.model.RemoteConnection
import com.deep.rcode.feature.workspace.domain.model.RemoteMount
import com.deep.rcode.feature.workspace.domain.model.RemoteProtocol
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Folder
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Layers
import compose.icons.feathericons.Trash2
import androidx.compose.ui.res.stringResource
import com.deep.rcode.R

@Composable
fun SyncSettingsSection(
    ignoredPatterns: String,
    useGitIgnore: Boolean,
    maxSyncBatchSize: Int,
    onPatternsChange: (String) -> Unit,
    onUseGitIgnoreChange: (Boolean) -> Unit,
    onMaxSyncBatchSizeChange: (Int) -> Unit
) {
    var patternsText by remember(ignoredPatterns) { mutableStateOf(ignoredPatterns) }
    var maxBatchSizeText by remember(maxSyncBatchSize) { mutableStateOf(maxSyncBatchSize.toString()) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        FeatherIcons.FileText,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.sync_ignore_list),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = stringResource(R.string.sync_ignore_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                OutlinedTextField(
                    value = patternsText,
                    onValueChange = { patternsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.sync_ignore_rules)) }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        onPatternsChange(patternsText)
                        android.widget.Toast.makeText(context, context.getString(R.string.sync_ignore_saved), android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.sync_save_rules))
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        FeatherIcons.CheckSquare,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.sync_follow_gitignore),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.sync_gitignore_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                Switch(
                    checked = useGitIgnore,
                    onCheckedChange = {
                        onUseGitIgnoreChange(it)
                        android.widget.Toast.makeText(context, if (it) context.getString(R.string.sync_gitignore_enabled) else context.getString(R.string.sync_gitignore_disabled), android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        FeatherIcons.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.sync_max_batch_size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = stringResource(R.string.sync_batch_size_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                OutlinedTextField(
                    value = maxBatchSizeText,
                    onValueChange = { maxBatchSizeText = it.filter { char -> char.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.sync_max_batch_count)) }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val size = maxBatchSizeText.toIntOrNull() ?: 50
                        onMaxSyncBatchSizeChange(size)
                        android.widget.Toast.makeText(context, context.getString(R.string.sync_batch_size_saved), android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.sync_save_batch_size))
                }
            }
        }
    }
}

@Composable
fun RemoteConnectionCard(
    conn: RemoteConnection,
    onEdit: (RemoteConnection) -> Unit,
    onDelete: (RemoteConnection) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)) {
                    val isLocal = conn.protocol == RemoteProtocol.LOCAL
                    Icon(
                        if (isLocal) FeatherIcons.HardDrive else FeatherIcons.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = conn.name, fontWeight = FontWeight.Normal, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = if (isLocal) "LOCAL://${conn.host}" else "${conn.protocol}://${conn.username}@${conn.host}:${conn.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Row {
                    IconButton(onClick = { onEdit(conn) }) {
                        Icon(FeatherIcons.Edit2, contentDescription = stringResource(R.string.common_edit), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onDelete(conn) }) {
                        Icon(FeatherIcons.Trash2, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteMountCard(
    mount: RemoteMount,
    isFailed: Boolean = false,
    onEdit: (RemoteMount) -> Unit,
    onDelete: (RemoteMount) -> Unit,
    onUpload: (RemoteMount) -> Unit,
    onDownload: (RemoteMount) -> Unit,
    onConnect: (RemoteMount) -> Unit,
    onDisconnect: (RemoteMount) -> Unit
) {
    val isLocal = mount.connection?.protocol == RemoteProtocol.LOCAL
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        FeatherIcons.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.sync_via_connection, mount.connection?.name ?: stringResource(R.string.sync_unknown_connection)),
                            fontWeight = FontWeight.Normal,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val (statusText, statusColor) = when {
                            mount.isActive -> stringResource(R.string.status_connected) to Color(0xFF22C55E)
                            isFailed -> stringResource(R.string.status_connection_failed) to MaterialTheme.colorScheme.error
                            else -> stringResource(R.string.status_disconnected) to Color(0xFFF59E0B)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Row {
                    IconButton(onClick = { onEdit(mount) }) {
                        Icon(FeatherIcons.Edit2, contentDescription = stringResource(R.string.common_edit), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onDelete(mount) }) {
                        Icon(FeatherIcons.Trash2, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (mount.isActive) {
                    TextButton(onClick = { onDisconnect(mount) }) {
                        Text(stringResource(R.string.sync_disconnect))
                    }
                    TextButton(onClick = { onUpload(mount) }) {
                        Text(if (isLocal) stringResource(R.string.sync_all) else stringResource(R.string.sync_upload_all))
                    }
                    if (!isLocal) {
                        TextButton(onClick = { onDownload(mount) }) {
                            Text(stringResource(R.string.sync_download_all))
                        }
                    }
                } else {
                    Button(onClick = { onConnect(mount) }) {
                        Text(stringResource(R.string.sync_connect_and_sync))
                    }
                }
            }
        }
    }
}
