package com.R.codecore.core.network

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/**
 * SSE 行定点字段抽取（网络层优化设计 P1）。
 *
 * 原实现 `JsonParser.parseString(data).asJsonObject` 会对每个 SSE 行构建一整棵 Gson JSON tree
 * （大量小对象/数组/字符串分配，是流式主路径的 CPU 与 GC 大头）。本工具改用 Gson 的流式
 * [JsonReader] 逐 token 遍历，只捕获 [paths] 指定的字段、不建树，显著降低解析开销。
 *
 * 语义（与旧整树解析保持一致，设计 §5.4）：
 * - 只捕获标量叶子（字符串/数字/布尔）；JSON null 与对象/数组不捕获（调用方按「缺失」处理，
 *   等价于旧实现的 `takeIf { !it.isJsonNull }`）；
 * - 数组元素按序参与路径（如 `choices.0.delta.content`），数组下标用十进制字符串；
 * - [JsonReader] 原生处理 `\n \" \uXXXX` 等转义，返回的已是反转义后的最终值；
 * - 整行非法 / 中途截断 / 类型不符一律不抛，返回已捕获部分；未知字段直接跳过；
 * - 同一路径只保留首次命中（putIfAbsent），对齐旧实现 firstOrNull 的取首语义。
 */
object SseFieldExtractor {

    private const val SEP = "."

    /**
     * @param json 单行 SSE data 的 JSON 文本
     * @param paths 目标字段路径列表，形如 `listOf(listOf("delta", "text"))`；
     *              数组下标作为路径段（如 `listOf("choices", "0", "delta", "content")`）
     * @return 路径（点连接）→ 标量字符串；未命中的路径不出现在结果中
     */
    fun extract(json: String, paths: List<List<String>>): Map<String, String> {
        if (json.isBlank() || paths.isEmpty()) return emptyMap()
        val wanted = paths.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        val wantedSet = wanted.map { it.joinToString(SEP) }.toHashSet()
        val result = LinkedHashMap<String, String>()
        try {
            JsonReader(StringReader(json)).use { reader ->
                walk(reader, emptyList(), wantedSet, result)
            }
        } catch (_: Exception) {
            // 解析失败/截断：返回已捕获部分，调用方按旧语义跳过该行
        }
        return result
    }

    private fun walk(
        reader: JsonReader,
        path: List<String>,
        wantedSet: Set<String>,
        result: MutableMap<String, String>
    ) {
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    walk(reader, path + name, wantedSet, result)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var index = 0
                while (reader.hasNext()) {
                    walk(reader, path + index.toString(), wantedSet, result)
                    index++
                }
                reader.endArray()
            }
            else -> {
                val joined = path.joinToString(SEP)
                if (joined in wantedSet) {
                    scalar(reader)?.let { value ->
                        result.putIfAbsent(joined, value)
                    }
                } else {
                    reader.skipValue()
                }
            }
        }
    }

    private fun scalar(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        else -> {
            // JSON null 或其它：跳过，不捕获（等价旧实现跳过 isJsonNull 字段）
            reader.skipValue()
            null
        }
    }
}
