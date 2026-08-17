package com.R.codecore.feature.agent.domain.tool.explorer

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.CommandEngine
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.agent.domain.tool.ToolResult
import com.R.codecore.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * rg 风格的项目搜索工具。参数原样传给容器内的 ripgrep，容器未就绪则报错。
 */
class SearchCodeTool @Inject constructor(
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository
) : AgentTool() {

    private companion object {
        const val TAG = "SearchTool"
        const val SEARCH_TIMEOUT_MS = 30_000L
        /** 默认最多返回的匹配条数，超出则提示缩小范围。 */
        const val DEFAULT_MAX_MATCHES = 200
        /** 底层 BoundedOutput 首尾限幅时写入输出中的省略标记（见 BoundedOutput.build）。 */
        const val OUTPUT_TRUNCATION_MARKER = "[输出过长，已省略中间"
    }

    override val name = "search"
    override val description = "按 rg 风格搜索文本。例：args=\"-n \\\"fun main\\\" ~/workspace/app\"。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "args" to ToolParameter(
            name = "args",
            type = ParameterType.STRING,
            description = "rg 风格参数。不填无效。常用：-i -F -e -g --hidden --。不要混入 shell 管道（|）、grep/head 等外部命令或重定向——它们不是 rg 参数。",
            required = true
        ),
        "max_matches" to ToolParameter(
            name = "max_matches",
            type = ParameterType.INTEGER,
            description = "最多返回的匹配条数，超出则提示缩小范围",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val rawArgs = args["args"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (rawArgs.isEmpty()) return ToolResult.Error("缺少搜索参数 args", "MISSING_ARGS")

            val tokens = parseShellWords(rawArgs)
                ?: return ToolResult.Error("args 中存在未闭合的引号", "INVALID_ARGS")
            if (tokens.isEmpty()) return ToolResult.Error("缺少搜索参数 args", "MISSING_ARGS")

            val maxMatches = args["max_matches"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1)
                ?: DEFAULT_MAX_MATCHES

            val startedAt = System.currentTimeMillis()

            // 先独立统计真实匹配总数（不受 BoundedOutput 限幅影响）；统计失败返回 null。
            val realMatches = countMatches(tokens)

            val result = commandEngine.runCommandSyncIfReady(
                command = buildRgCommand(tokens),
                projectPath = workspaceRepository.currentPath(),
                timeoutMs = SEARCH_TIMEOUT_MS
            ) ?: return ToolResult.Error("容器未就绪，无法执行 rg", "CONTAINER_NOT_READY")

            if (isRgMissing(result.output)) return ToolResult.Error("容器内未安装 rg", "RG_MISSING")
            if (result.exitCode != null && result.exitCode > 1) {
                return ToolResult.Error(result.output.ifBlank { "rg 执行失败" }, "RG_ERROR")
            }

            // matches：优先用 --count-matches 统计的真实匹配数，统计失败时退化为输出行数估算。
            val estimatedLines = result.output.lineSequence().filter { it.isNotBlank() }.count()
            val matchCount = realMatches ?: estimatedLines
            // 输出被底层 BoundedOutput 首尾限幅时，末尾会写入省略标记。
            val outputTruncated = result.output.contains(OUTPUT_TRUNCATION_MARKER)
            val truncated = (realMatches != null && realMatches > maxMatches) || outputTruncated

            var content = result.output
            if (realMatches != null && realMatches > maxMatches) {
                content = content.trimEnd('\n') +
                    "\n（匹配过多，共 $realMatches 条，已截断。请增加更精确的关键词或 -g 限定范围）"
            }

            ToolResult.Success(JsonObject(mapOf(
                "content" to JsonPrimitive(content),
                "matches" to JsonPrimitive(matchCount),
                "truncated" to JsonPrimitive(truncated),
                "max_matches" to JsonPrimitive(maxMatches),
                "elapsed_ms" to JsonPrimitive(System.currentTimeMillis() - startedAt),
                "backend" to JsonPrimitive("rg")
            )))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "search 异常", e)
            ToolResult.Error(e.message ?: "搜索失败", "SEARCH_ERROR")
        }
    }

    private fun buildRgCommand(tokens: List<String>): String {
        val args = mutableListOf(
            "rg",
            "--line-number",
            "--no-heading",
            "--with-filename",
            "--color",
            "never"
        )
        args.addAll(tokens.map { shellQuote(expandTilde(it)) })
        return args.joinToString(" ")
    }

    /** 与 [buildRgCommand] 同构的统计命令：`rg --count-matches` 每文件输出一行 `path:count`。 */
    private fun buildCountCommand(tokens: List<String>): String {
        val args = mutableListOf(
            "rg",
            "--count-matches",
            "--no-heading",
            "--with-filename",
            "--color",
            "never"
        )
        args.addAll(tokens.map { shellQuote(expandTilde(it)) })
        return args.joinToString(" ")
    }

    /**
     * 用 `rg --count-matches` 独立统计真实匹配总数（对每行末尾的数字求和，不依赖限幅后的输出行数）。
     * 失败（容器未就绪 / rg 缺失 / 执行出错）返回 null，此时 [execute] 中 matches 退化为行数估算。
     */
    private suspend fun countMatches(tokens: List<String>): Int? {
        val result = commandEngine.runCommandSyncIfReady(
            command = buildCountCommand(tokens),
            projectPath = workspaceRepository.currentPath(),
            timeoutMs = SEARCH_TIMEOUT_MS
        ) ?: return null
        if (isRgMissing(result.output)) return null
        if (result.exitCode != null && result.exitCode > 1) return null
        var total = 0
        for (line in result.output.lineSequence()) {
            if (line.isBlank()) continue
            total += line.substringAfterLast(':').trim().toIntOrNull() ?: 0
        }
        return total
    }

    /** 把 `~/` 开头的路径参数展开为 `/root/`，避免被单引号包裹后 shell 不展开 `~`。 */
    private fun expandTilde(arg: String): String =
        if (arg.startsWith("~/")) "/root/" + arg.removePrefix("~/") else arg

    private fun isRgMissing(output: String): Boolean {
        return output.contains("command not found", ignoreCase = true) ||
            output.contains("rg: not found", ignoreCase = true)
    }

    private fun parseShellWords(input: String): List<String>? {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var tokenStarted = false
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                quote == '\'' -> {
                    if (c == '\'') quote = null else current.append(c)
                    tokenStarted = true
                }
                quote == '"' -> {
                    when (c) {
                        '"' -> quote = null
                        '\\' -> {
                            if (i + 1 < input.length) {
                                i++
                                current.append(input[i])
                            } else {
                                current.append(c)
                            }
                        }
                        else -> current.append(c)
                    }
                    tokenStarted = true
                }
                c.isWhitespace() -> {
                    if (tokenStarted) {
                        result.add(current.toString())
                        current.clear()
                        tokenStarted = false
                    }
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    tokenStarted = true
                }
                c == '\\' -> {
                    if (i + 1 < input.length) {
                        i++
                        current.append(input[i])
                    } else {
                        current.append(c)
                    }
                    tokenStarted = true
                }
                else -> {
                    current.append(c)
                    tokenStarted = true
                }
            }
            i++
        }
        if (quote != null) return null
        if (tokenStarted) result.add(current.toString())
        return result
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
