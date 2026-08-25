package com.R.codecore.feature.agent.domain.provider

import com.R.codecore.core.network.DeltaAccumulator
import com.R.codecore.core.network.DeltaAccumulator.Semantic
import com.R.codecore.core.network.SseFieldExtractor
import com.R.codecore.core.util.AILogger
import com.R.codecore.feature.agent.data.remote.openai.OpenAIApi
import com.R.codecore.feature.settings.domain.model.ProviderType
import com.R.codecore.feature.settings.domain.model.defaultProviderApiPath
import java.io.IOException
import com.R.codecore.feature.agent.data.remote.openai.ChatCompletionRequest
import com.R.codecore.feature.agent.data.remote.openai.OpenAIChatMessage
import com.R.codecore.feature.agent.domain.model.AgentImage
import com.R.codecore.feature.agent.domain.model.AgentMessage
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ToolCall
import com.google.gson.JsonParser
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.R.codecore.feature.agent.data.remote.openai.OpenAIToolCall
import com.R.codecore.feature.agent.data.remote.openai.OpenAIToolDefinition
import com.R.codecore.feature.agent.data.remote.openai.OpenAIFunctionDefinition
import com.R.codecore.feature.agent.data.remote.openai.StreamOptions

/** P1 定点字段抽取：OpenAI Chat Completions 流式所需标量字段（点连接 = 嵌套路径，数组下标为路径段）。 */
private const val OPENAI_TOOL_CALL_SUBSCRIPT_COUNT = 2
private val OPENAI_CHAT_STREAM_PATHS: List<List<String>> = buildList {
    add(listOf("error", "code"))
    add(listOf("error", "message"))
    add(listOf("usage", "prompt_tokens"))
    add(listOf("usage", "completion_tokens"))
    add(listOf("choices", "0", "finish_reason"))
    add(listOf("choices", "0", "delta", "content"))
    add(listOf("choices", "0", "delta", "reasoning_content"))
    // 流式 chunk 中 delta.tool_calls 每 chunk 一个元素（index 标识累加目标），覆盖前 N 个元素。
    repeat(OPENAI_TOOL_CALL_SUBSCRIPT_COUNT) { k ->
        add(listOf("choices", "0", "delta", "tool_calls", "$k", "index"))
        add(listOf("choices", "0", "delta", "tool_calls", "$k", "id"))
        add(listOf("choices", "0", "delta", "tool_calls", "$k", "function", "name"))
        add(listOf("choices", "0", "delta", "tool_calls", "$k", "function", "arguments"))
    }
}

/** P1 定点字段抽取：OpenAI Responses API 流式热路径（output_text.delta）所需标量字段。 */
private val OPENAI_RESPONSES_STREAM_PATHS: List<List<String>> = listOf(
    listOf("error", "code"),
    listOf("error", "message"),
    listOf("type"),
    listOf("delta")
)

class OpenAIAdapter @Inject constructor(
    private val api: OpenAIApi
) : AIProvider {

    override var apiKey = ""
    override var baseUrl = "https://api.openai.com/"
    override var useFullUrl = false
    override var useResponseApi = false
    override var model = "gpt-4-turbo"
    override var logSessionId: String? = null

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        val openAIMessages = buildList {
            if (systemPrompt.isNotBlank()) {
                val role = if (model.startsWith("o1") || model.startsWith("o3")) "developer" else "system"
                add(OpenAIChatMessage(role = role, content = systemPrompt))
            }
            addAll(convertToOpenAIMessages(messages, useResponseApi))
        }

        val toolDefs = tools.takeIf { it.isNotEmpty() }?.map { tool ->
            OpenAIToolDefinition(
                function = OpenAIFunctionDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.toJsonSchema()
                )
            )
        }

        val url = if (useFullUrl) baseUrl else joinUrl(baseUrl, defaultProviderApiPath(ProviderType.OPENAI))
        if (useResponseApi) {
            val request = mutableMapOf<String, Any?>(
                "model" to model,
                "input" to convertToResponseApiInput(openAIMessages),
                "tools" to toolDefs
            )
            reasoningEffort?.let { request["reasoning"] = mapOf("effort" to it) }
            AILogger.logRequest(logSessionId, "OpenAI", model, "POST", url, request)

            val response = try {
                retryStaircase {
                    api.createResponses(url = url, authorization = "Bearer $apiKey", request = request)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val enriched = e.enrichWithHttpErrorBody()
                AILogger.logError(logSessionId, "OpenAI", enriched)
                throw enriched
            }
            AILogger.logResponse(logSessionId, "OpenAI", response)

            val outputs = response.getAsJsonArray("output")
            var content = ""
            val toolCalls = mutableListOf<ToolCall>()
            var finishReason: String? = null

            outputs?.forEach { out ->
                val msg = out.asJsonObject
                if (msg.get("role")?.asString == "assistant") {
                    msg.getAsJsonArray("content")?.forEach { partEl ->
                        val part = partEl.asJsonObject
                        when (part.get("type")?.asString) {
                            "output_text" -> content += part.get("text")?.asString ?: ""
                            "tool_call" -> {
                                val id = part.get("id")?.asString ?: ""
                                val name = part.get("name")?.asString ?: ""
                                val args = part.get("arguments")?.asString ?: ""
                                toolCalls.add(ToolCall(id, name, parseArgs(args)))
                            }
                        }
                    }
                }
            }
            // status of output items is completed
            finishReason = "stop" // simplify for Responses API
            val usage = response.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
            val inputTokens = usage?.get("input_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val outputTokens = usage?.get("output_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            return AIResponse(content = content, toolCalls = toolCalls, stopReason = finishReason, inputTokens = inputTokens, outputTokens = outputTokens)
        }

        val request = ChatCompletionRequest(
            model = model,
            messages = openAIMessages,
            reasoning_effort = reasoningEffort,
            tools = toolDefs,
            tool_choice = if (toolDefs != null) "auto" else null,
            stream = false
        )
        AILogger.logRequest(logSessionId, "OpenAI", model, "POST", url, request)

        val response = try {
            retryStaircase {
                api.createChatCompletion(url = url, authorization = "Bearer $apiKey", request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "OpenAI", enriched)
            throw enriched
        }
        AILogger.logResponse(logSessionId, "OpenAI", response)

        val message = response.choices.firstOrNull()?.message
        val finishReason = response.choices.firstOrNull()?.finish_reason
        val content = message?.content.asTextContent()
        val toolCalls = message?.tool_calls?.map { convertToToolCall(it) } ?: emptyList()
        val reasoning = message?.reasoning_content?.takeIf { it.isNotEmpty() }
        val usage = response.usage

        return AIResponse(content = content, toolCalls = toolCalls, stopReason = finishReason, reasoning = reasoning, inputTokens = usage?.prompt_tokens ?: 0, outputTokens = usage?.completion_tokens ?: 0)
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): Flow<AIStreamChunk> = flow {
        val openAIMessages = buildList {
            if (systemPrompt.isNotBlank()) {
                val role = if (model.startsWith("o1") || model.startsWith("o3")) "developer" else "system"
                add(OpenAIChatMessage(role = role, content = systemPrompt))
            }
            addAll(convertToOpenAIMessages(messages, useResponseApi))
        }
        val toolDefs = tools.takeIf { it.isNotEmpty() }?.map { tool ->
            OpenAIToolDefinition(
                function = OpenAIFunctionDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.toJsonSchema()
                )
            )
        }

        val url = if (useFullUrl) baseUrl else joinUrl(baseUrl, defaultProviderApiPath(ProviderType.OPENAI))
        
        if (useResponseApi) {
            val request = mutableMapOf<String, Any?>(
                "model" to model,
                "input" to convertToResponseApiInput(openAIMessages),
                "tools" to toolDefs,
                "stream" to true
            )
            reasoningEffort?.let { request["reasoning"] = mapOf("effort" to it) }
            AILogger.logRequest(logSessionId, "OpenAI", model, "POST", url, request)
            val rawSse = StringBuilder()
            try {
                streamWithStaircaseRetry(attemptOnce = { onProduced ->
                    val textBuilder = StringBuilder()
                    val toolAccs = LinkedHashMap<Int, OpenAIToolAcc>()
                    var finishReason: String? = null
                    var streamInputTokens = 0
                    var streamOutputTokens = 0

                    val body = api.streamResponses(
                        url = url,
                        authorization = "Bearer $apiKey",
                        request = request
                    )

                    body.use { rb ->
                        // 首字节超时 watchdog：60s 内未收到首个内容块则关闭流，触发可重试的 IOException。
                        val firstByteReceived = java.util.concurrent.atomic.AtomicBoolean(false)
                        val watchdog = launchFirstByteWatchdog({ rb.close() }) { firstByteReceived.get() }
                        val closeHandle = coroutineContext[Job]?.invokeOnCompletion {
                            runCatching { rb.close() }
                        }
                        try {
                            val source = rb.source()
                            while (true) {
                                coroutineContext.ensureActive()
                                val line = source.readUtf8Line()
                                    ?: throw IOException("SSE 流被中断：未收到 [DONE] 结束标记（疑似网络断开）")
                                if (!line.startsWith("data:")) continue
                                val data = line.removePrefix("data:").trim()
                                if (data.isEmpty()) continue
                                rawSse.append(line).append('\n')
                                if (data == "[DONE]") break
                                // P1 定点字段抽取：热路径（response.output_text.delta）只取 type/delta 两个标量，不建整树。
                                val m = runCatching { SseFieldExtractor.extract(data, OPENAI_RESPONSES_STREAM_PATHS) }.getOrElse { emptyMap() }
                                // 错误：error 对象至少带 message，命中任一字段即抛出。
                                m["error.message"]?.let { msg ->
                                    throw StreamApiException(m["error.code"], msg)
                                } ?: m["error.code"]?.let { code ->
                                    throw StreamApiException(code, "未知错误")
                                }
                                try {
                                    when (m["type"]) {
                                        "response.output_text.delta" -> {
                                            val delta = m["delta"] ?: ""
                                            if (delta.isNotEmpty()) {
                                                textBuilder.append(delta)
                                                if (firstByteReceived.compareAndSet(false, true)) watchdog.cancel()
                                                onProduced()
                                                emit(AIStreamChunk.TextDelta(delta))
                                            }
                                        }
                                        "response.completed" -> {
                                            // 结束事件每轮一次（低频）：保留整树解析以复用原有结构化遍历（output 数组）。
                                            val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull()
                                            if (obj != null) {
                                                val outputs = obj.getAsJsonObject("response")?.getAsJsonArray("output")
                                                outputs?.forEach { out ->
                                                    val msg = out.asJsonObject
                                                    if (msg.get("role")?.asString == "assistant") {
                                                        msg.getAsJsonArray("content")?.forEach { partEl ->
                                                            val part = partEl.asJsonObject
                                                            if (part.get("type")?.asString == "tool_call") {
                                                                val id = part.get("id")?.asString ?: ""
                                                                val name = part.get("name")?.asString ?: ""
                                                                val args = part.get("arguments")?.asString ?: ""
                                                                val idx = toolAccs.size
                                                                val acc = toolAccs.getOrPut(idx) { OpenAIToolAcc() }
                                                                acc.id = id
                                                                acc.name = name
                                                                acc.args.accept(args)
                                                            }
                                                        }
                                                    }
                                                }
                                                finishReason = "stop"
                                                val usageObj = obj.get("response")?.takeIf { it.isJsonObject }?.asJsonObject
                                                    ?.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
                                                streamInputTokens = usageObj?.get("input_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                                streamOutputTokens = usageObj?.get("output_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                            }
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    coroutineContext.ensureActive()
                                }
                            }
                        } finally {
                            watchdog.cancel()
                            closeHandle?.dispose()
                        }
                    }

                    val toolCalls = toolAccs.values
                        .filter { it.id.isNotEmpty() || it.name.isNotEmpty() }
                        .map { acc -> ToolCall(id = acc.id, name = acc.name, arguments = parseArgs(acc.args.text)) }
                    onProduced()
                    emit(AIStreamChunk.Final(AIResponse(content = textBuilder.toString(), toolCalls = toolCalls, stopReason = finishReason, inputTokens = streamInputTokens, outputTokens = streamOutputTokens)))
                },
                onRetry = { attempt, max -> emit(AIStreamChunk.Retrying(attempt, max)) }
            )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                val enriched = e.enrichWithHttpErrorBody()
                AILogger.logError(logSessionId, "OpenAI", enriched)
                throw enriched
            }
            return@flow
        }

        val request = ChatCompletionRequest(
            model = model,
            messages = openAIMessages,
            reasoning_effort = reasoningEffort,
            tools = toolDefs,
            tool_choice = if (toolDefs != null) "auto" else null,
            stream = true,
            stream_options = StreamOptions(include_usage = true)
        )
        AILogger.logRequest(logSessionId, "OpenAI", model, "POST", url, request)
        // 累积原始 SSE，整轮结束（或失败）后整体落盘，避免高频写盘。
        val rawSse = StringBuilder()

        // 首字节前失败可安全重试；一旦开始吐字（onProduced 已调用）再失败则上抛，避免重复文本。
        try {
            streamWithStaircaseRetry(
                attemptOnce = { onProduced ->
            val textBuilder = StringBuilder()
            // tool_call index -> 累积中的工具调用（保序）。
            val toolAccs = LinkedHashMap<Int, OpenAIToolAcc>()
            var finishReason: String? = null
            var streamInputTokens = 0
            var streamOutputTokens = 0

            val body = api.streamChatCompletion(
                url = url,
                authorization = "Bearer $apiKey",
                request = request
            )

            body.use { rb ->
                // 首字节超时 watchdog：60s 内未收到首个内容块则关闭流，触发可重试的 IOException。
                val firstByteReceived = java.util.concurrent.atomic.AtomicBoolean(false)
                val watchdog = launchFirstByteWatchdog({ rb.close() }) { firstByteReceived.get() }
                val closeHandle = coroutineContext[Job]?.invokeOnCompletion {
                    runCatching { rb.close() }
                }
                try {
                    val source = rb.source()
                    // 收到服务端 [DONE] 标记即 break 正常结束；readUtf8Line() 返回 null 则视为
                    // 流被异常截断（网络中断/TCP 重置/readTimeout），必须抛异常让重试/日志接管——
                    // 否则原本会用截断数据「正常完成」，表现为 AI 突然中断且无任何错误日志。
                    // （收到 [DONE] 即 break，故走到 readUtf8Line()==null 时必然未收到过结束标记。）
                    while (true) {
                        coroutineContext.ensureActive()
                        val line = source.readUtf8Line()
                            ?: throw IOException("SSE 流被中断：未收到 [DONE] 结束标记（疑似网络断开）")
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        rawSse.append(line).append('\n')
                        if (data == "[DONE]") break
                        // P1 定点字段抽取：JsonReader 流式逐 token 只取目标标量，不建整棵 JSON 树。
                        val m = runCatching { SseFieldExtractor.extract(data, OPENAI_CHAT_STREAM_PATHS) }.getOrElse { emptyMap() }
                        // 错误：OpenAI 的 error 对象至少带 message，命中任一字段即抛出。
                        m["error.message"]?.let { msg ->
                            throw StreamApiException(m["error.code"], msg)
                        } ?: m["error.code"]?.let { code ->
                            throw StreamApiException(code, "未知错误")
                        }
                        // 单行 SSE 解析：不同上游/模型的字段类型偶有出入（如把对象写成数组、把字符串写成对象），
                        // 定点抽取对缺失/类型不符的字段视为「无该字段」，单行异常不应中断整条流——
                        // 这里宽松解析，出错仅跳过该行；已累积的文本与后续行不受影响。
                        // 必须放行 CancellationException，否则会吞掉协程取消信号。
                        try {
                            m["usage.prompt_tokens"]?.toIntOrNull()?.let { streamInputTokens = it }
                            m["usage.completion_tokens"]?.toIntOrNull()?.let { streamOutputTokens = it }

                            m["choices.0.finish_reason"]?.let { finishReason = it }

                            // 文字增量
                            m["choices.0.delta.content"]?.let { c ->
                                if (c.isNotEmpty()) {
                                    textBuilder.append(c)
                                    if (firstByteReceived.compareAndSet(false, true)) watchdog.cancel()
                                    onProduced()
                                    emit(AIStreamChunk.TextDelta(c))
                                }
                            }
                            // 思考过程增量（reasoning_content）：仅 UI 实时展示，不计入正文、不触发 onProduced
                            // （思考不落库，重试时重新流出即可，无重复文本风险），但收到即说明连接已活，取消首字节超时。
                            m["choices.0.delta.reasoning_content"]?.let { r ->
                                if (r.isNotEmpty()) {
                                    if (firstByteReceived.compareAndSet(false, true)) watchdog.cancel()
                                    emit(AIStreamChunk.ReasoningDelta(r))
                                }
                            }
                            // 工具调用增量：按 index 聚合 id/name/arguments 片段。
                            // 流式 chunk 中 delta.tool_calls 数组通常每 chunk 一个元素（以 index 标识累加目标），
                            // 定点抽取覆盖前 OPENAI_TOOL_CALL_SUBSCRIPT_COUNT 个元素，逐个处理。
                            // 有些模型（如 DeepSeek）在后续增量 chunk 中只传 arguments 片段，
                            // id 和 name 为空字符串 ""，不应覆盖已收到的有效值——否则首次 chunk
                            // 收到的完整 id/name 会被后续空值清空，导致 ToolCall 丢失。
                            for (k in 0 until OPENAI_TOOL_CALL_SUBSCRIPT_COUNT) {
                                val prefix = "choices.0.delta.tool_calls.$k"
                                val idx = m["$prefix.index"]?.toIntOrNull() ?: continue
                                val acc = toolAccs.getOrPut(idx) { OpenAIToolAcc() }
                                // 仅在 id/name 非空时更新，避免增量 chunk 的空值覆盖首 chunk 的有效值
                                m["$prefix.id"]?.takeIf { it.isNotEmpty() }?.let { acc.id = it }
                                m["$prefix.function.name"]?.takeIf { it.isNotEmpty() }?.let { acc.name = it }
                                m["$prefix.function.arguments"]?.let { acc.args.accept(it) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            coroutineContext.ensureActive()
                            // 该行 SSE 解析失败，跳过；不影响已累积文本与后续行。
                        }
                    }
                } finally {
                    watchdog.cancel()
                    closeHandle?.dispose()
                }
            }

            val toolCalls = toolAccs.values
                .filter { it.id.isNotEmpty() || it.name.isNotEmpty() }
                .map { acc -> ToolCall(id = acc.id, name = acc.name, arguments = parseArgs(acc.args.text)) }
            onProduced()
            emit(AIStreamChunk.Final(AIResponse(content = textBuilder.toString(), toolCalls = toolCalls, stopReason = finishReason, inputTokens = streamInputTokens, outputTokens = streamOutputTokens)))
            },
            onRetry = { attempt, max -> emit(AIStreamChunk.Retrying(attempt, max)) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "OpenAI", enriched)
            throw enriched
        } finally {
            // 无论成功/失败/取消，把已收到的原始 SSE 落盘（重试时会从上次中断处续写）。
            AILogger.logResponseStream(logSessionId, "OpenAI", rawSse.toString())
        }
    }.flowOn(Dispatchers.IO)

    /** 流式过程中按 index 累积的工具调用状态。 */
    private class OpenAIToolAcc {
        var id = ""
        var name = ""
        /** 工具参数累积：增量片段语义（INCREMENTAL），带 base64 折叠与长度护栏，防止病态参数累积放大。 */
        val args = DeltaAccumulator(Semantic.INCREMENTAL)
    }

    /** 把累积的工具入参 JSON 字符串解析为 JsonObject；为空或非法时回退为空对象。 */
    private fun parseArgs(raw: String): JsonObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    }

    private fun convertToOpenAIMessages(
        messages: List<AgentMessage>,
        useResponsesContentParts: Boolean
    ): MutableList<OpenAIChatMessage> {
        val raw = messages.map { message ->
            when (message) {
                is AgentMessage.UserMessage -> OpenAIChatMessage(
                    role = "user",
                    content = message.toOpenAIUserContent(useResponsesContentParts)
                )
                is AgentMessage.AssistantMessage -> {
                    val toolCalls = if (message.toolCalls.isNotEmpty()) {
                        message.toolCalls.map { convertToOpenAIToolCall(it) }
                    } else null
                    // DeepSeek 思考模式要求 assistant 消息的 reasoning_content 字段必须存在
                    // （即使是空串也要带上），否则工具调用轮回传时 API 报 400。
                    val reasoningContent = if (model.contains("deepseek", ignoreCase = true)) {
                        message.reasoning
                    } else {
                        message.reasoning.ifEmpty { null }
                    }
                    OpenAIChatMessage(
                        role = "assistant",
                        content = message.content,
                        tool_calls = toolCalls,
                        reasoning_content = reasoningContent
                    )
                }
                is AgentMessage.ToolResultMessage -> OpenAIChatMessage(
                    role = "tool",
                    content = message.result,
                    tool_call_id = message.id
                )
            }
        }

        // 防御性清理：保证 assistant(tool_calls) 与其 tool 响应消息按 tool_call_id 一一配对并紧跟，
        // 避免上游 400。可能破坏配对的场景：
        // 1) 并发/异步工具结果乱序落位（如 askUserQuestion 阻塞等待期间其他工具结果插队），
        //    assistant(tool_calls) 与其响应被其他消息隔开 → 将匹配的 tool 响应吸附回紧跟其后；
        // 2) 孤立 tool 消息（前驱 assistant 无 tool_calls，如上下文压缩导致配对断裂）→ 跳过，
        //    否则 OpenAI 报 "Messages with role 'tool' must be a response to a preceding
        //    message with 'tool_calls'"；
        // 3) assistant 声明的 tool_calls 无对应响应（如用户拒绝导致部分调用未执行）→ 裁剪，
        //    否则 OpenAI 报 "insufficient tool messages following tool_calls message"。
        val cleaned = mutableListOf<OpenAIChatMessage>()
        val consumed = BooleanArray(raw.size)
        for (i in raw.indices) {
            if (consumed[i]) continue
            val msg = raw[i]
            if (msg.role == "assistant" && msg.tool_calls?.isNotEmpty() == true) {
                val remaining = msg.tool_calls!!.map { it.id }.toMutableSet()
                val matchedTools = mutableListOf<OpenAIChatMessage>()
                for (j in i + 1 until raw.size) {
                    if (consumed[j]) continue
                    val m = raw[j]
                    if (m.role == "tool" && m.tool_call_id != null && m.tool_call_id in remaining) {
                        matchedTools.add(m)
                        consumed[j] = true
                        remaining.remove(m.tool_call_id)
                        if (remaining.isEmpty()) break
                    }
                }
                val keptCalls = if (remaining.isEmpty()) msg.tool_calls
                else msg.tool_calls!!.filter { it.id !in remaining }
                cleaned.add(if (keptCalls === msg.tool_calls) msg else msg.copy(tool_calls = keptCalls.ifEmpty { null }))
                cleaned.addAll(matchedTools)
            } else if (msg.role == "tool") {
                consumed[i] = true // 孤立 tool 消息，跳过
            } else {
                cleaned.add(msg)
            }
        }
        return cleaned
    }

    /**
     * 把 Chat Completions 消息结构（[OpenAIChatMessage]，role=tool / tool_calls 字段）转换为
     * Responses API 的 input item 结构。
     *
     * Responses API 不认 Chat Completions 的 `role=tool` 与 assistant 上的 `tool_calls` 字段，
     * 必须转换为：
     * - 工具结果：顶层 `{"type": "function_call_output", "call_id", "output"}` 条目；
     * - 带工具调用的 assistant：content 内嵌 `{"type": "function_call", "id", "name", "arguments"}` 片段。
     *
     * 入参 [messages] 已由 [convertToOpenAIMessages] 完成「assistant(tool_calls) ↔ tool 结果」的配对与裁剪，
     * 此处只做逐条格式映射，顺序保持不变，保证 function_call_output 紧跟其对应的 function_call。
     */
    private fun convertToResponseApiInput(messages: List<OpenAIChatMessage>): List<Map<String, Any?>> =
        messages.map { msg ->
            when (msg.role) {
                "assistant" -> {
                    val toolCalls = msg.tool_calls.orEmpty()
                    if (toolCalls.isEmpty()) {
                        mapOf("role" to "assistant", "content" to (msg.content ?: ""))
                    } else {
                        val parts = mutableListOf<Map<String, Any?>>()
                        (msg.content as? String)?.takeIf { it.isNotBlank() }?.let {
                            parts.add(mapOf("type" to "output_text", "text" to it))
                        }
                        toolCalls.forEach { tc ->
                            parts.add(
                                mapOf(
                                    "type" to "function_call",
                                    "id" to tc.id,
                                    "name" to tc.function.name,
                                    "arguments" to tc.function.arguments
                                )
                            )
                        }
                        mapOf("role" to "assistant", "content" to parts)
                    }
                }
                "tool" -> mapOf(
                    "type" to "function_call_output",
                    "call_id" to (msg.tool_call_id ?: ""),
                    "output" to (msg.content?.toString() ?: "")
                )
                // user / system / developer 消息结构在两种 API 下一致（user 的多模态 content 已是
                // input_text / input_image 片段），原样透传即可。
                else -> mapOf("role" to msg.role, "content" to (msg.content ?: ""))
            }
        }

    private fun AgentMessage.UserMessage.toOpenAIUserContent(useResponsesContentParts: Boolean): Any {
        if (images.isEmpty()) return content

        val parts = mutableListOf<Map<String, Any>>()
        if (content.isNotBlank()) {
            parts.add(
                if (useResponsesContentParts) {
                    mapOf("type" to "input_text", "text" to content)
                } else {
                    mapOf("type" to "text", "text" to content)
                }
            )
        }
        images.forEach { image ->
            parts.add(image.toOpenAIImagePart(useResponsesContentParts))
        }
        return parts
    }

    private fun AgentImage.toOpenAIImagePart(useResponsesContentParts: Boolean): Map<String, Any> {
        val imageUrl = "data:$mimeType;base64,$base64Data"
        return if (useResponsesContentParts) {
            mapOf(
                "type" to "input_image",
                "image_url" to imageUrl,
                "detail" to "auto"
            )
        } else {
            mapOf(
                "type" to "image_url",
                "image_url" to mapOf(
                    "url" to imageUrl,
                    "detail" to "auto"
                )
            )
        }
    }

    /**
     * OpenAI chat completion 返回的 content 可能是字符串或数组（多模态/生图模型）。
     * 数组元素里可能含 image_url 的 base64 data URL（几 MB），直接 toString() 会把整段 base64
     * 当成 assistant 文本落库，撑爆 SQLite CursorWindow 导致启动崩溃。这里只提取文本部分，
     * 图片只保留说明/远程 URL 引用，绝不把 base64 写进 content。
     */
    private fun Any?.asTextContent(): String = when (this) {
        null -> ""
        is String -> this
        is List<*> -> extractTextFromContentParts(this)
        else -> toString()
    }

    private fun extractTextFromContentParts(parts: List<*>): String {
        val text = StringBuilder()
        for (part in parts) {
            when (part) {
                is Map<*, *> -> {
                    when (val type = part["type"] as? String) {
                        "text", "input_text", "output_text" -> {
                            (part["text"] as? String)?.let { text.append(it) }
                        }
                        "image_url" -> {
                            val url = when (val img = part["image_url"]) {
                                is String -> img
                                is Map<*, *> -> img["url"] as? String
                                else -> null
                            }
                            if (url != null && url.startsWith("data:image", ignoreCase = true)) {
                                text.append("\n[图片已省略：内嵌图片数据过大]")
                            } else if (!url.isNullOrBlank()) {
                                text.append("\n[图片：").append(url).append("]")
                            }
                        }
                        "input_image" -> {
                            text.append("\n[图片已省略：内嵌图片数据过大]")
                        }
                        else -> {}
                    }
                }
                is String -> text.append(part)
                else -> {}
            }
        }
        return text.toString()
    }

    private fun convertToToolCall(openAIToolCall: OpenAIToolCall): ToolCall {
        val argumentsJson = runCatching {
            Json.parseToJsonElement(openAIToolCall.function.arguments).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }
        return ToolCall(
            id = openAIToolCall.id,
            name = openAIToolCall.function.name,
            arguments = argumentsJson
        )
    }

    private fun convertToOpenAIToolCall(toolCall: ToolCall): OpenAIToolCall {
        return OpenAIToolCall(
            id = toolCall.id,
            type = "function",
            function = com.R.codecore.feature.agent.data.remote.openai.OpenAIFunctionCall(
                name = toolCall.name,
                arguments = JsonObject(toolCall.arguments).toString()
            )
        )
    }
}
