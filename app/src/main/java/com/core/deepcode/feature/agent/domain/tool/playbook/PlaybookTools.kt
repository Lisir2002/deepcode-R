package com.core.deepcode.feature.agent.domain.tool.playbook

import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.data.local.entity.PlaybookRunEntity
import com.core.deepcode.feature.agent.domain.model.AgentContext
import com.core.deepcode.feature.agent.domain.playbook.PlaybookExecutor
import com.core.deepcode.feature.agent.domain.playbook.PlaybookOpResult
import com.core.deepcode.feature.agent.domain.playbook.PlaybookStageView
import com.core.deepcode.feature.agent.domain.tool.AbstractContextualTool
import com.core.deepcode.feature.agent.domain.tool.ParameterType
import com.core.deepcode.feature.agent.domain.tool.ToolCapability
import com.core.deepcode.feature.agent.domain.tool.ToolParameter
import com.core.deepcode.feature.agent.domain.tool.ToolPermissionPolicy
import com.core.deepcode.feature.agent.domain.tool.ToolResult
import com.core.deepcode.feature.settings.data.repository.NormFlowSettingsRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/** PlaybookOpResult → ToolResult 的公共转换（四个 playbook 工具共用）。 */
private fun playbookResultToTool(result: PlaybookOpResult): ToolResult = when (result) {
    is PlaybookOpResult.Stage -> ToolResult.Success(stageJson(result.view, result.message))
    is PlaybookOpResult.Ok -> ToolResult.Success(
        JsonObject(mapOf("message" to JsonPrimitive(result.message), "playbook" to JsonPrimitive(result.playbookName)))
    )
    is PlaybookOpResult.Completed -> ToolResult.Success(
        JsonObject(mapOf("completed" to JsonPrimitive(true), "message" to JsonPrimitive(result.message)))
    )
    is PlaybookOpResult.Aborted -> ToolResult.Success(
        JsonObject(mapOf("aborted" to JsonPrimitive(true), "message" to JsonPrimitive(result.message)))
    )
    is PlaybookOpResult.Advisory -> ToolResult.Success(
        JsonObject(mapOf(
            "advisory" to JsonPrimitive(true),
            "message" to JsonPrimitive(result.message),
            "stage" to stageJson(result.view)
        ))
    )
    is PlaybookOpResult.Error -> ToolResult.Error(result.message, result.errorCode)
}

/** 阶段视图 → JSON（阶段名/进度/目标/门/子代理，供模型读取推进）。 */
internal fun stageJson(view: PlaybookStageView, message: String = ""): JsonObject = JsonObject(
    mapOf(
        "message" to JsonPrimitive(message),
        "playbook" to JsonPrimitive(view.playbookName),
        "stage_index" to JsonPrimitive(view.stageIndex + 1),
        "stage_count" to JsonPrimitive(view.stageCount),
        "stage_name" to JsonPrimitive(view.stageName),
        "stage_description" to JsonPrimitive(view.stageDescription),
        "gate" to JsonPrimitive(view.gate.name.lowercase()),
        "agents" to JsonArray(view.agents.map { JsonPrimitive(it) })
    )
)

/**
 * playbook_start：启动一个剧本（对齐 norm-chain-design.md §3.3.5）。
 * 按名称**精确匹配**剧本资产（feature-dev / code-review / bug-fix 等），未命中返回可用清单回退 plan/goal。
 * 启动后返回首阶段描述；后续阶段工作完成后用 playbook_advance 推进。
 *
 * **D5-pa 自动触发开关**：本工具是模型**自主**触发剧本的入口，受 `playbook_auto` 子开关控制
 * （对齐 §3.5）；开关关闭时返回指引提示改用 `/playbook` 斜杠命令（显式入口不受开关影响）。
 */
class PlaybookStartTool @Inject constructor(
    private val playbookExecutor: PlaybookExecutor,
    private val normFlowSettingsRepository: NormFlowSettingsRepository
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "PlaybookStartTool"
    }

    override val name = "playbook_start"
    override val description = "启动一个多阶段剧本（Playbook）。按 name 精确匹配剧本资产（如 feature-dev / code-review / bug-fix），" +
        "启动后返回首阶段目标，阶段工作完成后用 playbook_advance 推进。未命中时返回可用剧本清单，不要猜测剧本名。" +
        "大任务应优先识别匹配的剧本按阶段推进，避免跳步。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "name" to ToolParameter(
            name = "name",
            type = ParameterType.STRING,
            description = "剧本名称（精确匹配，如 feature-dev）",
            required = true
        ),
        "context" to ToolParameter(
            name = "context",
            type = ParameterType.STRING,
            description = "可选：启动时的补充上下文（任务描述），会随首阶段目标注入",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话", "NO_SESSION")
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数: name", "MISSING_NAME")
        val userContext = args["context"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        // D5-pa：playbook_auto 子开关控制模型自主触发（对齐 §3.5）；关闭时指引改用 /playbook 显式入口。
        if (!normFlowSettingsRepository.isPlaybookAutoActive()) {
            return ToolResult.Error(
                "playbook 自动触发已关闭（playbook_auto 开关）：模型不能自主启动剧本，请改用 /playbook 命令显式启动。",
                "PLAYBOOK_AUTO_DISABLED"
            )
        }
        return try {
            val result = playbookExecutor.start(name, sessionId)
            FileLogger.i(TAG, "playbook_start: session=$sessionId name=$name")
            val withContext = if (userContext.isNotEmpty() && result is PlaybookOpResult.Stage) {
                PlaybookOpResult.Stage(
                    view = result.view,
                    message = result.message + "\n任务补充上下文：$userContext"
                )
            } else {
                result
            }
            playbookResultToTool(withContext)
        } catch (e: Exception) {
            FileLogger.e(TAG, "playbook_start 失败: $name", e)
            ToolResult.Error("启动剧本失败: ${e.message}")
        }
    }
}

/**
 * playbook_advance：推进/中止当前阶段（对齐 norm-chain-design.md §3.3.5）。
 * 默认作用于本会话最近一次 RUNNING 运行（模型无需管理 runId）。
 * - action=done：当前阶段完成，推进到下一阶段（末尾阶段 → 剧本完成）；approval 门需用户批准。
 * - action=fail：当前阶段失败，剧本中止（可 playbook_retry 从失败阶段恢复）。
 * 可选 artifacts 记录本阶段产物清单（resume/retry 时注入对照跳过已完成操作）。
 */
class PlaybookAdvanceTool @Inject constructor(
    private val playbookExecutor: PlaybookExecutor
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "PlaybookAdvanceTool"
    }

    override val name = "playbook_advance"
    override val description = "推进当前剧本阶段：action=done 声明当前阶段完成并进入下一阶段（末尾阶段则剧本完成），" +
        "action=fail 声明当前阶段失败并中止剧本。默认作用于本会话最近一次进行中的运行；可选 run_id 指定。可选 artifacts 记录本阶段产物文件清单。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "done（阶段完成，推进）/ fail（阶段失败，中止）",
            required = true,
            enum = listOf("done", "fail")
        ),
        "run_id" to ToolParameter(
            name = "run_id",
            type = ParameterType.STRING,
            description = "运行 id；缺省作用于本会话最近一次进行中的运行",
            required = false
        ),
        "artifacts" to ToolParameter(
            name = "artifacts",
            type = ParameterType.ARRAY,
            description = "本阶段产出/完成的文件路径清单（action=done 时可选，用于恢复时对照跳过已完成操作）",
            required = false,
            itemsSchema = mapOf("type" to "string")
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话", "NO_SESSION")
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数: action", "MISSING_ACTION")
        val runId = args["run_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val artifacts = (args["artifacts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() } ?: emptyList()
        return try {
            val result = playbookExecutor.advance(sessionId, action, runId, artifacts)
            FileLogger.i(TAG, "playbook_advance: session=$sessionId action=$action runId=$runId")
            playbookResultToTool(result)
        } catch (e: Exception) {
            FileLogger.e(TAG, "playbook_advance 失败: $action", e)
            ToolResult.Error("推进剧本失败: ${e.message}")
        }
    }
}

/**
 * playbook_status：查询本会话最近一次剧本运行的状态（对齐 norm-chain-design.md §3.3.5 / §3.3.4）。
 * 返回运行级状态 + 当前阶段 + 各阶段进度，供模型在推进前核对。
 */
class PlaybookStatusTool @Inject constructor(
    private val playbookExecutor: PlaybookExecutor
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "PlaybookStatusTool"
    }

    override val name = "playbook_status"
    override val description = "查询本会话最近一次剧本运行的状态：运行级状态（running/completed/aborted/interrupted）+ 当前阶段进度。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val parameters: Map<String, ToolParameter> = emptyMap()

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话", "NO_SESSION")
        return try {
            val run = playbookExecutor.status(sessionId)
                ?: return ToolResult.Success(
                    JsonObject(mapOf("running" to JsonPrimitive(false), "message" to JsonPrimitive("当前会话没有剧本运行记录")))
                )
            ToolResult.Success(runJson(run))
        } catch (e: Exception) {
            FileLogger.e(TAG, "playbook_status 失败", e)
            ToolResult.Error("查询剧本状态失败: ${e.message}")
        }
    }

    private fun runJson(run: PlaybookRunEntity): JsonObject {
        val stages = run.stageStatuses.ifBlank { "[]" }
        return JsonObject(
            mapOf(
                "running" to JsonPrimitive(run.statusEnum().name == "RUNNING"),
                "playbook" to JsonPrimitive(run.playbookName),
                "status" to JsonPrimitive(run.statusEnum().name.lowercase()),
                "current_stage_index" to JsonPrimitive(run.currentStageIndex + 1),
                "stage_statuses" to JsonPrimitive(stages)
            )
        )
    }
}

/**
 * playbook_abort：中止本会话最近一次进行中的剧本运行（置 ABORTED）。
 * 中止后可 playbook_start 从头重跑，或由上层 /playbook retry 从失败阶段恢复。
 */
class PlaybookAbortTool @Inject constructor(
    private val playbookExecutor: PlaybookExecutor
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "PlaybookAbortTool"
    }

    override val name = "playbook_abort"
    override val description = "中止本会话最近一次进行中的剧本运行（置 ABORTED）。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "run_id" to ToolParameter(
            name = "run_id",
            type = ParameterType.STRING,
            description = "运行 id；缺省作用于本会话最近一次进行中的运行",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话", "NO_SESSION")
        val runId = args["run_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return try {
            val result = playbookExecutor.abort(sessionId, runId)
            FileLogger.i(TAG, "playbook_abort: session=$sessionId runId=$runId")
            playbookResultToTool(result)
        } catch (e: Exception) {
            FileLogger.e(TAG, "playbook_abort 失败", e)
            ToolResult.Error("中止剧本失败: ${e.message}")
        }
    }
}
