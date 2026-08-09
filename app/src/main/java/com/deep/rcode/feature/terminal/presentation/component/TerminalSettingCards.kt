package com.deep.rcode.feature.terminal.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deep.rcode.core.theme.Brand
import com.deep.rcode.core.theme.Elevation
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.agent.domain.container.ContainerInitState
import com.deep.rcode.feature.terminal.data.bundle.BundleInstallState
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundle
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId
import compose.icons.FeatherIcons
import compose.icons.feathericons.Box
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Globe
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Search
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Trash2

/**
 * 终端设置相关共享组件 + Tokens。
 * 三份页面（TerminalSettingsScreen / TerminalBundleManagerScreen / TerminalCustomPackagesScreen）
 * 统一从这里拿卡片与样式，保证一改全改。
 */

// ================================================================
// Tokens：颜色 + 尺寸 + 按钮规格
// ================================================================
@Immutable
object SemanticColors {
    /** 成功/已就绪：统一绿色（Brand.StatusGreen），不与品牌蓝（primary）混用，避免状态语义混淆 */
    val Success: Color
        @Composable get() = if (isSystemInDarkTheme()) Brand.StatusGreen.Dark else Brand.StatusGreen.Light
    /** 进行中：使用主题 secondary，跟随亮/暗切换 */
    val InProgress: Color @Composable get() = MaterialTheme.colorScheme.secondary
    /** 失败/错误：主题 error，跟随亮/暗切换 / 动态色 */
    val Error: Color @Composable get() = MaterialTheme.colorScheme.error
    /** 警告/进行中：使用主题 tertiary，跟随亮/暗切换 */
    val Warning: Color @Composable get() = MaterialTheme.colorScheme.tertiary
}

@Immutable
object DotSize {
    /** 大状态点：容器摘要卡右上角 */
    val Status = 10.dp
    /** 小状态点：Bundle 卡 / 自定义包列表项 */
    val Chip = 8.dp
}

@Immutable
object ProgressSize {
    val Height = 4.dp
    val Corner = RoundedCornerShape(6.dp)
}

@Immutable
object ButtonSpec {
    val Height = 36.dp
    val IconTextSpacer = Spacing.xs   // 4 dp（与项目 Spacing.xs 对齐）
    val TextFontSize = 13.sp
    val IconSize = 18.dp
    val ChipIndicatorSize = 16.dp
}

// 给 Theme 注入的钩子（现在用 default，以后方便换主题统一覆盖）
val LocalTerminalButtonSpec = staticCompositionLocalOf { ButtonSpec }

// ================================================================
// 共享卡片 1：容器环境大卡片（三种变体）
//   - FULL：TerminalSettings 主入口：初始化 + 切换镜像 + 重置
//   - INIT_ONLY：子页只需要"初始化容器" CTA
//   - READ_ONLY：子页连 CTA 都不要
// ================================================================
enum class ContainerCardMode { FULL, INIT_ONLY, READ_ONLY }

@Composable
internal fun SharedContainerEnvCard(
    containerInstalled: Boolean,
    initProgress: ContainerInitState,
    storageUsedMb: Long,
    mode: ContainerCardMode = ContainerCardMode.FULL,
    customInstallState: BundleInstallState? = null,
    onInit: () -> Unit = {},
    onReset: () -> Unit = {},
    onPickMirror: () -> Unit = {}
) {
    val processingTextWhenReady: String? = run {
        if (initProgress !is ContainerInitState.Ready) return@run null
        val bundleId = when (initProgress) {
            is ContainerInitState.BundleInstalling -> initProgress.bundleId
            is ContainerInitState.BundleUninstalling -> initProgress.bundleId
            else -> null
        }
        when {
            bundleId != null ->
                if (initProgress is ContainerInitState.BundleInstalling) "正在安装${bundleId.stableKey}…"
                else "正在卸载${bundleId.stableKey}…"
            customInstallState is BundleInstallState.Installing -> "正在安装自定义包…"
            customInstallState is BundleInstallState.Uninstalling -> "正在卸载自定义包…"
            else -> null
        }
    }
    val statusText = when (initProgress) {
        is ContainerInitState.Idle -> "未初始化"
        is ContainerInitState.ExtractingRootfs -> "正在解压 rootfs…"
        ContainerInitState.DeployingProot -> "正在部署 proot…"
        is ContainerInitState.Ready -> processingTextWhenReady ?: "已就绪"
        is ContainerInitState.BundleInstalling ->
            "已就绪（正在安装${initProgress.bundleId?.stableKey ?: "功能包"}…）"
        is ContainerInitState.BundleUninstalling -> "已就绪（正在卸载…）"
        is ContainerInitState.Failed -> "失败：${initProgress.reason.take(20)}…"
    }
    val dotColor: Color = when {
        initProgress is ContainerInitState.Failed -> SemanticColors.Error
        !containerInstalled && initProgress !is ContainerInitState.ExtractingRootfs
            && initProgress != ContainerInitState.DeployingProot -> SemanticColors.Error
        containerInstalled -> SemanticColors.Success
        else -> SemanticColors.Warning
    }

    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = TerminalCardsSpec.BorderAlpha)
    Card(
        modifier = Modifier
            .padding(horizontal = Spacing.md)
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(Radius.md)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = TerminalCardsSpec.BgSoftAlpha)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = TerminalCardsSpec.Elevation),
        shape = RoundedCornerShape(Radius.md)  // 10.dp
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    FeatherIcons.HardDrive,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(text = "本地容器环境", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(DotSize.Status)
                        .background(dotColor, shape = RoundedCornerShape(50))
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = when {
                    containerInstalled -> "占用 $storageUsedMb MB · Alpine 3.21 · arm64-v8a · PRoot"
                    else -> "rootfs 解压后约 150 MB，按需安装 Bundle 后 300-500 MB"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (mode) {
                ContainerCardMode.FULL -> {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (!containerInstalled) {
                            PrimaryButton(onClick = onInit, icon = FeatherIcons.Plus, text = "初始化环境")
                        }
                        if (containerInstalled) {
                            SecondaryButton(
                                onClick = onPickMirror,
                                icon = FeatherIcons.Globe,
                                text = "切换镜像源"
                            )
                            DangerTextButton(onClick = onReset, icon = FeatherIcons.Trash2, text = "重置环境")
                        }
                    }
                }
                ContainerCardMode.INIT_ONLY -> if (!containerInstalled) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    PrimaryButton(onClick = onInit, icon = FeatherIcons.Plus, text = "初始化容器")
                }
                ContainerCardMode.READ_ONLY -> Unit // no CTA
            }
        }
    }
}

// ================================================================
// 共享卡片 2：AI 推荐组合条
// ================================================================
@Composable
internal fun SharedAiRecommendationStrip(
    allInstalled: Boolean,
    containerReady: Boolean,
    onInstallAll: () -> Unit
) {
    val accent =
        if (allInstalled) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.secondaryContainer
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = TerminalCardsSpec.BorderAlpha)
    Card(
        modifier = Modifier
            .padding(horizontal = Spacing.md)
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(Radius.md)),
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = TerminalCardsSpec.BgStrongAlpha)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = TerminalCardsSpec.Elevation),
        shape = RoundedCornerShape(Radius.md)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    FeatherIcons.Cpu,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = "AI 推荐组合（Python + rg + Git + Bash + Curl）",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = if (allInstalled) "全部组件已就绪，AI 运行代码块/搜索/git 开箱即用。"
                else "共约 75 MB，覆盖 AI 代码运行与搜索。推荐使用 AI 功能的用户一键安装。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            if (allInstalled) {
                AssistChip(
                    onClick = {},
                    leadingIcon = {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(ButtonSpec.ChipIndicatorSize))
                    },
                    label = { Text("已完成", fontSize = ButtonSpec.TextFontSize) },
                    enabled = false
                )
            } else {
                PrimaryButton(
                    onClick = onInstallAll,
                    enabled = containerReady,
                    icon = FeatherIcons.Plus,
                    text = "一键安装"
                )
            }
        }
    }
}

// ================================================================
// 共享卡片 3：Bundle 单张卡
// ================================================================
@Composable
internal fun SharedBundleCard(
    bundle: TerminalBundle,
    state: BundleInstallState,
    containerReady: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit
) {
    data class Status(val text: String, val color: Color, val showProgress: Boolean)
    val status = when (state) {
        is BundleInstallState.NotInstalled ->
            Status("未安装", MaterialTheme.colorScheme.onSurfaceVariant, false)
        is BundleInstallState.Installing ->
            Status("安装中…${state.line?.take(30) ?: ""}", SemanticColors.InProgress, true)
        is BundleInstallState.Uninstalling ->
            Status("卸载中…", SemanticColors.InProgress, true)
        is BundleInstallState.Failed ->
            Status("失败：${state.reason.take(16)}", SemanticColors.Error, false)
        is BundleInstallState.Installed ->
            Status("已安装", SemanticColors.Success, false)
    }

    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = TerminalCardsSpec.BorderAlpha)
    Card(
        modifier = Modifier
            .padding(horizontal = Spacing.md)
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(Radius.md)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = TerminalCardsSpec.Elevation),
        shape = RoundedCornerShape(Radius.md)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    sharedBundleIcon(bundle),
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = bundle.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(DotSize.Chip)
                        .background(status.color, shape = RoundedCornerShape(50))
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = "约 ${bundle.sizeEstimateMb} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = bundle.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = when (state) {
                    is BundleInstallState.Installing -> "状态：${status.text.take(50)} …"
                    is BundleInstallState.Failed -> "状态：${status.text}"
                    is BundleInstallState.Installed -> "状态：已安装 · 配置版本 v${bundle.version}"
                    is BundleInstallState.Uninstalling -> "状态：正在卸载…"
                    else -> "状态：${status.text}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = status.color,
                fontFamily = FontFamily.Monospace
            )
            if (status.showProgress) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                SharedLinearProgress()
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            when (state) {
                is BundleInstallState.Installed ->
                    DangerOutlinedButton(onClick = onUninstall, icon = FeatherIcons.Trash2, text = "卸载")
                is BundleInstallState.Installing, is BundleInstallState.Uninstalling ->
                    ProgressPlaceholderButton(text = "处理中…")
                else ->
                    PrimaryButton(
                        onClick = onInstall,
                        enabled = containerReady,
                        icon = FeatherIcons.Plus,
                        text = "安装"
                    )
            }
        }
    }
}

@Composable
private fun sharedBundleIcon(b: TerminalBundle): ImageVector = when (b.id) {
    TerminalBundleId.PYTHON -> FeatherIcons.Cpu
    TerminalBundleId.NODE -> FeatherIcons.Box
    TerminalBundleId.RIPGREP -> FeatherIcons.Search
    TerminalBundleId.GIT -> FeatherIcons.GitBranch
    TerminalBundleId.BASH -> FeatherIcons.Terminal
    TerminalBundleId.NET -> FeatherIcons.Globe
}

// ================================================================
// 共享基础：进度条 + 按钮组（统一高度/字号/间距，一改全改）
// ================================================================
@Composable
internal fun SharedLinearProgress(modifier: Modifier = Modifier, color: Color? = null) {
    LinearProgressIndicator(
        modifier = modifier
            .fillMaxWidth()
            .height(ProgressSize.Height)
            .clip(ProgressSize.Corner),
        color = color ?: SemanticColors.InProgress
    )
}

@Composable
internal fun PrimaryButton(
    onClick: () -> Unit,
    icon: ImageVector,
    text: String,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(ButtonSpec.Height)
    ) {
        Icon(icon, null, modifier = Modifier.size(ButtonSpec.IconSize))
        Spacer(modifier = Modifier.width(ButtonSpec.IconTextSpacer))
        Text(text, fontSize = ButtonSpec.TextFontSize)
    }
}

@Composable
internal fun SecondaryButton(
    onClick: () -> Unit,
    icon: ImageVector,
    text: String,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(ButtonSpec.Height)
    ) {
        Icon(icon, null, modifier = Modifier.size(ButtonSpec.IconSize))
        Spacer(modifier = Modifier.width(ButtonSpec.IconTextSpacer))
        Text(text, fontSize = ButtonSpec.TextFontSize)
    }
}

@Composable
internal fun DangerOutlinedButton(
    onClick: () -> Unit,
    icon: ImageVector,
    text: String,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(ButtonSpec.Height),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = SemanticColors.Error)
    ) {
        Icon(icon, null, modifier = Modifier.size(ButtonSpec.IconSize), tint = SemanticColors.Error)
        Spacer(modifier = Modifier.width(ButtonSpec.IconTextSpacer))
        Text(text, fontSize = ButtonSpec.TextFontSize, color = SemanticColors.Error)
    }
}

@Composable
internal fun DangerTextButton(
    onClick: () -> Unit,
    icon: ImageVector,
    text: String,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(ButtonSpec.Height),
        colors = ButtonDefaults.textButtonColors(contentColor = SemanticColors.Error)
    ) {
        Icon(icon, null, modifier = Modifier.size(ButtonSpec.IconSize), tint = SemanticColors.Error)
        Spacer(modifier = Modifier.width(ButtonSpec.IconTextSpacer))
        Text(text, fontSize = ButtonSpec.TextFontSize)
    }
}

@Composable
internal fun ProgressPlaceholderButton(
    text: String
) {
    TextButton(
        onClick = {},
        enabled = false,
        modifier = Modifier.height(ButtonSpec.Height),
        colors = ButtonDefaults.textButtonColors(disabledContentColor = SemanticColors.InProgress)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(ButtonSpec.ChipIndicatorSize),
            strokeWidth = 2.dp,
            color = SemanticColors.InProgress
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(text, fontSize = ButtonSpec.TextFontSize)
    }
}

@Composable
internal fun NeutralTextButton(
    onClick: () -> Unit,
    icon: ImageVector?,
    text: String,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(ButtonSpec.Height)
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(ButtonSpec.IconSize))
            Spacer(modifier = Modifier.width(ButtonSpec.IconTextSpacer))
        }
        Text(text, fontSize = 12.sp)
    }
}
