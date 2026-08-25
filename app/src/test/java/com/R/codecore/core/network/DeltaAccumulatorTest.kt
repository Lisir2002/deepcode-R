package com.R.codecore.core.network

import com.R.codecore.core.network.DeltaAccumulator.NormalizedDelta
import com.R.codecore.core.network.DeltaAccumulator.Semantic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DeltaAccumulator 语义归一化 + 护栏的纯算法单测（增量/全量/混合三夹具，防回归）。 */
class DeltaAccumulatorTest {

    // ----- 增量流（INCREMENTAL） -----
    @Test
    fun incrementalAppendsAllChunks() {
        val acc = DeltaAccumulator(Semantic.INCREMENTAL)
        acc.accept("你")
        acc.accept("好")
        acc.accept("世界")
        assertEquals("你好世界", acc.text)
    }

    // ----- 全量重发（AUTO_DETECT）：本次 bug 核心场景 -----
    @Test
    fun autoDetectDedupesFullResend() {
        val acc = DeltaAccumulator()
        val full = "思考过程文本。" + "模型内部推理步骤编号#".repeat(40)
        val first = acc.accept(full)
        assertTrue(first is NormalizedDelta.Append)
        assertEquals(0, acc.duplicateCount)
        // 后续几百个相同 chunk → 全部去重，只保留一份
        repeat(300) { acc.accept(full) }
        assertEquals(300, acc.duplicateCount)
        assertEquals(full, acc.text)
    }

    @Test
    fun autoDetectHandlesIncrementalExtension() {
        val acc = DeltaAccumulator()
        acc.accept("ABCDE")
        val delta = acc.accept("ABCDEFGH")
        assertTrue(delta is NormalizedDelta.Append)
        assertEquals("FGH", (delta as NormalizedDelta.Append).text)
        assertEquals("ABCDEFGH", acc.text)
    }

    @Test
    fun autoDetectAppendsNewSegment() {
        val acc = DeltaAccumulator()
        acc.accept("first")
        val d = acc.accept("second")
        assertTrue(d is NormalizedDelta.Append)
        assertEquals("firstsecond", acc.text)
    }

    @Test
    fun autoDetectDropsRollbackDuplicate() {
        val acc = DeltaAccumulator()
        acc.accept("ABCDEFGH")
        val d = acc.accept("ABCD") // 回退到已有前缀 → 重复，丢弃
        assertTrue(d is NormalizedDelta.Duplicate)
        assertEquals("ABCDEFGH", acc.text)
    }

    @Test
    fun mixedFlowAppendsNewSegmentsThenDedupesFull() {
        val acc = DeltaAccumulator()
        acc.accept("line1")
        acc.accept("line2") // 新段，追加
        assertEquals("line1line2", acc.text)
        // 之后全量重发当前完整内容 → 去重
        val d = acc.accept("line1line2")
        assertTrue(d is NormalizedDelta.Duplicate)
        assertEquals("line1line2", acc.text)
    }

    // ----- 全量快照（FULL_SNAPSHOT） -----
    @Test
    fun fullSnapshotReplaces() {
        val acc = DeltaAccumulator(Semantic.FULL_SNAPSHOT)
        acc.accept("first")
        acc.accept("second")
        assertEquals("second", acc.text)
    }

    // ----- 护栏 -----
    @Test
    fun truncatesAtMaxChars() {
        val acc = DeltaAccumulator(Semantic.INCREMENTAL, maxChars = 10)
        acc.accept("12345678901234567890")
        assertTrue(acc.isTruncated)
        assertEquals(10, acc.text.length)
        assertEquals("1234567890", acc.text)
    }

    @Test
    fun foldsBareBase64() {
        val acc = DeltaAccumulator(Semantic.INCREMENTAL)
        val longB64 = "A".repeat(256) + "B".repeat(256)
        acc.accept("思考：" + longB64)
        assertFalse(acc.text.contains("A".repeat(256)))
        assertTrue(acc.text.contains("[图片已省略：内嵌图片数据过大]"))
    }

    @Test
    fun shortTextNotFolded() {
        val acc = DeltaAccumulator(Semantic.INCREMENTAL)
        val short = "ABCabc0123+/-_"
        acc.accept("说明：" + short)
        assertTrue(acc.text.contains(short))
    }

    @Test
    fun emptyChunkIgnored() {
        val acc = DeltaAccumulator()
        val d = acc.accept("")
        assertTrue(d is NormalizedDelta.Append)
        assertEquals("", acc.text)
        acc.accept("x")
        assertEquals("x", acc.text)
    }

    @Test
    fun resetClearsState() {
        val acc = DeltaAccumulator()
        acc.accept("abc")
        acc.accept("abc") // duplicate
        assertEquals(1, acc.duplicateCount)
        acc.reset()
        assertEquals("", acc.text)
        assertEquals(0, acc.duplicateCount)
        assertFalse(acc.isTruncated)
    }

    @Test
    fun rawCharsCountsAllReceivedIncludingDuplicates() {
        val acc = DeltaAccumulator()
        val full = "full-reasoning-content"
        acc.accept(full)
        repeat(9) { acc.accept(full) } // 9 次全量重发 → 去重
        assertEquals(10 * full.length.toLong(), acc.rawCharsReceived)
        // 归一化后只保留一份，放大比率可观测
        assertTrue(acc.rawCharsReceived.toDouble() / acc.text.length > 9.0)
    }

    @Test
    fun truncationKeepsHeadForRoundTrip() {
        // 回传约束：截断保留头部（思考语义在开头），reasoning 非空。
        val acc = DeltaAccumulator(Semantic.INCREMENTAL, maxChars = 10)
        acc.accept("头部思考内容在前，尾部被截断的部分在后")
        assertTrue(acc.isTruncated)
        assertTrue(acc.text.startsWith("头部思考内容在前"))
        assertTrue(acc.text.isNotEmpty())
    }
}
