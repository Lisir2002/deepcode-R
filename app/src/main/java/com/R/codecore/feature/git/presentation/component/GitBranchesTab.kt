package com.R.codecore.feature.git.presentation.component

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.R.codecore.R
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.git.domain.model.GitBranch
import com.R.codecore.feature.git.domain.model.GitTag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.SubdirectoryArrowLeft
import androidx.compose.material.icons.rounded.Tag

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun BranchesTab(
    branches: List<GitBranch>,
    tags: List<GitTag>,
    branchesLoading: Boolean,
    branchesLoaded: Boolean,
    checkoutLoading: String?,
    onCheckout: (String, Boolean) -> Unit,
    onCreateBranch: (String, String?, Boolean) -> Unit,
    onDeleteBranch: (String) -> Unit,
    onDeleteRemoteBranch: (String) -> Unit,
    onRenameBranch: (String, String) -> Unit,
    onCreateTag: (String) -> Unit,
    onDeleteTag: (String) -> Unit
) {
    if (branchesLoading || (!branchesLoaded && branches.isEmpty() && tags.isEmpty())) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    stringResource(R.string.git_loading_branches),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    if (branches.isEmpty() && tags.isEmpty()) {
        EmptyState(stringResource(R.string.git_no_branches))
        return
    }
    val currentBranch = branches.firstOrNull { it.current }?.name ?: stringResource(R.string.git_no_checked_out_branch)
    val localBranches = branches.filter { !it.remote }
    val remoteBranches = branches.filter { it.remote }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    fun isExpanded(key: String): Boolean = expanded[key] ?: true

    var pendingCheckout by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var pendingRename by remember { mutableStateOf<String?>(null) }
    var showCreateTagDialog by remember { mutableStateOf(false) }
    var pendingDeleteTag by remember { mutableStateOf<String?>(null) }

    pendingCheckout?.let { (ref, isRemote) ->
        val isTag = tags.any { it.name == ref }
        AlertDialog(
            onDismissRequest = { pendingCheckout = null },
            title = { Text(stringResource(R.string.git_switch_branch)) },
            text = {
                Text(
                    if (isTag) stringResource(R.string.git_detached_head_warning, ref)
                    else if (isRemote) stringResource(R.string.git_create_tracking_branch, ref)
                    else stringResource(R.string.git_switch_confirm, ref)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingCheckout = null
                    onCheckout(ref, isRemote)
                }) { Text(stringResource(R.string.common_switch)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCheckout = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showCreateDialog) {
        val localBranchNames = localBranches.map { it.name }
        var newName by remember { mutableStateOf("") }
        var startPoint by remember { mutableStateOf(currentBranch) }
        var checkout by remember { mutableStateOf(true) }
        var expanded by remember { mutableStateOf(false) }
        val sheetState = rememberModalBottomSheetState()

        ModalBottomSheet(
            onDismissRequest = { showCreateDialog = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.git_new_branch),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.git_branch_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = startPoint,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.git_base_branch)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        localBranchNames.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = { startPoint = name; expanded = false }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.git_create_and_switch))
                    Switch(checked = checkout, onCheckedChange = { checkout = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showCreateDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                    Spacer(Modifier.width(Spacing.sm))
                    Button(
                        onClick = {
                            onCreateBranch(newName.trim(), startPoint, checkout)
                            showCreateDialog = false
                        },
                        enabled = newName.isNotBlank()
                    ) { Text(stringResource(R.string.common_create)) }
                }
            }
        }
    }

    pendingDelete?.let { (name, isRemote) ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (isRemote) stringResource(R.string.git_delete_remote_branch) else stringResource(R.string.git_delete_branch)) },
            text = {
                Text(
                    if (isRemote) stringResource(R.string.git_delete_remote_branch_confirm, name)
                    else stringResource(R.string.git_delete_local_branch_confirm, name)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    if (isRemote) onDeleteRemoteBranch(name) else onDeleteBranch(name)
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    pendingRename?.let { oldName ->
        var newName by remember(oldName) { mutableStateOf(oldName) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(stringResource(R.string.git_rename_branch)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.git_rename_to, oldName))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.git_new_branch_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newName.trim()
                        if (trimmed.isNotBlank() && trimmed != oldName) {
                            onRenameBranch(oldName, trimmed)
                            pendingRename = null
                        }
                    },
                    enabled = newName.trim().isNotBlank() && newName.trim() != oldName
                ) { Text(stringResource(R.string.common_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showCreateTagDialog) {
        var tagName by remember { mutableStateOf("") }
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showCreateTagDialog = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.common_new_tab),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.git_create_tag_desc, currentBranch),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = tagName,
                    onValueChange = { tagName = it },
                    label = { Text(stringResource(R.string.git_tag_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showCreateTagDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                    Spacer(Modifier.width(Spacing.sm))
                    Button(
                        onClick = {
                            val trimmed = tagName.trim()
                            if (trimmed.isNotBlank()) {
                                onCreateTag(trimmed)
                                showCreateTagDialog = false
                            }
                        },
                        enabled = tagName.trim().isNotBlank()
                    ) { Text(stringResource(R.string.common_create)) }
                }
            }
        }
    }

    pendingDeleteTag?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDeleteTag = null },
            title = { Text(stringResource(R.string.git_delete_tag)) },
            text = { Text(stringResource(R.string.git_delete_tag_confirm, name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteTag = null
                    onDeleteTag(name)
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteTag = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    val localTree = remember(localBranches) { buildBranchTree(localBranches) }
    val remoteTree = remember(remoteBranches) { buildBranchTree(remoteBranches) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.xl)
    ) {
        item { BranchesOverview(currentBranch, localBranches.size, remoteBranches.size, tags.size) }
        item {
            RefSectionHeader(
                title = "HEAD",
                isExpanded = isExpanded("head"),
                onToggle = { expanded["head"] = !isExpanded("head") }
            )
        }
        if (isExpanded("head")) {
            item {
                RefRow(
                    name = currentBranch,
                    subtitle = stringResource(R.string.git_checked_out),
                    icon = Icons.Rounded.Commit,
                    isCurrent = true
                )
            }
        }
        if (localBranches.isNotEmpty()) {
            item {
                RefSectionHeader(
                    title = "${stringResource(R.string.common_local)} (${localBranches.size})",
                    isExpanded = isExpanded("local"),
                    onToggle = { expanded["local"] = !isExpanded("local") },
                    onAdd = { showCreateDialog = true }
                )
            }
            if (isExpanded("local")) {
                renderBranchTree(
                    localTree,
                    depth = 1,
                    expanded,
                    isRemote = false,
                    checkoutLoading = checkoutLoading,
                    onCheckout = { ref, remote -> pendingCheckout = ref to remote },
                    onRenameBranch = { pendingRename = it },
                    onDeleteBranch = { pendingDelete = it to false },
                    onDeleteRemoteBranch = {}
                )
            }
        }
        if (remoteBranches.isNotEmpty()) {
            item {
                RefSectionHeader(
                    title = "${stringResource(R.string.git_remote)} (${remoteBranches.size})",
                    isExpanded = isExpanded("remote"),
                    onToggle = { expanded["remote"] = !isExpanded("remote") }
                )
            }
            if (isExpanded("remote")) {
                renderBranchTree(
                    remoteTree,
                    depth = 1,
                    expanded,
                    isRemote = true,
                    checkoutLoading = checkoutLoading,
                    onCheckout = { ref, remote -> pendingCheckout = ref to remote },
                    onRenameBranch = {},
                    onDeleteBranch = {},
                    onDeleteRemoteBranch = { pendingDelete = it to true }
                )
            }
        }
        if (tags.isNotEmpty()) {
            item {
                RefSectionHeader(
                    title = "${stringResource(R.string.git_tags)} (${tags.size})",
                    isExpanded = isExpanded("tags"),
                    onToggle = { expanded["tags"] = !isExpanded("tags") },
                    onAdd = { showCreateTagDialog = true }
                )
            }
            if (isExpanded("tags")) {
                tags.forEach { t ->
                    item(key = "tag-${t.name}") {
                        RefRow(
                            name = t.name,
                            subtitle = t.shortHash,
                            icon = Icons.Rounded.Tag,
                            isCurrent = false,
                            isLoading = checkoutLoading == t.name,
                            actions = listOf(
                                RefAction.Switch(onClick = { pendingCheckout = t.name to false }),
                                RefAction.Delete(onClick = { pendingDeleteTag = t.name })
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BranchesOverview(
    currentBranch: String,
    localCount: Int,
    remoteCount: Int,
    tagCount: Int
) {
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
                            Icons.Rounded.AccountTree,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.git_current_branch),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = currentBranch,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatusMetric(stringResource(R.string.common_local), localCount, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                StatusMetric(stringResource(R.string.git_remote), remoteCount, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                StatusMetric(stringResource(R.string.git_tags), tagCount, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RefSectionHeader(
    title: String,
    isExpanded: Boolean,
    indent: Int = 0,
    onToggle: () -> Unit,
    onAdd: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = Spacing.lg + (indent * 16).dp, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = if (isExpanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (onAdd != null) {
            IconButton(onClick = onAdd, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.git_new),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 分支/标签行可执行的操作项，用于长按弹出的操作菜单。
 */
private sealed class RefAction(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val isDestructive: Boolean,
    val onClick: () -> Unit
) {
    class Switch(onClick: () -> Unit) : RefAction(R.string.common_switch, Icons.Rounded.SubdirectoryArrowLeft, false, onClick)
    class Rename(onClick: () -> Unit) : RefAction(R.string.common_rename, Icons.Rounded.Edit, false, onClick)
    class Delete(onClick: () -> Unit) : RefAction(R.string.common_delete, Icons.Rounded.Delete, true, onClick)
}

@Composable
private fun RefRow(
    name: String,
    subtitle: String?,
    icon: ImageVector,
    isCurrent: Boolean,
    isLoading: Boolean = false,
    indent: Int = 0,
    actions: List<RefAction> = emptyList()
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val contentColor = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (isLoading || actions.isEmpty()) it
                else it.clickable { menuExpanded = true }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg + (indent * 16).dp, end = Spacing.lg)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    if (menuExpanded) {
        RefActionSheet(
            refName = name,
            actions = actions,
            onDismiss = { menuExpanded = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefActionSheet(
    refName: String,
    actions: List<RefAction>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = refName,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            actions.forEach { action ->
                val tint = if (action.isDestructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                Surface(
                    onClick = {
                        onDismiss()
                        action.onClick()
                    },
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = tint
                        )
                        Spacer(Modifier.width(Spacing.lg))
                        Text(
                            text = stringResource(action.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = tint
                        )
                    }
                }
            }
        }
    }
}

/**
 * 分支树节点：中间段为虚拟文件夹（branch 可能为空），叶子段承载 [GitBranch]。
 */
private data class BranchNode(
    val segment: String,
    val fullPath: String,
    var branch: GitBranch? = null,
    val children: MutableList<BranchNode> = mutableListOf()
)

/**
 * 按 `/` 切分分支名构建层级树。同层按段名字典序排序。
 */
private fun buildBranchTree(branches: List<GitBranch>): List<BranchNode> {
    val root = BranchNode("", "", null)
    for (b in branches.sortedBy { it.name }) {
        val parts = b.name.split('/')
        var cur = root
        val path = StringBuilder()
        parts.forEachIndexed { i, part ->
            if (path.isNotEmpty()) path.append('/')
            path.append(part)
            val isLeaf = i == parts.lastIndex
            val existing = cur.children.find { it.segment == part }
            cur = if (existing == null) {
                BranchNode(part, path.toString(), if (isLeaf) b else null).also { cur.children.add(it) }
            } else {
                if (isLeaf) existing.branch = b
                existing
            }
        }
    }
    sortBranchTree(root)
    return root.children
}

private fun sortBranchTree(node: BranchNode) {
    node.children.sortBy { it.segment.lowercase() }
    node.children.forEach(::sortBranchTree)
}

/**
 * 递归向 [LazyListScope] 注入分支树节点。文件夹节点可折叠。
 */
private fun LazyListScope.renderBranchTree(
    nodes: List<BranchNode>,
    depth: Int,
    expanded: MutableMap<String, Boolean>,
    isRemote: Boolean,
    checkoutLoading: String?,
    onCheckout: (String, Boolean) -> Unit,
    onRenameBranch: (String) -> Unit,
    onDeleteBranch: (String) -> Unit,
    onDeleteRemoteBranch: (String) -> Unit
) {
    for (node in nodes) {
        val isFolder = node.children.isNotEmpty()
        val isOpen = expanded[node.fullPath] ?: true
        item(key = "node-${node.fullPath}") {
            if (isFolder) {
                RefSectionHeader(
                    title = node.segment,
                    isExpanded = isOpen,
                    indent = depth,
                    onToggle = { expanded[node.fullPath] = !isOpen }
                )
            } else {
                node.branch?.let { b ->
                    val actions = if (isRemote) {
                        listOf(
                            RefAction.Switch(onClick = { onCheckout(b.name, true) }),
                            RefAction.Delete(onClick = { onDeleteRemoteBranch(b.name) })
                        )
                    } else {
                        buildList {
                            if (!b.current) add(RefAction.Switch(onClick = { onCheckout(b.name, false) }))
                            add(RefAction.Rename(onClick = { onRenameBranch(b.name) }))
                            if (!b.current) add(RefAction.Delete(onClick = { onDeleteBranch(b.name) }))
                        }
                    }
                    RefRow(
                        name = node.segment,
                        subtitle = if (b.current) stringResource(R.string.git_checked_out) else null,
                        icon = if (isRemote) Icons.Rounded.Cloud else Icons.Rounded.AccountTree,
                        isCurrent = b.current,
                        isLoading = checkoutLoading == b.name,
                        indent = depth,
                        actions = actions
                    )
                }
            }
        }
        if (isFolder && isOpen) {
            renderBranchTree(node.children, depth + 1, expanded, isRemote, checkoutLoading, onCheckout, onRenameBranch, onDeleteBranch, onDeleteRemoteBranch)
        }
    }
}
