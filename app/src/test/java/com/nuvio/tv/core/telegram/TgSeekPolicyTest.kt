// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.core.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TgSeekPolicyTest {

    private val mb = 1024L * 1024L
    private val total = 2_000L * mb

    @Test
    fun `small file is covered by single window (linear emerges)`() {
        val w = TgSeekPolicy.windowFor(
            pos = 0L, totalSize = 100L * mb, freeBytes = 400L * mb,
            backKept = 16L * mb, minAhead = 48L * mb, maxAhead = 192L * mb
        )
        assertEquals(0L, w.start)
        assertEquals(100L * mb, w.endExclusive)
    }

    @Test
    fun `window keeps back and adaptive ahead`() {
        val pos = 500L * mb
        val w = TgSeekPolicy.windowFor(
            pos = pos, totalSize = total, freeBytes = 400L * mb,
            backKept = 16L * mb, minAhead = 48L * mb, maxAhead = 192L * mb
        )
        assertEquals(pos - 16L * mb, w.start)
        // free/4 = 100MB ahead
        assertEquals(pos + 100L * mb, w.endExclusive)
    }

    @Test
    fun `window ahead clamps to min and max`() {
        val low = TgSeekPolicy.windowFor(500L * mb, total, 10L * mb)
        assertEquals(500L * mb + 48L * mb, low.endExclusive)
        val high = TgSeekPolicy.windowFor(500L * mb, total, 8_000L * mb)
        assertEquals(500L * mb + 192L * mb, high.endExclusive)
    }

    @Test
    fun `window clamps to file end`() {
        val w = TgSeekPolicy.windowFor(total - 10L * mb, total, 8_000L * mb)
        assertEquals(total, w.endExclusive)
    }

    @Test
    fun `null cursor always needs emission`() {
        assertTrue(TgSeekPolicy.needsReposition(0L, null))
    }

    @Test
    fun `small seeks inside window never reposition`() {
        val cursor = TgSeekPolicy.Window(484L * mb, 600L * mb)
        // +10s (~20MB) and -10s inside window+hysteresis
        assertFalse(TgSeekPolicy.needsReposition(504L * mb, cursor))
        assertFalse(TgSeekPolicy.needsReposition(490L * mb, cursor))
        // inside hysteresis past the end: no emission
        assertFalse(TgSeekPolicy.needsReposition(600L * mb + 20L * mb, cursor))
    }

    @Test
    fun `big jump outside hysteresis repositions once`() {
        val cursor = TgSeekPolicy.Window(484L * mb, 600L * mb)
        assertTrue(TgSeekPolicy.needsReposition(600L * mb + 64L * mb, cursor))
        // backward beyond kept back
        assertTrue(TgSeekPolicy.needsReposition(100L * mb, cursor))
    }

    @Test
    fun `moov-at-end pattern costs exactly three cursors`() {
        // head window -> tail probe -> back to head: each step repositions once.
        val head = TgSeekPolicy.windowFor(0L, total, 400L * mb)
        val tailPos = total - 2L * mb
        assertTrue(TgSeekPolicy.needsReposition(tailPos, head))
        val tail = TgSeekPolicy.tailProbeWindow(total)
        assertEquals(total - 4L * mb, tail.start)
        assertEquals(total, tail.endExclusive)
        assertTrue(TgSeekPolicy.needsReposition(0L, tail))
        // …and then it is stable: revisits need nothing.
        val headAgain = TgSeekPolicy.windowFor(0L, total, 400L * mb)
        assertFalse(TgSeekPolicy.needsReposition(4L * mb, headAgain))
    }

    @Test
    fun `tail region detection`() {
        assertTrue(TgSeekPolicy.isTailRegion(total - 1L * mb, total))
        assertFalse(TgSeekPolicy.isTailRegion(total / 2, total))
    }

    @Test
    fun `brake only under the floor with hysteresis on resume`() {
        assertTrue(TgSeekPolicy.shouldBrake(63L * mb))
        assertFalse(TgSeekPolicy.shouldBrake(64L * mb))
        assertFalse(TgSeekPolicy.shouldBrake(0L))
        assertFalse(TgSeekPolicy.shouldBrake(-1L))
        assertTrue(TgSeekPolicy.shouldResume(150L * mb))
        assertFalse(TgSeekPolicy.shouldResume(149L * mb))
    }

    @Test
    fun `rotate only with low free and big temp`() {
        assertTrue(TgSeekPolicy.shouldRotate(100L * mb, 400L * mb))
        assertFalse(TgSeekPolicy.shouldRotate(200L * mb, 900L * mb))
        assertFalse(TgSeekPolicy.shouldRotate(100L * mb, 100L * mb))
        assertFalse(TgSeekPolicy.shouldRotate(0L, 900L * mb))
    }

    @Test
    fun `speculative opens never move an existing cursor`() {
        // Primera emisión: siempre.
        assertTrue(TgSeekPolicy.mayReposition(isOpenPhase = true, hasCursor = false, nowMs = 10_000L, lastIssueMs = 0L))
        // Opens con cursor: nunca (sniff head/tail/mid).
        assertFalse(TgSeekPolicy.mayReposition(isOpenPhase = true, hasCursor = true, nowMs = 10_000L, lastIssueMs = 0L))
        // Lector sostenido: respeta cooldown.
        assertFalse(TgSeekPolicy.mayReposition(isOpenPhase = false, hasCursor = true, nowMs = 10_000L, lastIssueMs = 9_000L))
        assertTrue(TgSeekPolicy.mayReposition(isOpenPhase = false, hasCursor = true, nowMs = 13_000L, lastIssueMs = 9_000L))
    }
}
