package com.R.codecore.feature.workspace.presentation.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.FeatherIcons
import compose.icons.feathericons.Info
import compose.icons.feathericons.Server
import compose.icons.feathericons.Settings
import androidx.compose.ui.res.stringResource
import com.R.codecore.R

@Composable
fun WiFiFtpServerSection(viewModel: RemoteServerViewModel) {
    val isRunning by viewModel.ftpServerManager.isRunning.collectAsStateWithLifecycle()
    val serverUrl by viewModel.ftpServerManager.serverUrl.collectAsStateWithLifecycle()
    val port by viewModel.ftpServerManager.port.collectAsStateWithLifecycle()
    val username by viewModel.ftpServerManager.username.collectAsStateWithLifecycle()
    val password by viewModel.ftpServerManager.password.collectAsStateWithLifecycle()
    val isAnonymous by viewModel.ftpServerManager.isAnonymous.collectAsStateWithLifecycle()
    val autoStart by viewModel.ftpServerManager.autoStart.collectAsStateWithLifecycle()
    val errorMessage by viewModel.ftpServerManager.errorMessage.collectAsStateWithLifecycle()

    var editPort by remember(port) { mutableStateOf(port.toString()) }
    var editUsername by remember(username) { mutableStateOf(username) }
    var editPassword by remember(password) { mutableStateOf(password) }
    var editAnonymous by remember(isAnonymous) { mutableStateOf(isAnonymous) }
    var editAutoStart by remember(autoStart) { mutableStateOf(autoStart) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    FeatherIcons.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = stringResource(R.string.ftp_usage_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.ftp_usage_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

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
                            FeatherIcons.Server,
                            contentDescription = null,
                            tint = if (isRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "FTP",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isRunning) stringResource(R.string.ftp_running, serverUrl) else stringResource(R.string.ftp_not_running),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    Switch(
                        checked = isRunning,
                        onCheckedChange = { viewModel.toggleFtpServer() }
                    )
                }

                if (errorMessage != null) {
                    val error = errorMessage
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = context.getString(R.string.ftp_error, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        FeatherIcons.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.ftp_config_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                OutlinedTextField(
                    value = editPort,
                    onValueChange = { editPort = it.filter { char -> char.isDigit() } },
                    label = { Text(stringResource(R.string.ftp_listen_port)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = editUsername,
                    onValueChange = { editUsername = it },
                    label = { Text(stringResource(R.string.ftp_login_username)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !editAnonymous
                )

                OutlinedTextField(
                    value = editPassword,
                    onValueChange = { editPassword = it },
                    label = { Text(stringResource(R.string.ftp_login_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !editAnonymous
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.ftp_allow_anonymous), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = stringResource(R.string.ftp_anonymous_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = editAnonymous, onCheckedChange = { editAnonymous = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.ftp_auto_start), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = stringResource(R.string.ftp_auto_start_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = editAutoStart, onCheckedChange = { editAutoStart = it })
                }

                Button(
                    onClick = {
                        val p = editPort.toIntOrNull() ?: 2121
                        viewModel.saveFtpServerConfig(p, editUsername, editPassword, editAnonymous, editAutoStart)
                        android.widget.Toast.makeText(context, context.getString(R.string.ftp_config_saved), android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.ftp_save_config))
                }
            }
        }
    }
}
