package com.deep.rcode.feature.agent.presentation.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing

/**
 * 文件修改列表的底部弹窗：占屏约 8/10，顶部文件切换标签，内容区展示选中文件的 diff。
 * 点击弹窗外部或下拉关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileDiffSheet(
    fileDiffs: List<EditDiff>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedIndex by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg)
    ) {
        // 占屏 8/10：ModalBottomSheet 本身无 fillMaxHeight 参数，用内部 Column 撑满
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
        ) {
            // 标题栏
            Text(
                text = "文件修改 (${fileDiffs.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
            )

            // 文件切换标签：横向滚动
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                fileDiffs.forEachIndexed { index, diff ->
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = diff.path.substringAfterLast('/'),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (diff.added > 0 || diff.removed > 0) {
                                    Spacer(Modifier.width(Spacing.xs))
                                    Text(
                                        text = "+${diff.added} -${diff.removed}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (index == selectedIndex) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))
            HorizontalDivider()

            // Diff 内容区
            if (fileDiffs.isNotEmpty()) {
                val current = fileDiffs[selectedIndex.coerceIn(0, fileDiffs.lastIndex)]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.md)
                ) {
                    // 文件路径标题
                    Text(
                        text = current.path,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = Spacing.sm)
                    )

                    // 逐段 diff
                    current.hunks.forEach { hunk ->
                        DiffView(diff = hunk.diff, startLine = hunk.startLine)
                        Spacer(Modifier.height(Spacing.sm))
                    }
                }
            }
        }
    }
}