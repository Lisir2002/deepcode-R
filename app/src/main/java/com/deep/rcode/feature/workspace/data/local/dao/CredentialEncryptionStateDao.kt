package com.deep.rcode.feature.workspace.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.deep.rcode.core.db.entity.CredentialEncryptionStateEntity

@Dao
interface CredentialEncryptionStateDao {

    /** 一定返回一行；空 = null，需先 initialise。 */
    @Query("SELECT * FROM credential_encryption_state WHERE id = 1 LIMIT 1")
    suspend fun getSingleOrNull(): CredentialEncryptionStateEntity?

    /** 永远 upsert 一行（id=1）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CredentialEncryptionStateEntity)
}