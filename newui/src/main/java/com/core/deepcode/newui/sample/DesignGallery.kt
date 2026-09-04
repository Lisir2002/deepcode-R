package com.core.deepcode.newui.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.core.deepcode.newui.designsystem.component.atom.AppCard
import com.core.deepcode.newui.designsystem.component.atom.AppChip
import com.core.deepcode.newui.designsystem.component.atom.AppIcon
import com.core.deepcode.newui.designsystem.component.atom.IconContainer
import com.core.deepcode.newui.designsystem.component.molecule.AppAccordion
import com.core.deepcode.newui.designsystem.component.molecule.AppAlertTone
import com.core.deepcode.newui.designsystem.component.molecule.AppAvatar
import com.core.deepcode.newui.designsystem.component.molecule.AppBadge
import com.core.deepcode.newui.designsystem.component.molecule.AppBadgeDot
import com.core.deepcode.newui.designsystem.component.molecule.AppBreadcrumb
import com.core.deepcode.newui.designsystem.component.molecule.AppButton
import com.core.deepcode.newui.designsystem.component.molecule.AppButtonVariant
import com.core.deepcode.newui.designsystem.component.molecule.AppChatBubble
import com.core.deepcode.newui.designsystem.component.molecule.AppCheckRow
import com.core.deepcode.newui.designsystem.component.molecule.AppDialog
import com.core.deepcode.newui.designsystem.component.molecule.AppDivider
import com.core.deepcode.newui.designsystem.component.molecule.AppFAB
import com.core.deepcode.newui.designsystem.component.molecule.AppFilterChips
import com.core.deepcode.newui.designsystem.component.molecule.AppInlineAlert
import com.core.deepcode.newui.designsystem.component.molecule.AppMenuRow
import com.core.deepcode.newui.designsystem.component.molecule.AppProgressBar
import com.core.deepcode.newui.designsystem.component.molecule.AppRatingBar
import com.core.deepcode.newui.designsystem.component.molecule.AppRingProgress
import com.core.deepcode.newui.designsystem.component.molecule.AppSearchBar
import com.core.deepcode.newui.designsystem.component.molecule.AppSectionGroup
import com.core.deepcode.newui.designsystem.component.molecule.AppSectionHeader
import com.core.deepcode.newui.designsystem.component.molecule.AppSegmentedToggle
import com.core.deepcode.newui.designsystem.component.molecule.AppShimmerBox
import com.core.deepcode.newui.designsystem.component.molecule.AppSkeletonList
import com.core.deepcode.newui.designsystem.component.molecule.AppSlider
import com.core.deepcode.newui.designsystem.component.molecule.AppSparkline
import com.core.deepcode.newui.designsystem.component.molecule.AppStatCard
import com.core.deepcode.newui.designsystem.component.molecule.AppStatusDot
import com.core.deepcode.newui.designsystem.component.molecule.AppStepper
import com.core.deepcode.newui.designsystem.component.molecule.AppSwitchRow
import com.core.deepcode.newui.designsystem.component.molecule.AppTabs
import com.core.deepcode.newui.designsystem.component.molecule.AppTextField
import com.core.deepcode.newui.designsystem.component.molecule.AppToast
import com.core.deepcode.newui.designsystem.component.molecule.AppTypingIndicator
import com.core.deepcode.newui.designsystem.component.molecule.AppSwipeAction
import com.core.deepcode.newui.designsystem.component.molecule.AppTimeline
import com.core.deepcode.newui.designsystem.component.molecule.AppTimelineItem
import com.core.deepcode.newui.designsystem.component.molecule.AppTimelineTone
import com.core.deepcode.newui.designsystem.component.molecule.AppProgressSteps
import com.core.deepcode.newui.designsystem.component.molecule.AppTagInput
import com.core.deepcode.newui.designsystem.component.molecule.AppPagination
import com.core.deepcode.newui.designsystem.component.molecule.AppKeyCombo
import com.core.deepcode.newui.designsystem.component.molecule.AppKeyCap
import com.core.deepcode.newui.designsystem.component.molecule.AppNotificationItem
import com.core.deepcode.newui.designsystem.component.molecule.AppMiniBarChart
import com.core.deepcode.newui.designsystem.component.molecule.AppFileCard
import com.core.deepcode.newui.designsystem.component.molecule.AppFileState
import com.core.deepcode.newui.designsystem.layout.AppEmptyState
import com.core.deepcode.newui.designsystem.layout.AppErrorState
import com.core.deepcode.newui.designsystem.layout.AppLoadingState
import com.core.deepcode.newui.designsystem.layout.AppTopBar
import com.core.deepcode.newui.designsystem.layout.pageContentPadding
import com.core.deepcode.newui.designsystem.layout.pageMaxWidth
import com.core.deepcode.newui.designsystem.slot.SlotSet
import com.core.deepcode.newui.designsystem.theme.AppTheme
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppElevation
import com.core.deepcode.newui.designsystem.token.generated.AppLayout
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 样板页（§7 S0）：在一个页面内陈列令牌 / 原子组件 / 布局 / 三态 / 槽位，
 * 供负责人检验并敲定"基本完整落地"。附 android @Preview 可独立预览。
 */

@Composable
fun DesignGallery() {
    AppTheme {
        SlotSet(
            topBar = {
                AppTopBar(
                    title = "Design Gallery",
                    navigationIcon = null,
                )
            },
            content = {
                GalleryBody()
            },
        )
    }
}

@Composable
private fun GalleryBody() {
    val scroll = rememberScrollState()
    var showDialog by remember { mutableStateOf(false) }
    var fieldText by remember { mutableStateOf("") }
    var searchText by remember { mutableStateOf("") }
    var segmentedIndex by remember { mutableStateOf(0) }
    var switchOn by remember { mutableStateOf(true) }
    var stepperValue by remember { mutableStateOf(3) }
    var fabExpanded by remember { mutableStateOf(false) }
    var toastVisible by remember { mutableStateOf(false) }
    var rating by remember { mutableStateOf(3) }
    var sliderValue by remember { mutableStateOf(34f) }
    var checkOn by remember { mutableStateOf(true) }
    var accordionOpen by remember { mutableStateOf(true) }
    var tabIndex by remember { mutableStateOf(0) }
    var filterSet by remember { mutableStateOf(setOf(0, 2)) }
    var page by remember { mutableStateOf(2) }
    var tags by remember { mutableStateOf(listOf("kotlin", "compose", "agent")) }
    // 文件卡运行态：自动循环演示（上传推进 → 完成）
    var fileProgress by remember { mutableStateOf(0f) }
    var fileState by remember { mutableStateOf(AppFileState.Uploading) }
    LaunchedEffect(Unit) {
        while (true) {
            fileState = AppFileState.Uploading
            fileProgress = 0f
            while (fileProgress < 1f) {
                delay(360)
                fileProgress = (fileProgress + 0.10f).coerceAtMost(1f)
            }
            fileState = AppFileState.Downloaded
            delay(1800)
        }
    }
    Column(
        modifier = Modifier
            .verticalScroll(scroll)
            .pageMaxWidth()
            .pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Lg),
    ) {
        Section("令牌 · 色板") {
            ColorRow(
                listOf(
                    "BrandPrimary" to AppColor.BrandPrimary,
                    "BrandSurface" to AppColor.BrandSurface,
                    "BrandAccent" to AppColor.BrandAccent,
                    "StatusSuccess" to AppColor.StatusSuccess,
                    "StatusDanger" to AppColor.StatusDanger,
                ),
            )
        }

        Section("令牌 · 度量/圆角/阴影") {
            Text("Spacing: xs=${AppSpacing.Xs} sm=${AppSpacing.Sm} md=${AppSpacing.Md} lg=${AppSpacing.Lg}")
            Text("Radius: sm=${AppRadius.Sm} md=${AppRadius.Md} lg=${AppRadius.Lg} pill=${AppRadius.Pill}")
            Text("Elevation: z0=${AppElevation.Z0} z1=${AppElevation.Z1} z2=${AppElevation.Z2} z4=${AppElevation.Z4}")
            Text("Sizing: touch=${AppSizing.TouchTarget} iconBlock=${AppSizing.IconBlock}")
            Text("Layout: pageH=${AppLayout.PageHorizontal} maxWidth=${AppLayout.ContentMaxWidth}")
        }

        Section("原子组件") {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(AppSpacing.Lg), verticalAlignment = Alignment.CenterVertically) {
                    IconContainer(icon = Icons.Rounded.Code, tint = Color.White)
                    Text(
                        text = "AppCard · IconContainer",
                        modifier = Modifier.padding(start = AppSpacing.Lg),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                AppChip(text = "选中", icon = Icons.Rounded.Home)
                AppChip(text = "未选中", selected = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                AppIcon(icon = Icons.Rounded.Settings)
                AppIcon(icon = Icons.Rounded.Palette, size = AppSizing.IconL)
                AppIcon(icon = Icons.Rounded.Code, size = AppSizing.IconXs)
            }
        }

        Section("分子组件 · 按钮") {
            AppButton(text = "Primary", onClick = {})
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                AppButton(
                    text = "Tonal",
                    variant = AppButtonVariant.FilledTonal,
                    onClick = {},
                )
                AppButton(
                    text = "Outlined",
                    variant = AppButtonVariant.Outlined,
                    onClick = {},
                )
                AppButton(
                    text = "Text",
                    variant = AppButtonVariant.Text,
                    onClick = {},
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                AppButton(text = "禁用", enabled = false, onClick = {})
                AppButton(
                    text = "危险",
                    variant = AppButtonVariant.Outlined,
                    onClick = {},
                )
            }
        }

        Section("分子组件 · 输入") {
            AppTextField(
                value = fieldText,
                onValueChange = { fieldText = it },
                label = "示例输入",
                placeholder = "请输入内容…",
            )
        }

        Section("分子组件 · 分组 / 列表行 / 状态") {
            AppSectionHeader(title = "区块标题")
            AppSectionGroup {
                AppMenuRow(
                    title = "设置项（无图标）",
                    subtitle = "副标题说明",
                    trailing = { Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                AppDivider()
                AppMenuRow(
                    title = "带图标块",
                    subtitle = "iconContainer = true",
                    icon = Icons.Rounded.Code,
                    iconContainer = true,
                )
                AppDivider()
                AppMenuRow(
                    title = "纯色前置图标",
                    icon = Icons.Rounded.Settings,
                    iconContainer = false,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Lg)) {
                AppStatusDot(color = AppColor.StatusSuccess, label = "成功")
                AppStatusDot(color = AppColor.StatusWarning, label = "警告")
                AppStatusDot(color = AppColor.StatusDanger)
            }
        }

        Section("分子组件 · 弹窗") {
            AppButton(text = "打开弹窗", onClick = { showDialog = true })
        }

        Section("分子组建族 · 表单 & 检索") {
            AppSegmentedToggle(
                options = listOf("全部", "进行中", "已完成"),
                selectedIndex = segmentedIndex,
                onSelect = { segmentedIndex = it },
            )
            AppSwitchRow(
                title = "自动同步",
                subtitle = "开启后在后台自动同步远端变更",
                checked = switchOn,
                onCheckedChange = { switchOn = it },
            )
            AppStepper(
                value = stepperValue,
                onValueChange = { stepperValue = it },
                min = 0,
                max = 10,
            )
            AppSearchBar(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = "搜索项目 / 命令 / 会话…",
                onClear = { searchText = "" },
            )
        }

        Section("分子组建族 · 反馈高动效") {
            AppProgressBar(progress = 0.66f)
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppShimmerBox(Modifier.size(56.dp).size(56.dp))
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                    AppShimmerBox(Modifier.fillMaxWidth(0.7f).size(14.dp))
                    AppShimmerBox(Modifier.fillMaxWidth().size(14.dp))
                    AppShimmerBox(Modifier.fillMaxWidth().size(14.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xl)) {
                Box {
                    AppIcon(icon = Icons.Rounded.Notifications)
                    AppBadge(count = 12)
                }
                AppBadge(count = 999, maxShow = 99)
                AppBadgeDot()
            }
        }

        Section("分子组建族 · 数据 / 导航 / 操作") {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                AppAvatar(text = "DeepCore", online = true)
                AppAvatar(text = "AI", online = false)
            }
            AppBreadcrumb(items = listOf("工作区", "remote", "agents", "prompts"))
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                AppStatCard(
                    label = "已执行命令",
                    value = 1284.0,
                    icon = Icons.Rounded.Code,
                    trend = "+12.5%",
                    modifier = Modifier.weight(1f),
                )
                AppStatCard(
                    label = "完成率",
                    value = 86.0,
                    icon = Icons.Rounded.Home,
                    trend = "+3.2%",
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                AppFAB(
                    icon = Icons.Rounded.Add,
                    text = "新建",
                    expanded = fabExpanded,
                    onClick = { fabExpanded = !fabExpanded },
                )
                AppButton(
                    text = if (toastVisible) "隐藏 Toast" else "显示 Toast",
                    onClick = { toastVisible = !toastVisible },
                )
            }
            AppToast(
                message = "操作成功，已保存更改",
                visible = toastVisible,
                icon = Icons.Rounded.Notifications,
            )
        }

        Section("分子组建族 · 数据可视化") {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xl), verticalAlignment = Alignment.CenterVertically) {
                AppRingProgress(progress = 0.72f)
                AppRingProgress(progress = 0.42f, boxSize = 64.dp, strokeWidth = 6.dp)
            }
            AppSparkline(
                data = listOf(20f, 34f, 28f, 52f, 48f, 70f, 86f, 66f, 92f),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            )
        }

        Section("分子组建族 · 表单增强") {
            AppRatingBar(value = rating, onValueChange = { rating = it })
            AppSlider(value = sliderValue, onValueChange = { sliderValue = it }, valueRange = 0f..100f)
            AppCheckRow(
                title = "始终显示输出面板",
                subtitle = "执行命令后自动展开运行区",
                checked = checkOn,
                onCheckedChange = { checkOn = it },
            )
        }

        Section("分子组建族 · 分级 / 导航 / 检索") {
            AppTabs(
                tabs = listOf("会话", "工具", "进程"),
                selectedIndex = tabIndex,
                onSelect = { tabIndex = it },
            )
            AppFilterChips(
                options = listOf("全部", "未读", "已收藏", "归档"),
                selectedIndices = filterSet,
                onToggle = { i ->
                    filterSet = if (i in filterSet) filterSet - i else filterSet + i
                },
            )
            AppAccordion(
                title = if (accordionOpen) "展开的高级选项" else "折叠的高级选项",
                subtitle = "点击展开 / 收起",
                expanded = accordionOpen,
                onToggle = { accordionOpen = !accordionOpen },
            ) {
                AppCheckRow(title = "启用沙箱隔离", checked = checkOn, onCheckedChange = { checkOn = it })
            }
        }

        Section("分子组建族 · 反馈层") {
            AppSkeletonList(rows = 2)
            AppInlineAlert(tone = AppAlertTone.Success, title = "配置已保存", message = "更改已同步到远端仓库。")
            AppInlineAlert(tone = AppAlertTone.Warning, message = "该命令需要容器运行时，请先启动 PRoot。")
            AppInlineAlert(tone = AppAlertTone.Danger, message = "数据目录不可写，请检查权限。", onDismiss = {})
        }

        Section("分子组建族 · AI 对话") {
            AppChatBubble(text = "我能帮你剖析项目结构并生成设计方案。", isUser = false)
            AppChatBubble(text = "好的，先扫描代码库并总结架构。", isUser = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppChatBubble(text = "正在思考", isUser = false, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.padding(start = AppSpacing.Md))
                AppTypingIndicator()
            }
        }

        Section("分子组建族 · 滑扫操作") {
            AppSwipeAction(
                actions = {
                    IconContainer(icon = Icons.Rounded.Delete, tint = Color.White, background = AppColor.StatusDanger)
                },
            ) {
                AppMenuRow(title = "右滑删除示例", subtitle = "向右滑动露出操作", icon = Icons.Rounded.Code)
            }
        }

        Section("分子组建族 · 时序 / 流程") {
            AppTimeline(
                items = listOf(
                    AppTimelineItem(title = "创建会话", subtitle = "初始化 AI Agent 上下文", time = "10:02", icon = Icons.Rounded.Add),
                    AppTimelineItem(title = "执行构建", subtitle = "assembleDebug 通过", time = "10:05", tone = AppTimelineTone.Success),
                    AppTimelineItem(title = "推送提交", subtitle = "feat(agent): 流式工具调用", time = "10:11", icon = Icons.Rounded.Code),
                    AppTimelineItem(title = "检测远端变更", subtitle = "合入前需解决冲突", time = "10:23", tone = AppTimelineTone.Danger),
                ),
            )
            AppProgressSteps(steps = listOf("解析", "授权", "执行", "完成"), currentIndex = 2)
        }

        Section("分子组建族 · 标签 / 分页") {
            AppTagInput(
                tags = tags,
                onAdd = { if (it !in tags) tags = tags + it },
                onRemove = { tags = tags - it },
                placeholder = "输入标签后回车添加…",
            )
            AppPagination(
                page = page,
                pageCount = 9,
                onPageChange = { page = it },
            )
        }

        Section("分子组建族 · 快捷键 / 通知") {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md), verticalAlignment = Alignment.CenterVertically) {
                AppKeyCombo(keys = listOf("⌘", "K"))
                AppKeyCombo(keys = listOf("Ctrl", "⇧", "P"))
                AppKeyCap(label = "Esc")
            }
            AppNotificationItem(
                title = "容器启动完成",
                body = "PRoot Alpine 已就绪，可执行终端命令。",
                time = "刚刚",
                icon = Icons.Rounded.Notifications,
                unread = true,
            )
            AppNotificationItem(
                title = "会员权限已更新",
                time = "5 分钟前",
                icon = Icons.Rounded.Settings,
            )
        }

        Section("分子组建族 · 数据可视化 / 文件") {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
                AppMiniBarChart(
                    values = listOf(40f, 72f, 58f, 90f, 66f, 84f),
                    highlightIndex = 3,
                    modifier = Modifier.weight(1f).height(72.dp),
                )
                AppMiniBarChart(
                    values = listOf(50f, 30f, 20f, 80f, 60f),
                    barColor = AppColor.StatusSuccess,
                    modifier = Modifier.weight(1f).height(72.dp),
                )
            }
            AppFileCard(fileName = "README.md", fileSize = "4.1 KB", state = AppFileState.Ready)
            AppFileCard(
                fileName = "tokens.json",
                fileSize = "—",
                state = fileState,
                progress = fileProgress,
                icon = Icons.Rounded.Code,
            )
            AppFileCard(fileName = "secrets.local", fileSize = "12 B", state = AppFileState.Error)
        }

        Section("三态") {
            Box(Modifier.fillMaxWidth().size(96.dp), contentAlignment = Alignment.Center) {
                AppLoadingState()
            }
            AppEmptyState(
                icon = Icons.Rounded.Home,
                title = "暂无内容",
                description = "这是空态示例，展示空状态占位。",
            )
            AppCard(modifier = Modifier.fillMaxWidth()) {
                AppErrorState(message = "网络异常，请重试。")
            }
        }
    }

    if (showDialog) {
        AppDialog(
            title = "确认操作",
            text = "这是 AppDialog 演示：破坏性操作请用 Confirm/Danger 配色。",
            onDismiss = { showDialog = false },
            dismissText = "取消",
            confirmText = "确认",
            onConfirm = { showDialog = false },
            confirmButtonColor = AppColor.StatusDanger,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun ColorRow(list: List<Pair<String, Color>>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        list.forEach { (name, color) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(color, shape = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.Md)),
                )
                Text(text = name, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}