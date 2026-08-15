package com.deep.rcode.feature.agent.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deep.rcode.R
import com.deep.rcode.core.theme.Brand
import com.deep.rcode.core.theme.LocalAppDarkMode
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.agent.domain.container.progress.InstallPhaseType
import com.deep.rcode.feature.agent.domain.container.progress.InstallProgress
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.XCircle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import android.os.SystemClock

/** 环境组件状态。 */
enum class EnvironmentStatus { INSTALLED, MISSING, INSTALLING }

/** 单个环境组件状态（UI 渲染用）。 */
data class EnvironmentComponentState(
    val name: String,
    val status: EnvironmentStatus,
    val version: String? = null,
    val path: String? = null,
    /** 安装中进度 0.0..1.0；非安装中为 null。 */
    val installPercent: Float? = null
)

private val EnvJson = Json { ignoreUnknownKeys = true }

/**
 * 从 check_environment 工具结果（transport JSON）解析组件状态列表。
 * content 形如 `{"status":"success","data":{"components":[{"name":"Java","status":"installed","path":"...","version":"..."}]}}`。
 * 解析失败返回空列表。
 */
internal fun parseEnvironmentComponents(content: String): List<EnvironmentComponentState> {
    return runCatching {
        val root = EnvJson.parseToJsonElement(content).jsonObject
        val data = root["data"]?.jsonObject ?: return emptyList()
        val components = data["components"]?.jsonArray ?: return emptyList()
        components.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val status = obj["status"]?.jsonPrimitive?.contentOrNull
            val version = obj["version"]?.jsonPrimitive?.contentOrNull
            val path = obj["path"]?.jsonPrimitive?.contentOrNull
            EnvironmentComponentState(
                name = name,
                status = when (status) {
                    "installed" -> EnvironmentStatus.INSTALLED
                    else -> EnvironmentStatus.MISSING
                },
                version = version,
                path = path
            )
        }
    }.getOrDefault(emptyList())
}

/** 安装进度行：进度条 + 当前阶段描述 + 预计剩余时间（ETA）。 */
@Composable
internal fun InstallProgressRow(progress: InstallProgress) {
    val percent = progress.percent
    val isDark = LocalAppDarkMode.current
    val accent = when (progress.phase) {
        InstallPhaseType.DONE -> Color(0xFF22C55E)
        InstallPhaseType.FAILED -> Color(0xFFEF4444)
        else -> Brand.Blue
    }
    // ETA 计算：跟踪安装起始时间，按「已用时间 / 进度」线性外推剩余时间。
    // 进度回退（新安装会话开始）或首次出现百分比时重置计时起点。
    var installStart by remember { mutableStateOf(0L) }
    var lastPercent by remember { mutableStateOf<Float?>(null) }
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(percent) {
        if (percent != null) {
            val prev = lastPercent
            lastPercent = percent
            if (installStart == 0L || (prev != null && percent < prev)) {
                installStart = SystemClock.elapsedRealtime()
            }
        }
        while (isActive) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val etaSeconds = if (installStart > 0 && percent != null && percent in 0.02f..0.98f) {
        val elapsedMs = (now - installStart).coerceAtLeast(0L)
        (elapsedMs / percent * (1 - percent) / 1000f).toLong().coerceAtLeast(0L)
    } else null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = progress.detail ?: stringResource(R.string.env_overview_installing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (etaSeconds != null && etaSeconds > 0) {
                Text(
                    text = stringResource(R.string.env_overview_eta, formatEta(etaSeconds)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (percent != null) {
                Text(
                    text = "${(percent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (percent != null) {
            LinearProgressIndicator(
                progress = { percent.coerceIn(0f, 1f) },
                color = accent,
                trackColor = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(
                color = accent,
                trackColor = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 秒数格式化为可读的剩余时间（如 1m 30s / 45s / 2h 5m）。 */
private fun formatEta(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    if (minutes < 60) {
        val sec = seconds % 60
        return if (sec > 0) "${minutes}m ${sec}s" else "${minutes}m"
    }
    val hours = minutes / 60
    val remMin = minutes % 60
    return if (remMin > 0) "${hours}h ${remMin}m" else "${hours}h"
}

/** 单个环境组件行：名称 + 状态徽章 + 版本。 */
@Composable
internal fun EnvironmentComponentRow(component: EnvironmentComponentState) {
    val isDark = LocalAppDarkMode.current
    val badge = when (component.status) {
        EnvironmentStatus.INSTALLED -> ComponentBadge(
            FeatherIcons.CheckCircle,
            Color(0xFF22C55E),
            stringResource(R.string.env_status_installed),
            Color(0xFF22C55E),
            Color(0xFF22C55E).copy(alpha = if (isDark) 0.16f else 0.1f)
        )
        EnvironmentStatus.MISSING -> ComponentBadge(
            FeatherIcons.XCircle,
            Color(0xFFEF4444),
            stringResource(R.string.env_status_missing),
            Color(0xFFEF4444),
            Color(0xFFEF4444).copy(alpha = if (isDark) 0.16f else 0.1f)
        )
        EnvironmentStatus.INSTALLING -> ComponentBadge(
            FeatherIcons.Cpu,
            Brand.Blue,
            stringResource(R.string.env_status_installing),
            Brand.Blue,
            Brand.Blue.copy(alpha = if (isDark) 0.16f else 0.1f)
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            imageVector = badge.icon,
            contentDescription = null,
            tint = badge.iconColor,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = component.name,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!component.version.isNullOrBlank()) {
            Text(
                text = component.version,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Surface(
            shape = RoundedCornerShape(Radius.pill),
            color = badge.badgeBg
        ) {
            Text(
                text = badge.badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = badge.badgeColor,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

/** 组件状态徽章的展示信息（图标、颜色、文本）。 */
private data class ComponentBadge(
    val icon: ImageVector,
    val iconColor: Color,
    val badgeText: String,
    val badgeColor: Color,
    val badgeBg: Color
)
