package com.R.codecore.datalayer.repository

import com.R.codecore.datalayer.sqldelight.T2iDb
import com.R.codecore.datalayer.sqldelight.t2i.T2i_result
import com.R.codecore.datalayer.sqldelight.t2i.T2i_task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * t2i 域 Repository（设计 §11.5 / L2）：文生图任务 + 多结果门面。
 * 结果图字节存 BlobStore，本域只留 blob_ref 引用。
 */
class T2iRepository(private val db: T2iDb) {

    private val q get() = db.t2iQueries

    suspend fun createTask(
        id: String, prompt: String, paramsJson: String?, status: String,
        now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) { q.insertTask(id, prompt, paramsJson, status, now, now) }

    suspend fun updateTaskStatus(id: String, status: String, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { q.updateTaskStatus(status, now, id) }

    suspend fun getTask(id: String): T2i_task? =
        withContext(Dispatchers.IO) { q.selectTaskById(id).executeAsOneOrNull() }

    suspend fun listTasks(): List<T2i_task> =
        withContext(Dispatchers.IO) { q.selectAllTasks().executeAsList() }

    suspend fun addResult(
        id: String, taskId: String, blobRef: String, seed: Long?, width: Long?, height: Long?, seq: Long,
    ) = withContext(Dispatchers.IO) { q.insertResult(id, taskId, blobRef, seed, width, height, seq) }

    suspend fun listResults(taskId: String): List<T2i_result> =
        withContext(Dispatchers.IO) { q.selectResultsByTask(taskId).executeAsList() }

    suspend fun deleteTask(id: String) =
        withContext(Dispatchers.IO) { q.deleteTask(id) }
}
