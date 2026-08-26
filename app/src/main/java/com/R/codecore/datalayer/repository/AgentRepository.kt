package com.R.codecore.datalayer.repository

import com.R.codecore.datalayer.sqldelight.AgentDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * agent 域 Repository（设计 §11.1 / L2）：会话/消息/子块/工具调用/检查点 的访问门面。
 * 业务只依赖本门面，不直接写 SQL。
 */
class AgentRepository(private val db: AgentDb) {

    private val q get() = db.agentQueries

    suspend fun createSession(
        id: String, title: String?, mode: String, model: String?, now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) { q.insertSession(id, title, mode, model, "active", now, now) }

    suspend fun listSessions(): List<com.R.codecore.datalayer.sqldelight.agent.Agent_session> =
        withContext(Dispatchers.IO) { q.selectAllSessions().executeAsList() }

    suspend fun getSession(id: String): com.R.codecore.datalayer.sqldelight.agent.Agent_session? =
        withContext(Dispatchers.IO) { q.selectSessionById(id).executeAsOneOrNull() }

    suspend fun renameSession(id: String, title: String, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { q.updateSessionTitle(title, now, id) }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) { q.deleteSession(id) }

    suspend fun appendMessage(id: String, sessionId: String, role: String, seq: Long, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { q.insertMessage(id, sessionId, role, seq, now) }

    suspend fun appendPart(
        id: String, messageId: String, kind: String, seq: Long,
        text: String?, toolName: String?, toolArgs: String?, toolResult: String?, toolError: String?,
    ) = withContext(Dispatchers.IO) {
        q.insertMessagePart(id, messageId, kind, seq, text, toolName, toolArgs, toolResult, toolError)
    }

    suspend fun appendToolCall(
        id: String, messageId: String, name: String, argsJson: String?, resultJson: String?, status: String,
        now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) { q.insertToolCall(id, messageId, name, argsJson, resultJson, status, now) }

    suspend fun saveCheckpoint(id: String, sessionId: String, snapshotJson: String, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { q.insertCheckpoint(id, sessionId, snapshotJson, now) }

    suspend fun listCheckpoints(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Agent_checkpoint> =
        withContext(Dispatchers.IO) { q.selectCheckpointsBySession(sessionId).executeAsList() }
}
