package com.deep.rcode.feature.agent.domain.zth

import com.deep.rcode.feature.agent.domain.model.AgentMode
import com.deep.rcode.feature.agent.domain.tool.ToolCall
import com.deep.rcode.feature.agent.domain.tool.ToolCapability
import com.deep.rcode.feature.settings.data.repository.ExecutionMode
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C.5 Phase 5：StatefulAgentWorkflow 集成钩子（**薄包装，零侵入核心循环**）。
 *
 * ### 设计原则（不破坏现有 StatefulAgentWorkflow 的 3 个不变性）
 *  - HOOK-INV-1：4 个 hook 函数只做「数据转换 → 调用 ZthGuardAggregateFacade」，不修改 workflow 状态树。
 *    workflow 侧调用 hook 后自行根据返回值决定后续 reducer/side effect（非黑盒）。
 *  - HOOK-INV-2：不修改 `AgentSessionState`、`AgentAction`、`AgentSideEffect` 定义（避免破坏
 *    StatefulAgentWorkflow 现有 reducer 的完备 when 覆盖——RC62~RC64e 曾因 Action 改动引入 Unresolved reference）。
 *  - HOOK-INV-3：此文件不依赖 Compose / ViewModel（纯 domain 层 Kotlin，可 JUnit）。
 *    UI 层的 ConfirmationCard 触发由 Facade 内部通过 `Provider<ZthConfirmationCardViewModel>` 懒加载。
 *
 * ### 4 个接入点（StatefulAgentWorkflow.executeEvents 内部应插入调用的位置——**KDoc 标注，不实际改动 workflow**）
 *  接入点 A：`SwitchModeTool(PLAN → BUILD)` 返回后且 `planApprovalManager.awaitApproval()` 之前。
 *           → 调用 `hookPrePlanAudit(sessionId, originalPlanReason, ...)`。
 *  接入点 B：`permissionManager.requestBatch(approvedToolCalls)` 返回后，`ExecuteToolBatch` 之前。
 *           → 调用 `hookPreToolAudit(sessionId, toolCalls)`。
 *  接入点 C：`ToolBatchFinished` reducer 处理完（写 messages / 结果入库）后。
 *           → 调用 `hookPostToolAudit(sessionId, callId, outputText, modifiedFiles)`。
 *  接入点 D：`CallLlm` 的 try/catch 块 catch(Throwable) 分支（已存在的 visionFallbackRetried 处理逻辑之后）。
 *           → 调用 `hookOnThrowable(sessionId, throwable, ...)`，返回值的 autoRecoveryHint 显示到 error banner。
 *
 * 未来接入时：只需要在 StatefulAgentWorkflow 的 4 处插入对应 hook 调用，**无需改动 hook 内部实现**。
 */
@Singleton
class ZthWorkflowHooks @Inject constructor(
    private val facade: ZthGuardAggregateFacade
) {
    private companion object {
        const val TAG = "ZthWorkflowHooks"
        private val ArgsPreviewJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }

    /** 接入点 A：PLAN→BUILD 切换（SwitchModeTool 之后，PlanApprovalManager 之前）。 */
    suspend fun hookPrePlanAudit(
        sessionId: String?,
        originalPlanReason: String,
        planText: String,
        affectedFiles: List<String>,
        estimatedToolCalls: Int,
        mode: AgentMode = AgentMode.PLAN,
        executionMode: ExecutionMode = ExecutionMode.LOCAL_PROOT,
        onlineValidated: Boolean = true
    ): Pair<com.deep.rcode.feature.agent.domain.tool.mode.PlanApprovalChoice, StructuredPlanBundle> {
        val structured = facade.structuredPlan(
            sessionId = sessionId ?: "global", planText = planText,
            affectedFiles = affectedFiles, estimatedToolCalls = estimatedToolCalls
        )
        return facade.prePlanAudit(
            sessionId = sessionId, originalPlanReason = originalPlanReason,
            structuredPlan = structured, mode = mode, executionMode = executionMode,
            onlineValidated = onlineValidated
        )
    }

    /** 接入点 B：工具执行前（permission 审批完，ExecuteToolBatch 之前）。把 ToolCall → ZthToolCallPlanItem。 */
    suspend fun hookPreToolAudit(
        sessionId: String?,
        toolCalls: List<ToolCall>,
        capabilityResolver: (ToolCall) -> Set<ToolCapability>,
        mode: AgentMode = AgentMode.BUILD,
        executionMode: ExecutionMode = ExecutionMode.LOCAL_PROOT,
        onlineValidated: Boolean = true,
        perf: ZthPerformanceClass = ZthPerformanceClass.HIGH_END
    ): ZthPreToolAuditBundle {
        val items = toolCalls.map { tc ->
            val argsText = runCatching { ArgsPreviewJson.encodeToString(tc.arguments) }
                .getOrDefault(tc.arguments.toString())
            ZthToolCallPlanItem(
                toolName = tc.name,
                capabilities = capabilityResolver(tc),
                argsPreviewText = argsText.take(1500),
                mcpServerName = if (tc.name.contains("mcp.")) tc.name.substringBeforeLast(".", "") else null,
                skillBundleKey = null
            )
        }
        return facade.preToolAudit(
            sessionId = sessionId, items = items, mode = mode,
            executionMode = executionMode, onlineValidated = onlineValidated, perf = perf
        )
    }

    /** 接入点 C：工具执行完成（ToolBatchFinished reducer 之后）。modifiedFilesHint 后续从 CheckpointManager.getModifiedFiles 补齐。 */
    suspend fun hookPostToolAudit(
        sessionId: String?,
        callId: String, toolName: String, outputText: String,
        modifiedFilesHint: List<String> = emptyList(),
        mode: AgentMode = AgentMode.BUILD,
        executionMode: ExecutionMode = ExecutionMode.LOCAL_PROOT,
        onlineValidated: Boolean = true
    ): ZthPostToolAuditBundle = facade.postToolCompletedAudit(
        sessionId = sessionId, toolName = toolName, callId = callId,
        outputText = outputText, modifiedFilesHint = modifiedFilesHint,
        mode = mode, executionMode = executionMode, onlineValidated = onlineValidated
    )

    /** 接入点 D：CallLlm / tool 执行抛出 Throwable（已存在的 visionFallbackRetried 逻辑之后）。 */
    suspend fun hookOnThrowable(
        sessionId: String?,
        throwable: Throwable?,
        mode: AgentMode = AgentMode.BUILD,
        executionMode: ExecutionMode = ExecutionMode.LOCAL_PROOT,
        onlineValidated: Boolean = true,
        currentCommandPrefix: String? = null,
        httpStatusCode: Int? = null,
        bundleDownloadFailure: BundleDownloadResult.Failure? = null
    ): ZthThrowableClassifyBundle = facade.onThrowableAudit(
        sessionId = sessionId, throwable = throwable, mode = mode,
        executionMode = executionMode, onlineValidated = onlineValidated,
        currentCommandPrefix = currentCommandPrefix, httpStatusCode = httpStatusCode,
        bundleDownloadFailure = bundleDownloadFailure
    )

    // ── 3 步质量保障简写（避免 UI/代码修改层绕过 Facade 直接写）───────────────

    suspend fun qgatePreflight(
        expectedFiles: List<String>,
        fileExistsChecker: suspend (String) -> Boolean = { java.io.File(it).exists() }
    ): PreflightResult = facade.preflightVerify(expectedFiles, fileExistsChecker)

    suspend fun qgatePostflight(checkpointId: String, compareHash: suspend (String) -> Boolean): PostflightResult =
        facade.postflightDiff(checkpointId, compareHash)
}
