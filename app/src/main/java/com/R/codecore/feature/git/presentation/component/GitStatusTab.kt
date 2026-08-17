package com.R.codecore.feature.git.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.R.codecore.R
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.git.domain.model.GitFileChange
import com.R.codecore.feature.git.domain.model.GitStatus
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.DownloadCloud
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Minus
import compose.icons.feathericons.Plus
import compose.icons.feathericons.UploadCloud

@Composable
internal fun StatusTab(
    status: GitStatus?,
    busy: Boolean,
    hasRemote: Boolean,
    hasIdentity: Boolean,
    onStage: (String) -> Unit,
    onUnstage: (String) -> Unit,
    onStageAll: () -> Unit,
    onCommit: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onFileDiff: (String) -> Unit
) {
    val s = status
    val clean = s == null || (s.staged.isEmpty() && s.unstaged.isEmpty() && s.untracked.isEmpty())

    Column(Modifier.fillMaxSize()) {
        StatusOverview(status = s, clean = clean)
        StatusActionsBar(
            busy = busy,
            hasStagedChanges = s?.staged?.isNotEmpty() == true,
            hasRemote = hasRemote,
            hasIdentity = hasIdentity,
            onStageAll = onStageAll,
            onCommit = onCommit,
            onPull = onPull,
            onPush = onPush
        )

        HorizontalDivider()

        if (clean) {
            EmptyState(stringResource(R.string.git_clean_with_changes))
        } else {
            val ss = s ?: return
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Spacing.xl)
            ) {
                if (ss.staged.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.git_staged_count, ss.staged.size)) }
                    items(ss.staged, key = { "s-${it.path}" }) { f ->
                        FileRow(f, actionIcon = FeatherIcons.Minus, actionDesc = stringResource(R.string.git_unstage), onAction = { onUnstage(f.path) }, enabled = !busy)
                    }
                }
                if (ss.unstaged.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.git_modified_count, ss.unstaged.size)) }
                    items(ss.unstaged, key = { "u-${it.path}" }) { f ->
                        FileRow(f, actionIcon = FeatherIcons.Plus, actionDesc = stringResource(R.string.git_stage), onAction = { onStage(f.path) }, enabled = !busy, onClick = { onFileDiff(f.path) })
                    }
                }
                if (ss.untracked.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.git_untracked_count, ss.untracked.size)) }
                    items(ss.untracked, key = { it }) { path ->
                        FileRow(
                            file = GitFileChange(path, "?", staged = false),
                            actionIcon = FeatherIcons.Plus,
                            actionDesc = stringResource(R.string.git_stage),
                            onAction = { onStage(path) },
                            enabled = !busy
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusOverview(status: GitStatus?, clean: Boolean) {
    val staged = status?.staged?.size ?: 0
    val modified = status?.unstaged?.size ?: 0
    val untracked = status?.untracked?.size ?: 0

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            FeatherIcons.GitBranch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (clean) stringResource(R.string.git_clean) else stringResource(R.string.git_has_changes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = status?.branch ?: stringResource(R.string.git_no_branch),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (status != null && (status.ahead > 0 || status.behind > 0)) {
                    Spacer(Modifier.width(Spacing.sm))
                    SyncPill(ahead = status.ahead, behind = status.behind)
                }
            }

            Spacer(Modifier.height(Spacing.md))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatusMetric(stringResource(R.string.git_staged_label), staged, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                StatusMetric(stringResource(R.string.git_modified_label), modified, Color(0xFFD97706), Modifier.weight(1f))
                StatusMetric(stringResource(R.string.git_untracked_label), untracked, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SyncPill(ahead: Int, behind: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Radius.pill)
    ) {
        Text(
            text = buildString {
                if (ahead > 0) append("↑$ahead")
                if (behind > 0) {
                    if (isNotEmpty()) append("  ")
                    append("↓$behind")
                }
            },
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
        )
    }
}

@Composable
private fun StatusActionsBar(
    busy: Boolean,
    hasStagedChanges: Boolean,
    hasRemote: Boolean,
    hasIdentity: Boolean,
    onStageAll: () -> Unit,
    onCommit: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit
) {
    val canCommit = !busy && hasStagedChanges && hasIdentity
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        if (maxWidth < 420.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ActionButton(stringResource(R.string.git_commit_changes), FeatherIcons.Check, prominent = true, enabled = canCommit, onClick = onCommit, modifier = Modifier.fillMaxWidth())
                if (!hasIdentity) {
                    Text(
                        stringResource(R.string.git_no_identity),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ActionButton(stringResource(R.string.git_stage_all), FeatherIcons.Plus, enabled = !busy, onClick = onStageAll, modifier = Modifier.weight(1f))
                    ActionButton(stringResource(R.string.git_pull), FeatherIcons.DownloadCloud, enabled = !busy && hasRemote, onClick = onPull, modifier = Modifier.weight(1f))
                    ActionButton(stringResource(R.string.git_push), FeatherIcons.UploadCloud, enabled = !busy && hasRemote, onClick = onPush, modifier = Modifier.weight(1f))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ActionButton(stringResource(R.string.git_commit_changes), FeatherIcons.Check, prominent = true, enabled = canCommit, onClick = onCommit, modifier = Modifier.weight(1.4f))
                    ActionButton(stringResource(R.string.git_stage_all), FeatherIcons.Plus, enabled = !busy, onClick = onStageAll, modifier = Modifier.weight(1f))
                    ActionButton(stringResource(R.string.git_pull), FeatherIcons.DownloadCloud, enabled = !busy && hasRemote, onClick = onPull, modifier = Modifier.weight(1f))
                    ActionButton(stringResource(R.string.git_push), FeatherIcons.UploadCloud, enabled = !busy && hasRemote, onClick = onPush, modifier = Modifier.weight(1f))
                }
                if (!hasIdentity) {
                    Text(
                        stringResource(R.string.git_no_identity),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: GitFileChange,
    actionIcon: ImageVector,
    actionDesc: String,
    onAction: () -> Unit,
    enabled: Boolean,
    onClick: (() -> Unit)? = null
) {
    val fileName = file.path.substringAfterLast('/')
    val directory = file.path.substringBeforeLast('/', missingDelimiterValue = "")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusChip(file.statusCode)
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (directory.isNotEmpty()) {
                    Text(
                        text = directory,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(Spacing.sm))
            IconButton(onClick = onAction, enabled = enabled) {
                Icon(
                    actionIcon,
                    contentDescription = actionDesc,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(start = 60.dp)
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    prominent: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (prominent) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(48.dp),
            shape = RoundedCornerShape(Radius.sm),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            contentPadding = PaddingValues(horizontal = Spacing.md)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(48.dp),
            shape = RoundedCornerShape(Radius.sm),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            contentPadding = PaddingValues(horizontal = Spacing.sm)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
