package com.R.codecore.feature.credentials.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.R.codecore.R
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.credentials.domain.model.GitCredential
import com.R.codecore.feature.credentials.domain.model.newCredentialId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff

/**
 * 凭据编辑 BottomSheet 弹窗：从底部弹出编辑/新增 host、用户名、Token。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CredentialEditorSheet(
    initial: GitCredential?,
    onDismiss: () -> Unit,
    onSave: (GitCredential) -> Unit,
    onDelete: ((String) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var host by remember(initial) { mutableStateOf(initial?.host ?: "") }
    var username by remember(initial) { mutableStateOf(initial?.username ?: "") }
    var token by remember(initial) { mutableStateOf(initial?.token ?: "") }
    var label by remember(initial) { mutableStateOf(initial?.label ?: "") }
    var isDefault by remember(initial) { mutableStateOf(initial?.isDefault ?: false) }
    var tokenVisible by remember { mutableStateOf(false) }

    val canSave = host.trim().isNotBlank() && username.trim().isNotBlank() && token.isNotBlank()

    fun current(): GitCredential? {
        if (!canSave) return null
        return GitCredential(
            id = initial?.id ?: newCredentialId(),
            host = host.trim(),
            username = username.trim(),
            token = token,
            label = label.trim(),
            isDefault = isDefault,
            createdAt = initial?.createdAt ?: 0L,
            updatedAt = initial?.updatedAt ?: 0L
        )
    }

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
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (initial == null) stringResource(R.string.credential_add) else stringResource(R.string.credential_edit),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (initial != null && onDelete != null) {
                    IconButton(onClick = {
                        onDelete(initial.id)
                        onDismiss()
                    }) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.credential_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.credential_usage_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(R.string.credential_host)) },
                placeholder = { Text("github.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.common_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.credential_token)) },
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(if (tokenVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (tokenVisible) stringResource(R.string.common_hide) else stringResource(R.string.common_show))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.credential_alias)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.credential_set_default), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.credential_default_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = isDefault, onCheckedChange = { isDefault = it })
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
                        current()?.let {
                            onSave(it)
                            onDismiss()
                        }
                    },
                    enabled = canSave
                ) {
                    Text(if (initial != null) stringResource(R.string.common_save) else stringResource(R.string.common_add))
                }
            }
        }
    }
}

/**
 * 保留原本 CredentialEditorScreen 用于兼容调用。
 */
@Composable
internal fun CredentialEditorScreen(
    initial: GitCredential?,
    onBack: () -> Unit,
    onSave: (GitCredential) -> Unit,
    onDelete: (String) -> Unit
) {
    CredentialEditorSheet(
        initial = initial,
        onDismiss = onBack,
        onSave = onSave,
        onDelete = onDelete
    )
}
