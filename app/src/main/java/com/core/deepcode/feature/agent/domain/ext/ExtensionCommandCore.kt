package com.core.deepcode.feature.agent.domain.ext

import com.core.deepcode.core.util.FileLogger
import java.io.File

/**
 * [ExtensionLoader] 命令扫描的纯解析核心（无 Android 依赖，JVM 可测）。
 *
 * 目录指纹 = 内置命令资产清单 + 用户命令目录下 `.md` 文件的 (mtime, size) 映射；
 * 读取先比对指纹，未变复用缓存，变了才重扫 —— 对齐
 * [com.core.deepcode.feature.agent.domain.prompt.AgentAssetCore] 的 mtime 懒刷新。
 * 同名命令优先级：用户目录 > 插件（[extraFiles]）> 内置资产。
 */
internal class ExtensionCommandCore(
    internal val userDir: File,
    private val assetsList: () -> List<String> = { emptyList() },
    private val assetsRead: (String) -> String? = { null },
    /** 插件命令内容源（key=文件名，value=内容），优先级介于用户目录与内置资产之间。 */
    private val extraFiles: () -> Map<String, String> = { emptyMap() }
) {
    private data class FileStamp(val mtime: Long, val size: Long)

    private val lock = Any()
    private var cachedStamp: Map<String, FileStamp>? = null
    private var cached: List<ExtensionCommand>? = null

    /** 全部命令（用户覆盖内置，按 name 排序）。 */
    fun commands(): List<ExtensionCommand> = load()

    /** 失效缓存（FileObserver 事件 / 主动刷新用）；下次读取强制重扫。 */
    fun invalidate() {
        synchronized(lock) {
            cachedStamp = null
            cached = null
        }
    }

    private fun load(): List<ExtensionCommand> {
        val stamp = buildStamp()
        synchronized(lock) {
            val c = cached
            if (c != null && cachedStamp == stamp) return c
            val parsed = scan(stamp)
            cachedStamp = stamp
            cached = parsed
            return parsed
        }
    }

    private fun buildStamp(): Map<String, FileStamp> {
        val result = LinkedHashMap<String, FileStamp>()
        // 内置资产（静态，时间戳固定，先入）
        for (name in assetsList()) if (name.endsWith(".md")) result[name] = FileStamp(0L, 0L)
        // 插件命令（静态内存源，先入，优先级低于用户目录）
        for (name in extraFiles().keys) if (name.endsWith(".md")) result.putIfAbsent(name, FileStamp(-1L, 0L))
        // 用户目录同名覆盖（mtime 取用户文件，优先级更高）
        userDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.forEach { result[it.name] = FileStamp(it.lastModified(), it.length()) }
        return result
    }

    private fun scan(stamp: Map<String, FileStamp>): List<ExtensionCommand> {
        val byName = LinkedHashMap<String, ExtensionCommand>()
        for (fileName in stamp.keys) {
            val content = readContent(fileName) ?: continue
            val cmd = ExtensionCommand.parse(fileName, content) ?: continue
            // 同名覆盖：stamp 中用户文件已覆盖内置同名 key，故天然取用户版本
            byName[cmd.name] = cmd
        }
        return byName.values.sortedBy { it.name }
    }

    /** 优先级读取正文：用户目录 > 插件 > assets 内置。 */
    private fun readContent(name: String): String? {
        val userFile = File(userDir, name)
        if (userFile.isFile) {
            return runCatching { userFile.readText() }
                .onFailure { FileLogger.w(TAG, "读取用户命令失败 $name: ${it.message}", it) }
                .getOrNull()
        }
        extraFiles()[name]?.let { return it }
        return assetsRead(name)
    }

    private companion object {
        const val TAG = "ExtensionCommandCore"
    }
}
