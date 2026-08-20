package com.R.codecore.feature.agent.presentation.component

import androidx.compose.ui.res.stringResource
import com.R.codecore.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.zth.ZthConfirmationCardStateMachine
import com.R.codecore.feature.agent.presentation.ZthConfirmationCardViewModel

/**
 * ZTH ConfirmationCard 底部 Sheet（Compose UI 组件，不含业务）。
 *
 * 展示：
 *  - 顶部风险徽章（LOW/MED/HIGH/CRITICAL 4 色阶）
 *  - hallucinationConfidence 色条 0~1
 *  - ContentReviewer / CapabilityGuard 命中的规则 ids（最多显示前 10，其余折叠）
 *  - FailureClassification.autoRecoveryHint 解释文案
 *  - 中段：「修改计划」OutlinedTextField（点「修改计划」按钮展开）
 *  - 下段：SwipeToConfirm 滑动条 + 三按钮（修改 / 拒绝 / 取消）
 *
 *  所有交互通过 [ZthConfirmationCardViewModel] 方法回调；不直接写 DB。
 */
@Composable
fun ZthConfirmationCardSheet(
    modifier: Modifier = Modifier,
    vm: ZthConfirmationCardViewModel,
    onDismissRequest: () -> Unit = {}
) {
    val ui by vm.uiState.collectAsState()
    val payload = ui.pendingCard ?: run {
        // 无挂起卡片 → 空 UI（一般不进来）
        Box(modifier.height(1.dp).background(Color.Transparent)) {}
        return
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(Modifier.padding(Spacing.md)) {
            // ── 顶部：风险徽章 + 标题 + 关闭按钮 ──────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RiskBadge(confidence = payload.hallucinationConfidence, tier = payload.tier.tier)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "ZTH 幻觉确认 · ${payload.cardTemplateId}·chain-${payload.chainIndex}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "触发：${payload.triggerSubClass.name}（tier=${payload.tier}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(
                    onClick = { vm.onClickCancel() },
                    enabled = !ui.committing && payload.tier.tier <= 1
                ) {
                    Text(if (payload.tier.tier >= 2) stringResource(R.string.ui______6e2b79aa_2) else stringResource(R.string.ui____625fb26b_7))
                }
            }

            // ── hallucinationConfidence 色条（0~1）────────────────────
            Spacer(Modifier.height(Spacing.sm))
            HallucinationBar(confidence = payload.hallucinationConfidence)

            // ── 命中规则 ids（最多 10 条）──────────────────────────────
            if (payload.hitRuleIds.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "命中规则（${payload.hitRuleIds.size}）：",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(Spacing.xs))
                val display = payload.hitRuleIds.take(10)
                display.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        row.forEachIndexed { i, rid ->
                            val isHigh = rid.startsWith("pii_") || rid.startsWith("e8_") || rid.startsWith("hall_")
                            Box(
                                Modifier
                                    .weight(1f)
                                    .padding(end = if (i == 0) 6.dp else 0.dp)
                                    .background(
                                        if (isHigh) Color(0xFFFFEBEE) else Color(0xFFF3E5F5),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    rid,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isHigh) Color(0xFFB71C1C) else Color(0xFF4A148C)
                                )
                            }
                        }
                    }
                }
                if (payload.hitRuleIds.size > 10) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "其余 ${payload.hitRuleIds.size - 10} 条规则已省略（进入 zth_user_confirmed_sentinels.s_cardPayloadCiphertext 审计）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── explanation 文案（FailureClassification.autoRecoveryHint） ──
            payload.explanation?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(Spacing.sm))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider(Modifier.padding(vertical = Spacing.sm))

            // ── 中段：修改计划（点击修改 → 文本框展开）──────────────────
            var showEdit by remember { mutableStateOf(false) }
            var editText by remember { mutableStateOf(payload.modifiedPlanPlaintext ?: payload.planPlaintext.take(200)) }
            if (!showEdit) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showEdit = true; vm.onClickModifyPlan() },
                        enabled = !ui.committing,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) { Text(stringResource(R.string.ui__________ea569350_2)) }
                }
            } else {
                Text(stringResource(R.string.ui______621aea14_2), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    placeholder = { Text(stringResource(R.string.ui_________b19d3f5d_2)) }
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showEdit = false; vm.onAbortEdit() }) { Text(stringResource(R.string.ui______cbb46593_2)) }
                    Button(
                        onClick = { showEdit = false; vm.onDoneEdit(editText) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) { Text(stringResource(R.string.ui_________00433286_2)) }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = Spacing.sm))

            // ── 下段：三按钮 + Swipe ──────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        val reason = if (showEdit) editText.takeLast(80) else null
                        vm.onClickReject(reason)
                    },
                    enabled = !ui.committing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) { Text(stringResource(R.string.ui______36d2d19a_2)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.onClickCancel() }, enabled = !ui.committing && payload.tier.tier <= 1) {
                    Text(if (payload.tier.tier >= 2) stringResource(R.string.ui_______e6f7b9c8_2) else stringResource(R.string.ui______45292500_2))
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            SwipeToConfirm(
                modifier = Modifier.fillMaxWidth(),
                enabled = !ui.committing && payload.tier.tier <= 1 || (payload.tier.tier >= 2 && !ui.committing),
                label = buildString {
                    append("滑动≥92% 确认执行（ZTH-0 幻觉零容忍）")
                    if (ui.currentState == ZthConfirmationCardStateMachine.CardState.CONFIRMING_TX) append(stringResource(R.string.ui_link_24ee85e1_2))
                },
                onProgressChange = { pct -> vm.onSwipe(pct) },
                onConfirmed = { if (ui.confirmButtonEnabled) vm.onClickConfirm() }
            )
            if (ui.committing) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    stringResource(R.string.ui_______e8de15ba_2),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            ui.lastError?.let {
                Spacer(Modifier.height(Spacing.xs))
                Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB71C1C))
            }
        }
    }
}

/** 风险徽章（颜色按 confidence+tier）：LOW < 0.3 绿 / MED 0.3~0.6 黄 / HIGH 0.6~0.9 橙红 / CRITICAL ≥0.9 深红 */
@Composable
private fun RiskBadge(confidence: Float, tier: Int) {
    val tierBoost = tier * 0.05f
    val adjusted = (confidence + tierBoost).coerceIn(0f, 1f)
    val (bg, fg, text) = when {
        adjusted >= 0.90f -> Triple(Color(0xFFB71C1C), Color.White, stringResource(R.string.ui_critical_b86271d9_2))
        adjusted >= 0.60f -> Triple(Color(0xFFE65100), Color.White, stringResource(R.string.ui_high_821cf95f_2))
        adjusted >= 0.30f -> Triple(Color(0xFFF9A825), Color.Black, stringResource(R.string.ui_med_b6334d78_2))
        else -> Triple(Color(0xFF2E7D32), Color.White, stringResource(R.string.ui_low_44246029_2))
    }
    Box(
        Modifier
            .background(bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

/** 幻觉置信分色条 0~1（渐变 绿→黄→橙→红）。 */
@Composable
private fun HallucinationBar(confidence: Float) {
    val pct = confidence.coerceIn(0f, 1f)
    val tint = when {
        pct >= 0.90f -> Color(0xFFC62828)
        pct >= 0.60f -> Color(0xFFEF6C00)
        pct >= 0.30f -> Color(0xFFFDD835)
        else -> Color(0xFF43A047)
    }
    Column {
        Text("幻觉置信分：${String.format("%.0f%%", pct * 100)}（越接近 100% 越像幻觉）",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(pct)
                    .height(8.dp)
                    .background(tint, RoundedCornerShape(4.dp))
            )
        }
    }
}
