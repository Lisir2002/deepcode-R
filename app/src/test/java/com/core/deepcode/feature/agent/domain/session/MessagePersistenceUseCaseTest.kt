package com.core.deepcode.feature.agent.domain.session

import com.core.deepcode.feature.agent.data.local.entity.AgentMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证落库前的内容净化：剥离内嵌 base64 图片 data URL、超长内容截断，
 * 防止超大 content 触发 SQLite CursorWindow 崩溃（SQLiteBlobTooBigException）。
 */
class MessagePersistenceUseCaseTest {

    @Test
    fun sanitizeContent_stripsInlineBase64Image() {
        val input = "这是回复。看图：![截图](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==) 结束。"

        val result = MessagePersistenceUseCase.sanitizeContent(input)

        assertTrue(result.contains("图片已省略"))
        assertFalse(result.contains("iVBORw0KGgo"))
        assertTrue(result.contains("这是回复"))
    }

    @Test
    fun sanitizeContent_stripsBareDataUrl() {
        val input = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQ=="

        val result = MessagePersistenceUseCase.sanitizeContent(input)

        assertTrue(result.contains("图片已省略"))
        assertFalse(result.contains("/9j/4AAQSk"))
    }

    @Test
    fun sanitizeContent_truncatesOversizedPlainText() {
        val oversized = "a".repeat(300_000)

        val result = MessagePersistenceUseCase.sanitizeContent(oversized)

        assertTrue(result.length <= MessagePersistenceUseCase.MAX_CONTENT_CHARS + 64)
        assertTrue(result.endsWith(MessagePersistenceUseCase.CONTENT_TRUNCATED_MARKER))
    }

    @Test
    fun sanitizeContent_keepsNormalTextUnchanged() {
        val normal = "普通消息，不需要处理。".repeat(10)

        assertEquals(normal, MessagePersistenceUseCase.sanitizeContent(normal))
    }

    // ── mergeChunks：分块消息按 chunk_index 序拼接 ──────────────────────────────

    @Test
    fun mergeChunks_reassemblesChunkedMessageInOrder() {
        val session = "s1"
        val gid = "msg-1"
        // 主行（chunk 0）携带元数据；续块行乱序传入，验证按 chunk_index 排序拼接。
        val main = entity(
            id = gid, sessionId = session, content = "第一块-", chunkGroupId = gid, chunkIndex = 0, taskId = "t1"
        )
        val c2 = entity(
            id = "$gid#c2", sessionId = session, content = "第三块", chunkGroupId = gid, chunkIndex = 2
        )
        val c1 = entity(
            id = "$gid#c1", sessionId = session, content = "第二块-", chunkGroupId = gid, chunkIndex = 1
        )

        val merged = MessagePersistenceUseCase.mergeChunks(listOf(main, c2, c1))

        assertEquals(1, merged.size)
        assertEquals("第一块-第二块-第三块", merged[0].content)
        // 主行元数据保留，chunk 字段清零
        assertEquals(gid, merged[0].id)
        assertEquals("t1", merged[0].taskId)
        assertEquals("", merged[0].chunkGroupId)
        assertEquals(0, merged[0].chunkIndex)
    }

    @Test
    fun mergeChunks_preservesChronologicalOrderAmongGroups() {
        val session = "s1"
        val gidA = "msg-A"
        val gidB = "msg-B"
        val a0 = entity(id = gidA, sessionId = session, content = "A0", chunkGroupId = gidA, chunkIndex = 0)
        val a1 = entity(id = "$gidA#c1", sessionId = session, content = "A1", chunkGroupId = gidA, chunkIndex = 1)
        val b0 = entity(id = gidB, sessionId = session, content = "B0", chunkGroupId = gidB, chunkIndex = 0)
        val b1 = entity(id = "$gidB#c1", sessionId = session, content = "B1", chunkGroupId = gidB, chunkIndex = 1)
        val plain = entity(id = "plain", sessionId = session, content = "普通")

        // 输入顺序 A0, B0, B1, A1, plain → 合并后仍保持首次出现顺序：A 组、B 组、普通。
        val merged = MessagePersistenceUseCase.mergeChunks(listOf(a0, b0, b1, a1, plain))

        assertEquals(3, merged.size)
        assertEquals("A0A1", merged[0].content)
        assertEquals("B0B1", merged[1].content)
        assertEquals("普通", merged[2].content)
    }

    @Test
    fun mergeChunks_concatenatesReasoningAndPreservesToolMetadata() {
        val session = "s1"
        val gid = "msg-tool"
        val main = entity(
            id = gid, sessionId = session, content = "正文0",
            chunkGroupId = gid, chunkIndex = 0,
            role = "ASSISTANT", reasoning = "思考0-", toolCallsJson = "[{\"id\":\"tc1\"}]"
        )
        val c1 = entity(
            id = "$gid#c1", sessionId = session, content = "正文1",
            chunkGroupId = gid, chunkIndex = 1, role = "ASSISTANT", reasoning = "思考1"
        )

        val merged = MessagePersistenceUseCase.mergeChunks(listOf(main, c1))

        assertEquals(1, merged.size)
        assertEquals("正文0正文1", merged[0].content)
        assertEquals("思考0-思考1", merged[0].reasoning)
        // 主行工具调用元数据保留
        assertEquals("[{\"id\":\"tc1\"}]", merged[0].toolCallsJson)
    }

    @Test
    fun mergeChunks_passthroughNonChunkedMessages() {
        val a = entity(id = "a", sessionId = "s1", content = "A")
        val b = entity(id = "b", sessionId = "s1", content = "B")

        val merged = MessagePersistenceUseCase.mergeChunks(listOf(a, b))

        assertEquals(listOf("A", "B"), merged.map { it.content })
    }

    @Test
    fun mergeChunks_emptyList() {
        assertTrue(MessagePersistenceUseCase.mergeChunks(emptyList()).isEmpty())
    }

    private fun entity(
        id: String,
        sessionId: String,
        content: String,
        chunkGroupId: String = "",
        chunkIndex: Int = 0,
        taskId: String = "",
        role: String = "ASSISTANT",
        reasoning: String? = null,
        toolCallsJson: String? = null
    ) = AgentMessageEntity(
        id = id,
        sessionId = sessionId,
        taskId = taskId,
        role = role,
        content = content,
        timestamp = 0L,
        chunkGroupId = chunkGroupId,
        chunkIndex = chunkIndex,
        reasoning = reasoning,
        toolCallsJson = toolCallsJson
    )
}
