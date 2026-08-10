/*
 * ConnectBot Terminal
 * Copyright 2026 Termlib contributors
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

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.MonotonicFrameClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        /** Detents one fling may report, mirroring MAX_DETENTS_PER_FLING. */
        const val FLING_DETENT_CAP = 200

        /** A wheel-up report at the anchor cell, in 1-based SGR coordinates. */
        const val UP = "\u001B[<64;8;6M"

        /** A wheel-down report at the anchor cell. */
        const val DOWN = "\u001B[<65;8;6M"
    }

    /**
     * A frame clock that runs animation frames back to back without waiting for
     * a real one.
     *
     * A decay animation is driven by whatever [MonotonicFrameClock] is in the
     * coroutine context, so supplying one makes a fling completely deterministic
     * and as fast as the arithmetic — no Compose harness, no injected velocity
     * that the device might not honour, and no dependence on how many frames a
     * real device would have managed to draw.
     */
    private class ImmediateFrameClock(
        private val frameNanos: Long,
        private val maxFrames: Int = 10_000,
    ) : MonotonicFrameClock {
        private var now = 0L
        private var frames = 0

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            check(++frames <= maxFrames) { "animation did not settle within $maxFrames frames" }
            now += frameNanos
            return onFrame(now)
        }
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

    /** How many times [report] appears in what has been sent so far. */
    private fun countOf(report: String): Int = Regex(Regex.escape(report)).findAll(reported()).count()

    /** Run [WheelScroller.fling] to completion on a deterministic frame clock. */
    private fun fling(
        scroller: WheelScroller,
        velocityPx: Float,
        frameNanos: Long = 16_000_000L,
    ) = runBlocking {
        withContext(ImmediateFrameClock(frameNanos)) {
            scroller.fling(
                initialVelocityPx = velocityPx,
                decaySpec = exponentialDecay(frictionMultiplier = 1f, absVelocityThreshold = 1f),
            )
        }
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
    // Fling
    // -----------------------------------------------------------------------

    @Test
    fun testFlingReportsInTheDirectionOfTravel() {
        val scroller = scroller()

        fling(scroller, velocityPx = 4000f)

        // A downward fling reveals earlier output, so it reports wheel up only.
        assertTrue("expected wheel-up reports", countOf(UP) > 0)
        assertEquals("no reports in the opposite direction", 0, countOf(DOWN))
    }

    @Test
    fun testFlingUpwardsReportsWheelDown() {
        val scroller = scroller()

        fling(scroller, velocityPx = -4000f)

        assertTrue("expected wheel-down reports", countOf(DOWN) > 0)
        assertEquals("no reports in the opposite direction", 0, countOf(UP))
    }

    @Test
    fun testFlingDistanceFollowsVelocity() {
        val gentle = scroller()
        fling(gentle, velocityPx = 1000f)
        val gentleReports = countOf(UP)

        output.setLength(0)
        val hard = scroller()
        fling(hard, velocityPx = 8000f)

        assertTrue(
            "a harder fling should travel further (gentle=$gentleReports, hard=${countOf(UP)})",
            countOf(UP) > gentleReports,
        )
    }

    @Test
    fun testFlingIsBoundedByTheDetentCap() {
        // Unlike the local path there is no scrollback to run out of, so an
        // enormous velocity has to stop somewhere.
        val scroller = scroller()

        fling(scroller, velocityPx = 5_000_000f)

        assertEquals("detents permitted by a single fling", FLING_DETENT_CAP, countOf(UP))
    }

    @Test
    fun testFlingDistanceDoesNotDependOnFrameRate() {
        // The per-sample limit caps how many detents leave in one frame. If that
        // limit dropped the excess, a device drawing half as many frames would
        // scroll a fling half as far. Carrying the excess is what makes these
        // two runs agree.
        val smooth = scroller()
        fling(smooth, velocityPx = 6000f, frameNanos = 8_000_000L)
        val smoothReports = countOf(UP)

        output.setLength(0)
        val janky = scroller()
        fling(janky, velocityPx = 6000f, frameNanos = 48_000_000L)

        assertEquals("same fling, fewer frames", smoothReports, countOf(UP))
    }

    @Test
    fun testFlingSendsNothingWhileTrackingIsOff() {
        val untracked = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { output.append(String(it, Charsets.ISO_8859_1)) },
        )

        fling(WheelScroller(untracked, LINE_HEIGHT, ANCHOR_ROW, ANCHOR_COL), velocityPx = 4000f)

        assertEquals("", reported())
    }

    @Test
    fun testNonFiniteFlingVelocityIsIgnored() {
        val scroller = scroller()

        fling(scroller, velocityPx = Float.NaN)

        assertEquals("", reported())
    }
}
