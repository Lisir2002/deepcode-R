package com.deep.rcode.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.deep.rcode.feature.agent.data.local.dao.AgentMessageDao
import com.deep.rcode.feature.agent.data.local.dao.ChatSessionDao
import com.deep.rcode.feature.agent.data.local.dao.CheckpointDao
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
        return Room.databaseBuilder(
            context,
            AgentDatabase::class.java,
            "rdeepcode_agent_db"
        ).addMigrations(*MigrationLoader.loadMigrations(context))
            .fallbackToDestructiveMigration(dropAllTables = false)
            .build()
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
    fun provideRemoteTerminalSessionManager(
        impl: com.deep.rcode.feature.terminal.domain.RemoteTerminalSessionManager,
        connection: RemoteSshConnection
    ): com.deep.rcode.feature.terminal.domain.RemoteTerminalSessionManager {
        // 实例化后注册 SSH 重连监听：每次 SSH 断线重连成功，自动把 Running 的交互 shell tab
        // 全部重建（后台命令 tab 不碰，避免副作用重复执行）。
        connection.registerOnReconnectedListener { impl.reconnectAllInteractiveRunningTabs() }
        return impl
    }

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
            sessionUseCase,
            messagePersistenceUseCase,
            checkpointManager
        )
    }
}
