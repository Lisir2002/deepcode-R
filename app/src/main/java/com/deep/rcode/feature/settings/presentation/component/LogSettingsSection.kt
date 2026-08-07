package com.deep.rcode.feature.settings.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.core.util.LogLevel
import com.deep.rcode.feature.settings.presentation.LogViewerUiState
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.deep.rcode.R

@Composable
internal fun LogSection(
    current: LogLevel,
    onSelect: (LogLevel) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        LogLevelCard(current = current, onSelect = onSelect)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LogViewerSection(
    state: LogViewerUiState,
    onSelectFile: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.md),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
    val context = LocalContext.current
            Column(modifier = Modifier.padding(Spacing.lg)) {
                Text(
                    text = state.filterServerName?.let { stringResource(R.string.log_mcp_prefix, it) } ?: stringResource(R.string.log_all),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = logViewerSummary(context, state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )

                if (state.files.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        state.files.forEach { fileName ->
                            FilterChip(
                                selected = fileName == state.selectedFileName,
                                onClick = { onSelectFile(fileName) },
                                label = { Text(fileName.removePrefix("log-").removeSuffix(".txt")) }
                            )
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(Radius.md),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            val verticalScroll = rememberScrollState()
            val text = when {
                state.loading -> stringResource(R.string.log_reading)
                state.error != null -> state.error
                state.content.isBlank() -> stringResource(R.string.log_no_match)
                else -> state.content
            }

            SelectionContainer {
                Text(
                    text = text,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScroll)
                        .padding(Spacing.md),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (state.error == null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }
    }
}

private fun logViewerSummary(context: Context, state: LogViewerUiState): String {
    val file = state.selectedFileName ?: context.getString(R.string.log_no_file)
    val scope = state.filterServerName?.let { context.getString(R.string.log_filter_prefix, it) } ?: context.getString(R.string.log_no_filter)
    val count = if (state.totalLines > state.shownLines) {
        context.getString(R.string.log_show_last_lines, state.shownLines, state.totalLines)
    } else {
        context.getString(R.string.log_show_lines, state.shownLines)
    }
    return "$file · $scope · $count"
}
