/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for turning continuous gesture travel into discrete wheel detents.
 *
 * These drive a real emulator with SGR mouse tracking enabled and assert on the
 * bytes it would write back, so they cover the conversion and the encoding
 * together.
 */
@RunWith(AndroidJUnit4::class)
class WheelScrollerTest {

    private companion object {
        const val LINE_HEIGHT = 20f
        const val ANCHOR_ROW = 5
        const val ANCHOR_COL = 7

        /** A wheel-up report at the anchor cell, in 1-based SGR coordinates. */
        const val UP = "\u001B[<64;8;6M"

        /** A wheel-down report at the anchor cell. */
        const val DOWN = "\u001B[<65;8;6M"
    }

    private val output = StringBuilder()

    /** The emulator behind the most recent [scroller], for tests that drive it directly. */
    private lateinit var emulator: TerminalEmulator

    private fun scroller(): WheelScroller {
        emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { output.append(String(it, Charsets.ISO_8859_1)) },
        )
        emulator.writeInput("\u001B[?1000h\u001B[?1006h".toByteArray())
        drain()
        output.setLength(0)

        return WheelScroller(
            emulator = emulator,
            lineHeightPx = LINE_HEIGHT,
            anchorRow = ANCHOR_ROW,
            anchorCol = ANCHOR_COL,
        )
    }

    /** Output is posted to the Looper, so let it settle before asserting. */
    private fun drain() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    private fun reported(): String {
        drain()
        return output.toString()
    }

    @Test
    fun testOneDetentPerLineOfTravel() {
        val scroller = scroller()

        scroller.scrollBy(LINE_HEIGHT)

        assertEquals(UP, reported())
    }

    @Test
    fun testFingerDownScrollsBackIntoHistory() {
        // Dragging down reveals earlier output, which is what a wheel-up does.
        val scroller = scroller()

        scroller.scrollBy(LINE_HEIGHT * 2)

        assertEquals(UP.repeat(2), reported())
    }

    @Test
    fun testFingerUpScrollsForward() {
        val scroller = scroller()

        scroller.scrollBy(-LINE_HEIGHT * 3)

        assertEquals(DOWN.repeat(3), reported())
    }

    @Test
    fun testTravelBelowOneDetentIsHeld() {
        val scroller = scroller()

        scroller.scrollBy(LINE_HEIGHT / 2)

        assertEquals("", reported())
    }

    @Test
    fun testHeldTravelAccumulatesIntoADetent() {
        val scroller = scroller()

        scroller.scrollBy(LINE_HEIGHT * 0.6f)
        scroller.scrollBy(LINE_HEIGHT * 0.6f)

        // 1.2 line heights of travel is one detent, with the remainder held.
        assertEquals(UP, reported())
    }

    @Test
    fun testReversalCancelsHeldTravel() {
        val scroller = scroller()

        scroller.scrollBy(LINE_HEIGHT * 0.75f)
        scroller.scrollBy(-LINE_HEIGHT * 1.5f)

        // Net travel is -0.75 of a line: not yet a detent in either direction.
        assertEquals("", reported())
    }

    @Test
    fun testBurstIsRateLimited() {
        val scroller = scroller()

        // A fast fling can cover this much between two animation frames.
        scroller.scrollBy(LINE_HEIGHT * 40)

        assertEquals("capped at 8 detents", UP.repeat(8), reported())
    }

    @Test
    fun testRateLimitDoesNotBacklog() {
        val scroller = scroller()

        // The dropped detents are gone, not queued: a following sample of one
        // line reports exactly one detent.
        scroller.scrollBy(LINE_HEIGHT * 40)
        drain()
        output.setLength(0)
        scroller.scrollBy(LINE_HEIGHT)

        assertEquals(UP, reported())
    }

    @Test
    fun testNonFiniteTravelIsIgnored() {
        val scroller = scroller()

        scroller.scrollBy(Float.NaN)
        scroller.scrollBy(Float.POSITIVE_INFINITY)
        scroller.scrollBy(LINE_HEIGHT)

        assertEquals("a bad sample must not poison the accumulator", UP, reported())
    }

    @Test
    fun testNothingIsSentWhileTrackingIsOff() {
        val untracked = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { output.append(String(it, Charsets.ISO_8859_1)) },
        )
        val scroller = WheelScroller(untracked, LINE_HEIGHT, ANCHOR_ROW, ANCHOR_COL)

        scroller.scrollBy(LINE_HEIGHT * 5)

        assertEquals("", reported())
    }

    @Test
    fun testTrackingDisabledMidGestureStopsReports() {
        // An application can drop mouse tracking while a finger is still down.
        // The scroller keeps converting travel, but nothing may reach the wire.
        val scroller = scroller()

        scroller.scrollBy(LINE_HEIGHT)
        drain()
        assertEquals("before DECRST", UP, output.toString())

        emulator.writeInput("\u001B[?1000l".toByteArray())
        drain()
        output.setLength(0)
        scroller.scrollBy(LINE_HEIGHT * 3)

        assertEquals("", reported())
    }

    // -----------------------------------------------------------------------
    // Fling bound
    // -----------------------------------------------------------------------

    @Test
    fun testMaxFlingTravelConvertsToTheDetentCap() {
        // The bound handed to the fling animation has to mean what it says: the
        // travel it permits is exactly the detent cap, fed one detent at a time
        // so the per-sample rate limit does not mask it.
        val scroller = scroller()

        var reports = 0
        var travelled = 0f
        while (travelled < scroller.maxFlingTravelPx) {
            scroller.scrollBy(LINE_HEIGHT)
            travelled += LINE_HEIGHT
            drain()
            reports += Regex(Regex.escape(UP)).findAll(output.toString()).count()
            output.setLength(0)
        }

        assertEquals("detents permitted by the fling bound", 200, reports)
    }
}
