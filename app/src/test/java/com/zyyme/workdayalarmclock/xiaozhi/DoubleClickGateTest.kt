package com.zyyme.workdayalarmclock.xiaozhi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleClickGateTest {

    private fun gate() = DoubleClickGate(windowMs = 300L)

    @Test
    fun disabledModuleKeepsInstantSingleClick() {
        val g = gate()
        assertEquals(DoubleClickGate.DownResult.NONE, g.onDown(0L, enabled = false))
        assertEquals(DoubleClickGate.UpResult.IMMEDIATE, g.onUp(80L, enabled = false, wasLongPress = false))
        assertFalse(g.isSinglePending())
    }

    @Test
    fun enabledModuleDefersSingleClick() {
        val g = gate()
        assertEquals(DoubleClickGate.DownResult.NONE, g.onDown(0L, enabled = true))
        assertEquals(DoubleClickGate.UpResult.DEFER, g.onUp(80L, enabled = true, wasLongPress = false))
        assertTrue(g.isSinglePending())
        assertTrue(g.consumeSingle())
        assertFalse(g.isSinglePending())
    }

    @Test
    fun secondPressWithinWindowIsDoubleClick() {
        val g = gate()
        g.onDown(0L, true)
        g.onUp(80L, true, false)
        assertEquals(DoubleClickGate.DownResult.DOUBLE, g.onDown(200L, true))
        // 双击后 pending 单击作废
        assertFalse(g.isSinglePending())
        // 第二击抬起被吞掉，避免再触发一次播放/暂停
        assertEquals(DoubleClickGate.UpResult.IGNORE, g.onUp(280L, true, false))
    }

    @Test
    fun slowSecondPressIsNotDoubleClick() {
        val g = gate()
        g.onDown(0L, true)
        g.onUp(80L, true, false)
        assertEquals(DoubleClickGate.DownResult.NONE, g.onDown(500L, true))
        assertEquals(DoubleClickGate.UpResult.DEFER, g.onUp(560L, true, false))
    }

    @Test
    fun longPressNeverBecomesDoubleClick() {
        val g = gate()
        g.onDown(0L, true)
        assertEquals(DoubleClickGate.UpResult.IMMEDIATE, g.onUp(950L, true, wasLongPress = true))
        assertFalse(g.isSinglePending())
        // 长按结束后立刻再按一下，不该被判成双击
        assertEquals(DoubleClickGate.DownResult.NONE, g.onDown(1000L, true))
    }

    @Test
    fun longPressAfterFirstClickCancelsPendingClick() {
        val g = gate()
        g.onDown(0L, true)
        g.onUp(60L, true, false)
        // 第二击按住不放 -> 走长按，不应触发单击
        g.onDown(120L, true)
        assertFalse(g.isSinglePending())
    }

    @Test
    fun resetClearsState() {
        val g = gate()
        g.onDown(0L, true)
        g.onUp(50L, true, false)
        g.reset()
        assertFalse(g.isSinglePending())
        assertEquals(DoubleClickGate.DownResult.NONE, g.onDown(100L, true))
    }
}
