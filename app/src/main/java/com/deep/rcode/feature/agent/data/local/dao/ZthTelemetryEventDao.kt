package com.deep.rcode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.deep.rcode.feature.agent.data.local.entity.ZthTelemetryEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ZthTelemetryEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(entity: ZthTelemetryEventEntity): Long

    /** C.4.16 4 张 Canvas 图表数据查询（全部时间范围；用户界面按「最近 7d / 30d」本地过滤）。 */
    @Query("SELECT * FROM zth_telemetry_events ORDER BY createdAtMs ASC")
    fun observeAll(): Flow<List<ZthTelemetryEventEntity>>

    @Query("SELECT * FROM zth_telemetry_events WHERE createdAtMs BETWEEN :fromMs AND :toMs ORDER BY createdAtMs ASC")
    suspend fun getRange(fromMs: Long, toMs: Long): List<ZthTelemetryEventEntity>

    /** 清理 > 90d 事件（后台 job 调）。 */
    @Query("DELETE FROM zth_telemetry_events WHERE createdAtMs < :beforeMs")
    suspend fun deleteOld(beforeMs: Long)

    @Query("SELECT COUNT(*) FROM zth_telemetry_events")
    suspend fun countAll(): Long
}
