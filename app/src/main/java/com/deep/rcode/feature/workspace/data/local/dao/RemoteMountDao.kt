package com.deep.rcode.feature.workspace.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.deep.rcode.feature.workspace.data.local.entity.RemoteMountEntity
import kotlinx.coroutines.flow.Flow

/**
 * DB-SHIELD-RC68 P2-1：拆分 RemoteConnectionDao（一张 DAO 管两张表）。
 * 原 RemoteConnectionDao 把 connection + mount 全塞一起，IDE 搜不到 RemoteMountDao 导致重复实现/注入混淆。
 */
@Dao
interface RemoteMountDao {
    @Query("SELECT * FROM remote_mounts")
    fun getAllMounts(): Flow<List<RemoteMountEntity>>

    @Query("SELECT * FROM remote_mounts WHERE id = :id")
    suspend fun getMountById(id: String): RemoteMountEntity?

    @Query("SELECT * FROM remote_mounts WHERE connectionId = :connectionId")
    fun getMountsByConnectionId(connectionId: String): Flow<List<RemoteMountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMount(mount: RemoteMountEntity)

    @Query("SELECT * FROM remote_mounts")
    suspend fun getAllMountsOnce(): List<RemoteMountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllMounts(mounts: List<RemoteMountEntity>)

    @Update
    suspend fun updateMount(mount: RemoteMountEntity)

    @Delete
    suspend fun deleteMount(mount: RemoteMountEntity)
}
