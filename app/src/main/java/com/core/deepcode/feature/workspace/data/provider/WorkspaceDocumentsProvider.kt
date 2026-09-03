package com.core.deepcode.feature.workspace.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.core.deepcode.R
import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.domain.container.ContainerInstaller
import java.io.File
import java.io.FileNotFoundException
import java.util.LinkedList

/**
 * SAF DocumentsProvider，把 app 私有 `filesDir` 暴露为**单一根**到系统「文件」app 及其它 app 的
 * SAF 选择器，满足「对外可见」。根下只放出两个子目录：
 * - `projects`：工作区（各项目）；
 * - `deepcode`：AI 配置目录（skills/ 与 mcp.json，容器内即 `/root/.deepcode`）。
 *
 * 其余内部目录（rootfs、容器二进制、数据库、DataStore 等）刻意不在根下列出，避免对外泄露/误删。
 *
 * 工作区物理上在 app 私有 ext4（filesDir），换取 symlink 支持（npm/pnpm/yarn/git 零配置可用）；
 * emulated 存储虽天然可见但内核拒绝 symlink。可见性这里用官方 SAF API 在 API 层补回，与物理位置解耦。
 *
 * docId 直接用文件绝对路径（与 Termux 实现一致），简单且跨进程稳定。
 *
 * ============= RC61b hotfix3 =============
 * ContentProvider 生命周期早于 Application.onCreate：其 onCreate() 在 Application.attachBaseContext
 * 之后、Application.onCreate 之前被系统调用。因此**这里的任何 RuntimeException 都会直接杀死整个进程**
 * （Android 对 Provider 异常的惩罚比 Activity 更严，没有崩溃弹窗直接消失）。本 Provider 因此做三条铁律：
 *   1. onCreate 先 FileLogger.init(context)，保证 Provider 自身抛异常也能落盘；
 *   2. 所有 @Override SAF 入口过 providerSafe{}：把 IllegalStateException/SecurityException 等
 *      非 SAF 契约异常统一转 FileNotFoundException（SAF 官方失败语义），禁止任何 Runtime 越抛穿透；
 *   3. exposedChildren 热路径绝不做 asset IO：extractDocs 由 Application 后台协程负责，
 *      Provider 内仅 lazy 建目录，避免首次 queryRoots 主线程 IO + 抛 IOEX 杀进程。
 */
class WorkspaceDocumentsProvider : DocumentsProvider() {

    private companion object {
        const val ALL_MIME_TYPES = "*/*"
        const val MAX_SEARCH_RESULTS = 50

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )
    }

    /** 暴露的单一根：app 私有 filesDir。 */
    private fun baseDir(): File =
        ctx().filesDir

    /**
     * 根下唯一对外可见的子目录名白名单：工作区 `projects` 与 AI 配置 `deepcode`（容器内 /root/.deepcode，
     * 含 skills/ 与 mcp.json）。两者首次访问即创建，其余 filesDir 内部目录不列出。
     *
     * RC61b hotfix3: **这里绝对不能再同步调 ContainerInstaller.extractDocs(ctx())**。
     * 它会触发 AssetManager.open（主线程 IO）且在个别 ROM 上会因 asset 路径不存在等原因抛
     * RuntimeException，直接穿透到 queryRoots→ContentProvider→系统杀进程。
     * 提取 docs 改为「AIEditorApp appScope 后台协程 + ContainerInstaller.init 协程」两处异步完成，
     * Provider 内只保证目录存在、文档到时自然就能列出。
     */
    private fun exposedChildren(): List<File> {
        return listOf(
            runCatching { File(baseDir(), "projects").apply { mkdirs() } }.getOrElse { emptyList<File>().first() }
                ?: File(ctx().cacheDir, "projects_safe_fallback"),
            runCatching { File(baseDir(), "deepcode").apply { mkdirs() } }.getOrElse { emptyList<File>().first() }
                ?: File(ctx().cacheDir, "deepcode_safe_fallback"),
        ).filterNotNull()
    }

    /** minSdk 26 上基类无 requireContext()，这里自取非空 context。
     *  RC61b hotfix3：返回值非空，任何失败都转 Provider 安全的 FileNotFoundException。 */
    private fun ctx(): android.content.Context =
        context ?: throw FileNotFoundException("Provider context unavailable")

    /**
     * 统一 Provider 入口安全包装：
     *   - 正常返回：原样透传；
     *   - 抛 FileNotFoundException(SAF 契约异常)：原样透传，系统文件管理器按"文件不存在"处理；
     *   - 抛其它任何异常：记日志并转 FileNotFoundException，**禁止向上抛 RuntimeException**。
     */
    private inline fun <T> providerSafe(tag: String, block: () -> T): T {
        return try {
            block()
        } catch (fnf: FileNotFoundException) {
            // SAF 契约内：直接抛即可，系统文件管理器按标准路径失败语义处理
            throw fnf
        } catch (t: Throwable) {
            FileLogger.e(
                "WorkspaceProvider",
                "$tag 抛非 SAF 异常（Provider 崩会直接杀进程），降级为 FileNotFoundException 放行",
                t
            )
            throw FileNotFoundException("$tag failed: ${t.message}")
        }
    }

    /**
     * RC61b：全链路沙箱校验。任何以「文件绝对路径」作为 docId 的访问都必须先过这关：
     *   1. 先 canonicalize 规范化（解 symlink、去 /../ 等），防止路径混淆。
     *   2. 根限定为 baseDir().canonicalPath（app 私有 filesDir），禁止越权到
     *      databases/、shared_prefs/、rootfs 等敏感目录。
     *   3. 再额外过一遍「根下白名单子目录」：只允许 projects/ 与 deepcode/。
     * 访问越权一律抛 FileNotFoundException（SAF 契约的官方失败语义），避免被系统
     * 文档管理器或三方 app 当作"安全漏洞"杀死进程。
     */
    private fun sandboxedFile(docIdOrPath: String, writeIntent: Boolean = false): File {
        val base = runCatching { baseDir().canonicalPath }.getOrNull()
            ?: throw FileNotFoundException("baseDir unavailable")
        val raw = File(docIdOrPath)
        val canonical = runCatching { raw.canonicalFile }.getOrNull()
            ?: throw FileNotFoundException("canonicalize failed: $docIdOrPath")
        // 规则 1：必须位于 filesDir 之下
        val okUnderBase = (canonical.path == base) || canonical.path.startsWith(base + File.separator)
        if (!okUnderBase) throw FileNotFoundException("outside baseDir: $docIdOrPath")
        // 规则 2：baseDir 本身只允许"读目录查询"（根本身不允许写/打开）；
        //         若 path == base，由调用方自己判断语义，这里先放行，调用方再判定。
        if (canonical.path == base) return canonical
        // 规则 3：白名单 projects/ 与 deepcode/ 下的任何后代才允许访问
        val exposed = exposedChildren().mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
        val okUnderExposed = exposed.any { p ->
            canonical.path == p || canonical.path.startsWith(p + File.separator)
        }
        if (!okUnderExposed) throw FileNotFoundException("outside exposed dirs: $docIdOrPath")
        // 规则 4：写意图时防止跟随 symlink 跳到外部（虽然前序已挡，这里做二次保险）
        if (writeIntent) {
            val parent = canonical.parentFile?.canonicalPath
                ?: throw FileNotFoundException("no parent: $docIdOrPath")
            val parentOk = (parent == base) || exposed.any { p ->
                parent == p || parent.startsWith(p + File.separator)
            }
            if (!parentOk) throw FileNotFoundException("write outside sandbox: $docIdOrPath")
        }
        return canonical
    }

    override fun onCreate(): Boolean = providerSafe("onCreate") {
        // Provider.onCreate 比 Application.onCreate 更早，必须在此确保 FileLogger 已初始化，
        // 否则 Provider 链上抛异常后 FileLogger 还没就绪 = 无日志 = 用户拿不到信息。
        runCatching {
            val c = context
            if (c != null) {
                com.core.deepcode.core.util.FileLogger.init(c)
                com.core.deepcode.core.util.FileLogger.i(
                    "WorkspaceProvider",
                    "onCreate，Provider 早于 Application.onCreate 先独立初始化 FileLogger 兜底"
                )
            }
        }.onFailure { /* 即使 init 本身失败（极端存储不可用），也不能让 onCreate 抛异常 */ }
        true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        providerSafe("queryRoots") {
            val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
            addRoot(result, baseDir(), ctx().getString(R.string.app_name))
            result
        }

    /** 往根游标追加一个 root：docId 用目录绝对路径，与 [docIdForFile] 一致。 */
    private fun addRoot(result: MatrixCursor, dir: File, title: String) {
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, docIdForFile(dir))
            add(Root.COLUMN_DOCUMENT_ID, docIdForFile(dir))
            add(Root.COLUMN_SUMMARY, null)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD,
            )
            add(Root.COLUMN_TITLE, title)
            add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES)
            add(Root.COLUMN_AVAILABLE_BYTES, dir.freeSpace)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        providerSafe("queryDocument") {
            val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
            includeFile(result, sandboxedFile(documentId).absolutePath, null)
            result
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = providerSafe("queryChildDocuments") {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = sandboxedFile(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("not a dir: $parentDocumentId")
        // 根目录（filesDir 本身）只放出白名单子目录，隐藏 rootfs/数据库等内部目录；
        // 其余层级照常列出全部内容。
        val children = if (parent.absolutePath == baseDir().absolutePath) {
            exposedChildren()
        } else {
            parent.listFiles()?.toList() ?: emptyList()
        }
        children.forEach { child ->
            // child 仍然要过沙箱：防止目录里放了跳出白名单的 symlink
            runCatching { sandboxedFile(child.absolutePath) }.getOrNull()
                ?.let { includeFile(result, null, it) }
        }
        result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = providerSafe("openDocument") {
        val write = mode.contains('w') || mode.contains('W') || mode.contains('t')
        val file = sandboxedFile(documentId, writeIntent = write)
        if (file.isDirectory) throw FileNotFoundException("is directory: $documentId")
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String = providerSafe("createDocument") {
        val parent = sandboxedFile(parentDocumentId, writeIntent = true)
        if (!parent.isDirectory) throw FileNotFoundException("parent not a dir: $parentDocumentId")
        var newFile = File(parent, displayName)
        // 防路径穿越：displayName 不能包含 "/"，否则 File(parent, displayName) 会跳到 parent 之外。
        if (displayName.contains('/') || displayName == "." || displayName == "..") {
            throw FileNotFoundException("invalid display name: $displayName")
        }
        var conflictId = 2
        while (newFile.exists()) {
            newFile = File(parent, "$displayName ($conflictId)")
            conflictId++
        }
        val ok = try {
            if (Document.MIME_TYPE_DIR == mimeType) newFile.mkdir() else newFile.createNewFile()
        } catch (e: Exception) {
            throw FileNotFoundException("Failed to create document: ${newFile.path}")
        }
        if (!ok) throw FileNotFoundException("Failed to create document: ${newFile.path}")
        // 最终再沙箱校验一次（防止 mkdir 通过 symlink 跳走）
        sandboxedFile(newFile.absolutePath).absolutePath
    }

    override fun deleteDocument(documentId: String) = providerSafe("deleteDocument") {
        val file = sandboxedFile(documentId, writeIntent = true)
        // 禁止删根（filesDir 本身）与白名单顶层目录（防止误把整个 projects/ 干掉）
        val base = baseDir().canonicalPath
        val exposed = exposedChildren().mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
        if (file.absolutePath == base || file.absolutePath in exposed) {
            throw FileNotFoundException("refuse to delete root/exposed root: $documentId")
        }
        if (!file.deleteRecursively()) {
            throw FileNotFoundException("Failed to delete document: $documentId")
        }
    }

    override fun getDocumentType(documentId: String): String =
        providerSafe("getDocumentType") { mimeType(sandboxedFile(documentId)) }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor = providerSafe("querySearchDocuments") {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val root = sandboxedFile(rootId)
        val rootCanonical = root.canonicalPath
        // 从根（filesDir）搜索时只下钻白名单子目录，避免扫到 rootfs 等内部目录；其余层级照常。
        val seeds = if (root.absolutePath == baseDir().absolutePath) exposedChildren() else listOf(root)
        val pending = LinkedList<File>().apply { addAll(seeds) }
        val needle = query.lowercase()

        while (pending.isNotEmpty() && result.count < MAX_SEARCH_RESULTS) {
            val file = pending.removeFirst()
            // 仅在根目录内搜索，避免 symlink 指向外部导致扫到整个磁盘
            val insideRoot = runCatching {
                sandboxedFile(file.absolutePath)
                true
            }.getOrDefault(false)
            if (!insideRoot) continue
            if (file.isDirectory) {
                file.listFiles()?.let { pending.addAll(it) }
            } else if (file.name.lowercase().contains(needle)) {
                includeFile(result, null, file)
            }
        }
        result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        // RC61b 修正：先通过 sandboxedFile 校验双方均在沙箱内，再 canonical 前缀比较。
        // 防止 docId 路径混淆、或者一方是跳出沙箱的 symlink。
        return runCatching {
            val parent = sandboxedFile(parentDocumentId).canonicalPath
            val child = sandboxedFile(documentId).canonicalPath
            if (parent == child) return@runCatching true
            child.startsWith(parent + File.separator)
        }.getOrDefault(false)
    }

    private fun docIdForFile(file: File): String = file.absolutePath

    /**
     * 原始 docId → File 的兼容入口。
     * 仅在 [includeFile] docId==null 场景（传入 child 是刚刚 sandboxedFile 过的安全 File）
     * 才允许不走沙箱；**其余所有 SAF 入口已改用 [sandboxedFile]**。
     */
    private fun fileForDocId(docId: String): File {
        val f = sandboxedFile(docId)
        if (!f.exists()) throw FileNotFoundException("${f.absolutePath} not found")
        return f
    }

    private fun mimeType(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return "application/octet-stream"
    }

    private fun includeFile(result: MatrixCursor, docId: String?, file: File?) {
        val resolvedFile = file ?: docId?.let { fileForDocId(it) }
        ?: throw FileNotFoundException("docId and file both null")
        val resolvedDocId = docId ?: docIdForFile(resolvedFile)

        var flags = 0
        if (resolvedFile.isDirectory) {
            if (resolvedFile.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (resolvedFile.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (resolvedFile.parentFile?.canWrite() == true) flags = flags or Document.FLAG_SUPPORTS_DELETE

        val mime = mimeType(resolvedFile)
        if (mime.startsWith("image/")) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL

        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, resolvedDocId)
            add(Document.COLUMN_DISPLAY_NAME, resolvedFile.name)
            add(Document.COLUMN_SIZE, resolvedFile.length())
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_LAST_MODIFIED, resolvedFile.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_ICON, R.mipmap.ic_launcher)
        }
    }
}
