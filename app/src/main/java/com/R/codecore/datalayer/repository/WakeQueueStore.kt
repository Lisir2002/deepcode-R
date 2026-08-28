package com.R.codecore.datalayer.repository

import com.R.codecore.datalayer.sqldelight.agent.Wake_queue

/**
 * 唤醒队列存储门面。自 v2-full-cleanup 起由 [AgentRepository]（V2 SQLDelight）实现，
 * 供 [WakeQueueManager] 及单元测试注入，隔离具体实现、便于内存 fake 验证。
 */
interface WakeQueueStore {
    suspend fun upsertWakeItem(
        wakeId: String, sessionId: String, source: String, type: String, content: String,
        status: String, createdAtMs: Long,
    )

    suspend fun listWakeBySessionAndStatus(sessionId: String, status: String): List<Wake_queue>

    suspend fun markWakeItemsConsumedBatch(ids: List<String>, status: String)

    suspend fun listPendingWakeItems(): List<Wake_queue>
}