package com.R.codecore.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.R.codecore.feature.agent.data.local.entity.ModeSwitchHistoryEntity

@Dao
interface ModeSwitchHistoryDao {
    @Insert
    suspend fun insert(item: ModeSwitchHistoryEntity): Long

    @Query("SELECT * FROM mode_switch_history WHERE sessionId = :sessionId ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecentBySession(sessionId: String, limit: Int): List<ModeSwitchHistoryEntity>

    @Query("SELECT * FROM mode_switch_history WHERE sessionId = :sessionId AND timestampMs > :since ORDER BY timestampMs ASC")
    suspend fun getBySessionSince(sessionId: String, since: Long): List<ModeSwitchHistoryEntity>

    @Query("DELETE FROM mode_switch_history WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
