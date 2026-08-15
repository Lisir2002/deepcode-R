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
import com.deep.rcode.feature.agent.data.local.dao.CheckpointFileSnapshotDao
import com.deep.rcode.feature.agent.data.local.dao.ModelCapabilityOverrideDao
import com.deep.rcode.feature.agent.data.local.dao.TodoItemDao
import com.deep.rcode.feature.agent.data.local.dao.UserConfirmedSentinelDao
import com.deep.rcode.feature.agent.data.local.dao.HallucinationFuseDao
import com.deep.rcode.feature.agent.data.local.dao.SentinelPlanRejectionAuditDao
import com.deep.rcode.feature.agent.data.local.dao.HardConstraintDeleteAuditDao
import com.deep.rcode.feature.agent.data.local.dao.L0SoftCompactRestoreLogDao
import com.deep.rcode.feature.agent.data.local.dao.ZthTelemetryEventDao
import com.deep.rcode.feature.credentials.data.local.dao.GitCredentialDao
import com.deep.rcode.feature.settings.data.local.dao.AIProviderDao
import com.deep.rcode.feature.workspace.data.local.dao.CredentialEncryptionStateDao
import com.deep.rcode.feature.workspace.data.local.dao.RemoteAuditLogDao
import com.deep.rcode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.deep.rcode.feature.workspace.data.local.dao.RemoteMountDao
import com.deep.rcode.feature.t2i.data.local.dao.T2IProviderDao
import com.deep.rcode.feature.t2i.data.local.dao.T2IProviderModelDao
import com.deep.rcode.feature.t2i.data.local.dao.T2ITaskDao
import com.deep.rcode.feature.agent.data.local.dao.SkillStateDao
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
import com.deep.rcode.feature.agent.domain.tool.container.CheckEnvironmentTool
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
        // ══════════════════════════════════════════════════════════
        // DB-SHIELD-RC68 持久化护盾 · Funnel 0（先于任何构建尝试）：
        //   · 三态分流器：
        //     ① DB 文件正常存在 → 直接走 Funnel 1-4（什么都不做，最快冷启动路径）。
        //     ② DB 文件不存在，但 database_crashes/ 下有最近崩溃备份 → 静默「侧拷贝」恢复
        //       .db + 同组 .wal/.shm → 再走 Funnel 1-4。用户历史对话在不知不觉中还原。
        //     ③ DB 文件不存在 + 没有备份 → 第一次安装新用户，什么都不做。
        // ══════════════════════════════════════════════════════════
        runCatching { funnel0AutoRestoreFromCrashBackupIfNeeded(context) }
            .onFailure { FileLogger.w("AgentModule", "Funnel 0 恢复逻辑内异常（忽略，继续正常构建）: ${it.message}", it) }

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

    /**
     * DB-SHIELD-RC68 持久化护盾 · Funnel 0：
     * 「主 DB 文件缺失 + 有 database_crashes/ 备份」时，在任何 Room 构建前，静默把最近的
     * 备份拷回主 DB 路径。这样 Funnel 1-4 在「已还原的 DB」上继续执行迁移，而不是直接
     * fallbackToDestructiveMigration() 把聊天记录清空。
     *
     * 三态分流：
     *   - STATE_A：主 DB 存在 → 直接返回（冷启动热路径无额外 I/O）
     *   - STATE_B：主 DB 不存在 + crash 备份存在 → 恢复最近备份 → 返回
     *   - STATE_C：主 DB 不存在 + 无备份 → 直接返回（首次安装用户，Room onCreate）
     *
     * 并发安全：在 provider 最开头同步执行一次（所有 Funnel 还没 helper openHelper），
     * 因此不存在多 openHelper 抢文件的 SQLITE_BUSY 风险。
     */
    private fun funnel0AutoRestoreFromCrashBackupIfNeeded(context: Context) {
        val mainDb = context.getDatabasePath("rdeepcode_agent_db")
        val crashDir = java.io.File(context.filesDir, "database_crashes")

        // ── STATE_A：热路径，99.9% 的正常启动直接 return ──
        if (mainDb.exists()) return
        if (!crashDir.isDirectory) return // STATE_C 分支（未崩溃过或 dir 不存在）

        // 找最近一份「*.db」结尾的主备份文件，再把同组 .wal/.shm 一起拷
        val candidates = crashDir.listFiles { f ->
            f.name.startsWith("rdeepcode_agent_db_backup_") &&
                    f.name.endsWith(".db") && f.isFile
        }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (candidates.isEmpty()) return // STATE_C：啥也没

        val chosen = candidates.first()
        FileLogger.i(
            "AgentModule",
            "Funnel 0 命中 STATE_B：主 DB 不存在，但找到崩溃备份 ${chosen.name}，" +
                    "启动前侧拷贝还原 → ${mainDb.absolutePath}"
        )
        runCatching {
            // 父目录 databases/ 可能不存在（第一次安装后立即崩的极端情况），先 mkdirs
            mainDb.parentFile?.takeIf { !it.exists() }?.mkdirs()
            chosen.copyTo(mainDb, overwrite = true)
            // 再把同组 .wal/.shm 拷回去；即使 checkpoint 过（通常不存在），存在就拷保证完整性
            listOf("-wal", "-shm").forEach { suffix ->
                val src = java.io.File(crashDir, "${chosen.name}$suffix").takeIf { it.exists() } ?: return@forEach
                val dst = java.io.File(mainDb.parent, "${mainDb.name}$suffix")
                src.copyTo(dst, overwrite = true)
            }
            FileLogger.i(
                "AgentModule",
                "Funnel 0 还原完成: mainDb=${mainDb.exists()} size=${mainDb.length()}, " +
                        "wal=${java.io.File(mainDb.parent, "${mainDb.name}-wal").exists()}, " +
                        "shm=${java.io.File(mainDb.parent, "${mainDb.name}-shm").exists()}"
            )
        }.onFailure {
            // 恢复失败不抛——继续走原 Funnel 1-4 流程，Room onCreate 建空表也比崩强
            FileLogger.w("AgentModule", "Funnel 0 还原时 I/O 异常，跳过：${it.message}", it)
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
            // DB-SHIELD：Funnel 1 成功 = 所有迁移脚本齐全且 TableInfo 校验通过。
            // （修复 P0-1：Funnel 1 不再设置任何 fallback，缺迁移必然抛异常 → onSuccess 只在真·迁移通过时出现）
            FileLogger.i("AgentModule", "Funnel 1 OK: 所有迁移脚本齐全，Room 构建 & TableInfo 校验通过")
            return it
        }
        val err1 = funnel1.exceptionOrNull()
        FileLogger.w("AgentModule", "Funnel 1 failed（迁移文件缺口 / schema mismatch）：${err1?.message}", err1)

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
            // 抢救后用「保守 destructive (dropAllTables=false)」再开 Room：
            //   - 如果 Funnel 2 已经补完缺表缺列，这里 TableInfo 校验通过 → 成功
            //   - 如果还是失败（比如列类型/约束就冲突了），Room 只删对不上的表 → 保留核心业务数据，进入 Funnel 3 诊断
            buildAgentDatabase(context, mode = FunnelMode.FUNNEL2_AFTER_RESCUE_RETRY)
        }
        funnel2.onSuccess {
            FileLogger.i("AgentModule", "Funnel 2 OK: 轻量抢救（+ retry with conservative destructive if needed）构建 success")
            return it
        }
        val err2 = funnel2.exceptionOrNull()
        FileLogger.w("AgentModule", "Funnel 2 failed：${err2?.message}", err2)

        val funnel3 = runCatching { buildAgentDatabase(context, mode = FunnelMode.FUNNEL3_CONSERVATIVE_DESTRUCTIVE) }
        funnel3.onSuccess {
            // Funnel 3 与 Funnel 2 retry 虽然参数完全一样，但语义不同：
            //   Funnel 2 = 「已执行过反射抢救 + 只尝试 retry」；Funnel 3 = 「抢救后仍失败，最终用保守 destructive 作为兜底尝试」
            // 所以日志级别升到 warn（提醒排障：此时已经有表被 Room 删掉了）
            FileLogger.w("AgentModule", "Funnel 3 OK: 保守 destructive (dropAllTables=false) 构建 success，" +
                    "Room 已删除 schema 对不上的表；核心业务表（chat_sessions/agent_messages）应保留。" +
                    "请检查 Funnel 2 RescueReport.failures，定位未被抢救的列/索引")
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
            // DB-SHIELD-RC68 持久化护盾 P0-3：强制 WAL（Write-Ahead Logging）模式。
            //   - Room 2.x 默认在 API 16+ 启用，但我们显式 setJournalMode(WAL) 以覆盖：
            //     ① 自定义 SupportSQLiteOpenHelper.Factory 改过 journal_mode 的第三方 fork；
            //     ② 未来 Room 3.x 可能改默认 TRUNCATE（可能引发「每次 commit 都 fsync 主 .db」冷启动慢 +
            //        Funnel 4 崩溃备份时数据可能未落 WAL 就杀进程）。
            //   - WAL 语义：writer 不阻塞 reader，同一 DB 连接多事务并发度高；
            //     配合 Funnel 4 snapshotDbFileForDisasterRecovery 里的 PRAGMA wal_checkpoint(TRUNCATE)
            //     在备份前强制合并回主 DB，导出的 .db 自包含（不依赖 .wal/.shm 可读）。
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // DB-SHIELD-RC68 持久化护盾 P0-3 补充：多实例失效通知（防止 WorkManager / RemoteService
            // 等子进程开了同一 DB 连接的另一个 RoomDatabase，两个进程各自的 in-memory cache 不同步）。
            // multiInstanceInvalidation 会在 onOpen 时注册一个基于 .journal 文件的 fd fsync 监听器，
            // 另一进程写完自动让本进程的 Flow/LiveData 刷新 → 用户看不到「进程 1 写入、进程 2 读历史」
            // 造成的「聊天刚写完却突然清空」的假持久化 bug。
            .enableMultiInstanceInvalidation()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    runCatching {
                        FileLogger.i("AgentModule", "Room DB onOpen(mode=$mode)，路径=${db.path}")
                        // DB-SHIELD-RC68 持久化护盾 P0-4：onOpen 做一次 WAL checkpoint(PASSIVE)。
                        //   PASSIVE = 不阻塞事务、没 checkpoint 成功的块仍保留在 WAL 里；
                        //   每次 onOpen 「顺手」合一次，避免 .wal 累积到几十 MB，
                        //   使得：① 冷启动打开 DB 变慢（Room 要扫 WAL 回放未提交页）；
                        //         ② Funnel 4 备份前 TRUNCATE checkpoint 执行时间长（更容易被系统杀）。
                        val cur = db.query("PRAGMA wal_checkpoint(PASSIVE);")
                        cur.use {
                            if (it.moveToFirst()) {
                                val busy = it.getInt(it.getColumnIndexOrThrow("busy"))
                                val log = it.getInt(it.getColumnIndexOrThrow("log"))
                                val checkpointed = it.getInt(it.getColumnIndexOrThrow("checkpointed"))
                                if (checkpointed > 0 || busy != 0) {
                                    FileLogger.i(
                                        "AgentModule",
                                        "onOpen PASSIVE checkpoint: busy=$busy log=$log checkpointed=$checkpointed"
                                    )
                                }
                            }
                        }
                    }.onFailure { FileLogger.w("AgentModule", "DB onOpen 日志 / PASSIVE checkpoint 失败（非致命，忽略）", it) }
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
            FunnelMode.FUNNEL1_CONSERVATIVE_MIGRATION -> {
                // DB-SHIELD 修复 P0-1：Funnel 1 绝对不配置任何 fallbackToDestructiveMigration。
                // 这样 Room 发现「有缺迁移 / 老版本 user_version 低于 MIN 但未被 LightweightSchemaRescue 覆盖」时，
                // 会直接抛 IllegalStateException → 被外层 runCatching 接住 → 正确走 Funnel 2 反射抢救。
                // 以前的写法错误地配置了 dropAllTables=false 的 fallback，缺迁移直接静默删部分表，
                // 导致 onSuccess 返回、Funnel 2 永远不触发、整个 DB-SHIELD 架构失效。
            }
            FunnelMode.FUNNEL2_AFTER_RESCUE_RETRY -> {
                // LightweightSchemaRescue 后重试：如果还对不上（列类型/索引 mismatch 等反射救不了的情况），
                // 允许 Room 只删对不上的表（保留核心 chat_sessions/agent_messages），不 DROP 全表。
                builder.fallbackToDestructiveMigration(dropAllTables = false)
            }
            FunnelMode.FUNNEL3_CONSERVATIVE_DESTRUCTIVE -> {
                // 与 Funnel 2 参数相同，但语义层级 + 日志级别更高（warn），提醒排障已经到了「Room 会删一些表」的阶段。
                builder.fallbackToDestructiveMigration(dropAllTables = false)
            }
            FunnelMode.FUNNEL4_FULL_DESTRUCTIVE_WITH_BACKUP -> {
                builder.fallbackToDestructiveMigration() // 真·终极兜底：DROP 所有表再重建（Funnel4 前必须已经备份 .db/.wal/.shm）
            }
        }
        val db = builder.build()
        // RC91 SCHEMA 42 修复：强制打开 DB（openHelper.writableDatabase）。
        // Room 的 build() 是惰性的——迁移执行与 TableInfo 校验发生在首次访问 DAO 时，而不是 build() 时。
        // 若不强制打开，外层 Funnel 链的 runCatching 永远接不到迁移失败，Funnel 1 会「假成功」直接返回，
        // 整个 DB-SHIELD 四阶段兜底架构失效（这正是此前 skill_state 迁移校验失败却能继续运行的原因）。
        // 强制打开让迁移/校验在 buildAgentDatabase 内同步完成，失败即抛 → 被 Funnel 链正确捕获。
        db.openHelper.writableDatabase
        return db
    }

    @Provides
    @Singleton
    fun provideCheckpointDao(database: AgentDatabase): CheckpointDao {
        return database.checkpointDao()
    }

    // DB-SHIELD-RC68 持久化护盾 P0-2：补上之前漏的两个 DAO @Provides
    //   - CheckpointFileSnapshotDao（用于文件快照表的备份/恢复）
    //   - RemoteMountDao（远程挂载，BackupManagerImpl 引用到 RemoteConnectionDao.getAllMountsOnce）
    // 缺少这两条 → Hilt 编译期 MissingBinding error。
    @Provides
    @Singleton
    fun provideCheckpointFileSnapshotDao(database: AgentDatabase): CheckpointFileSnapshotDao {
        return database.checkpointFileSnapshotDao()
    }

    @Provides
    @Singleton
    fun provideRemoteMountDao(database: AgentDatabase): RemoteMountDao {
        return database.remoteMountDao()
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
    fun provideRemoteConnectionDao(database: AgentDatabase): RemoteConnectionDao {
        return database.remoteConnectionDao()
    }

    @Provides
    @Singleton
    fun provideTodoItemDao(database: AgentDatabase): TodoItemDao {
        return database.todoItemDao()
    }

    @Provides
    @Singleton
    fun provideGitCredentialDao(database: AgentDatabase): GitCredentialDao {
        return database.gitCredentialDao()
    }

    @Provides
    @Singleton
    fun provideCredentialEncryptionStateDao(database: AgentDatabase): CredentialEncryptionStateDao {
        return database.credentialEncryptionStateDao()
    }

    @Provides
    @Singleton
    fun provideRemoteAuditLogDao(database: AgentDatabase): RemoteAuditLogDao {
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

    // ══════════════════════════════════════════════════════════
    // RC69 T2I：3 个 DAO @Provides（AgentDatabase L94-L96 已声明 abstract fun）
    //   必须有这三条，否则 Hilt 编译期 MissingBinding → KSP 雪崩连锁报错。
    // ══════════════════════════════════════════════════════════
    @Provides
    @Singleton
    fun provideT2IProviderDao(database: AgentDatabase): T2IProviderDao {
        return database.t2iProviderDao()
    }

    @Provides
    @Singleton
    fun provideT2IProviderModelDao(database: AgentDatabase): T2IProviderModelDao {
        return database.t2iProviderModelDao()
    }

    @Provides
    @Singleton
    fun provideT2ITaskDao(database: AgentDatabase): T2ITaskDao {
        return database.t2iTaskDao()
    }

    // ══ RC74 Skill：skill_state DAO 绑定
    @Provides
    @Singleton
    fun provideSkillStateDao(database: AgentDatabase): SkillStateDao {
        return database.skillStateDao()
    }

    // ══ RC69 T2I：ImageGenerator（interface）→ OpenAiCompatibleImageGenerator（实现）绑定
    //   AgentModule 是 @Module object，不能用 @Binds，所以用 @Provides 包一层构造器注入的 impl。
    @Provides
    @Singleton
    fun provideImageGenerator(impl: com.deep.rcode.feature.t2i.data.remote.OpenAiCompatibleImageGenerator): com.deep.rcode.feature.t2i.data.remote.ImageGenerator {
        return impl
    }

    // T2I 专用探测服务：构造函数是 @Inject 所以 Hilt 本身会实例化，这里声明一个 @Provides
    //   只是保证 Module 对象里能显式声明为单例（与 ModelApiService 同生命周期），供后续 ViewModel/Repo 直接取。
    @Provides
    @Singleton
    fun provideT2IModelProbeService(impl: com.deep.rcode.feature.t2i.data.remote.T2IModelProbeService):
            com.deep.rcode.feature.t2i.data.remote.T2IModelProbeService = impl

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
        checkEnvironmentTool: CheckEnvironmentTool,
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
        memoryTool: com.deep.rcode.feature.agent.domain.tool.memory.MemoryTool,
        generateImageTool: com.deep.rcode.feature.agent.domain.tool.image.GenerateImageTool
    ): ToolRegistry {
        return ToolRegistry().apply {
            register("readFile", readFileTool)
            register("sendFile", sendFileTool)
            register("viewImage", viewImageTool)
            register("writeFile", writeFileTool)
            register("editFile", editFileTool)
            register("Bash", executeCommandTool)
            register("check_environment", checkEnvironmentTool)
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
            // ══ RC69 T2I 文生图工具：generateImage(prompt="...", width, height, steps, hd, model)
            register("generateImage", generateImageTool)
        }
    }

    @Provides
    @Singleton
    fun provideCodeChangeTracker(): CodeChangeTracker {
        return CodeChangeTracker()
    }

    @Provides
    @Singleton
    fun provideToolDependencyScheduler(): com.deep.rcode.feature.agent.domain.tool.ToolDependencyScheduler {
        return com.deep.rcode.feature.agent.domain.tool.ToolDependencyScheduler()
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
        checkpointManager: com.deep.rcode.feature.agent.domain.checkpoint.CheckpointManager,
        dependencyScheduler: com.deep.rcode.feature.agent.domain.tool.ToolDependencyScheduler
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
            checkpointManager,
            dependencyScheduler
        )
    }
}
