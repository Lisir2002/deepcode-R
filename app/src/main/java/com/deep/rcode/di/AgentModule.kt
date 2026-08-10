package com.deep.rcode.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.data.local.dao.AgentMessageDao
import com.deep.rcode.feature.agent.data.local.dao.ChatSessionDao
import com.deep.rcode.feature.agent.data.local.dao.CheckpointDao
import com.deep.rcode.feature.agent.data.local.dao.ModelCapabilityOverrideDao
import com.deep.rcode.feature.agent.data.local.dao.TodoItemDao
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

import com.deep.rcode.core.db.MigrationLoader

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
        // RC61b：双阶段构建，保证 schema 校验失败时应用依然可启动
        // 第一阶段：优先走迁移（所有 SQL 脚本 + fallbackToDestructiveMigration(dropAllTables=false)）
        //           即"未知版本 → 只删未知表、保留业务表"的保守降级。
        // 第二阶段：第一阶段若抛异常（最常见 = migration 32 列名/索引与 Entity 不一致导致
        //           Room.onOpen 校验 IllegalStateException），退化为 destructive = true 的彻底
        //           重建。此路径会丢表中数据但保证 Hilt 注入链不崩 → App 不被系统杀。
        val firstStage = runCatching {
            buildAgentDatabase(context, destructiveFallback = false)
        }
        firstStage.onSuccess { return it }
        FileLogger.e(
            "AgentModule",
            "Room 首阶段构建失败（可能是迁移 schema 不匹配），降级为 destructive 重建，" +
                    "历史聊天/会话会清空但应用可继续使用。原因=${firstStage.exceptionOrNull()?.message}",
            firstStage.exceptionOrNull()
        )
        return buildAgentDatabase(context, destructiveFallback = true)
    }

    private fun buildAgentDatabase(
        context: Context,
        destructiveFallback: Boolean,
    ): AgentDatabase {
        val builder = Room.databaseBuilder(
            context,
            AgentDatabase::class.java,
            "rdeepcode_agent_db"
        ).addMigrations(*MigrationLoader.loadMigrations(context))
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // onOpen 是 Room 进行 TableInfo 实际 schema 校验的时机；
                    // 这里包一层 try，只记日志不向上抛（Room 本身也会抛），
                    // 便于通过 FileLogger 追溯"启动 1-2s 秒退"是否由 schema mismatch 引发。
                    runCatching {
                        FileLogger.i("AgentModule", "Room DB onOpen，路径=${db.path}")
                    }.onFailure { FileLogger.w("AgentModule", "DB onOpen 日志失败", it) }
                }

                override fun onCreate(db: SupportSQLiteDatabase) {
                    FileLogger.i("AgentModule", "Room DB 首次创建 (onCreate)")
                }

                override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                    FileLogger.w(
                        "AgentModule",
                        "Room 触发 destructive migration（全表重建），destructiveFallback=$destructiveFallback"
                    )
                }
            })
        if (destructiveFallback) {
            builder.fallbackToDestructiveMigration() // 真·兜底：未知版本直接删全部表再建
        } else {
            builder.fallbackToDestructiveMigration(dropAllTables = false) // 保守降级
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
