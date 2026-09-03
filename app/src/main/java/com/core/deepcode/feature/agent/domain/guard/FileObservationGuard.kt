package com.core.deepcode.feature.agent.domain.guard

import com.core.deepcode.feature.agent.domain.tool.ToolResultCache
import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件观察纪律护栏（D1-4，对齐 norm-chain §3.1.3「guard 段新增核心」）：
 *
 * **拦截策略**：硬拦截 + 版本 CAS + 新建豁免。
 * - **mtime 即版本**：复用 [ToolResultCache.recordFileMtime] 现有记录点（readFile 成功时记录，
 *   本批仅追加判定）；文件被写后更新观察版本，避免「自己刚改过又被拦」。
 * - **观察源**：`readFile` 观察（标记 path 已观察）+ `writeFile` 即已知（写入成功即视为已观察）；
 *   `search`/`list` 不标记（目录级/片段级信息，易误判）。
 * - **生效边界**：仅 agent 文件工具链（readFile/writeFile/editFile）；容器/终端内 shell 写
 *   （echo >、sed -i、run_code 脚本内写）不逐条拦截——无法可靠解析任意 shell 意图，
 *   靠 SOP/prompt 纪律约束「脚本内先读后写」。
 * - **错误码**：`FS_NOT_OBSERVED`（未观察就写已存在文件，提示先 readFile）/ `FS_STALE`
 *   （观察后文件被外部改动，mtime 不一致，提示重新 readFile）。
 * - **新建豁免**：目标不存在的 writeFile/editFile 直接放行（新建）；`writeFile` 覆写已存在文件
 *   ——因「writeFile 即已知」，不拦截，写入成功后视为已观察，同会话后续 editFile 放行。
 *
 * 拦截判定仅对 `editFile`（编辑已存在文件）生效；readFile/writeFile 为观察/写入动作本身，
 * 由 workflow 在成功分支调用 [markObserved] 更新观察版本（post-execute 段）。
 *
 * 纯内存 + 纯逻辑（无 Android 依赖），可 JVM 单测（注入 mock FileAccessProvider/ToolResultCache）。
 */
@Singleton
class FileObservationGuard @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val toolResultCache: ToolResultCache
) : ToolGuard {

    override val id = "file-observation"

    override suspend fun guard(ctx: ToolGuardContext): ToolGuardResult {
        // 生效边界：仅 editFile（编辑已存在文件）需要拦截；readFile 是观察动作、writeFile 即已知。
        if (ctx.toolName != EDIT_FILE) return ToolGuardResult.Pass
        val path = ctx.argString(ARG_PATH)?.trim() ?: return ToolGuardResult.Pass
        if (path.isBlank()) return ToolGuardResult.Pass

        // 新建豁免：目标不存在（编辑新文件）直接放行；访问失败按放行处理（不误伤）。
        val exists = try { fileAccess.exists(path) } catch (e: Exception) { return ToolGuardResult.Pass }
        if (!exists) return ToolGuardResult.Pass

        // 版本 CAS：未观察 → FS_NOT_OBSERVED；观察后 mtime 变化 → FS_STALE（可恢复，提示重读）。
        val observedMtime = toolResultCache.fileMtime(path)
        if (observedMtime == null) {
            return ToolGuardResult.Block(
                code = FS_NOT_OBSERVED,
                message = "编辑文件前必须先读取该文件（readFile）以观察其内容；文件尚未被读取，请先调用 readFile 读取 $path 后再 editFile。"
            )
        }
        val currentMtime = try { fileAccess.lastModified(path) } catch (e: Exception) { return ToolGuardResult.Pass }
        if (currentMtime != observedMtime) {
            return ToolGuardResult.Block(
                code = FS_STALE,
                message = "该文件自上次读取后已被外部修改（mtime 变化），当前观察内容可能已过期；请重新调用 readFile 读取 $path 后再 editFile。"
            )
        }
        return ToolGuardResult.Pass
    }

    /**
     * 更新观察版本（post-execute 段，readFile/writeFile/editFile 成功后调用）：
     * 记录当前 mtime 作为新版本；写后更新避免「自己刚改过又被拦」。失败静默降级。
     */
    fun markObserved(path: String) {
        if (path.isBlank()) return
        try {
            toolResultCache.recordFileMtime(path, fileAccess.lastModified(path))
        } catch (e: Exception) {
            FileLogger.w(TAG, "记录文件观察版本失败: $path", e)
        }
    }

    /** 读取已记录观察版本（供单测断言/调试）。 */
    fun observedVersion(path: String): Long? = toolResultCache.fileMtime(path)

    private companion object {
        const val TAG = "FileObservationGuard"
        const val EDIT_FILE = "editFile"
        const val ARG_PATH = "path"

        /** 未观察就写已存在文件（提示先 readFile）。 */
        const val FS_NOT_OBSERVED = "FS_NOT_OBSERVED"

        /** 观察后文件被外部改动，mtime 不一致（提示重新 readFile）。 */
        const val FS_STALE = "FS_STALE"
    }
}
