package com.R.codecore.datalayer.store

import com.R.codecore.datalayer.sqldelight.InfraDb

/**
 * 一等 Queue（设计 §6.3）：可靠队列，只管持久化数据面（触发/执行交现有 WorkManager/协程）。
 * at-least-once 消费：pending 取后可标记 running/done/failed，失败可重排 next_run。
 */
data class QueueItem(
    val id: Long,
    val topic: String,
    val payload: String,
    val status: String,
    val attempt: Long,
    val nextRun: Long,
    val errorMsg: String?,
    val createdAt: Long,
)

class Queue(private val db: InfraDb) {

    private val q get() = db.queueQueries

    fun enqueue(topic: String, payload: String, nextRun: Long = System.currentTimeMillis()): Long {
        q.insertQueueItem(topic, payload, "pending", 0, nextRun, null, System.currentTimeMillis())
        // selectLastInsertId 生成形态为 ExecutableQuery<Long>（单列函数查询直接返回标量）
        return q.selectLastInsertId().executeAsOne()
    }

    fun pending(topic: String, now: Long = System.currentTimeMillis()): List<QueueItem> =
        q.selectPending(topic, "pending", now).executeAsList().map { it.toItem() }

    fun mark(status: String, attempt: Long, error: String?, id: Long) =
        q.markStatus(status, attempt, error, id)

    fun delete(id: Long) = q.deleteQueueItem(id)

    fun get(id: Long): QueueItem? = q.selectById(id).executeAsOneOrNull()?.toItem()

    private fun com.R.codecore.datalayer.sqldelight.infra.Queue_store.toItem() = QueueItem(
        id = id,
        topic = topic,
        payload = payload,
        status = status,
        attempt = attempt,
        nextRun = next_run,
        errorMsg = error_msg,
        createdAt = created_at,
    )
}
