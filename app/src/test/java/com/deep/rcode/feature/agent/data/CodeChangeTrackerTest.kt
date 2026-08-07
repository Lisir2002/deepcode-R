package com.deep.rcode.feature.agent.data

import com.deep.rcode.feature.agent.domain.model.ChangeType
import com.deep.rcode.feature.agent.domain.tool.ToolCall
import com.deep.rcode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CodeChangeTrackerTest {

    private lateinit var tracker: CodeChangeTracker

    @Before
    fun setUp() {
        tracker = CodeChangeTracker()
    }

    @Test
    fun writeFileSuccess_tracksCreateChange() {
        val call = ToolCall(
            id = "call1",
            name = "writeFile",
            arguments = mapOf(
                "path" to JsonPrimitive("/src/Main.kt"),
                "content" to JsonPrimitive("fun main() {}")
            )
        )
        val result = ToolResult.Success(JsonPrimitive("written"))

        val changes = tracker.trackChange(call, result)

        assertEquals(1, changes.size)
        val change = changes[0]
        assertEquals("/src/Main.kt", change.filePath)
        assertEquals(ChangeType.CREATE, change.type)
        assertEquals(1, change.startLine)
        assertEquals(1, change.endLine)
        assertEquals("", change.oldCode)
        assertEquals("fun main() {}", change.newCode)
    }

    @Test
    fun writeFileError_doesNotTrack() {
        val call = ToolCall(
            id = "call1",
            name = "writeFile",
            arguments = mapOf(
                "path" to JsonPrimitive("/src/Main.kt"),
                "content" to JsonPrimitive("content")
            )
        )
        val result = ToolResult.Error("permission denied")

        val changes = tracker.trackChange(call, result)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun editFileSuccess_withEditsArray_tracksMultipleChanges() {
        val edits = kotlinx.serialization.json.JsonArray(
            listOf(
                kotlinx.serialization.json.buildJsonObject {
                    put("old_string", JsonPrimitive("old code 1"))
                    put("new_string", JsonPrimitive("new code 1"))
                },
                kotlinx.serialization.json.buildJsonObject {
                    put("old_string", JsonPrimitive("old code 2"))
                    put("new_string", JsonPrimitive("new code 2"))
                }
            )
        )
        val call = ToolCall(
            id = "call1",
            name = "editFile",
            arguments = mapOf(
                "path" to JsonPrimitive("/src/Utils.kt"),
                "edits" to edits
            )
        )
        val result = ToolResult.Success(JsonPrimitive("edited"))

        val changes = tracker.trackChange(call, result)

        assertEquals(2, changes.size)
        changes.forEach { change ->
            assertEquals("/src/Utils.kt", change.filePath)
            assertEquals(ChangeType.REPLACE, change.type)
            assertEquals(0, change.startLine)
            assertEquals(0, change.endLine)
        }
        assertEquals("old code 1", changes[0].oldCode)
        assertEquals("new code 1", changes[0].newCode)
        assertEquals("old code 2", changes[1].oldCode)
        assertEquals("new code 2", changes[1].newCode)
    }

    @Test
    fun editFileSuccess_withFlatOldNewStrings_tracksSingleChange() {
        val call = ToolCall(
            id = "call1",
            name = "editFile",
            arguments = mapOf(
                "path" to JsonPrimitive("/src/App.kt"),
                "old_string" to JsonPrimitive("val x = 1"),
                "new_string" to JsonPrimitive("val x = 2")
            )
        )
        val result = ToolResult.Success(JsonPrimitive("edited"))

        val changes = tracker.trackChange(call, result)

        assertEquals(1, changes.size)
        assertEquals(ChangeType.REPLACE, changes[0].type)
        assertEquals("val x = 1", changes[0].oldCode)
        assertEquals("val x = 2", changes[0].newCode)
    }

    @Test
    fun nonWriteTool_doesNotTrack() {
        val call = ToolCall(
            id = "call1",
            name = "executeCommand",
            arguments = mapOf("command" to JsonPrimitive("ls"))
        )
        val result = ToolResult.Success(JsonPrimitive("output"))

        val changes = tracker.trackChange(call, result)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun getChangesByFile_filtersCorrectly() {
        val call1 = ToolCall(
            id = "call1",
            name = "writeFile",
            arguments = mapOf(
                "path" to JsonPrimitive("/src/A.kt"),
                "content" to JsonPrimitive("A")
            )
        )
        val call2 = ToolCall(
            id = "call2",
            name = "writeFile",
            arguments = mapOf(
                "path" to JsonPrimitive("/src/B.kt"),
                "content" to JsonPrimitive("B")
            )
        )
        val result = ToolResult.Success(JsonPrimitive("ok"))

        tracker.trackChange(call1, result)
        tracker.trackChange(call2, result)

        val changesA = tracker.getChangesByFile("/src/A.kt")
        assertEquals(1, changesA.size)
        assertEquals("/src/A.kt", changesA[0].filePath)

        val changesB = tracker.getChangesByFile("/src/B.kt")
        assertEquals(1, changesB.size)
        assertEquals("/src/B.kt", changesB[0].filePath)
    }

    @Test
    fun clearChanges_removesAll() {
        val call = ToolCall(
            id = "call1",
            name = "writeFile",
            arguments = mapOf(
                "path" to JsonPrimitive("/src/Main.kt"),
                "content" to JsonPrimitive("content")
            )
        )
        tracker.trackChange(call, ToolResult.Success(JsonPrimitive("ok")))
        assertEquals(1, tracker.getAllChanges().size)

        tracker.clearChanges()
        assertTrue(tracker.getAllChanges().isEmpty())
    }
}
