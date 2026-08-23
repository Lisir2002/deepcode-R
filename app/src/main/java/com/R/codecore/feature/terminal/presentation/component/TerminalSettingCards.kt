package com.R.codecore.feature.terminal.presentation.component

import androidx.compose.ui.res.stringResource
import com.R.codecore.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
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
import com.R.codecore.core.theme.Brand
import com.R.codecore.core.theme.Elevation
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.container.ContainerArch
import com.R.codecore.feature.agent.domain.container.ContainerInitState
import com.R.codecore.feature.terminal.data.bundle.BundleInstallState
import com.R.codecore.feature.terminal.data.bundle.TerminalBundle
import com.R.codecore.feature.terminal.data.bundle.TerminalBundleId

/**
 * 终端设置相关共享组件 + Tokens。
 * 两份页面（TerminalSettingsScreen / TerminalBundleManagerScreen）
 * 统一从这里拿卡片与样式，保证一改全改。
 */

// ================================================================
// Tokens：颜色 + 尺寸 + 按钮规格
// ================================================================
@Immutable
object SemanticColors {
    /** 计算标准相对亮度 Y (sRGB linear)，阈值 0.30 以下视为"深色" */
    private fun Color.relativeLuminance(): Float {
        fun f(c: Float) = if (c <= 0.04045f) c / 12.92f else Math.pow((c + 0.055) / 1.055, 2.4).toFloat()
        return 0.2126f * f(red) + 0.7152f * f(green) + 0.0722f * f(blue)
    }

    /** 成功/已就绪：统一绿色（Brand.StatusGreen），不与品牌蓝（primary）混用。
     *  通过 MaterialTheme.colorScheme.background 的亮度判断程序实际是暗/亮主题，
     *  外壳 UI 必然跟随程序主题——即便系统是亮色但 App 被切到"强制黑"，
     *  也会正确选 Dark 色（而非系统 uiMode）。 */
    val Success: Color
        @Composable get() {
            val bg = androidx.compose.material3.MaterialTheme.colorScheme.background
            return if (bg.relativeLuminance() < 0.30f) Brand.StatusGreen.Dark else Brand.StatusGreen.Light
        }
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
    profileArch: ContainerArch = ContainerArch.ARM64,
    mode: ContainerCardMode = ContainerCardMode.FULL,
    customInstallState: BundleInstallState? = null,
    onInit: () -> Unit = {},
    onReset: () -> Unit = {},
    onPickMirror: () -> Unit = {}
) {
    val archLabel: String = when (profileArch) {
        ContainerArch.ARM64 -> "arm64-v8a · PRoot"
        ContainerArch.X86_64 -> stringResource(R.string.ui_x86_64_a2f3daf8)
    }
    val processingTextWhenReady: String? = run {
        // 安装/卸载功能包时，initProgress 会是 BundleInstalling / BundleUninstalling（而非 Ready），
        // 所以不能用「先判断 !Ready 就 return」，那样后面 Installing 的分支永远进不去。
        // 这里直接从 initProgress 取正在装/卸载的 bundleId，并结合 customInstallState 看自定义包：
        val bundleId = when (initProgress) {
            is ContainerInitState.BundleInstalling -> initProgress.bundleId
            is ContainerInitState.BundleUninstalling -> initProgress.bundleId
            else -> null
        }
        when {
            initProgress is ContainerInitState.BundleInstalling && bundleId != null ->
                "正在安装${bundleId.stableKey}…"
            initProgress is ContainerInitState.BundleUninstalling && bundleId != null ->
                "正在卸载${bundleId.stableKey}…"
            customInstallState is BundleInstallState.Installing -> stringResource(R.string.ui__________0e1c26ee)
            customInstallState is BundleInstallState.Uninstalling -> stringResource(R.string.ui__________b17be76a)
            else -> null
        }
    }
    val statusText = when (initProgress) {
        is ContainerInitState.Idle -> stringResource(R.string.ui______aeade8e9)
        is ContainerInitState.ExtractingRootfs -> stringResource(R.string.ui______c95456ed)
        ContainerInitState.DeployingProot -> stringResource(R.string.ui______c5778ca3)
        is ContainerInitState.Ready -> processingTextWhenReady ?: stringResource(R.string.ui_____c30ecc7a)
        is ContainerInitState.BundleInstalling ->
            "已就绪（正在安装${initProgress.bundleId?.stableKey ?: "功能包"}…）"
        is ContainerInitState.BundleUninstalling -> stringResource(R.string.ui_____cc71b3ef)
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
                    Icons.Rounded.Storage,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(text = stringResource(R.string.ui________3eb03d3e), style = MaterialTheme.typography.titleMedium)
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
                    containerInstalled -> "占用 $storageUsedMb MB · Alpine 3.21 · $archLabel"
                    else -> stringResource(R.string.ui_rootfs_602d203f)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (mode) {
                ContainerCardMode.FULL -> {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (!containerInstalled) {
                            PrimaryButton(onClick = onInit, icon = Icons.Rounded.Add, text = stringResource(R.string.ui_______30947f5e))
                        }
                        if (containerInstalled) {
                            SecondaryButton(
                                onClick = onPickMirror,
                                icon = Icons.Rounded.Public,
                                text = stringResource(R.string.ui_______52fffa6f)
                            )
                            DangerTextButton(onClick = onReset, icon = Icons.Rounded.Delete, text = stringResource(R.string.ui______fce7f40c))
                        }
                    }
                }
                ContainerCardMode.INIT_ONLY -> if (!containerInstalled) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    PrimaryButton(onClick = onInit, icon = Icons.Rounded.Add, text = stringResource(R.string.ui_______89a64382))
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
                    Icons.Rounded.Memory,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.ui_ai_b07ec14f),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = if (allInstalled) "全部组件已就绪，AI 运行代码块/搜索/git 开箱即用。"
                else stringResource(R.string.ui____2441ae83),
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
                    label = { Text(stringResource(R.string.ui_____fad5222c), fontSize = ButtonSpec.TextFontSize) },
                    enabled = false
                )
            } else {
                PrimaryButton(
                    onClick = onInstallAll,
                    enabled = containerReady,
                    icon = Icons.Rounded.Add,
                    text = stringResource(R.string.ui______04a0913e)
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
            Status(stringResource(R.string.ui_____f45c4e65), MaterialTheme.colorScheme.onSurfaceVariant, false)
        is BundleInstallState.Installing ->
            Status("安装中…${state.line?.take(30) ?: ""}", SemanticColors.InProgress, true)
        is BundleInstallState.Uninstalling ->
            Status(stringResource(R.string.ui_____69827c2b), SemanticColors.InProgress, true)
        is BundleInstallState.Failed ->
            Status("失败：${state.reason.take(16)}", SemanticColors.Error, false)
        is BundleInstallState.Installed ->
            Status(stringResource(R.string.ui_____9d5bf2a1), SemanticColors.Success, false)
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
                    is BundleInstallState.Uninstalling -> stringResource(R.string.ui____ef1eb44a)
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
                    DangerOutlinedButton(onClick = onUninstall, icon = Icons.Rounded.Delete, text = stringResource(R.string.ui____81824cff))
                is BundleInstallState.Installing, is BundleInstallState.Uninstalling ->
                    ProgressPlaceholderButton(text = stringResource(R.string.ui_____cf978c02))
                else ->
                    PrimaryButton(
                        onClick = onInstall,
                        enabled = containerReady,
                        icon = Icons.Rounded.Add,
                        text = stringResource(R.string.ui____e655a410_2)
                    )
            }
        }
    }
}

@Composable
private fun sharedBundleIcon(b: TerminalBundle): ImageVector = when (b.id) {
    TerminalBundleId.PYTHON -> Icons.Rounded.Memory
    TerminalBundleId.NODE -> Icons.Rounded.Code
    TerminalBundleId.RIPGREP -> Icons.Rounded.Search
    TerminalBundleId.GIT -> Icons.Rounded.AccountTree
    TerminalBundleId.BASH -> Icons.Rounded.Terminal
    TerminalBundleId.NET -> Icons.Rounded.Public
    TerminalBundleId.QEMU_X86_TRANSLATOR -> Icons.Rounded.Memory
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
