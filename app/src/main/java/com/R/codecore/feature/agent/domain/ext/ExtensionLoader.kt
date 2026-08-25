package com.R.codecore.feature.agent.domain.ext

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.ContainerInstaller
import com.R.codecore.feature.agent.domain.prompt.AgentAsset
import com.R.codecore.feature.agent.domain.prompt.AgentAssetRegistry
import com.R.codecore.feature.agent.domain.skill.Skill
import com.R.codecore.feature.agent.domain.skill.SkillRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 诊断用：加回 listCommandAssets/readCommandAsset/companion（不含 init/FileObserver）。
 */
@Singleton
class ExtensionLoader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller,
    private val agentAssetRegistry: AgentAssetRegistry,
    private val skillRepository: SkillRepository
) {

    /** 用户扩展根目录 `<rcodecore>/ext/`（首次访问建目录）。 */
    val userExtDir: File by lazy {
        File(containerInstaller.rcodecoreDir, "ext").also { it.mkdirs() }
    }

    /** 用户命令目录 `<rcodecore>/ext/commands/`。 */
    val userCommandsDir: File
        get() = File(userExtDir, "commands")

    private val commandCore = ExtensionCommandCore(
        userDir = userCommandsDir,
        assetsList = { listCommandAssets() },
        assetsRead = { name -> readCommandAsset(name) }
    )

    init {
        // 热加载辅机制：FileObserver 监听用户命令目录增删改 → 失效缓存（mtime 懒刷新兜底）。
        startWatching()
    }

    fun commands(): List<ExtensionCommand> = commandCore.commands()

    fun findCommand(name: String): ExtensionCommand? =
        commandCore.commands().firstOrNull { it.name == name }

    fun agents(): List<AgentAsset> = agentAssetRegistry.agents()

    fun skills(): List<Skill> = skillRepository.listSkills()

    private fun listCommandAssets(): List<String> = try {
        context.assets.list("ext/commands")?.toList() ?: emptyList()
    } catch (e: Exception) {
        FileLogger.w(TAG, "枚举内置命令资产失败: ${e.message}", e)
        emptyList()
    }

    private fun readCommandAsset(name: String): String? = try {
        context.assets.open("ext/commands/$name").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }

    // —— 热加载辅机制：FileObserver（幂等，自含 HandlerThread；失败仅降级，mtime 兜底） ——
    @Volatile private var watcherStarted = false

    @Synchronized
    private fun startWatching() {
        if (watcherStarted) return
        val thread = HandlerThread("ExtensionLoader-Watcher")
        runCatching {
            thread.start()
            val handler = Handler(thread.looper)
            runCatching { userCommandsDir.mkdirs() }
            handler.post {
                runCatching {
                    val observer = object : FileObserver(userCommandsDir, OBSERVE_EVENTS) {
                        override fun onEvent(event: Int, path: String?) {
                            commandCore.invalidate()
                        }
                    }
                    observer.startWatching()
                }.onFailure {
                    FileLogger.w(TAG, "FileObserver 监听失败 ${userCommandsDir.path}: ${it.message}", it)
                }
            }
        }.onFailure {
            FileLogger.w(TAG, "FileObserver 线程启动失败（mtime 懒刷新兜底）: ${it.message}", it)
        }
        watcherStarted = true
    }

    private companion object {
        const val TAG = "ExtensionLoader"
        const val OBSERVE_EVENTS = FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.CLOSE_WRITE
    }
}
