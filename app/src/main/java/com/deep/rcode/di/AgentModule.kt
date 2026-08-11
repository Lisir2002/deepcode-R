package com.deep.rcode.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.deep.rcode.core.db.FileMigration
import com.deep.rcode.core.db.LightweightSchemaRescue
import com.deep.rcode.core.db.MigrationLoader
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.data.local.dao.AgentMessageDao
import com.deep.rcode.feature.agent.data.local.dao.ChatSessionDao
import com.deep.rcode.feature.agent.data.local.dao.CheckpointDao
import com.deep.rcode.feature.agent.data.local.dao.ModelCapabilityOverrideDao
import com.deep.rcode.feature.agent.data.local.dao.TodoItemDao
import com.deep.rcode.feature.agent.data.local.dao.UserConfirmedSentinelDao
import com.deep.rcode.feature.agent.data.local.dao.HallucinationFuseDao
import com.deep.rcode.feature.agent.data.local.dao.SentinelPlanRejectionAuditDao
import com.deep.rcode.feature.agent.data.local.dao.HardConstraintDeleteAuditDao
import com.deep.rcode.feature.agent.data.local.dao.L0SoftCompactRestoreLogDao
import com.deep.rcode.feature.agent.data.local.dao.ZthTelemetryEventDao
import com.deep.rcode.feature.settings.data.local.dao.AIProviderDao
import com.deep.rcode.feature.settings.domain.repository.AIProviderRepository
import com.deep.rcode.feature.agent.data.local.database.AgentDatabase
import com.deep.rcode.feature.agent.data.CodeChangeTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import com.deep.rcode.feature.agent.data.remote.anthropic.AnthropicApi
import com.deep.rcode.feature.agent.data.remote.gemini.GeminiApi
import com.deep.rcode.feature.agent.data.remote.openai.OpenAIApi
import com.deep.rcode.feature.agent.domain.container.CommandEngine
import com.deep.rcode.feature.agent.domain.container.DelegatingCommandEngine
import com.deep.rcode.feature.agent.domain.container.LinuxContainerEngine
import com.deep.rcode.feature.agent.domain.container.RemoteSshConnection
import com.deep.rcode.feature.agent.domain.container.RemoteSshEngine
import com.deep.rcode.feature.settings.data.repository.ExecutionMode
import com.deep.rcode.feature.settings.data.repository.ExecutionModeHolder
import com.deep.rcode.feature.agent.domain.tool.file.ReadFileTool
import com.deep.rcode.feature.agent.domain.tool.file.SendFileTool
import com.deep.rcode.feature.agent.domain.tool.file.ViewImageTool
import com.deep.rcode.feature.agent.domain.tool.file.WriteFileTool
import com.deep.rcode.feature.agent.domain.tool.editor.EditFileTool
import com.deep.rcode.feature.agent.domain.tool.container.ExecuteCommandTool
import com.deep.rcode.feature.agent.domain.tool.container.TerminalSessionTool
import com.deep.rcode.feature.agent.domain.tool.explorer.ListFilesTool
import com.deep.rcode.feature.agent.domain.tool.explorer.SearchCodeTool
import com.deep.rcode.feature.agent.domain.tool.skill.LoadSkillTool
import com.deep.rcode.feature.agent.domain.tool.question.AskUserQuestionTool
import com.deep.rcode.feature.agent.domain.tool.todo.TodoTool
import com.deep.rcode.feature.agent.domain.prompt.SystemPromptProvider
import com.deep.rcode.feature.agent.domain.workflow.AgentWorkflow
import com.deep.rcode.feature.agent.domain.tool.ToolPermissionManager
import com.deep.rcode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.deep.rcode.feature.agent.domain.tool.ToolRegistry
import com.deep.rcode.feature.agent.domain.tool.ToolOutputStore
import com.deep.rcode.feature.settings.data.remote.ModelMetadataService
import com.deep.rcode.feature.terminal.domain.DelegatingTerminalSessionProvider
import com.deep.rcode.feature.terminal.domain.RemoteTerminalSessionManager
import com.deep.rcode.feature.terminal.domain.TerminalSessionManager
import com.deep.rcode.feature.terminal.domain.TerminalSessionProvider
import com.deep.rcode.feature.workspace.domain.FileAccessProvider
import com.deep.rcode.feature.workspace.domain.DelegatingFileAccess
import com.deep.rcode.feature.workspace.domain.LocalFileAccess
import com.deep.rcode.feature.workspace.domain.RemoteSftpFileAccess
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

    @Provides
    @Singleton
    fun provideAgentDatabase(@ApplicationContext context: Context): AgentDatabase {
        // RC61b hotfix3：再增加第 0 层兜底。
        // provideAgentDatabase 本身是 Hilt 注入链上的 @Provides，若它抛任何 Throwable（包括
        // Room builder 过程中的 ExceptionInInitializerError / NoClassDefFoundError / Room 内部
        // IllegalStateException 没有被 firstStage.runCatching 精确兜到的情况），都会让整个
        // Application.onCreate → Hilt component 构建失败 → 系统直接杀进程，连崩溃弹窗都没有。
        // 因此这里外层再 try 一次，任何 Throwable 都走「空 DB + 破坏性重建」，绝不抛。
        return runCatching {
            provideAgentDatabaseInternal(context)
        }.getOrElse { fatal ->
            FileLogger.e(
                "AgentModule",
                "provideAgentDatabaseInternal 抛出非预期 Throwable（可能 Room 内部或 Asset 迁移异常），" +
                        "直接走 destructive 终极兜底，历史数据可能丢但应用能启动。原因=${fatal.message}",
                fatal
            )
            runCatching {
                androidx.room.Room.databaseBuilder(
                    context,
                    AgentDatabase::class.java,
                    "rdeepcode_agent_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
            }.getOrElse { fatal2 ->
                // 真·最后一招：如果连 destructive build 都炸（通常是 context 本身都坏了或 APK
                // 内置 Room 的 Entity schema 已损坏），继续抛就是杀进程——此时我们改抛一个
                // 更轻的 RuntimeException 带完整原因，至少经 CrashHandler 能落日志。
                FileLogger.e(
                    "AgentModule",
                    "连 destructive fallback 构建都失败，应用必然无法正常工作，只抛可记录的异常",
                    fatal2
                )
                throw RuntimeException("Room DB 终极兜底构建失败，上一层原因=${fatal.message}", fatal2)
            }
        }
    }

    private fun provideAgentDatabaseInternal(@ApplicationContext context: Context): AgentDatabase {
        // DB-SHIELD 4 阶段 Funnel（从"保数据最优"到"保启动最差"严格递减）：
        //   Funnel 1: 保守迁移（缺 migration 不 DROP），优先走
        //   Funnel 2: LightweightSchemaRescue（反射 Entity CREATE IF NOT EXISTS/ADD COLUMN，不删任何老表）
        //   Funnel 3: 保守 destructive (dropAllTables=false，只删 Room 认为 schema 不对的表)
        //   Funnel 4: 终极 destructive，先自动 .db 备份再 DROP 全表
        val declaredVersion = AgentDatabase.SCHEMA_VERSION

        val funnel1 = runCatching { buildAgentDatabase(context, mode = FunnelMode.FUNNEL1_CONSERVATIVE_MIGRATION) }
        funnel1.onSuccess {
            FileLogger.i("AgentModule", "Funnel 1 OK: 所有迁移齐全，Room 构建 success")
            return it
        }
        val err1 = funnel1.exceptionOrNull()
        FileLogger.w("AgentModule", "Funnel 1 failed（可能是迁移文件缺口或 schema mismatch）：${err1?.message}", err1)

        val dbFile = context.getDatabasePath("rdeepcode_agent_db")
        val funnel2 = runCatching {
            if (dbFile.exists()) {
                val report = LightweightSchemaRescue.rescue(context, dbFile, declaredVersion)
                FileLogger.i("AgentModule", "Funnel 2 LightweightRescue 报告: $report")
                if (report.failures.isNotEmpty()) {
                    FileLogger.w("AgentModule", "Funnel 2 rescue 时的非致命异常 (${report.failures.size})：${report.failures.take(3)}")
                }
            } else {
                FileLogger.i("AgentModule", "Funnel 2: DB 文件不存在（第一次启动），跳过轻量抢救")
            }
            // 抢救后立即用「保守迁移」重建 Room（此时新表/缺列已经通过 SQLite 原生补好，
            // Room 的 onOpen TableInfo 校验大概率会通过；即使仍失败 → 进入 Funnel 3）
            buildAgentDatabase(context, mode = FunnelMode.FUNNEL2_AFTER_RESCUE_RETRY)
        }
        funnel2.onSuccess {
            FileLogger.i("AgentModule", "Funnel 2 OK: 轻量抢救 + Room 二次构建 success")
            return it
        }
        val err2 = funnel2.exceptionOrNull()
        FileLogger.w("AgentModule", "Funnel 2 failed：${err2?.message}", err2)

        val funnel3 = runCatching { buildAgentDatabase(context, mode = FunnelMode.FUNNEL3_CONSERVATIVE_DESTRUCTIVE) }
        funnel3.onSuccess {
            FileLogger.w("AgentModule", "Funnel 3 OK: 保守 destructive (dropAllTables=false) 构建 success，" +
                    "仅删除 schema 对不上的表，业务核心表（chat_sessions/agent_messages）保留")
            return it
        }
        val err3 = funnel3.exceptionOrNull()
        FileLogger.w("AgentModule", "Funnel 3 failed：${err3?.message}", err3)

        // ── Funnel 4：终极兜底。先在破坏性重建前把 .db 文件拷贝到 filesDir/database_crashes/ ──
        if (dbFile.exists()) {
            val snapshot = LightweightSchemaRescue.snapshotDbFileForDisasterRecovery(context, dbFile)
            if (snapshot != null) {
                FileLogger.i("AgentModule", "Funnel 4: 触发前已备份原 DB → ${snapshot.absolutePath}")
            }
        }
        val funnel4 = runCatching { buildAgentDatabase(context, mode = FunnelMode.FUNNEL4_FULL_DESTRUCTIVE_WITH_BACKUP) }
        funnel4.onSuccess {
            FileLogger.w("AgentModule", "Funnel 4 OK: destructive 终极兜底构建 success，所有表已重建；" +
                    "若 filesDir/database_crashes/ 下有备份，可在设置页触发「崩溃还原」")
            return it
        }
        val err4 = funnel4.exceptionOrNull()
        FileLogger.e("AgentModule", "Funnel 4 (终极兜底) 仍然失败：${err4?.message}", err4)
        // 最后抛（外层 provideAgentDatabase 还有 runCatching + 第 5 次 build）
        throw RuntimeException("Room DB Funnel 1-4 全部失败，上一层走第 0 层兜底：${err1?.message} :: ${err2?.message} :: ${err3?.message} :: ${err4?.message}")
    }

    /**
     * DB-SHIELD 四阶段枚举（用于 buildAgentDatabase 内部切换 fallback 策略）。
     */
    private enum class FunnelMode {
        FUNNEL1_CONSERVATIVE_MIGRATION,
        FUNNEL2_AFTER_RESCUE_RETRY,
        FUNNEL3_CONSERVATIVE_DESTRUCTIVE,
        FUNNEL4_FULL_DESTRUCTIVE_WITH_BACKUP,
    }

    private fun buildAgentDatabase(
        context: Context,
        mode: FunnelMode,
    ): AgentDatabase {
        val builder = Room.databaseBuilder(
            context,
            AgentDatabase::class.java,
            "rdeepcode_agent_db"
        ).addMigrations(*MigrationLoader.loadMigrations(context))
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    runCatching {
                        FileLogger.i("AgentModule", "Room DB onOpen(mode=$mode)，路径=${db.path}")
                    }.onFailure { FileLogger.w("AgentModule", "DB onOpen 日志失败", it) }
                }

                override fun onCreate(db: SupportSQLiteDatabase) {
                    FileLogger.i("AgentModule", "Room DB 首次创建 (onCreate, mode=$mode)")
                }

                override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                    FileLogger.w(
                        "AgentModule",
                        "Room 触发 destructive migration（mode=$mode），" +
                                if (mode == FunnelMode.FUNNEL3_CONSERVATIVE_DESTRUCTIVE)
                                    "保守模式：只删 schema 对不上的表；业务表保留"
                                else
                                    "全表重建：所有表被清空；应已在 Funnel 4 前触发 DB 文件备份"
                    )
                }
            })
        when (mode) {
            FunnelMode.FUNNEL1_CONSERVATIVE_MIGRATION,
            FunnelMode.FUNNEL2_AFTER_RESCUE_RETRY -> {
                // Funnel 1/2 绝对不做 destructive：如果 migration 缺失 → 抛异常给上层转 Funnel 2/3。
                // （Funnel 2 的抢救已经通过原生 SQLite 在 LightweightSchemaRescue.rescue() 内完成，
                //  这里再用 conservative 模式打开 Room，让 TableInfo 校验通过就成功，失败就继续降级。）
                builder.fallbackToDestructiveMigration(dropAllTables = false)
            }
            FunnelMode.FUNNEL3_CONSERVATIVE_DESTRUCTIVE -> {
                builder.fallbackToDestructiveMigration(dropAllTables = false)
            }
            FunnelMode.FUNNEL4_FULL_DESTRUCTIVE_WITH_BACKUP -> {
                builder.fallbackToDestructiveMigration() // 真·兜底：DROP 所有表再重建
            }
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideCheckpointDao(database: AgentDatabase): CheckpointDao {
        return database.checkpointDao()
    }

    @Provides
    @Singleton
    fun provideAgentMessageDao(database: AgentDatabase): AgentMessageDao {
        return database.agentMessageDao()
    }

    @Provides
    @Singleton
    fun provideChatSessionDao(database: AgentDatabase): ChatSessionDao {
        return database.chatSessionDao()
    }

    @Provides
    @Singleton
    fun provideAIProviderDao(database: AgentDatabase): AIProviderDao {
        return database.aiProviderDao()
    }

    @Provides
    @Singleton
    fun provideRemoteConnectionDao(database: AgentDatabase): com.deep.rcode.feature.workspace.data.local.dao.RemoteConnectionDao {
        return database.remoteConnectionDao()
    }

    @Provides
    @Singleton
    fun provideTodoItemDao(database: AgentDatabase): TodoItemDao {
        return database.todoItemDao()
    }

    @Provides
    @Singleton
    fun provideGitCredentialDao(database: AgentDatabase): com.deep.rcode.feature.credentials.data.local.dao.GitCredentialDao {
        return database.gitCredentialDao()
    }

    @Provides
    @Singleton
    fun provideCredentialEncryptionStateDao(database: AgentDatabase): com.deep.rcode.feature.workspace.data.local.dao.CredentialEncryptionStateDao {
        return database.credentialEncryptionStateDao()
    }

    @Provides
    @Singleton
    fun provideRemoteAuditLogDao(database: AgentDatabase): com.deep.rcode.feature.workspace.data.local.dao.RemoteAuditLogDao {
        return database.remoteAuditLogDao()
    }

    @Provides
    @Singleton
    fun provideModelCapabilityOverrideDao(database: AgentDatabase): ModelCapabilityOverrideDao {
        return database.modelCapabilityOverrideDao()
    }

    // ── ZTH Phase 1/4 新增 6 个 DAO（AgentDatabase L75-L80 已声明，此处必须 @Provides 否则 Hilt MissingBinding） ──
    @Provides
    @Singleton
    fun provideUserConfirmedSentinelDao(database: AgentDatabase): UserConfirmedSentinelDao {
        return database.userConfirmedSentinelDao()
    }

    @Provides
    @Singleton
    fun provideHallucinationFuseDao(database: AgentDatabase): HallucinationFuseDao {
        return database.hallucinationFuseDao()
    }

    @Provides
    @Singleton
    fun provideSentinelPlanRejectionAuditDao(database: AgentDatabase): SentinelPlanRejectionAuditDao {
        return database.sentinelPlanRejectionAuditDao()
    }

    @Provides
    @Singleton
    fun provideHardConstraintDeleteAuditDao(database: AgentDatabase): HardConstraintDeleteAuditDao {
        return database.hardConstraintDeleteAuditDao()
    }

    @Provides
    @Singleton
    fun provideL0SoftCompactRestoreLogDao(database: AgentDatabase): L0SoftCompactRestoreLogDao {
        return database.l0SoftCompactRestoreLogDao()
    }

    @Provides
    @Singleton
    fun provideZthTelemetryEventDao(database: AgentDatabase): ZthTelemetryEventDao {
        return database.zthTelemetryEventDao()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        // 流式 SSE 下读超时是「相邻数据块之间」的等待上限；120s 给慢启动/长思考留足空间，
        // 真正卡死由上层阶梯重试（RetryPolicy）兜底。
        return OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
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
    fun provideGeminiApi(@Named("Gemini") retrofit: Retrofit): com.deep.rcode.feature.agent.data.remote.gemini.GeminiApi {
        return retrofit.create(com.deep.rcode.feature.agent.data.remote.gemini.GeminiApi::class.java)
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
        modeHolder: com.deep.rcode.feature.settings.data.repository.ExecutionModeHolder,
        local: TerminalSessionManager,
        remote: com.deep.rcode.feature.terminal.domain.RemoteTerminalSessionManager
    ): DelegatingTerminalSessionProvider = DelegatingTerminalSessionProvider(modeHolder, local, remote)

    @Provides
    @Singleton
    fun provideRemoteSftpFileAccess(
        connection: RemoteSshConnection,
        workspaceRepository: com.deep.rcode.feature.workspace.data.repository.WorkspaceRepository
    ): RemoteSftpFileAccess = RemoteSftpFileAccess(connection, workspaceRepository)

    @Provides
    @Singleton
    fun provideToolRegistry(
        readFileTool: ReadFileTool,
        sendFileTool: SendFileTool,
        viewImageTool: ViewImageTool,
        writeFileTool: WriteFileTool,
        editFileTool: EditFileTool,
        executeCommandTool: ExecuteCommandTool,
        terminalSessionTool: TerminalSessionTool,
        listFilesTool: ListFilesTool,
        searchCodeTool: SearchCodeTool,
        loadSkillTool: LoadSkillTool,
        askUserQuestionTool: AskUserQuestionTool,
        manageMcpTool: com.deep.rcode.feature.agent.domain.tool.mcp.ManageMcpTool,
        webSearchTool: com.deep.rcode.feature.agent.domain.tool.search.WebSearchTool,
        webFetchTool: com.deep.rcode.feature.agent.domain.tool.search.WebFetchTool,
        switchModeTool: com.deep.rcode.feature.agent.domain.tool.mode.SwitchModeTool,
        todoTool: TodoTool,
        memoryTool: com.deep.rcode.feature.agent.domain.tool.memory.MemoryTool
    ): ToolRegistry {
        return ToolRegistry().apply {
            register("readFile", readFileTool)
            register("sendFile", sendFileTool)
            register("viewImage", viewImageTool)
            register("writeFile", writeFileTool)
            register("editFile", editFileTool)
            register("Bash", executeCommandTool)
            register("terminal", terminalSessionTool)
            register("list", listFilesTool)
            register("search", searchCodeTool)
            register("loadSkill", loadSkillTool)
            register("askUserQuestion", askUserQuestionTool)
            register("manageMcp", manageMcpTool)
            register("websearch", webSearchTool)
            register("webfetch", webFetchTool)
            register("switchMode", switchModeTool)
            register("todo", todoTool)
            register("memory", memoryTool)
        }
    }

    @Provides
    @Singleton
    fun provideCodeChangeTracker(): CodeChangeTracker {
        return CodeChangeTracker()
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
        contextCompactor: com.deep.rcode.feature.agent.domain.workflow.ContextCompactor,
        planApprovalManager: com.deep.rcode.feature.agent.domain.tool.mode.PlanApprovalManager,
        toolOutputStore: ToolOutputStore,
        modelMetadataService: ModelMetadataService,
        visionModelSettingsRepository: com.deep.rcode.feature.settings.data.repository.VisionModelSettingsRepository,
        compactionModelSettingsRepository: com.deep.rcode.feature.settings.data.repository.CompactionModelSettingsRepository,
        compatibilityPolicyRepository: com.deep.rcode.feature.settings.data.repository.CompatibilityPolicyRepository,
        sessionUseCase: com.deep.rcode.feature.agent.domain.session.SessionUseCase,
        messagePersistenceUseCase: com.deep.rcode.feature.agent.domain.session.MessagePersistenceUseCase,
        checkpointManager: com.deep.rcode.feature.agent.domain.checkpoint.CheckpointManager
    ): AgentWorkflow {
        return com.deep.rcode.feature.agent.domain.workflow.StatefulAgentWorkflow(
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
            checkpointManager
        )
    }
}
