package com.R.codecore.feature.agent.domain.memory

import com.R.codecore.core.util.FileLogger
import org.yaml.snakeyaml.Yaml
import java.io.File

object MemoryParser {
    private const val TAG = "MemoryParser"
    private const val MAX_DESC_CHARS = 500

    fun parse(file: File, scope: MemoryScope): Memory? {
        val text = try {
            if (!file.isFile || !file.canRead()) return null
            file.readText()
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取 Memory 文件失败: ${file.absolutePath}", e)
            return null
        }

        val (frontmatter, body) = splitAndParseFrontmatter(text)

        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)

        // M-3：tags 支持两种形态——YAML 列表或逗号分隔字符串，逐项清洗空白。
        val tags = when (val raw = frontmatter["tags"]) {
            is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf { t -> t.isNotBlank() } }
            is String -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
        // M-4：access_count 为可选中继字段，缺失视为 0。
        val accessCount = (frontmatter["access_count"] as? Number)?.toInt() ?: 0

        return Memory(
            name = name,
            description = description,
            scope = scope,
            file = file,
            content = body.trim(),
            tags = tags,
            accessCount = accessCount
        )
    }

    fun format(
        name: String,
        description: String,
        content: String,
        tags: List<String> = emptyList(),
        accessCount: Int = 0
    ): String {
        val safeName = yamlScalar(name)
        val safeDesc = yamlScalar(description)
        val sb = StringBuilder("---\nname: $safeName\ndescription: $safeDesc\n")
        // M-3：tags 写成 YAML 流式列表，便于 parse 回读为 List。
        if (tags.isNotEmpty()) {
            sb.append("tags: [").append(tags.joinToString(", ") { yamlScalar(it) }).append("]\n")
        }
        // M-4：accessCount 大于 0 时才落盘；新记忆从 0 起，旧文件缺省也为 0。
        if (accessCount > 0) {
            sb.append("access_count: $accessCount\n")
        }
        sb.append("---\n").append(content)
        return sb.toString()
    }

    /** 把任意字符串转成安全的 YAML 标量，避免冒号/引号/换行破坏 frontmatter。 */
    private fun yamlScalar(value: String): String {
        val needsQuote = value.contains(':') || value.contains('#') ||
            value.contains('"') || value.contains('\'') ||
            value.startsWith('-') || value.startsWith(' ') || value.endsWith(' ') ||
            value.contains('\n') || value.isBlank()
        return if (needsQuote) {
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            value
        }
    }

    private fun splitAndParseFrontmatter(text: String): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")

        val map = try {
            val yaml = Yaml()
            val loaded = yaml.load<Map<String, Any>>(block)
            loaded ?: emptyMap()
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析 YAML 失败", e)
            emptyMap()
        }

        return map to rest
    }
}
