package com.R.codecore.feature.agent.domain.tool.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandGuardsTest {

    // ---------- CommandLoopGuard: 无界循环 ----------

    @Test
    fun hasUnboundedLoop_matchesInfiniteLoops() {
        assertTrue(CommandLoopGuard.hasUnboundedLoop("while true; do echo hi; done"))
        assertTrue(CommandLoopGuard.hasUnboundedLoop("while :; do echo hi; done"))
        assertTrue(CommandLoopGuard.hasUnboundedLoop("while : ; do sleep 1; done"))
        assertTrue(CommandLoopGuard.hasUnboundedLoop("while [ 1 ]; do echo hi; done"))
        assertTrue(CommandLoopGuard.hasUnboundedLoop("while [[ 1 ]]; do echo hi; done"))
        assertTrue(CommandLoopGuard.hasUnboundedLoop("until false; do echo hi; done"))
        // bash C 风格双括号无界循环（曾因单括号正则匹配不到而漏检）
        assertTrue(CommandLoopGuard.hasUnboundedLoop("for ((;;)); do echo hi; done"))
        assertTrue(CommandLoopGuard.hasUnboundedLoop("for (( ; ; )); do echo hi; done"))
    }

    @Test
    fun hasUnboundedLoop_doesNotMatchBoundedOrConditional() {
        assertFalse(CommandLoopGuard.hasUnboundedLoop("while read line; do echo $line; done < file"))
        assertFalse(CommandLoopGuard.hasUnboundedLoop("while [ -f /tmp/x ]; do sleep 1; done"))
        assertFalse(CommandLoopGuard.hasUnboundedLoop("until ping -c 1 host; do sleep 1; done"))
        assertFalse(CommandLoopGuard.hasUnboundedLoop("for i in 1 2 3; do echo $i; done"))
        assertFalse(CommandLoopGuard.hasUnboundedLoop("for (( i=0; i<10; i++ )); do echo $i; done"))
        assertFalse(CommandLoopGuard.hasUnboundedLoop("echo mywhile true"))
        assertFalse(CommandLoopGuard.hasUnboundedLoop(""))
    }

    // ---------- CommandLoopGuard: fork bomb ----------

    @Test
    fun isForkBomb_matchesClassicBomb() {
        assertTrue(CommandLoopGuard.isForkBomb(":(){ :|:& };:"))
        assertTrue(CommandLoopGuard.isForkBomb(":(){:|:&};:"))
        assertTrue(CommandLoopGuard.isForkBomb(":() { :|:& };:"))
    }

    @Test
    fun isForkBomb_doesNotMatchNormalCommands() {
        assertFalse(CommandLoopGuard.isForkBomb("ls -la"))
        assertFalse(CommandLoopGuard.isForkBomb("for i in 1 2 3; do echo $i; done"))
        assertFalse(CommandLoopGuard.isForkBomb(""))
    }

    // ---------- BusyBoxCompatibilityGuard: GNU-only 参数 ----------

    @Test
    fun warningMessage_flagsGnuOnlyFlags() {
        // 事故原型：nc -q
        assertNotNull(BusyBoxCompatibilityGuard.warningMessage("nc -q 1 host port"))
        assertNotNull(BusyBoxCompatibilityGuard.warningMessage("grep -P '\\d+' file"))
        assertNotNull(BusyBoxCompatibilityGuard.warningMessage("grep '\\d+' file"))
        assertNotNull(BusyBoxCompatibilityGuard.warningMessage("find . -printf '%p\\n'"))
        assertNotNull(BusyBoxCompatibilityGuard.warningMessage("cat list | xargs -d '\\n' rm"))
        assertNotNull(BusyBoxCompatibilityGuard.warningMessage("cp --parents src dst"))
        // 无限 ping（未带次数/时限）
        assertNotNull(BusyBoxCompatibilityGuard.warningMessage("ping 8.8.8.8"))
    }

    @Test
    fun warningMessage_doesNotFlagCompatibleUsages() {
        assertNull(BusyBoxCompatibilityGuard.warningMessage("nc -w 1 host port"))
        assertNull(BusyBoxCompatibilityGuard.warningMessage("grep -E '[0-9]+' file"))
        assertNull(BusyBoxCompatibilityGuard.warningMessage("find . -name '*.kt'"))
        assertNull(BusyBoxCompatibilityGuard.warningMessage("cat list | xargs -0 rm"))
        assertNull(BusyBoxCompatibilityGuard.warningMessage("cp -r src dst"))
        assertNull(BusyBoxCompatibilityGuard.warningMessage("ping -c 3 8.8.8.8"))
        assertNull(BusyBoxCompatibilityGuard.warningMessage("ls -la"))
        assertNull(BusyBoxCompatibilityGuard.warningMessage(""))
    }

    @Test
    fun appendHint_onlyAddsWhenRiskDetected() {
        val output = "hello"
        assertEquals(output, BusyBoxCompatibilityGuard.appendHint("ls -la", output))
        val withHint = BusyBoxCompatibilityGuard.appendHint("nc -q 1 host port", output)
        assertTrue(withHint.contains("nc"))
        assertTrue(withHint.startsWith(output))
    }
}
