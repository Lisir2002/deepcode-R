package com.R.codecore.feature.agent.domain.job

import com.R.codecore.core.util.FileLogger
import com.R.codecore.datalayer.repository.AgentRepository as V2AgentRepository
import com.R.codecore.datalayer.sqldelight.agent.Agent_jobs as V2Job
import com.R.codecore.feature.agent.data.local.entity.JobEntity
import com.R.codecore.feature.agent.data.local.entity.JobStatus
import com.R.codecore.feature.agent.domain.container.BoundedOutput
import com.R.codecore.feature.agent.domain.container.CommandEngine
import com.R.codecore.feature.agent.domain.container.CommandEvent
import com.R.codecore.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话级「后台任务」服务（对齐 DSH JobStart/JobRegistry）：
 *
 * - 长任务（容器内编译/测试/构建、远程同步）状态落库 [JobEntity]，App 切后台/进程回收不丢；
 * - 进程句柄与输出缓冲放内存注册表（[runningHandles] / [buffers]）；进程被回收即恢复时状态置
 *   [JobStatus.INTERRUPTED]，提示重跑；
 * - 重复 start 幂等（first-wins）：同 jobId 已存在且 RUNNING 时直接返回既有任务；
 * - 输出用 [BoundedOutput] 限幅（head+tail），`job_log` 读取缓冲，避免超大输出撑爆内存/上下文。
 *
 * 供 job_start / job_status / job_kill / job_log 四工具与启动恢复扫描共用。
 */
@Singleton
class JobService @Inject constructor(
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val v2Agent: V2AgentRepository,
) {
    private companion object {
        const val TAG = "JobService"

        /** 后台命令默认超时（毫秒），对齐 [CommandEngine.DEFAULT_TIMEOUT_MS]。 */
        const val DEFAULT_TIMEOUT_MS = 1_800_000L

        /** job_log 单次返回的输出上限（字符），避免一次读回撑爆上下文。 */
        const val LOG_TAIL_CHARS = 20_000
    }

    /** 应用级后台作用域：job 生命周期独立于单个工具调用，App 存活期间持续运行。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 运行中的任务协程句柄：jobId -> coroutine Job。 */
    private val runningHandles = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** 运行中任务的输出缓冲：jobId -> BoundedOutput。 */
    private val buffers = ConcurrentHashMap<String, BoundedOutput>()

    /**
     * 启动后台任务（幂等，first-wins）：创建 RUNNING 记录并立即返回，命令在后台协程执行。
     * @param kind 任务类型（如 container / ssh）。
     * @param title 人类可读标题。
     * @param command 要执行的 shell 命令。
     * @param timeoutMs 命令最长执行时间（毫秒），超时强制终止并置 FAILED。
     * @param jobId 外部可指定的任务 id（用于幂等）；缺省自动生成。
     * @return 任务实体（新建或复用的既有 RUNNING 任务）。
     */
    suspend fun start(
        sessionId: String,
        kind: String,
        title: String,
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        jobId: String = UUID.randomUUID().toString()
    ): JobEntity {
        // first-wins：同 jobId 已存在且仍 RUNNING → 直接复用，不重复启动。
        runningHandles[jobId]?.let {
            val existing = v2Agent.getJobById(jobId)?.toEntity()
            if (existing?.statusEnum() == JobStatus.RUNNING) return existing
        }
        val now = System.currentTimeMillis()
        val entity = JobEntity(
            jobId = jobId,
            sessionId = sessionId,
            kind = kind,
            title = title,
            status = JobStatus.RUNNING.name,
            exitCode = null,
            outputLocator = "mem:$jobId",
            createdAtMs = now,
            finishedAtMs = null,
            updatedAtMs = now
        )
        v2Agent.upsertJob(
            jobId = entity.jobId,
            sessionId = entity.sessionId,
            kind = entity.kind,
            title = entity.title,
            status = entity.status,
            exitCode = entity.exitCode?.toLong(),
            outputLocator = entity.outputLocator,
            createdAtMs = entity.createdAtMs,
            finishedAtMs = entity.finishedAtMs,
            updatedAtMs = entity.updatedAtMs
        )
        val buffer = BoundedOutput()
        buffers[jobId] = buffer
        val handle = scope.launch {
            var exitCode: Int? = null
            var timedOut = false
            try {
                val workdir = workspaceRepository.currentPath()
                commandEngine.runCommandStream(command, workdir, timeoutMs.coerceAtLeast(1000L)).collect { event ->
                    when (event) {
                        is CommandEvent.Line -> {
                            buffer.append(event.text)
                            buffer.append("\n")
                        }
                        is CommandEvent.TimedOut -> timedOut = true
                        is CommandEvent.Exit -> exitCode = event.code
                    }
                }
            } catch (e: CancellationException) {
                // kill 主动取消：状态已由 kill 置 KILLED，此处直接结束。
                if (runningHandles.remove(jobId) == null) return@launch
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "后台任务异常: jobId=$jobId title=$title", e)
                exitCode = -1
            }
            val finalStatus = when {
                timedOut -> JobStatus.FAILED
                exitCode == null -> JobStatus.INTERRUPTED
                exitCode == 0 -> JobStatus.DONE
                else -> JobStatus.FAILED
            }
            persistResult(jobId, finalStatus, exitCode)
            runningHandles.remove(jobId)
            buffers.remove(jobId)
        }
        runningHandles[jobId] = handle
        return entity
    }

    /** 读取任务状态；不存在返回 null。 */
    suspend fun getStatus(jobId: String, sessionId: String): JobEntity? {
        val entity = v2Agent.getJobById(jobId)?.toEntity() ?: return null
        // owner/session 隔离：仅同会话可读。
        return entity.takeIf { it.sessionId == sessionId }
    }

    /** 列出某会话的全部任务（新→旧）。 */
    suspend fun listBySession(sessionId: String): List<JobEntity> =
        v2Agent.listJobs(sessionId).map { it.toEntity() }

    /**
     * 终止任务：置 KILLED 并取消运行协程（容器命令随之被杀）。
     * 仅同会话可操作；已终态任务视为操作失败。
     * @return 是否成功 kill 一个仍 RUNNING 的任务。
     */
    suspend fun kill(jobId: String, sessionId: String): Boolean {
        val entity = v2Agent.getJobById(jobId)?.toEntity() ?: return false
        if (entity.sessionId != sessionId) return false
        if (entity.statusEnum() != JobStatus.RUNNING) return false
        val now = System.currentTimeMillis()
        v2Agent.updateJobResult(jobId, JobStatus.KILLED.name, entity.exitCode?.toLong(), now, now)
        runningHandles.remove(jobId)?.let { handle ->
            scope.launch { runCatching { handle.cancelAndJoin() } }
        }
        buffers.remove(jobId)
        return true
    }

    /**
     * 读取任务输出（截断为 [LOG_TAIL_CHARS] 字符）：
     * 运行中读内存缓冲；结束后缓冲可能已被释放，回退读记录（无则提示已结束且无独立输出）。
     * 仅同会话可读。
     */
    suspend fun readLog(jobId: String, sessionId: String, maxChars: Int = LOG_TAIL_CHARS): String? {
        val entity = v2Agent.getJobById(jobId)?.toEntity() ?: return null
        if (entity.sessionId != sessionId) return null
        val buffer = buffers[jobId] ?: return null
        val text = buffer.build()
        return if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    /**
     * 启动恢复扫描：把进程被回收后遗留的 RUNNING 记录置 INTERRUPTED（恢复时提示重跑）。
     * 由 App 启动周期调用（沿用 core/worker 模式）。
     */
    suspend fun markInterruptedOnBoot() {
        try {
            val now = System.currentTimeMillis()
            val running = v2Agent.listRunningJobs().map { it.toEntity() }
            running.forEach { job ->
                // 内存注册表里仍存活的任务不误标（正常情况启动时注册表为空）。
                if (runningHandles.containsKey(job.jobId)) return@forEach
                v2Agent.updateJobResult(job.jobId, JobStatus.INTERRUPTED.name, null, now, now)
                FileLogger.w(TAG, "启动恢复：任务 ${job.jobId}（${job.title}）标记为 INTERRUPTED，需重跑")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "启动恢复扫描失败", e)
        }
    }

    private suspend fun persistResult(jobId: String, status: JobStatus, exitCode: Int?) {
        val now = System.currentTimeMillis()
        v2Agent.updateJobResult(jobId, status.name, exitCode?.toLong(), now, now)
    }

    // ── V2（SQLDelight）↔ Room Entity 映射 ──────────────────────────────

    private fun V2Job.toEntity() = JobEntity(
        jobId = job_id,
        sessionId = session_id,
        kind = kind,
        title = title,
        status = status,
        exitCode = exit_code?.toInt(),
        outputLocator = output_locator,
        createdAtMs = created_at_ms,
        finishedAtMs = finished_at_ms,
        updatedAtMs = updated_at_ms
    )
}
