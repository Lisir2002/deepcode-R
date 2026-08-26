package com.R.codecore.datalayer.store

import com.R.codecore.datalayer.sqldelight.InfraDb

/**
 * 一等 BlobStore（设计 §6.4）：大二进制存储。
 * 当前字节直接存 DB BLOB 列（事务原子、备份随库走）；实施期可加单条体积上限护栏改走磁盘文件。
 */
data class BlobMeta(val id: Long, val mime: String?, val size: Long, val createdAt: Long)

class BlobStore(private val db: InfraDb) {

    private val q get() = db.blobQueries

    fun put(data: ByteArray, mime: String? = null): Long {
        q.insertBlob(mime, data.size.toLong(), data, System.currentTimeMillis())
        // selectLastInsertId 生成形态为 ExecutableQuery<Long>（单列函数查询直接返回标量）
        return q.selectLastInsertId().executeAsOne()
    }

    fun get(id: Long): ByteArray? = q.selectBlob(id).executeAsOneOrNull()?.data_

    fun meta(id: Long): BlobMeta? =
        q.selectBlobMeta(id).executeAsOneOrNull()?.let { BlobMeta(it.id, it.mime, it.size, it.created_at) }

    fun delete(id: Long) = q.deleteBlob(id)
}
