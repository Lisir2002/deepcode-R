package com.R.codecore.feature.t2i.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 文生图任务（含同步/异步、重试、崩溃恢复状态机）。
 *
 * ### 状态流转
 * ```
 * PENDING ──开始调用──► RUNNING ──成功──► SUCCESS
 *                      │    │
 *                      │    └──失败且 retryCount < maxRetries──► PENDING_RETRY ──► PENDING
 *                      │
 *                      └──失败且无重试──► FAILED
 *
 * 冷启动时崩溃恢复（T2ITaskRecoveryWorker）：
 *   status ∈ {PENDING, RUNNING, PENDING_RETRY}
 *   AND updatedAtMs < now - 30min
 *   → 要么 remoteTaskId 非空轮询一次（ASYNC 形态），要么直接标记 DANGLING 提供 UI 「重试」按钮。
 * ```
 *
 * ### 快照字段
 * 创建任务时把 provider 的 baseUrl / endpointMode / encryptedApiKey 解析后，
 * 仅快照 providerId + endpointModeRef（配置变更不影响历史任务恢复）；
 * 密钥永远不从 Entity 快照，恢复时从 t2i_providers 实时读取（保证用户改 key 后历史未完成任务用新 key）。
 */
@Entity(
    tableName = "t2i_tasks",
    indices = [
        // RC91：与迁移 39 创建的 index_t2i_tasks_sessionId_createdAtMs（DESC）对齐
        Index(
            value = ["sessionId", "createdAtMs"],
            orders = [Index.Order.ASC, Index.Order.DESC]
        ),
        // 与迁移 39 创建的 index_t2i_tasks_status 对齐
        Index(value = ["status"]),
        // 与迁移 39 创建的 index_t2i_tasks_messageId 对齐
        Index(value = ["messageId"])
    ]
)
data class T2ITaskEntity(
    @PrimaryKey val id: String,
    /** 归属会话（外键 → chat_sessions.id）。 */
    val sessionId: String,
    /** 关联消息（agent_messages.id），便于 UI 在消息气泡里渲染图片。 */
    @ColumnInfo(defaultValue = "''")
    val messageId: String = "",
    val prompt: String,
    @ColumnInfo(defaultValue = "''")
    val negativePrompt: String = "",
    @ColumnInfo(defaultValue = "1024")
    val width: Int = 1024,
    @ColumnInfo(defaultValue = "1024")
    val height: Int = 1024,
    @ColumnInfo(defaultValue = "30")
    val steps: Int = 30,
    /** 0 表示随机种子。 */
    @ColumnInfo(defaultValue = "0")
    val seed: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val hd: Boolean = false,
    /** 路由快照：任务创建时选中的 provider ID。 */
    val providerId: String,
    val modelId: String,
    /**
     * 路由快照：创建任务时的 baseUrl（JSON 字段为 providerRef 名以兼容未来扩展字段）。
     * T2I 设计不把 URL 快照下来（保持最小化快照，避免用户换 baseUrl 后历史任务还打老地址），
     * 所以该字段留空字符串占位（由仓储恢复时用 providerId 实时查 baseUrl）。
     */
    @ColumnInfo(defaultValue = "''")
    val providerRef: String = "",
    /** 路由快照：SYNC/ASYNC/AUTO 当时的解析值。 */
    @ColumnInfo(defaultValue = "''")
    val endpointModeRef: String = "",
    /** PENDING | RUNNING | SUCCESS | FAILED | PENDING_RETRY | DANGLING */
    @ColumnInfo(defaultValue = "'PENDING'")
    val status: String = "PENDING",
    /** 成功后主图本地文件路径（filesDir/t2i_images/{id}.png）。 */
    @ColumnInfo(defaultValue = "''")
    val imagePath: String = "",
    /** 缩略图路径（用于会话列表气泡预览，比主图小，避免一次性载入大图 OOM）。 */
    @ColumnInfo(defaultValue = "''")
    val thumbnailPath: String = "",
    /** ASYNC 形态下远端返回的任务 ID，用于轮询。 */
    @ColumnInfo(defaultValue = "''")
    val remoteTaskId: String = "",
    /** 0~100，供 UI 进度条。 */
    @ColumnInfo(defaultValue = "0")
    val progressPercent: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val retryCount: Int = 0,
    @ColumnInfo(defaultValue = "3")
    val maxRetries: Int = 3,
    /** 机器可读错误码，用于权限引擎 P4/P5 判断是否退款。 */
    @ColumnInfo(defaultValue = "''")
    val errorCode: String = "",
    @ColumnInfo(defaultValue = "''")
    val errorMessage: String = "",
    /** 权限引擎决策快照：ALLOWED | DENIED_* | QUOTA_EXCEEDED。 */
    @ColumnInfo(defaultValue = "''")
    val permissionDecision: String = "",
    /** 已从月度/日/会话额度里扣除的 token 数（用于失败退款回加）。 */
    @ColumnInfo(defaultValue = "0")
    val quotaDeductedTokens: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val createdAtMs: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val updatedAtMs: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val completedAtMs: Long = 0
)
