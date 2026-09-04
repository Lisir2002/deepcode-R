package com.core.deepcode.newui.sample

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random
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
import com.core.deepcode.newui.designsystem.component.molecule.AppIconButton
import com.core.deepcode.newui.designsystem.component.molecule.AppDialog
import com.core.deepcode.newui.designsystem.component.molecule.AppDialogTone
import com.core.deepcode.newui.designsystem.component.molecule.AppDivider
import com.core.deepcode.newui.designsystem.component.molecule.AppActionSheet
import com.core.deepcode.newui.designsystem.component.molecule.AppActionSheetItem
import com.core.deepcode.newui.designsystem.component.molecule.AppAlertDialog
import com.core.deepcode.newui.designsystem.component.molecule.AppBottomSheetList
import com.core.deepcode.newui.designsystem.component.molecule.AppConfirmDialog
import com.core.deepcode.newui.designsystem.component.molecule.AppMenu
import com.core.deepcode.newui.designsystem.component.molecule.AppMenuDivider
import com.core.deepcode.newui.designsystem.component.molecule.AppMenuItem
import com.core.deepcode.newui.designsystem.component.molecule.AppPromptDialog
import com.core.deepcode.newui.designsystem.component.molecule.AppSelectionDialog
import com.core.deepcode.newui.designsystem.component.molecule.AppSelectionItem
import com.core.deepcode.newui.designsystem.component.molecule.AppSelectionList
import com.core.deepcode.newui.designsystem.component.molecule.AppSelectionMode
import com.core.deepcode.newui.designsystem.component.molecule.AppSelectField
import com.core.deepcode.newui.designsystem.component.molecule.AppComboBox
import com.core.deepcode.newui.designsystem.component.molecule.AppCommandGroup
import com.core.deepcode.newui.designsystem.component.molecule.AppCommandPalette
import com.core.deepcode.newui.designsystem.component.molecule.AppContextMenu
import com.core.deepcode.newui.designsystem.component.molecule.AppMenuAction
import com.core.deepcode.newui.designsystem.component.molecule.AppFormDialog
import com.core.deepcode.newui.designsystem.component.molecule.AppPermissionDialog
import com.core.deepcode.newui.designsystem.component.molecule.AppSuccessDialog
import com.core.deepcode.newui.designsystem.component.molecule.AppMultiSelectDialog
import com.core.deepcode.newui.designsystem.component.molecule.AppCascadingMenu
import com.core.deepcode.newui.designsystem.component.molecule.AppCascadeNode
import com.core.deepcode.newui.designsystem.component.molecule.AppNavigationMenu
import com.core.deepcode.newui.designsystem.component.molecule.AppNavigationItem
import com.core.deepcode.newui.designsystem.component.molecule.AppCountdownDialog
import com.core.deepcode.newui.designsystem.component.molecule.AppLoadingOverlay
import com.core.deepcode.newui.designsystem.component.molecule.AppUpdateDialog
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
import com.core.deepcode.newui.designsystem.component.molecule.AppConfetti
import com.core.deepcode.newui.designsystem.component.molecule.AppDock
import com.core.deepcode.newui.designsystem.component.molecule.AppDockItem
import com.core.deepcode.newui.designsystem.component.molecule.AppGradientBorder
import com.core.deepcode.newui.designsystem.component.molecule.AppMarquee
import com.core.deepcode.newui.designsystem.component.molecule.AppRollingNumber
import com.core.deepcode.newui.designsystem.component.molecule.AppScrambleText
import com.core.deepcode.newui.designsystem.component.molecule.AppScrollProgress
import com.core.deepcode.newui.designsystem.component.molecule.AppSpotlightCard
import com.core.deepcode.newui.designsystem.component.molecule.AppTypewriterText
import com.core.deepcode.newui.designsystem.component.molecule.AppSwipeAction
import com.core.deepcode.newui.designsystem.component.molecule.AppSwipeButton
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
fun DesignGallery(onNavigateBack: (() -> Unit)? = null) {
    AppTheme {
        SlotSet(
            topBar = {
                AppTopBar(
                    title = "Design Gallery",
                    navigationIcon = if (onNavigateBack != null) {
                        {
                            IconButton(onClick = onNavigateBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "返回",
                                )
                            }
                        }
                    } else null,
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
    // 滑扫协调：同批只开一项
    var swipeExpanded by remember { mutableStateOf<Int?>(null) }
    // 列表菜单 / 弹窗族演示状态
    var menuExpanded by remember { mutableStateOf(false) }
    var selectValue by remember { mutableStateOf("Claude") }
    var bottomSheetOpen by remember { mutableStateOf(false) }
    var singlePick by remember { mutableStateOf(0) }
    var multiPick by remember { mutableStateOf(setOf(1, 3)) }
    var showAlertDialog by remember { mutableStateOf(false) }
    var showSelectionDialog by remember { mutableStateOf(false) }
    var showActionSheet by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    // 新增列表/弹窗补充类型演示状态
    var comboValue by remember { mutableStateOf("Auto") }
    var contextVisible by remember { mutableStateOf(false) }
    var contextPos by remember { mutableStateOf(Offset.Zero) }
    var paletteOpen by remember { mutableStateOf(false) }
    var paletteQuery by remember { mutableStateOf("") }
    var showFormDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var showMultiSelectDialog by remember { mutableStateOf(false) }
    var multiSelData by remember { mutableStateOf(setOf(0, 2)) }
    var lastSwipeAction by remember { mutableStateOf<String?>(null) }
    // 本阶段新增类型演示状态：级联菜单 / 导航菜单 / 倒计时弹窗 / 阻塞遮罩
    var cascadeExpanded by remember { mutableStateOf(false) }
    var navIndex by remember { mutableStateOf(0) }
    var showCountdownDialog by remember { mutableStateOf(false) }
    var showLoadingOverlay by remember { mutableStateOf(false) }
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
    // 可视化运行态：进度/环形/步骤/图表自动循环演示
    var progressBar by remember { mutableStateOf(0f) }
    var ringProgress by remember { mutableStateOf(0f) }
    var stepIndex by remember { mutableStateOf(0) }
    var sparkData by remember { mutableStateOf(listOf(20f, 34f, 28f, 52f, 48f, 70f, 86f, 66f, 92f)) }
    var barData by remember { mutableStateOf(listOf(40f, 72f, 58f, 90f, 66f, 84f)) }
    LaunchedEffect(Unit) {
        val rnd = Random.Default
        while (true) {
            progressBar = 0f
            ringProgress = 0f
            repeat(10) { i ->
                progressBar = (i + 1) / 10f
                ringProgress = (i + 1) / 10f
                delay(240)
            }
            stepIndex = (stepIndex + 1) % 4
            sparkData = List(9) { 20f + rnd.nextFloat() * 80f }
            barData = List(6) { 30f + rnd.nextFloat() * 70f }
            delay(1500)
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

        Section("分子组建族 · 列表菜单") {
            // 下拉菜单：锚定到按钮的 DropdownMenu
            Box {
                AppButton(
                    text = "更多操作",
                    variant = AppButtonVariant.Outlined,
                    onClick = { menuExpanded = !menuExpanded },
                )
                AppMenu(expanded = menuExpanded, onDismiss = { menuExpanded = false }) {
                    AppMenuItem(label = "重命名", leadingIcon = Icons.Rounded.Settings, onClick = { menuExpanded = false })
                    AppMenuItem(label = "加入收藏", leadingIcon = Icons.Rounded.Notifications, onClick = { menuExpanded = false })
                    AppMenuDivider()
                    AppMenuItem(
                        label = "删除",
                        leadingIcon = Icons.Rounded.DeleteOutline,
                        tint = AppColor.StatusDanger,
                        onClick = { menuExpanded = false },
                    )
                }
            }
            // 暴露式下拉选择框：常驻显示已选项
            AppSelectField(
                value = selectValue,
                options = listOf("Claude", "OpenAI", "Gemini"),
                onSelect = { selectValue = it },
                label = "默认模型",
                leadingIcon = Icons.Rounded.Settings,
            )
            // 内联选择列表：单选
            AppSelectionList(
                items = listOf(
                    AppSelectionItem("Claude", "Sonnet 4"),
                    AppSelectionItem("OpenAI", "GPT-5"),
                    AppSelectionItem("Gemini", "2.0 Pro"),
                ),
                mode = AppSelectionMode.Single,
                selected = singlePick,
                onSelect = { singlePick = it },
            )
            // 内联选择列表：多选
            AppSelectionList(
                items = listOf(
                    AppSelectionItem("聊天气泡"),
                    AppSelectionItem("文件卡片"),
                    AppSelectionItem("消息通知"),
                    AppSelectionItem("状态指示"),
                ),
                mode = AppSelectionMode.Multiple,
                selected = multiPick,
                onSelect = {},
                onToggle = { i -> multiPick = if (i in multiPick) multiPick - i else multiPick + i },
            )
            AppButton(text = "抽屉选择列表", variant = AppButtonVariant.FilledTonal, onClick = { bottomSheetOpen = true })
            if (bottomSheetOpen) {
                AppBottomSheetList(onDismiss = { bottomSheetOpen = false }, title = "选择目标位置") {
                    AppSelectionList(
                        items = listOf(
                            AppSelectionItem("工作区", "workspace"),
                            AppSelectionItem("文档", "docs"),
                            AppSelectionItem("日志", "logs"),
                        ),
                        mode = AppSelectionMode.Single,
                        selected = singlePick,
                        onSelect = { singlePick = it }
                    )
                }
            }
            // 可搜索下拉选择框：输入即过滤
            AppComboBox(
                value = comboValue,
                options = listOf("Auto", "Gemini 2.0", "GPT-5", "Claude Sonnet", "Qwen Max", "DeepSeek V3"),
                onSelect = { comboValue = it },
                label = "可搜索下拉选择框",
                leadingIcon = Icons.Rounded.Search,
            )
            // 上下文菜单：长按目标弹出
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadius.Md))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { pos -> contextPos = pos; contextVisible = true })
                    }
                    .padding(AppSpacing.Md),
            ) {
                Text(
                    text = "长按此处打开上下文菜单",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (contextVisible) {
                AppContextMenu(
                    visible = true,
                    position = contextPos,
                    items = listOf(
                        AppMenuAction("复制路径", Icons.Rounded.Code),
                        AppMenuAction("打开文件", Icons.Rounded.InsertDriveFile),
                        AppMenuAction("删除", Icons.Rounded.Delete, danger = true),
                    ),
                    onItemClick = { contextVisible = false },
                    onDismiss = { contextVisible = false },
                )
            }
            Text(
                text = "命令面板 = 系统快捷键命令中枢（🚀 桌面入口）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppIconButton(
                text = "命令面板 ⌘K",
                variant = AppButtonVariant.FilledTonal,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                onClick = { paletteOpen = true },
            )
            // 级联子菜单：文件 → 导出 → 格式 的多级飞墙
            Box {
                AppIconButton(
                    text = "级联子菜单",
                    variant = AppButtonVariant.FilledTonal,
                    leadingIcon = { Icon(Icons.Rounded.InsertDriveFile, contentDescription = null) },
                    onClick = { cascadeExpanded = !cascadeExpanded },
                )
                AppCascadingMenu(
                    expanded = cascadeExpanded,
                    onDismiss = { cascadeExpanded = false },
                    items = listOf(
                        AppCascadeNode("文件", icon = Icons.Rounded.InsertDriveFile, children = listOf(
                            AppCascadeNode("打开…", icon = Icons.Rounded.Search),
                            AppCascadeNode("导出", icon = Icons.Rounded.Archive, children = listOf(
                                AppCascadeNode("Markdown", icon = Icons.Rounded.Code),
                                AppCascadeNode("JSON", icon = Icons.Rounded.Code),
                                AppCascadeNode("PNG 插图", icon = Icons.Rounded.Palette),
                            )),
                        )),
                        AppCascadeNode("分享", icon = Icons.Rounded.Notifications),
                        AppCascadeNode("删除", icon = Icons.Rounded.Delete, danger = true),
                    ),
                    onItemClick = { cascadeExpanded = false },
                )
            }
            // 导航/侧栏列表：带计数徽标与危险项
            AppNavigationMenu(
                items = listOf(
                    AppNavigationItem("工作台", Icons.Rounded.Home),
                    AppNavigationItem("会话", Icons.Rounded.Code, badge = 12),
                    AppNavigationItem("通知", Icons.Rounded.Notifications, badge = 3),
                    AppNavigationItem("退出登录", Icons.Rounded.Settings, danger = true),
                ),
                selectedIndex = navIndex,
                onSelect = { navIndex = it },
            )
        }

        Section("分子组件 · 弹窗") {
            Text("确认 / 提示 / 输入 / 选择 / 更新 · 动作面板", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                AppButton(text = "确认", onClick = { showDialog = true })
                AppButton(text = "提示", variant = AppButtonVariant.FilledTonal, onClick = { showAlertDialog = true })
                AppButton(text = "输入", variant = AppButtonVariant.Text, onClick = { showPromptDialog = true })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                AppButton(text = "选择", variant = AppButtonVariant.Outlined, onClick = { showSelectionDialog = true })
                AppButton(text = "更新", variant = AppButtonVariant.Outlined, onClick = { showUpdateDialog = true })
                AppButton(text = "动作面板", variant = AppButtonVariant.Outlined, onClick = { showActionSheet = true })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                AppButton(text = "表单", variant = AppButtonVariant.Text, onClick = { showFormDialog = true })
                AppButton(text = "权限", variant = AppButtonVariant.Text, onClick = { showPermissionDialog = true })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                AppButton(text = "成功", variant = AppButtonVariant.Text, onClick = { showSuccessDialog = true })
                AppButton(text = "多选", variant = AppButtonVariant.Text, onClick = { showMultiSelectDialog = true })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                AppButton(text = "倒计时防误", variant = AppButtonVariant.Text, onClick = { showCountdownDialog = true })
                AppButton(text = "阻塞遮罩", variant = AppButtonVariant.Text, onClick = { showLoadingOverlay = true })
            }
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
            AppProgressBar(progress = progressBar)
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
                AppRingProgress(progress = ringProgress)
                AppRingProgress(progress = ringProgress, boxSize = 64.dp, strokeWidth = 6.dp)
            }
            AppSparkline(
                data = sparkData,
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
            val swipeRows = listOf("会话 A · deepcode-agent", "会话 B · settings refactor", "会话 C · terminal local")
            swipeRows.forEachIndexed { index, title ->
                Column {
                    AppSwipeAction(
                        index = index,
                        expandedIndex = swipeExpanded,
                        onExpanded = { swipeExpanded = it },
                        actionWidth = 128.dp,
                        actions = {
                            AppSwipeButton(
                                icon = Icons.Rounded.Archive,
                                label = "归档",
                                background = AppColor.StatusInfo,
                                onClick = { /* 预留接入归档逻辑 */ },
                            )
                            AppSwipeButton(
                                icon = Icons.Rounded.Delete,
                                label = "删除",
                                background = AppColor.StatusDanger,
                                onClick = { /* 预留接入删除逻辑 */ },
                            )
                        },
                    ) {
                        AppMenuRow(title = title, subtitle = "左滑露出操作 · 点击内容收起 · 同批只开一项", icon = Icons.Rounded.Code)
                    }
                    if (index != swipeRows.lastIndex) {
                        Spacer(Modifier.height(AppSpacing.Sm))
                    }
                }
            }
        }

        Section("分子组建族 · 高动效展示") {
            var progress by remember { mutableStateOf(0f) }
            var rolling by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                while (true) {
                    rolling = (24..180).random()
                    progress = 0f
                    delay(2000)
                    progress = 1f
                    delay(2400)
                }
            }
            AppScrollProgress(fraction = progress)
            Spacer(Modifier.height(AppSpacing.Sm))

            Row(
                modifier = Modifier.fillMaxWidth().padding(AppSpacing.Sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("令牌速率", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AppRollingNumber(value = rolling, style = MaterialTheme.typography.headlineMedium, color = AppColor.BrandPrimary)
                }
                AppDock(
                    selectedIndex = 0,
                    items = listOf(
                        AppDockItem(Icons.Rounded.Home, "工作台"),
                        AppDockItem(Icons.Rounded.Code, "代码"),
                        AppDockItem(Icons.Rounded.Settings, "设置"),
                        AppDockItem(Icons.Rounded.Notifications, "通知"),
                    ),
                )
            }
            Spacer(Modifier.height(AppSpacing.Xs))

            AppMarquee { Text("　✦ DeepCore-Code · 容器已就绪 ● 终端已连接 → 构建中… 　", style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(AppSpacing.Md))

            AppGradientBorder(modifier = Modifier.fillMaxWidth()) {
                AppMenuRow(title = "渐变描边卡", subtitle = "边缘锥形渐变缓慢流淌", icon = Icons.Rounded.Palette)
            }
            Spacer(Modifier.height(AppSpacing.Md))

            AppTypewriterText(text = "正在生成 tool_call → 执行 shell 构建…")
            Spacer(Modifier.height(AppSpacing.Md))

            AppScrambleText(text = "AGENT_RUN_0.0.0.2")
            Spacer(Modifier.height(AppSpacing.Md))

            AppSpotlightCard(modifier = Modifier.fillMaxWidth().height(112.dp)) {
                Column {
                    Text("聚光高亮卡", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(AppSpacing.Xs))
                    Text("舞台高光沿卡片缓慢游弋", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(AppSpacing.Md))

            Box(Modifier.fillMaxWidth().height(88.dp)) {
                AppConfetti(Modifier.fillMaxSize())
                Column(
                    Modifier.align(Alignment.Center).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("发版成功", style = MaterialTheme.typography.titleMedium, color = AppColor.StatusSuccess)
                    Text("v0.0.0.2 · 庆祝彩带", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
            AppProgressSteps(steps = listOf("解析", "授权", "执行", "完成"), currentIndex = stepIndex)
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
                    values = barData,
                    highlightIndex = 3,
                    modifier = Modifier.weight(1f).height(72.dp),
                )
                AppMiniBarChart(
                    values = barData,
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
        AppConfirmDialog(
            visible = true,
            title = "删除会话？",
            message = "该操作不可撤销，会话及其历史将永久删除。",
            onDismiss = { showDialog = false },
            confirmText = "删除",
            onConfirm = { showDialog = false },
            tone = AppDialogTone.Danger,
        )
    }
    if (showAlertDialog) {
        AppAlertDialog(
            visible = true,
            title = "容器未启动",
            message = "执行 Shell 前请先在终端启动 PRoot 容器。",
            onDismiss = { showAlertDialog = false },
            tone = AppDialogTone.Warning,
        )
    }
    if (showPromptDialog) {
        AppPromptDialog(
            visible = true,
            title = "新建会话",
            onDismiss = { showPromptDialog = false },
            onConfirm = { showPromptDialog = false },
            placeholder = "给会话起个名字…",
            confirmText = "创建",
        )
    }
    if (showSelectionDialog) {
        AppSelectionDialog(
            visible = true,
            title = "移动到分组",
            options = listOf("会话", "收藏", "归档"),
            selectedIndex = singlePick,
            onSelect = { singlePick = it },
            onDismiss = { showSelectionDialog = false },
        )
    }
    if (showUpdateDialog) {
        AppUpdateDialog(
            visible = true,
            title = "发现新版本",
            version = "0.0.0.3",
            notes = listOf("重构滑扫操作系统", "新增列表菜单与弹窗组件族", "修复若干崩溃"),
            onDismiss = { showUpdateDialog = false },
            onUpdate = { showUpdateDialog = false },
            tone = AppDialogTone.Info,
        )
    }
    AppActionSheet(
        visible = showActionSheet,
        title = "对“deepcode-agent”执行",
        items = listOf(
            AppActionSheetItem(Icons.Rounded.Delete, "删除", danger = true),
            AppActionSheetItem(Icons.Rounded.Archive, "归档"),
        ),
        onItemClick = { showActionSheet = false },
        onDismiss = { showActionSheet = false },
    )
    // 补充弹窗类型渲染
    if (showFormDialog) {
        AppFormDialog(
            visible = true,
            title = "新建反馈",
            onDismiss = { showFormDialog = false },
            onConfirm = { _, _ -> showFormDialog = false },
            subjectLabel = "主题",
            bodyLabel = "详情",
            confirmText = "提交",
            tone = AppDialogTone.Info,
        )
    }
    if (showPermissionDialog) {
        AppPermissionDialog(
            visible = true,
            title = "开启通知权限？",
            message = "开启后我们会在构建完成、会话超时等关键节点提醒你，不会推送无关信息。",
            onDismiss = { showPermissionDialog = false },
            onAllow = { showPermissionDialog = false },
            permissionName = "通知权限",
            allowText = "允许",
            deniedText = "暂不",
        )
    }
    if (showSuccessDialog) {
        AppSuccessDialog(
            visible = true,
            title = "导出成功",
            message = "设计令牌已导出为打包产物。",
            detail = listOf("tokens.json", "AppTokens.kt", "style.css"),
            onDismiss = { showSuccessDialog = false },
            confirmText = "完成",
        )
    }
    if (showMultiSelectDialog) {
        AppMultiSelectDialog(
            visible = true,
            title = "选择批量导出字段",
            options = listOf("会话", "消费", "工具", "文件", "凭据"),
            selected = multiSelData,
            onToggle = { i -> multiSelData = if (i in multiSelData) multiSelData - i else multiSelData + i },
            onDismiss = { showMultiSelectDialog = false },
            onConfirm = { showMultiSelectDialog = false },
        )
    }
    // 命令面板：全屏浮层，须置于滚动内容之外的同级覆盖
    if (paletteOpen) {
        AppCommandPalette(
            visible = true,
            groups = listOf(
                AppCommandGroup("文件", listOf(
                    AppMenuAction("打开文件", Icons.Rounded.InsertDriveFile),
                    AppMenuAction("复制路径", Icons.Rounded.Code),
                    AppMenuAction("新建会话", Icons.Rounded.Add),
                )),
                AppCommandGroup("操作", listOf(
                    AppMenuAction("归档", Icons.Rounded.Archive),
                    AppMenuAction("删除", Icons.Rounded.Delete, danger = true),
                )),
            ),
            query = paletteQuery,
            onQueryChange = { paletteQuery = it },
            onSelect = { paletteOpen = false },
            onDismiss = { paletteOpen = false },
        )
    }
    // 阻塞加载遮罩：演示为 3.2 秒后自动收起
    LaunchedEffect(showLoadingOverlay) {
        if (showLoadingOverlay) {
            delay(3200)
            showLoadingOverlay = false
        }
    }
    AppLoadingOverlay(
        visible = showLoadingOverlay,
        message = "正在同步工作区…",
    )
    // 倒计时防误弹窗：删除操作前强制读秒
    if (showCountdownDialog) {
        AppCountdownDialog(
            visible = true,
            title = "清空回收站",
            message = "回收站内的 12 个会话将被彻底清除，该操作无法撤销。",
            onDismiss = { showCountdownDialog = false },
            onConfirm = { showCountdownDialog = false },
            seconds = 3,
            confirmText = "清空",
            cancelText = "取消",
            tone = AppDialogTone.Danger,
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