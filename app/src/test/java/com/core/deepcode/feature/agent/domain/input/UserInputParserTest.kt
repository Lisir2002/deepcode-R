package com.core.deepcode.feature.agent.domain.input

import com.core.deepcode.feature.agent.domain.input.UserInputParser.IntentLabel
import com.core.deepcode.feature.agent.domain.input.UserInputParser.Marker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D0-1 语法层：UserInputParser 结构化解析（command/args/text/marker）+ 意图分类。
 * 对齐 norm-chain §3.10.1 验收：解析 command/args/text/marker/意图分类正确。
 */
class UserInputParserTest {

    private val parser = UserInputParser()

    @Test
    fun slashCommand_withArgs_parsed() {
        val p = parser.parse("/playbook start")
        assertEquals("/playbook", p.command)
        assertEquals("start", p.args)
        assertEquals("start", p.text)
        assertEquals(IntentLabel.COMMAND, p.intentLabel)
        assertEquals(Marker.NONE, p.marker)
    }

    @Test
    fun slashCommand_withMultiArg_keepsRestInText() {
        val p = parser.parse("/playbook start 项目A")
        assertEquals("/playbook", p.command)
        assertEquals("start", p.args)
        assertEquals("start 项目A", p.text)
    }

    @Test
    fun markerForce_prependedToSlashCommand() {
        val p = parser.parse("!/mode design")
        assertEquals(Marker.FORCE, p.marker)
        assertEquals("/mode", p.command)
        assertEquals("design", p.args)
        assertEquals("design", p.text)
    }

    @Test
    fun markerConsult_onlyConsultation() {
        val p = parser.parse("?这段代码什么意思")
        assertEquals(Marker.CONSULT, p.marker)
        assertNull(p.command)
        assertEquals("这段代码什么意思", p.text)
    }

    @Test
    fun noMarker_none() {
        assertEquals(Marker.NONE, parser.parse("修复一个 bug").marker)
    }

    @Test
    fun taskVerbs_classifiedTask() {
        assertEquals(IntentLabel.TASK, parser.parse("帮我实现一个功能").intentLabel)
        assertEquals(IntentLabel.TASK, parser.parse("修复登录崩溃").intentLabel)
    }

    @Test
    fun queryWords_classifiedQuery() {
        assertEquals(IntentLabel.QUERY, parser.parse("为什么编译失败").intentLabel)
        assertEquals(IntentLabel.QUERY, parser.parse("这个函数是干什么的？").intentLabel)
        assertEquals(IntentLabel.QUERY, parser.parse("有哪些方案").intentLabel)
    }

    @Test
    fun fileVerbs_shortText_classifiedFile() {
        assertEquals(IntentLabel.FILE, parser.parse("删除 main.kt").intentLabel)
        assertEquals(IntentLabel.FILE, parser.parse("重命名 build.gradle").intentLabel)
    }

    @Test
    fun blankText_classifiedUnknown() {
        assertEquals(IntentLabel.UNKNOWN, parser.parse("").intentLabel)
        assertEquals(IntentLabel.UNKNOWN, parser.parse("   ").intentLabel)
    }
}
