package com.R.codecore.feature.agent.domain.tool.job

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.job.JobService
import com.R.codecore.feature.agent.data.local.entity.JobEntity
import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.tool.AbstractContextualTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * job_start：启动一个后台任务（对齐 DSH JobStart）。
 * 命令在容器/SSH 环境后台执行，立即返回 job_id；之后用 job_status / job_log / job_kill 管理。
 * 适合长编译、长测试、批量同步等耗时任务，避免前台阻塞多轮往返。
 */
class JobStartTool @Inject constructor(
    private val jobService: JobService
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "JobStartTool"
        const val MAX_TIMEOUT_SECONDS = 3_600L
    }

    override val name = "job_start"
    override val description = "在后台启动一个耗时命令任务（如长编译/测试/批量同步），立即返回 job_id，不阻塞等待。" +
        "之后用 job_status 查状态、job_log 读输出、job_kill 终止。命令执行于当前执行环境（本地容器或远程 SSH）。"

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "要后台执行的 shell 命令，如 './gradlew :app:assembleRelease'",
            required = true
        ),
        "title" to ToolParameter(
            name = "title",
            type = ParameterType.STRING,
            description = "任务标题（展示/日志用），不填则用命令开头截断",
            required = false
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "任务最长执行时间（秒），超时强制终止并置 FAILED。默认 1800 秒，上限 3600 秒",
            required = false
        ),
        "kind" to ToolParameter(
            name = "kind",
            type = ParameterType.STRING,
            description = "任务类型（如 container / ssh），默认 container",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话", "NO_SESSION")
        val command = args["command"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数: command", "MISSING_COMMAND")
        if (command.isEmpty()) return ToolResult.Error("command 不能为空", "MISSING_COMMAND")
        val timeoutMs = (args["timeout"]?.jsonPrimitive?.longOrNull ?: 1_800L)
            .coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: command.take(40)
        val kind = args["kind"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "container"

        return try {
            val job = jobService.start(sessionId, kind, title, command, timeoutMs)
            FileLogger.i(TAG, "后台任务已启动 jobId=${job.jobId} title=$title timeout=${timeoutMs}ms")
            ToolResult.Success(jobJson(job))
        } catch (e: Exception) {
            FileLogger.e(TAG, "启动后台任务失败: $command", e)
            ToolResult.Error("启动后台任务失败: ${e.message}", "JOB_START_FAILED")
        }
    }

    private fun jobJson(job: JobEntity): JsonObject = JsonObject(
        mapOf(
            "job_id" to JsonPrimitive(job.jobId),
            "status" to JsonPrimitive(job.statusEnum().name.lowercase()),
            "title" to JsonPrimitive(job.title),
            "kind" to JsonPrimitive(job.kind)
        )
    )
}

/**
 * job_status：查询任务状态（对齐 DSH JobRegistry.get/list）。
 * 传 job_id 查单个；省略则列出本会话全部任务（新→旧）。
 */
class JobStatusTool @Inject constructor(
    private val jobService: JobService
) : AbstractContextualTool() {

    override val name = "job_status"
    override val description = "查询后台任务状态。传 job_id 查单个任务；省略 job_id 则列出本会话全部任务。" +
        "状态：running / done / failed / killed / interrupted。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "job_id" to ToolParameter(
            name = "job_id",
            type = ParameterType.STRING,
            description = "任务 id；省略则列出本会话全部任务",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话", "NO_SESSION")
        return try {
            val jobId = args["job_id"]?.jsonPrimitive?.contentOrNull?.trim()
            if (jobId.isNullOrEmpty()) {
                val jobs = jobService.listBySession(sessionId)
                ToolResult.Success(
                    JsonObject(mapOf(
                        "jobs" to JsonPrimitive(
                            jobs.joinToString("\n") { statusLine(it) }
                        ),
                        "count" to JsonPrimitive(jobs.size)
                    ))
                )
            } else {
                val job = jobService.getStatus(jobId, sessionId)
                    ?: return ToolResult.Error("未找到任务: $jobId", "JOB_NOT_FOUND")
                ToolResult.Success(JsonObject(mapOf("job" to JsonPrimitive(statusLine(job)))))
            }
        } catch (e: Exception) {
            ToolResult.Error("查询任务状态失败: ${e.message}")
        }
    }

    private fun statusLine(job: JobEntity): String {
        val exit = job.exitCode?.let { " exit=$it" } ?: ""
        val finished = job.finishedAtMs?.let { " finished=${it}" } ?: ""
        return "${job.jobId}  [${job.statusEnum().name.lowercase()}]  ${job.title}$exit$finished"
    }
}

/**
 * job_kill：终止后台任务（对齐 DSH JobRegistry.kill）。仅本会话可操作，已终态任务不可重复 kill。
 */
class JobKillTool @Inject constructor(
    private val jobService: JobService
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "JobKillTool"
    }

    override val name = "job_kill"
    override val description = "终止一个正在运行的后台任务（按 job_id）。已结束的任务无法再终止。"

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "job_id" to ToolParameter(
            name = "job_id",
            type = ParameterType.STRING,
            description = "要终止的任务 id",
            required = true
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话", "NO_SESSION")
        val jobId = args["job_id"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数: job_id", "MISSING_JOB_ID")
        return try {
            val killed = jobService.kill(jobId, sessionId)
            if (killed) {
                FileLogger.i(TAG, "已终止后台任务: $jobId")
                ToolResult.Success(JsonPrimitive("已终止后台任务 $jobId。"))
            } else {
                ToolResult.Error("任务不存在、不属于当前会话或已结束，无法终止: $jobId", "JOB_NOT_KILLABLE")
            }
        } catch (e: Exception) {
            ToolResult.Error("终止任务失败: ${e.message}")
        }
    }
}

/**
 * job_log：读取后台任务输出（对齐 DSH JobRegistry.read）。
 * 运行中读实时缓冲；结束读记录尾部。返回结构化 {job_id, status, output, truncated}。
 */
class JobLogTool @Inject constructor(
    private val jobService: JobService
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "JobLogTool"
        const val DEFAULT_MAX_CHARS = 20_000
    }

    override val name = "job_log"
    override val description = "读取后台任务的实时输出（按 job_id）。可选 max_chars 限制返回长度，默认 20000 字符。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "job_id" to ToolParameter(
            name = "job_id",
            type = ParameterType.STRING,
            description = "任务 id",
            required = true
        ),
        "max_chars" to ToolParameter(
            name = "max_chars",
            type = ParameterType.INTEGER,
            description = "最多返回字符数（默认 20000）",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话", "NO_SESSION")
        val jobId = args["job_id"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数: job_id", "MISSING_JOB_ID")
        val maxChars = args["max_chars"]?.jsonPrimitive?.longOrNull?.toInt()
            ?.coerceIn(100, 200_000) ?: DEFAULT_MAX_CHARS
        return try {
            val job = jobService.getStatus(jobId, sessionId)
                ?: return ToolResult.Error("未找到任务: $jobId", "JOB_NOT_FOUND")
            val output = jobService.readLog(jobId, sessionId, maxChars) ?: ""
            ToolResult.Success(
                JsonObject(mapOf(
                    "job_id" to JsonPrimitive(jobId),
                    "status" to JsonPrimitive(job.statusEnum().name.lowercase()),
                    "output" to JsonPrimitive(output),
                    "truncated" to JsonPrimitive(output.length >= maxChars)
                ))
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "读取任务输出失败: $jobId", e)
            ToolResult.Error("读取任务输出失败: ${e.message}")
        }
    }
}
