package com.R.codecore.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.R.codecore.datalayer.DataReadMode
import com.R.codecore.datalayer.DataReadModeHolder
import com.R.codecore.datalayer.repository.AgentRepository as V2AgentRepository
import com.R.codecore.datalayer.sqldelight.agent.Agent_session as V2AgentSession
import com.R.codecore.feature.agent.data.local.dao.AgentMessageDao
import com.R.codecore.feature.agent.data.local.dao.ChatSessionDao
import com.R.codecore.feature.agent.domain.container.ContainerInstaller
import com.R.codecore.feature.proxy.domain.ClashProxyManager
import com.R.codecore.feature.proxy.domain.ProxyRuntimeState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

internal data class UsageStats(
    val totalSessions: Int,
    val totalMessages: Int,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val firstUsedMs: Long,
    val activeDays: Int
)

@HiltViewModel
internal class AboutStatsViewModel @Inject constructor(
    private val sessionDao: ChatSessionDao,
    private val messageDao: AgentMessageDao,
    private val v2Agent: V2AgentRepository,
    private val readMode: DataReadModeHolder,
    private val proxyManager: ClashProxyManager,
    private val containerInstaller: ContainerInstaller
) : ViewModel() {

    private val _stats = MutableStateFlow(
        UsageStats(
            totalSessions = 0,
            totalMessages = 0,
            totalInputTokens = 0L,
            totalOutputTokens = 0L,
            firstUsedMs = 0L,
            activeDays = 0
        )
    )
    val stats: StateFlow<UsageStats> = _stats.asStateFlow()

    /** mihomo 代理内核运行态（来自 [ClashProxyManager]）。 */
    val proxyState: StateFlow<ProxyRuntimeState> = proxyManager.state

    private val _terminalReady = MutableStateFlow(false)
    val terminalReady: StateFlow<Boolean> = _terminalReady.asStateFlow()

    init {
        refresh()
        viewModelScope.launch(Dispatchers.IO) {
            _terminalReady.value =
                containerInstaller.isInstalled() || containerInstaller.isInstalledX86()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _stats.value = load()
        }
    }

    private suspend fun load(): UsageStats = withContext(Dispatchers.IO) {
        val sessions = if (readMode.currentMode() == DataReadMode.V2) {
            v2Agent.getAllOnce().map { it.toEntity() }
        } else {
            sessionDao.getAllOnce()
        }
        val messages = if (readMode.currentMode() == DataReadMode.V2) {
            v2Agent.getAllMessagesOnce().map { it.toEntity() }
        } else {
            messageDao.getAllOnce()
        }

        val totalSessions = sessions.size
        val totalMessages = messages.size
        val totalInputTokens = sessions.sumOf { it.totalInputTokens.toLong() }
        val totalOutputTokens = sessions.sumOf { it.totalOutputTokens.toLong() }
        val firstUsedMs = sessions.minOfOrNull { it.createdAtMs } ?: 0L

        val dayBuckets = sessions.mapNotNull { session ->
            utcDayBucket(session.createdAtMs)
        }.toSet()
        val activeDays = dayBuckets.size

        UsageStats(
            totalSessions = totalSessions,
            totalMessages = totalMessages,
            totalInputTokens = totalInputTokens,
            totalOutputTokens = totalOutputTokens,
            firstUsedMs = firstUsedMs,
            activeDays = activeDays
        )
    }

    // ── V2 映射 ──────────────────────────────────────────────────────

    private fun V2AgentSession.toEntity() = com.R.codecore.feature.agent.data.local.entity.ChatSessionEntity(
        id = id,
        title = title ?: "",
        createdAtMs = created_at,
        updatedAtMs = updated_at,
        workspacePath = workspace_path,
        mode = mode,
        reasoningEffort = reasoning_effort,
        providerId = provider_id,
        model = model,
        totalInputTokens = total_input_tokens.toInt(),
        totalOutputTokens = total_output_tokens.toInt(),
        lastInputTokens = last_input_tokens.toInt(),
    )

    private fun com.R.codecore.datalayer.sqldelight.agent.Agent_message.toEntity() = com.R.codecore.feature.agent.data.local.entity.AgentMessageEntity(
        id = id,
        sessionId = session_id,
        taskId = task_id,
        role = role,
        content = content,
        timestamp = seq,
        toolCallsJson = tool_calls_json,
        toolCallId = tool_call_id,
        toolName = tool_name,
        toolArgs = tool_args,
        isError = is_error == 1L,
        reasoning = reasoning,
        signature = signature,
        attachmentsJson = attachments_json,
        isCompacted = is_compacted == 1L,
        isContextSummary = is_context_summary == 1L,
        isCompactionMarker = is_compaction_marker == 1L,
        inputTokens = input_tokens.toInt(),
        outputTokens = output_tokens.toInt(),
        chunkGroupId = chunk_group_id,
        chunkIndex = chunk_index.toInt(),
    )

    private fun utcDayBucket(ms: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
