package com.core.deepcode.datalayer.store

import com.core.deepcode.datalayer.sqldelight.InfraDb

/**
 * 一等 TimeSeries（设计 §6.5）：审计/用量/agent 轨迹时序。
 * 热数据全量、冷数据分层采样归档（清理钩子见 [purgeOlderThan]）。
 */
data class TsEntry(
    val id: Long,
    val ts: Long,
    val type: String,
    val payloadJson: String,
    val meta: String?,
)

class TimeSeries(private val db: InfraDb) {

    private val q get() = db.tsQueries

    fun record(ts: Long, type: String, payloadJson: String, meta: String? = null): Long {
        q.insertTs(ts, type, payloadJson, meta)
        // selectLastInsertId 生成形态为 ExecutableQuery<Long>（单列函数查询直接返回标量）
        return q.selectLastInsertId().executeAsOne()
    }

    fun byType(type: String): List<TsEntry> =
        q.selectTsByType(type).executeAsList().map { it.toEntry() }

    fun range(type: String, from: Long, to: Long): List<TsEntry> =
        q.selectTsRange(type, from, to).executeAsList().map { it.toEntry() }

    /** 分层采样归档的清理钩子：删除某类型早于阈值的冷数据（设计 §6.5）。返回清理后该类型剩余条数。 */
    fun purgeOlderThan(type: String, thresholdTs: Long): Int {
        q.deleteTsOlderThan(type, thresholdTs)
        // countTsByType 生成形态为 Query<Long>（COUNT(*) 单列函数查询直接返回标量）
        return q.countTsByType(type).executeAsOne().toInt()
    }

    private fun com.core.deepcode.datalayer.sqldelight.infra.Ts_store.toEntry() =
        TsEntry(id, ts, type, payload_json, meta)
}
