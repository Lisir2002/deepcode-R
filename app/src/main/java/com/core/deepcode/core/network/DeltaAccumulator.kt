package com.core.deepcode.core.network

/**
 * 流式增量语义归一化累积器（预防「字段语义假设」类 bug）。
 *
 * 背景：同一个流式字段在不同 provider / 兼容网关下可能是三种语义——
 *  - [Semantic.INCREMENTAL] 增量：每个 chunk 是新增片段（OpenAI `delta.content` / Anthropic `delta.text`）；
 *  - [Semantic.FULL_SNAPSHOT] 全量快照：每个 chunk 是完整内容（Gemini `thought` / `functionCall`）；
 *  - [Semantic.AUTO_DETECT] 自动判别：兼容网关常全量重发完整内容（DeepSeek `reasoning_content`）。
 * 若把「全量重发」当「增量」累积，会出现内容成倍放大（如思考过程重复几百行 base64）。
 *
 * 本类在累积层做语义归一化 + 护栏（长度上限 / 重复去重 / 裸 base64 折叠），
 * 使下游（workflow 累积、UI 展示、落库）不再依赖对上游语义的假设。
 */
class DeltaAccumulator(
    private val semantic: Semantic = Semantic.AUTO_DETECT,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val foldBase64: Boolean = true,
    private val base64MinLength: Int = DEFAULT_BASE64_MIN_LENGTH
) {

    enum class Semantic {
        /** 每个 chunk 是新增片段，直接追加。 */
        INCREMENTAL,
        /** 每个 chunk 是完整内容，整体替换。 */
        FULL_SNAPSHOT,
        /** 自动判别：与上次完整快照比对，识别「全量重发/回退重复」并去重，其余按增量累积。 */
        AUTO_DETECT
    }

    /** 归一化结果：Append 为真增量（直接累积），Duplicate 为全量重发/重复（已去重，仅统计）。 */
    sealed class NormalizedDelta {
        data class Append(val text: String) : NormalizedDelta()
        data class Duplicate(val count: Int) : NormalizedDelta()
    }

    private val buffer = StringBuilder()
    /** AUTO_DETECT 判别的基准：上次真实累积后的全文（「上次完整快照」）。 */
    private var lastFull = ""
    private var duplicateCountInternal = 0
    private var truncatedInternal = false
    /** 归一化前累计接收的原始字符数（观测埋点：与 [text] 长度对比可得「放大/折叠比率」）。 */
    private var rawCharsInternal = 0L

    /** 归一化后的完整累积内容（已截断 / 折叠）。 */
    val text: String get() = buffer.toString()

    /** 是否因超过 [maxChars] 被截断。 */
    val isTruncated: Boolean get() = truncatedInternal

    /** 累计识别到的「全量重发/重复」次数（AUTO_DETECT 下才有意义，供观测埋点）。 */
    val duplicateCount: Int get() = duplicateCountInternal

    /** 归一化前累计接收的原始字符数（供观测埋点计算放大/折叠比率）。 */
    val rawCharsReceived: Long get() = rawCharsInternal

    /**
     * 接收一个流式 chunk，返回归一化结果。
     * 空 chunk 不产生累积（返回空 [NormalizedDelta.Append]）。
     */
    fun accept(chunk: String): NormalizedDelta {
        if (chunk.isEmpty()) return NormalizedDelta.Append("")
        rawCharsInternal += chunk.length
        // AUTO_DETECT：与「上次完整快照」比对，全量重发（相同）或回退到已有前缀 → 去重。
        if (semantic == Semantic.AUTO_DETECT && lastFull.isNotEmpty()) {
            val duplicated = chunk == lastFull ||
                (chunk.length <= lastFull.length && lastFull.startsWith(chunk))
            if (duplicated) {
                duplicateCountInternal++
                return NormalizedDelta.Duplicate(duplicateCountInternal)
            }
        }
        val toAppend = when (semantic) {
            Semantic.INCREMENTAL -> chunk
            Semantic.FULL_SNAPSHOT -> {
                buffer.setLength(0)
                chunk
            }
            Semantic.AUTO_DETECT -> {
                // 新 chunk 扩展了上次完整快照 → 真增量，只取新增尾巴；否则为新段，整体追加。
                if (lastFull.isNotEmpty() && chunk.length > lastFull.length && chunk.startsWith(lastFull)) {
                    chunk.removePrefix(lastFull)
                } else {
                    chunk
                }
            }
        }
        buffer.append(if (foldBase64) foldBase64Segments(toAppend) else toAppend)
        lastFull = buffer.toString()
        if (buffer.length > maxChars) {
            buffer.setLength(maxChars)
            lastFull = buffer.toString()
            truncatedInternal = true
        }
        return NormalizedDelta.Append(toAppend)
    }

    /** 清空累积与统计（网络重试时调用，语义同 `StringBuilder.setLength(0)`）。 */
    fun reset() {
        buffer.setLength(0)
        lastFull = ""
        duplicateCountInternal = 0
        truncatedInternal = false
        rawCharsInternal = 0
    }

    private fun foldBase64Segments(input: String): String {
        if (base64MinLength <= 0 || input.length < base64MinLength) return input
        return base64SegmentRegex.replace(input, BASE64_OMITTED)
    }

    private val base64SegmentRegex by lazy {
        Regex("[A-Za-z0-9+/]{$base64MinLength,}={0,2}")
    }

    private companion object {
        /** 与落库层 [com.core.deepcode.feature.agent.domain.session.MessagePersistenceUseCase.MAX_CONTENT_CHARS] 对齐。 */
        const val DEFAULT_MAX_CHARS = 200_000
        const val DEFAULT_BASE64_MIN_LENGTH = 256
        /** 裸 base64 占位符（文案与 MessagePersistenceUseCase.IMAGE_OMITTED_MARKER 保持一致，用户理解统一）。 */
        const val BASE64_OMITTED = "[图片已省略：内嵌图片数据过大]"
    }
}
