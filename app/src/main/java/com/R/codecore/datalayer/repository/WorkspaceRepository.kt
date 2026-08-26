package com.R.codecore.datalayer.repository

import com.R.codecore.datalayer.sqldelight.WorkspaceDb
import com.R.codecore.datalayer.sqldelight.workspace.Workspace_file
import com.R.codecore.datalayer.sqldelight.workspace.Workspace_project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * workspace 域 Repository（设计 §11.4 / L2）：工程 + 文件索引门面。
 */
class WorkspaceRepository(private val db: WorkspaceDb) {

    private val q get() = db.workspaceQueries

    suspend fun createProject(
        id: String, name: String, path: String, type: String?,
        now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) { q.insertProject(id, name, path, type, now, now) }

    suspend fun getProject(id: String): Workspace_project? =
        withContext(Dispatchers.IO) { q.selectProjectById(id).executeAsOneOrNull() }

    suspend fun listProjects(): List<Workspace_project> =
        withContext(Dispatchers.IO) { q.selectAllProjects().executeAsList() }

    suspend fun deleteProject(id: String) =
        withContext(Dispatchers.IO) { q.deleteProject(id) }

    suspend fun upsertFile(
        id: String, projectId: String, relPath: String, kind: String?,
        size: Long?, hash: String?, now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) { q.upsertFile(id, projectId, relPath, kind, size, hash, now) }

    suspend fun listFiles(projectId: String): List<Workspace_file> =
        withContext(Dispatchers.IO) { q.selectFilesByProject(projectId).executeAsList() }

    suspend fun deleteFile(id: String) =
        withContext(Dispatchers.IO) { q.deleteFile(id) }
}
