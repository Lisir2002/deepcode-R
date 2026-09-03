package com.core.deepcode.di

import com.core.deepcode.feature.agent.data.CodeChangeTracker
import com.core.deepcode.feature.agent.domain.tool.todo.TodoTool
import com.core.deepcode.feature.settings.domain.repository.AIProviderRepository
import com.core.deepcode.feature.agent.data.remote.anthropic.AnthropicApi
import com.core.deepcode.feature.agent.data.remote.gemini.GeminiApi
import com.core.deepcode.feature.agent.data.remote.openai.OpenAIApi
import com.core.deepcode.feature.agent.domain.container.CommandEngine
import com.core.deepcode.feature.agent.domain.container.DelegatingCommandEngine
import com.core.deepcode.feature.agent.domain.container.LinuxContainerEngine
import com.core.deepcode.feature.agent.domain.container.RemoteSshConnection
import com.core.deepcode.feature.agent.domain.container.RemoteSshEngine
import com.core.deepcode.feature.settings.data.repository.ExecutionMode
import com.core.deepcode.feature.settings.data.repository.ExecutionModeHolder
import com.core.deepcode.feature.agent.domain.tool.file.ReadFileTool
import com.core.deepcode.feature.agent.domain.tool.file.SendFileTool
import com.core.deepcode.feature.agent.domain.tool.file.ViewImageTool
import com.core.deepcode.feature.agent.domain.tool.file.WriteFileTool
import com.core.deepcode.feature.agent.domain.tool.editor.EditFileTool
import com.core.deepcode.feature.agent.domain.tool.container.ExecuteCommandTool
import com.core.deepcode.feature.agent.domain.tool.container.CheckEnvironmentTool
import com.core.deepcode.feature.agent.domain.tool.container.EnsureAndroidEnvTool
import com.core.deepcode.feature.agent.domain.tool.container.SwitchContainerArchTool
import com.core.deepcode.feature.agent.domain.tool.container.TerminalSessionTool
import com.core.deepcode.feature.agent.domain.tool.explorer.ListFilesTool
import com.core.deepcode.feature.agent.domain.tool.explorer.SearchCodeTool
import com.core.deepcode.feature.agent.domain.tool.skill.LoadSkillTool
import com.core.deepcode.feature.agent.domain.tool.question.AskUserQuestionTool
import com.core.deepcode.feature.agent.domain.tool.browser.BrowserAgentTool
import com.core.deepcode.feature.agent.domain.prompt.SystemPromptProvider
import com.core.deepcode.feature.agent.domain.workflow.AgentWorkflow
import com.core.deepcode.feature.agent.domain.tool.ToolPermissionManager
import com.core.deepcode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.core.deepcode.feature.agent.domain.tool.AgentTool
import com.core.deepcode.feature.agent.domain.tool.ToolRegistry
import com.core.deepcode.feature.agent.domain.tool.intent.IntentAnalyzeTool
import com.core.deepcode.feature.agent.domain.tool.ToolOutputStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.core.deepcode.feature.settings.data.remote.ModelMetadataService
import com.core.deepcode.feature.terminal.domain.DelegatingTerminalSessionProvider
import com.core.deepcode.feature.terminal.domain.RemoteTerminalSessionManager
import com.core.deepcode.feature.terminal.domain.TerminalSessionManager
import com.core.deepcode.feature.terminal.domain.TerminalSessionProvider
import com.core.deepcode.feature.workspace.domain.FileAccessProvider
import com.core.deepcode.feature.workspace.domain.DelegatingFileAccess
import com.core.deepcode.feature.workspace.domain.LocalFileAccess
import com.core.deepcode.feature.workspace.domain.RemoteSftpFileAccess
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    // ══════════════════════════════════════════════════════════
    // 数据层已整体迁移至 SQLDelight V2（六库拓扑，见 DataLayerModule），
    // 旧 Room 数据层（单巨库 AgentDatabase v49 / 按域拆分独立库、DAO、
    // DbSplitMigrator 一次性拆库）已全部移除。本模块只保留网络/工具/工作流绑定。
    // ══════════════════════════════════════════════════════════

    // ══ RC69 T2I：ImageGenerator（interface）→ OpenAiCompatibleImageGenerator（实现）绑定
    //   AgentModule 是 @Module object，不能用 @Binds，所以用 @Provides 包一层构造器注入的 impl。
    @Provides
    @Singleton
    fun provideImageGenerator(impl: com.core.deepcode.feature.t2i.data.remote.OpenAiCompatibleImageGenerator): com.core.deepcode.feature.t2i.data.remote.ImageGenerator {
        return impl
    }

    // T2I 专用探测服务：构造函数是 @Inject 所以 Hilt 本身会实例化，这里声明一个 @Provides
    //   只是保证 Module 对象里能显式声明为单例（与 ModelApiService 同生命周期），供后续 ViewModel/Repo 直接取。
    @Provides
    @Singleton
    fun provideT2IModelProbeService(impl: com.core.deepcode.feature.t2i.data.remote.T2IModelProbeService):
            com.core.deepcode.feature.t2i.data.remote.T2IModelProbeService = impl

    @Provides
    @Singleton
    fun provideOkHttpClient(proxyRouteHolder: com.core.deepcode.feature.proxy.domain.ProxyRouteHolder): OkHttpClient {
        // 流式 SSE 下读超时是「相邻数据块之间」的等待上限；120s 给慢启动/长思考留足空间，
        // 真正卡死由上层阶梯重试（RetryPolicy）兜底。
        return OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            // 网络代理（§4.2）：注入 ProxyRouteHolder 的路由选择器，启用时代理走 mihomo mixed-port，
            // 未启用直连；以 @Singleton 无依赖 Holder 避免与 ClashProxyManager 成环。
            .proxySelector(proxyRouteHolder.selector)
            // 网络层优化 C2：连接池调优（默认 5 连接 / 5min 保活）。模型接口常往返复用，
            // 放宽到 8 连接 / 15min 提升长连接复用率，降低首字节（TTFT）延迟。
            .connectionPool(okhttp3.ConnectionPool(8, 15, TimeUnit.MINUTES))
            // 网络层优化 C3：短 TTL DNS 缓存，避免每次连接走系统 DNS（弱网可省几十~几百 ms）。
            // 网络层优化 C5+：系统解析失败时回退公共 DNS（223.5.5.5 等）兜底，规避
            // "Unable to resolve host"（被网络分流/私人 DNS 劫持时系统查不到、公共 DNS 却可查）。
            .dns(com.core.deepcode.core.network.PublicDnsFallback(com.core.deepcode.core.network.CachingDns()))
            .build()
    }

    @Provides
    @Singleton
    @Named("OpenAI")
    fun provideOpenAIRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @Named("Anthropic")
    fun provideAnthropicRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideOpenAIApi(@Named("OpenAI") retrofit: Retrofit): OpenAIApi {
        return retrofit.create(OpenAIApi::class.java)
    }

    @Provides
    @Singleton
    fun provideAnthropicApi(@Named("Anthropic") retrofit: Retrofit): AnthropicApi {
        return retrofit.create(AnthropicApi::class.java)
    }

    @Provides
    @Singleton
    @Named("Gemini")
    fun provideGeminiRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideGeminiApi(@Named("Gemini") retrofit: Retrofit): com.core.deepcode.feature.agent.data.remote.gemini.GeminiApi {
        return retrofit.create(com.core.deepcode.feature.agent.data.remote.gemini.GeminiApi::class.java)
    }

    @Provides
    @Singleton
    fun provideCommandEngine(delegate: DelegatingCommandEngine): CommandEngine = delegate

    @Provides
    @Singleton
    fun provideFileAccess(delegate: DelegatingFileAccess): FileAccessProvider = delegate

    @Provides
    @Singleton
    fun provideTerminalSessionProvider(delegate: DelegatingTerminalSessionProvider): TerminalSessionProvider = delegate

    @Provides
    @Singleton
    fun provideDelegatingTerminalSessionProvider(
        modeHolder: com.core.deepcode.feature.settings.data.repository.ExecutionModeHolder,
        local: TerminalSessionManager,
        remote: com.core.deepcode.feature.terminal.domain.RemoteTerminalSessionManager
    ): DelegatingTerminalSessionProvider = DelegatingTerminalSessionProvider(modeHolder, local, remote)

    @Provides
    @Singleton
    fun provideRemoteSftpFileAccess(
        connection: RemoteSshConnection,
        workspaceRepository: com.core.deepcode.feature.workspace.data.repository.WorkspaceRepository
    ): RemoteSftpFileAccess = RemoteSftpFileAccess(connection, workspaceRepository)

    @Provides
    @Singleton
    fun provideToolResultTypeRegistry(): com.core.deepcode.feature.agent.domain.tool.ToolResultTypeRegistry {
        return com.core.deepcode.feature.agent.domain.tool.ToolResultTypeRegistry()
    }

    @Provides
    @Singleton
    fun provideToolRegistry(
        readFileTool: ReadFileTool,
        sendFileTool: SendFileTool,
        viewImageTool: ViewImageTool,
        writeFileTool: WriteFileTool,
        editFileTool: EditFileTool,
        executeCommandTool: ExecuteCommandTool,
        runCodeTool: com.core.deepcode.feature.agent.domain.tool.container.RunCodeTool,
        checkEnvironmentTool: CheckEnvironmentTool,
        ensureAndroidEnvTool: EnsureAndroidEnvTool,
        switchContainerArchTool: SwitchContainerArchTool,
        terminalSessionTool: TerminalSessionTool,
        listFilesTool: ListFilesTool,
        searchCodeTool: SearchCodeTool,
        loadSkillTool: LoadSkillTool,
        runSkillScriptTool: com.core.deepcode.feature.agent.domain.tool.skill.RunSkillScriptTool,
        loadRuleTool: com.core.deepcode.feature.agent.domain.tool.rule.LoadRuleTool,
        loadSopTool: com.core.deepcode.feature.agent.domain.tool.sop.LoadSopTool,
        askUserQuestionTool: AskUserQuestionTool,
        manageMcpTool: com.core.deepcode.feature.agent.domain.tool.mcp.ManageMcpTool,
        webSearchTool: com.core.deepcode.feature.agent.domain.tool.search.WebSearchTool,
        webFetchTool: com.core.deepcode.feature.agent.domain.tool.search.WebFetchTool,
        switchModeTool: com.core.deepcode.feature.agent.domain.tool.mode.SwitchModeTool,
        todoTool: TodoTool,
        memoryTool: com.core.deepcode.feature.agent.domain.tool.memory.MemoryTool,
        generateImageTool: com.core.deepcode.feature.agent.domain.tool.image.GenerateImageTool,
        browserTool: BrowserAgentTool,
        storageTool: com.core.deepcode.feature.agent.domain.tool.storage.StorageTool,
        networkProxyTool: com.core.deepcode.feature.agent.domain.tool.proxy.NetworkProxyTool,
        goalTool: com.core.deepcode.feature.agent.domain.tool.goal.GoalTool,
        jobStartTool: com.core.deepcode.feature.agent.domain.tool.job.JobStartTool,
        jobStatusTool: com.core.deepcode.feature.agent.domain.tool.job.JobStatusTool,
        jobKillTool: com.core.deepcode.feature.agent.domain.tool.job.JobKillTool,
        jobLogTool: com.core.deepcode.feature.agent.domain.tool.job.JobLogTool,
        scheduleTool: com.core.deepcode.feature.agent.domain.tool.schedule.ScheduleTool,
        planTool: com.core.deepcode.feature.agent.domain.tool.plan.PlanTool,
        intentAnalyzeTool: IntentAnalyzeTool,
        playbookStartTool: com.core.deepcode.feature.agent.domain.tool.playbook.PlaybookStartTool,
        playbookAdvanceTool: com.core.deepcode.feature.agent.domain.tool.playbook.PlaybookAdvanceTool,
        playbookStatusTool: com.core.deepcode.feature.agent.domain.tool.playbook.PlaybookStatusTool,
        playbookAbortTool: com.core.deepcode.feature.agent.domain.tool.playbook.PlaybookAbortTool,
        gitOpsTool: com.core.deepcode.feature.agent.domain.tool.git.GitOpsTool,
        resultTypeRegistry: com.core.deepcode.feature.agent.domain.tool.ToolResultTypeRegistry
    ): ToolRegistry {
        return ToolRegistry().apply {
            // L3 联动注册：工具注册到 ToolRegistry 时，同步把 provides 类型登记到中央注册表，
            // 供依赖调度（L4）、结果缓存（L5）、增量索引（L6）按类型消费。
            fun registerTool(name: String, tool: AgentTool) {
                register(name, tool)
                tool.provides.forEach { type ->
                    resultTypeRegistry.register(
                        type = type,
                        schema = com.core.deepcode.feature.agent.domain.tool.TypeSchema(
                            type = type,
                            capability = tool.capabilities.firstOrNull()
                        ),
                        producer = name
                    )
                }
            }
            registerTool("readFile", readFileTool)
            registerTool("sendFile", sendFileTool)
            registerTool("viewImage", viewImageTool)
            registerTool("writeFile", writeFileTool)
            registerTool("editFile", editFileTool)
            registerTool("Bash", executeCommandTool)
            registerTool("run_code", runCodeTool)
            registerTool("check_environment", checkEnvironmentTool)
            registerTool("ensure_android_env", ensureAndroidEnvTool)
            registerTool("switch_container_arch", switchContainerArchTool)
            registerTool("terminal", terminalSessionTool)
            registerTool("list", listFilesTool)
            registerTool("search", searchCodeTool)
            registerTool("loadSkill", loadSkillTool)
            registerTool("runSkillScript", runSkillScriptTool)
            // ══ 分层规则正文按需加载（D3-3）：load_rule(rule_name) 取完整正文（摘要/正文两级形态）
            registerTool("load_rule", loadRuleTool)
            // ══ SOP 标准作业正文按需加载（D4-4）：loadSop(sop_name) 取完整编号步骤（摘要常驻 + 按需取正文）
            registerTool("loadSop", loadSopTool)
            registerTool("askUserQuestion", askUserQuestionTool)
            registerTool("manageMcp", manageMcpTool)
            registerTool("websearch", webSearchTool)
            registerTool("webfetch", webFetchTool)
            registerTool("switchMode", switchModeTool)
            registerTool("todo", todoTool)
            registerTool("memory", memoryTool)
            // ══ RC69 T2I 文生图工具：generateImage(prompt="...", width, height, steps, hd, model)
            registerTool("generateImage", generateImageTool)
            // ══ 内置服务浏览器：模型在共享 WebView 会话中浏览/操作网页（含容器服务与登录站点）
            registerTool("browser", browserTool)
            // ══ 设备存储护栏工具：结构化 list/read/write/delete + ASK 确认（见设计「护栏」）
            registerTool("device_storage", storageTool)
            // ══ 网络代理工具（VPN 形态）：模型自助管理容器内 mihomo 代理，ASK 确认 + MODIFY_NETWORK 能力隔离
            registerTool("network_proxy", networkProxyTool)
            // ══ 会话任务目标状态机（DSH goal）：set/get/update/done/abandon + 每轮注入
            registerTool("goal", goalTool)
            // ══ 后台任务（DSH jobs）：job_start/status/kill/log，长任务后台执行 + Room 持久化
            registerTool("job_start", jobStartTool)
            registerTool("job_status", jobStatusTool)
            registerTool("job_kill", jobKillTool)
            registerTool("job_log", jobLogTool)
            // ══ 定时提醒（DSH schedule）：create(after/at/every) + list + cancel，到点注入会话
            registerTool("schedule", scheduleTool)
            // ══ 计划协作（DSH plan + Claude Code Plan/Spec）：propose/get/approve/abandon + 每轮注入 pendingSelection
            registerTool("plan", planTool)
            // ══ 用户意图判定平台（D0）：规则预分类五形态 + behaviorMode，Parser 门控（task/command）建议调用
            registerTool("intent_analyze", intentAnalyzeTool)
            // ══ Playbook 剧本编排（D5-4，norm-chain §3.3.5）：start/advance/status/abort 四工具
            registerTool("playbook_start", playbookStartTool)
            registerTool("playbook_advance", playbookAdvanceTool)
            registerTool("playbook_status", playbookStatusTool)
            registerTool("playbook_abort", playbookAbortTool)
            // ══ GitOps 工程化工具：提交规范校验/建议、hooks 状态、发版前体检/打 Tag、版本日志生成
            registerTool("gitops", gitOpsTool)

            // ══ D2-3 轨迹摘要提取器登记（norm-chain §3.8.2：规则表挂 ToolResultTypeRegistry，
            //    仅少数工具定制，其余走通用截断）。提取器签名 (args, resultData) → 一行摘要。
            fun argText(args: Map<String, JsonElement>, key: String): String =
                (args[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            fun firstText(obj: JsonObject, vararg keys: String): String {
                for (key in keys) {
                    (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                }
                return ""
            }
            // readFile：路径（来自 args）+ 行数（来自结果 total_lines/read_lines）。
            resultTypeRegistry.registerTrajectorySummarizer("readFile") { args, data ->
                val obj = data as? JsonObject
                val lines = obj?.get("read_lines")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("total_lines")?.jsonPrimitive?.contentOrNull
                    ?: "-"
                val truncated = obj?.get("truncated")?.jsonPrimitive?.contentOrNull == "true"
                "readFile(${argText(args, "path")}) lines=$lines${if (truncated) " [truncated]" else ""}"
            }
            // writeFile：目标文件 + 是否新建/覆盖（路径来自 args，结果 path 展示路径）。
            resultTypeRegistry.registerTrajectorySummarizer("writeFile") { args, data ->
                val path = argText(args, "path").ifBlank { firstText(data as? JsonObject ?: JsonObject(emptyMap()), "path") }
                "writeFile($path) 已写入"
            }
            // editFile：目标文件 + 变更状态（结果 status/path）。
            resultTypeRegistry.registerTrajectorySummarizer("editFile") { args, data ->
                val obj = data as? JsonObject ?: JsonObject(emptyMap())
                val path = argText(args, "path").ifBlank { firstText(obj, "path") }
                val status = firstText(obj, "status").ifBlank { "done" }
                "editFile($path) status=$status"
            }
            // run_code：exit code + stdout 尾部。
            resultTypeRegistry.registerTrajectorySummarizer("run_code") { _, data ->
                val obj = data as? JsonObject ?: JsonObject(emptyMap())
                val exit = obj["exitCode"]?.jsonPrimitive?.contentOrNull ?: "?"
                val tail = (obj["stdout"]?.jsonPrimitive?.contentOrNull ?: "").takeLast(80).replace('\n', ' ')
                "run_code exit=$exit stdout≈$tail"
            }
            // Bash：输出尾部（纯文本结果走通用截断，此处仅标注命令执行）。
            resultTypeRegistry.registerTrajectorySummarizer("Bash") { args, data ->
                val cmd = argText(args, "command").take(60)
                "Bash($cmd)"
            }
        }
    }

    @Provides
    @Singleton
    fun provideCodeChangeTracker(): CodeChangeTracker {
        return CodeChangeTracker()
    }

    @Provides
    @Singleton
    fun provideToolDependencyScheduler(): com.core.deepcode.feature.agent.domain.tool.ToolDependencyScheduler {
        return com.core.deepcode.feature.agent.domain.tool.ToolDependencyScheduler()
    }

    @Provides
    @Singleton
    fun provideToolResultCache(): com.core.deepcode.feature.agent.domain.tool.ToolResultCache {
        return com.core.deepcode.feature.agent.domain.tool.ToolResultCache()
    }

    @Provides
    @Singleton
    fun provideToolEventBus(): com.core.deepcode.feature.agent.domain.tool.ToolEventBus {
        return com.core.deepcode.feature.agent.domain.tool.ToolEventBus()
    }

    @Provides
    @Singleton
    fun provideIncrementalIndexStore(): com.core.deepcode.feature.agent.domain.tool.IncrementalIndexStore {
        return com.core.deepcode.feature.agent.domain.tool.IncrementalIndexStore()
    }

    @Provides
    @Singleton
    fun provideAgentWorkflow(
        toolRegistry: ToolRegistry,
        aiProviderRepository: AIProviderRepository,
        openAIApi: OpenAIApi,
        anthropicApi: AnthropicApi,
        geminiApi: GeminiApi,
        promptProvider: SystemPromptProvider,
        permissionManager: ToolPermissionManager,
        policyEngine: ToolPermissionPolicyEngine,
        contextCompactor: com.core.deepcode.feature.agent.domain.workflow.ContextCompactor,
        planApprovalManager: com.core.deepcode.feature.agent.domain.tool.mode.PlanApprovalManager,
        toolOutputStore: ToolOutputStore,
        modelMetadataService: ModelMetadataService,
        visionModelSettingsRepository: com.core.deepcode.feature.settings.data.repository.VisionModelSettingsRepository,
        compactionModelSettingsRepository: com.core.deepcode.feature.settings.data.repository.CompactionModelSettingsRepository,
        compatibilityPolicyRepository: com.core.deepcode.feature.settings.data.repository.CompatibilityPolicyRepository,
        sessionUseCase: com.core.deepcode.feature.agent.domain.session.SessionUseCase,
        messagePersistenceUseCase: com.core.deepcode.feature.agent.domain.session.MessagePersistenceUseCase,
        checkpointManager: com.core.deepcode.feature.agent.domain.checkpoint.CheckpointManager,
        dependencyScheduler: com.core.deepcode.feature.agent.domain.tool.ToolDependencyScheduler,
        toolResultCache: com.core.deepcode.feature.agent.domain.tool.ToolResultCache,
        toolEventBus: com.core.deepcode.feature.agent.domain.tool.ToolEventBus,
        incrementalIndexStore: com.core.deepcode.feature.agent.domain.tool.IncrementalIndexStore,
        skillStateRepository: com.core.deepcode.feature.agent.domain.skill.SkillStateRepository,
        skillExecutor: com.core.deepcode.feature.agent.domain.skill.SkillExecutor,
        skillRuntimeProbe: com.core.deepcode.feature.agent.domain.skill.SkillRuntimeProbe,
        hookDispatcher: com.core.deepcode.feature.agent.domain.hook.HookDispatcher,
        wakeQueueManager: com.core.deepcode.feature.agent.domain.wake.WakeQueueManager,
        goalService: com.core.deepcode.feature.agent.domain.goal.GoalService,
        planService: com.core.deepcode.feature.agent.domain.plan.PlanService,
        toolGuards: Set<@JvmSuppressWildcards com.core.deepcode.feature.agent.domain.guard.ToolGuard>,
        fileObservationGuard: com.core.deepcode.feature.agent.domain.guard.FileObservationGuard,
        normFlowSettingsRepository: com.core.deepcode.feature.settings.data.repository.NormFlowSettingsRepository,
        trajectoryService: com.core.deepcode.feature.agent.domain.trajectory.TrajectoryService,
        playbookExecutor: com.core.deepcode.feature.agent.domain.playbook.PlaybookExecutor
    ): AgentWorkflow {
        return com.core.deepcode.feature.agent.domain.workflow.StatefulAgentWorkflow(
            toolRegistry,
            aiProviderRepository,
            openAIApi,
            anthropicApi,
            geminiApi,
            promptProvider,
            permissionManager,
            policyEngine,
            contextCompactor,
            planApprovalManager,
            toolOutputStore,
            modelMetadataService,
            visionModelSettingsRepository,
            compactionModelSettingsRepository,
            compatibilityPolicyRepository,
            sessionUseCase,
            messagePersistenceUseCase,
            checkpointManager,
            dependencyScheduler,
            toolResultCache,
            toolEventBus,
            incrementalIndexStore,
            skillStateRepository,
            skillExecutor,
            skillRuntimeProbe,
            hookDispatcher,
            wakeQueueManager,
            goalService,
            planService,
            toolGuards,
            fileObservationGuard,
            normFlowSettingsRepository,
            trajectoryService,
            playbookExecutor
        )
    }
}
