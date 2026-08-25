package com.R.codecore.feature.agent.domain.guard

import com.R.codecore.feature.agent.domain.tool.ToolResultCache
import com.R.codecore.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FileObservationGuard 单测（D1-4，对齐 norm-chain-design.md §3.1.3 文件观察纪律）：
 * 硬拦截 + mtime 版本 CAS + 新建豁免 + writeFile 即已知。
 *
 * 验收对照：
 * - 未读即写已存在文件 → FS_NOT_OBSERVED BLOCK；
 * - 读后外部改 mtime 再写 → FS_STALE BLOCK；
 * - writeFile 新建 → 放行；writeFile 后同会话 editFile → 放行。
 */
class FileObservationGuardTest {

    private val fileAccess = FakeFileAccess()
    private val cache = ToolResultCache()
    private val guard = FileObservationGuard(fileAccess, cache)

    private fun ctx(toolName: String, path: String): ToolGuardContext = ToolGuardContext(
        toolName = toolName,
        args = mapOf("path" to JsonPrimitive(path)),
        sessionId = "session-1",
        projectRoot = "/ws"
    )

    // ---------- 未观察 → FS_NOT_OBSERVED ----------

    @Test
    fun editExisting_unobserved_blocksFsNotObserved() = runBlocking {
        val path = "/ws/a.kt"
        fileAccess.files[path] = 100L // 已存在
        val result = guard.guard(ctx("editFile", path))
        assertTrue(result is ToolGuardResult.Block)
        result as ToolGuardResult.Block
        assertEquals("FS_NOT_OBSERVED", result.code)
        assertTrue(result.message.contains("readFile"))
    }

    // ---------- 观察后 mtime 变化 → FS_STALE ----------

    @Test
    fun editExisting_observedThenMtimeChanged_blocksFsStale() = runBlocking {
        val path = "/ws/a.kt"
        fileAccess.files[path] = 100L
        guard.markObserved(path) // 模拟 readFile 成功观察（版本 100）
        fileAccess.files[path] = 200L // 外部修改 mtime
        val result = guard.guard(ctx("editFile", path))
        assertTrue(result is ToolGuardResult.Block)
        result as ToolGuardResult.Block
        assertEquals("FS_STALE", result.code)
        assertTrue(result.message.contains("readFile"))
    }

    // ---------- 观察后 mtime 一致 → PASS ----------

    @Test
    fun editExisting_observedSameMtime_passes() = runBlocking {
        val path = "/ws/a.kt"
        fileAccess.files[path] = 100L
        guard.markObserved(path)
        assertEquals(ToolGuardResult.Pass, guard.guard(ctx("editFile", path)))
    }

    // ---------- 新建豁免 ----------

    @Test
    fun editNewFile_passes() = runBlocking {
        val path = "/ws/new.kt"
        assertEquals(ToolGuardResult.Pass, guard.guard(ctx("editFile", path)))
    }

    // ---------- writeFile 即已知 → 同会话 editFile 放行 ----------

    @Test
    fun editAfterWriteFile_passes() = runBlocking {
        val path = "/ws/a.kt"
        fileAccess.files[path] = 100L
        guard.markObserved(path) // writeFile 成功后 post-execute 段调用，即视为已知
        assertEquals(ToolGuardResult.Pass, guard.guard(ctx("editFile", path)))
    }

    // ---------- 生效边界：仅 editFile ----------

    @Test
    fun nonEditFileTool_passes() = runBlocking {
        val path = "/ws/a.kt"
        fileAccess.files[path] = 100L
        // readFile 是观察动作、writeFile 即已知，均不拦截
        assertEquals(ToolGuardResult.Pass, guard.guard(ctx("readFile", path)))
        assertEquals(ToolGuardResult.Pass, guard.guard(ctx("writeFile", path)))
    }

    // ---------- 观察版本更新 ----------

    @Test
    fun markObserved_recordsVersion() {
        val path = "/ws/a.kt"
        fileAccess.files[path] = 300L
        guard.markObserved(path)
        assertEquals(300L, guard.observedVersion(path))
    }

    @Test
    fun markObserved_blankPath_noop() {
        guard.markObserved("  ")
        assertEquals(null, cache.fileMtime("  "))
    }
}

/** 内存版 FileAccessProvider：仅实现单测需要的方法（exists/lastModified）。 */
private class FakeFileAccess : FileAccessProvider {
    val files = HashMap<String, Long>()

    override fun exists(path: String): Boolean = files.containsKey(path)
    override fun lastModified(path: String): Long = files[path] ?: 0L

    override fun readFile(path: String): String = throw UnsupportedOperationException()
    override fun readLines(path: String): Sequence<String> = throw UnsupportedOperationException()
    override fun writeFile(path: String, content: String, overwrite: Boolean) = throw UnsupportedOperationException()
    override fun isDirectory(path: String): Boolean = false
    override fun isFile(path: String): Boolean = files.containsKey(path)
    override fun fileSize(path: String): Long = 0L
    override fun permissions(path: String): String = "rwx"
    override fun listFiles(path: String): List<com.R.codecore.feature.workspace.domain.FileEntry> = emptyList()
    override fun readBytes(path: String): ByteArray = ByteArray(0)
    override fun copyToLocal(path: String): File = throw UnsupportedOperationException()
    override fun delete(path: String) = throw UnsupportedOperationException()
    override fun mkdirs(path: String) = throw UnsupportedOperationException()
    override fun parentPath(path: String): String? = null
    override fun toDisplayPath(path: String): String = path
}
