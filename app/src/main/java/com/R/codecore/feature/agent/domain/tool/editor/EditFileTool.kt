package com.R.codecore.feature.agent.domain.tool.editor

import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.PendingToolPermission
import com.R.codecore.feature.agent.domain.tool.ToolCall
import com.R.codecore.feature.agent.domain.tool.ToolEvent
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.agent.domain.tool.ToolResult
import com.R.codecore.core.util.FileLogger
import com.R.codecore.core.util.LineDiff
import com.R.codecore.datalayer.repository.AgentRepository as V2AgentRepository
import com.R.codecore.feature.agent.data.local.entity.FileEditHunkEntity
import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.workspace.domain.FileAccessProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

private const val TAG = "EditFileTool"

/**
 * F-2：LCS DP 表单元格上限。LineDiff.diff 构建 (n+1)×(m+1) 的 Int 表，
 * 当 old/new 行数乘积超过此值时（约 16MB：4M 格 × 4B），跳过 O(n·m) 计算，防超大编辑在移动端 OOM。
 */
private const val MAX_LCS_CELLS = 4_000_000L

/**
 * 基于「精确字符串匹配」的文件编辑工具，取代旧的按行号 insert/replace/delete 三件套。
 *
 * 选择字符串匹配而非行号的原因：连续编辑时，第一次修改会让后续所有行号发生漂移，
 * 行号方案因此天然脆弱。字符串匹配只依赖内容本身，且一个工具即可覆盖三种语义：
 *   - 替换：old_string -> new_string
 *   - 删除：new_string 传空串
 *   - 插入：把某段替换为「它自身 + 新内容」
 *
 * 一次调用可对同一个文件进行多处编辑：传入 edits 数组，按数组顺序依次应用，
 * 每个编辑都在前一个编辑的结果之上匹配。整批编辑是「全有或全无」的——只要有任何
 * 一个编辑匹配失败（找不到或匹配多处而未开 replace_all），整次调用都不写盘并报错，
 * 文件不会处于改了一半的状态。
 *
 * 为保证安全，默认要求每个 old_string 在当前内容中唯一；若有多处匹配，需提供更长的
 * 上下文，或对该编辑显式设置 replace_all=true 才会全部替换。
 */
class EditFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val v2Agent: V2AgentRepository,
) : AgentTool() {
    override val name = "editFile"
    override val description =
        "通过精确的字符串匹配替换修改已存在的文件内容。作为局部修改文件的首选工具。支持通过 edits 数组一次性应用多处修改，整批编辑是原子的——任一处匹配失败将整批回滚，文件不会处于改了一半的状态。整文件重写请用 writeFile。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.WRITE_WORKSPACE)

    /** L3 结构化结果协议：产出 file.edited 类型，消费 file.read（编辑前需读取原内容）。 */
    override val provides = setOf("file.edited")
    override val consumes = setOf("file.read")

    /** edits 数组单个元素的结构，供 function-calling 的 items schema。 */
    private val editItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "old_string" to mapOf(
                "type" to "string",
                "description" to "要被替换的原文，需与文件当前内容精确匹配（含缩进和换行）。带足够上下文以保证唯一。"
            ),
            "new_string" to mapOf(
                "type" to "string",
                "description" to "替换后的新内容。传空字符串表示删除匹配到的内容。"
            ),
            "replace_all" to mapOf(
                "type" to "boolean",
                "description" to "是否替换该 old_string 的全部匹配项。默认 false（要求唯一匹配）。"
            )
        ),
        "required" to listOf("old_string", "new_string")
    )

    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "文件路径：~/workspace/... 为项目文件；其它绝对路径（如 /etc/...、/root/...）为容器系统文件。", required = true),
        "edits" to ToolParameter(
            "edits",
            ParameterType.ARRAY,
            "要应用的编辑列表，按顺序依次生效，每个编辑在前一个的结果上匹配。" +
                "单处修改也用只含一个元素的数组。每个元素：{old_string, new_string, replace_all?}。",
            required = true,
            itemsSchema = editItemSchema
        )
    )

    /** 解析后的单个编辑。 */
    private data class Edit(val oldString: String, val newString: String, val replaceAll: Boolean)

    /** 单个编辑应用后的差异，用于 UI 渲染与喂回模型。 */
    private data class Hunk(val startLine: Int, val added: Int, val removed: Int, val diff: String)

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val path = args["path"]?.jsonPrimitive?.contentOrNull ?: "未知文件"
        val editCount = (args["edits"] as? JsonArray)?.size ?: 1
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认修改文件",
            summary = "AI 请求修改 $path",
            details = "编辑数量：$editCount\n工具会先完整匹配，任一编辑失败则不会写入。",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = executeInternal(args, null)

    /** F-3：优先走 executeWithContext 以获得会话 ID，用于 hunk 落库；无上下文时降级为不落库。 */
    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        return executeInternal(args, context.sessionId)
    }

    /** L7 事件自声明：编辑成功后广播 file.edited（字段级 hash/diff 由本工具在执行结果中附带）。 */
    override fun buildPostExecutionEvent(
        toolCall: ToolCall,
        result: ToolResult,
        context: AgentContext
    ): ToolEvent? {
        val path = (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull ?: return null
        return ToolEvent.FileEdited(path = path, oldHash = null, newHash = "", diffSummary = "", sessionId = context.sessionId)
    }

    private suspend fun executeInternal(args: Map<String, JsonElement>, sessionId: String?): ToolResult {
        return try {
            val path = args["path"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult.Error("路径参数缺失", "MISSING_PATH")

            val edits = parseEdits(args)
                ?: return ToolResult.Error("edits 参数缺失或为空：请在 edits 数组里给出至少一个 {old_string,new_string} 编辑", "MISSING_EDITS")

            // 逐个校验编辑本身的合法性（空 old_string / 无变化），避免无意义改动。
            edits.forEachIndexed { i, e ->
                if (e.oldString.isEmpty()) {
                    return ToolResult.Error("第 ${i + 1} 个编辑的 old_string 不能为空；创建文件请用 writeFile。", "EMPTY_OLD_STRING")
                }
                if (e.oldString == e.newString) {
                    return ToolResult.Error("第 ${i + 1} 个编辑的 old_string 与 new_string 相同，无需修改", "NO_OP")
                }
            }

            FileLogger.d(TAG, "edit_file path=$path (edits=${edits.size})")
            if (!fileAccess.exists(path)) {
                FileLogger.w(TAG, "edit_file 文件不存在: $path")
                return ToolResult.Error("文件不存在: $path", "FILE_NOT_FOUND")
            }

            // 先在内存里顺序应用所有编辑；任一失败立刻返回、绝不写盘（全有或全无）。
            var content = fileAccess.readFile(path)
            // F-3：留存编辑前的原文，供 hunk 落库回溯。
            val originalContent = content
            val hunks = ArrayList<Hunk>(edits.size)
            var totalReplacements = 0

            edits.forEachIndexed { i, e ->
                val occurrences = content.split(e.oldString).size - 1
                if (occurrences == 0) {
                    FileLogger.w(TAG, "edit_file 第 ${i + 1} 个编辑未匹配: $path")
                    // F-1：未匹配时给出 Top-N 相似候选，帮助 AI 修正 old_string
                    return ToolResult.Error(
                        buildNoMatchMessage(i + 1, content, e.oldString),
                        "NO_MATCH"
                    )
                }
                if (occurrences > 1 && !e.replaceAll) {
                    FileLogger.w(TAG, "edit_file 第 ${i + 1} 个编辑匹配 $occurrences 处且非 replace_all: $path")
                    return ToolResult.Error(
                        "第 ${i + 1} 个编辑的 old_string 在文件中匹配到 $occurrences 处，请提供更长的唯一上下文，或对该编辑设置 replace_all=true",
                        "MULTIPLE_MATCHES"
                    )
                }

                // 变更在「应用本编辑前」内容中的起始行号（1 基）。因为内容已包含此前所有编辑的结果，
                // 这个行号已经反映了前序编辑造成的行漂移，对自上而下的常规编辑顺序是准确的。
                val matchIndex = content.indexOf(e.oldString)
                val startLine = if (matchIndex >= 0) content.substring(0, matchIndex).count { it == '\n' } + 1 else 1

                val diff = safeToUnified(e.oldString, e.newString)
                val added = diff.lines().count { it.startsWith("+") }
                val removed = diff.lines().count { it.startsWith("-") }
                hunks.add(Hunk(startLine = startLine, added = added, removed = removed, diff = diff))

                content = if (e.replaceAll) content.replace(e.oldString, e.newString)
                else content.replaceFirst(e.oldString, e.newString)
                totalReplacements += if (e.replaceAll) occurrences else 1
            }

            fileAccess.writeFile(path, content, overwrite = true)

            val addedTotal = hunks.sumOf { it.added }
            val removedTotal = hunks.sumOf { it.removed }
            val hunksJson = JsonArray(hunks.map { h ->
                JsonObject(mapOf(
                    "start_line" to JsonPrimitive(h.startLine),
                    "added" to JsonPrimitive(h.added),
                    "removed" to JsonPrimitive(h.removed),
                    "diff" to JsonPrimitive(h.diff)
                ))
            })

            // F-3：edit 落库快照（operation=edit，含差异 hunk），支撑「撤销编辑」。
            if (sessionId != null) {
                try {
                    val hunkEntity = FileEditHunkEntity(
                        id = "hunk_${UUID.randomUUID().toString().replace("-", "")}",
                        sessionId = sessionId,
                        filePath = path,
                        operation = "edit",
                        hunk = hunksJson.toString(),
                        oldContent = originalContent.take(HUNK_SNAPSHOT_MAX_CHARS),
                        newContent = content.take(HUNK_SNAPSHOT_MAX_CHARS),
                        createdAtMs = System.currentTimeMillis()
                    )
                    v2Agent.insertFileEditHunk(
                            id = hunkEntity.id,
                            sessionId = hunkEntity.sessionId,
                            filePath = hunkEntity.filePath,
                            operation = hunkEntity.operation,
                            hunk = hunkEntity.hunk,
                            oldContent = hunkEntity.oldContent,
                            newContent = hunkEntity.newContent,
                            createdAtMs = hunkEntity.createdAtMs
                        )
                    } catch (e: Exception) {
                    FileLogger.w(TAG, "记录文件 hunk 失败: $path", e)
                }
            }

            FileLogger.v(TAG, "edit_file 成功 path=$path edits=${edits.size} replacements=$totalReplacements")
            ToolResult.Success(
                JsonObject(mapOf(
                    "status" to JsonPrimitive("edited"),
                    "path" to JsonPrimitive(fileAccess.toDisplayPath(path)),
                    "edits_count" to JsonPrimitive(edits.size),
                    "replacements" to JsonPrimitive(totalReplacements),
                    "total_lines" to JsonPrimitive(content.lines().size),
                    "added_lines" to JsonPrimitive(addedTotal),
                    "removed_lines" to JsonPrimitive(removedTotal),
                    "hunks" to hunksJson
                ))
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "edit_file 异常", e)
            ToolResult.Error(e.message ?: "编辑文件失败", "EDIT_ERROR")
        }
    }

    /**
     * 解析编辑列表：读取 edits 数组。
     */
    private fun parseEdits(args: Map<String, JsonElement>): List<Edit>? {
        val arr = args["edits"] as? JsonArray ?: return null
        if (arr.isEmpty()) return null
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val old = obj["old_string"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val new = obj["new_string"]?.jsonPrimitive?.contentOrNull ?: ""
            val all = obj["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
            Edit(old, new, all)
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * F-2：生成 old→new 的统一差异。当 old/new 行数乘积超过阈值时跳过 O(n·m) 的 LCS DP 表，
     * 退化为「整段删除+新增」的粗粒度差异（不构建 Int 表），防止超大编辑在移动端 OOM。
     */
    private fun safeToUnified(oldText: String, newText: String): String {
        val n = oldText.split("\n").size.toLong()
        val m = newText.split("\n").size.toLong()
        if (n * m <= MAX_LCS_CELLS) {
            return LineDiff.toUnified(oldText, newText)
        }
        FileLogger.w(TAG, "edit_file LCS 过大 ($n x $m)，跳过 DP 退化为整体替换差异")
        val sb = StringBuilder()
        oldText.split("\n").forEach { sb.append('-').append(it).append('\n') }
        newText.split("\n").forEach { sb.append('+').append(it).append('\n') }
        return sb.toString()
    }

    /**
     * F-1：构建「未匹配」错误信息，附 Top-N 相似候选行，帮助 AI 对照修正 old_string。
     * 候选基于「行的空白归一化后是否出现」+「字符级相似度」双维度打分，
     * 兼顾「仅缩进/空格差异」与「拼写/大小写笔误」两类常见场景。
     */
    private fun buildNoMatchMessage(editIndex: Int, content: String, oldString: String): String {
        val sb = StringBuilder()
        sb.append("第 $editIndex 个编辑未在文件中找到 old_string，请确认内容（含缩进/换行）与当前文件完全一致。")
        val candidates = findSimilarCandidates(content, oldString, topN = SIMILAR_CANDIDATES)
        if (candidates.isNotEmpty()) {
            sb.append("文件中的相似候选：\n")
            candidates.forEachIndexed { idx, c ->
                sb.append("  ").append(idx + 1).append(". 行 ").append(c.lineNo)
                    .append("（相似度 ").append((c.score * 100).toInt()).append("%）：")
                val preview = c.line.trim().take(80)
                sb.append(if (preview.isNotEmpty()) "「$preview」" else "（空行）").append('\n')
            }
            sb.append("提示：若目标行较长，请用整行原文作 old_string；若为局部短语，请带上唯一上下文。")
        }
        return sb.toString()
    }

    /**
     * F-1：在 [content] 中找出与 [needle] 最相似的行候选，按相关度降序返回前 [topN] 个。
     *
     * 打分 = 空白归一化完全一致(1.0) / 包含关系(0.9) / 否则按归一化 Levenshtein 相似度。
     * 只对足够相近的行（相似度 ≥ [SIMILAR_THRESHOLD]）返回，避免噪声。
     */
    private fun findSimilarCandidates(content: String, needle: String, topN: Int): List<SimilarCandidate> {
        val needleNorm = needle.trim()
        if (needleNorm.isEmpty() || needleNorm.length > MAX_CANDIDATE_NEEDLE_CHARS) return emptyList()
        // 空白归一化（连续空白压成单个空格）：能匹配仅「空格/换行差异」的整行场景。
        val needleWs = normalizeWhitespace(needleNorm)

        val candidates = mutableListOf<SimilarCandidate>()
        var lineNo = 0
        for (rawLine in content.split('\n')) {
            lineNo++
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // 跳过过长行：超长行相似度计算性价比低且易误报。
            if (line.length > MAX_CANDIDATE_LINE_CHARS) continue
            val lineWs = normalizeWhitespace(line)

            val score = when {
                lineWs == needleWs -> 1.0
                lineWs.contains(needleWs) || needleWs.contains(lineWs) -> 0.9
                else -> similarity(lineWs, needleWs)
            }
            if (score >= SIMILAR_THRESHOLD) {
                candidates.add(SimilarCandidate(lineNo = lineNo, line = rawLine, score = score))
            }
        }
        candidates.sortWith(
            compareByDescending<SimilarCandidate> { it.score }
                .thenBy { it.line.length }
                .thenBy { it.lineNo }
        )
        return candidates.take(topN)
    }

    /** 相似度阈值：低于此值的行不列为候选。 */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0
        val dist = boundedLevenshtein(a, b, maxDist = (maxLen * (1 - SIMILAR_THRESHOLD)).toInt().coerceAtLeast(1))
            ?: return 0.0 // 超出阈值直接判为不相似，避免大行 O(n·m)
        return 1.0 - dist.toDouble() / maxLen
    }

    /**
     * 归一化 Levenshtein：仅在距离不超过 [maxDist] 时返回精确距离（提前终止），
     * 超过返回 null。边界：任一为空串时直接按长度判定，不做 DP。
     */
    private fun boundedLevenshtein(a: String, b: String, maxDist: Int): Int? {
        if (a.isEmpty()) return if (b.length <= maxDist) b.length else null
        if (b.isEmpty()) return if (a.length <= maxDist) a.length else null
        val n = a.length
        val m = b.length
        if (kotlin.math.abs(n - m) > maxDist) return null

        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost)
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > maxDist) return null // 当前行最小值已超阈值，可提前终止
            val tmp = prev; prev = curr; curr = tmp
        }
        return if (prev[m] <= maxDist) prev[m] else null
    }

    /** 把连续空白（空格/制表/换行）压成单个空格，用于「忽略空白差异」的整行比对。 */
    private fun normalizeWhitespace(s: String): String {
        val sb = StringBuilder(s.length)
        var pendingSpace = false
        for (c in s) {
            if (c == ' ' || c == '\t') {
                pendingSpace = true
            } else {
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                sb.append(c)
            }
        }
        return sb.toString().trim()
    }

    /** F-1：相似候选（行号 + 原行 + 相关度）。 */
    private data class SimilarCandidate(val lineNo: Int, val line: String, val score: Double)

    private companion object {
        /** F-1：返回的相似候选数量上限。 */
        const val SIMILAR_CANDIDATES = 3
        /** F-1：相似度阈值，低于此值不列为候选。 */
        const val SIMILAR_THRESHOLD = 0.6
        /** F-1：参与比对的 needle 长度上限，超出不做候选（代价高且易误报）。 */
        const val MAX_CANDIDATE_NEEDLE_CHARS = 300
        /** F-1：参与比对的单行长度上限。 */
        const val MAX_CANDIDATE_LINE_CHARS = 300
        /** F-3：hunk 落库时单条快照（old/new content）保存的最大字符数，防止超大文件撑爆数据库。 */
        const val HUNK_SNAPSHOT_MAX_CHARS = 50_000
    }
}
