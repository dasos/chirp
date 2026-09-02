package com.chirp.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SttTurnWindowTest {

    private val win = SttTurnWindow(startedAt = 0L, silenceTimeoutMs = 4_000L)

    @Test
    fun `expires only after the window since start with no voice`() {
        assertFalse(win.hasVoice)
        assertFalse(win.expired(3_999L))
        assertTrue(win.expired(4_000L))
    }

    @Test
    fun `voice resets the window`() {
        win.onVoice(2_000L)
        assertFalse(win.expired(5_999L)) // window measured from voice, not start
        assertTrue(win.expired(6_000L))
    }

    @Test
    fun `segments stitch and count as voice`() {
        win.addSegment("hello", at = 500L)
        assertEquals("hello", win.accumulated)
        val acc = win.addSegment("world", at = 3_000L)
        assertEquals("hello world", acc)
        assertTrue(win.hasVoice)
        assertFalse(win.expired(6_999L)) // voice at 3s -> expires at 7s
        assertTrue(win.expired(7_000L))
    }

    @Test
    fun `blank segments are ignored and do not reset the window`() {
        win.onVoice(1_000L)
        win.addSegment("   ", at = 3_000L)
        assertEquals("", win.accumulated)
        assertTrue(win.expired(5_000L)) // still measured from 1s
    }
}