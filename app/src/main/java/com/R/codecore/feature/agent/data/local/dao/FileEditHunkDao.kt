package com.R.codecore.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.R.codecore.feature.agent.data.local.entity.FileEditHunkEntity

@Dao
interface FileEditHunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FileEditHunkEntity)

    @Query("SELECT * FROM file_edit_hunks WHERE sessionId = :sessionId ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun getRecentBySession(sessionId: String, limit: Int): List<FileEditHunkEntity>

    @Query("SELECT * FROM file_edit_hunks WHERE id = :id")
    suspend fun getById(id: String): FileEditHunkEntity?

    @Query("DELETE FROM file_edit_hunks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM file_edit_hunks WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
