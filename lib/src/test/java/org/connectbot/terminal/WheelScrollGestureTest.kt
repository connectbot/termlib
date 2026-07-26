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

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * End-to-end tests for routing a scroll gesture to the running application when
 * it has enabled mouse tracking, rather than to the terminal's own scrollback.
 *
 * The two cases have to stay distinct: an application that never asked for the
 * mouse must keep the local scrolling behaviour it has always had.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1200dp-xhdpi")
class WheelScrollGestureTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val output = StringBuilder()

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.window.setLayout(1000, 1200)
        }
    }

    private companion object {
        /** Longer than WAIT_FOR_SECOND_TOUCH_MS, so a move counts as a scroll. */
        const val GRACE_PERIOD_MS = 100L

        const val DRAG_STEPS = 4
        const val DRAG_STEP_MS = 16L
        const val DRAG_STEP_PX = 100f
    }

    /** The mouse-tracking sequence Claude Code's flicker-free renderer sends. */
    private val enableMouseTracking = "\u001B[?1000h\u001B[?1002h\u001B[?1003h\u001B[?1006h"

    private fun emulatorWithContent(): TerminalEmulator {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { output.append(String(it, Charsets.ISO_8859_1)) },
        )
        val content = (1..100).joinToString("\r\n") { "Line $it" }
        emulator.writeInput(content.toByteArray())
        (emulator as? TerminalEmulatorImpl)?.processPendingUpdates()
        return emulator
    }

    /** Count of wheel reports in the output, ignoring any motion reports. */
    private fun wheelReports(): Int = Regex("\u001B\\[<6[4-7];").findAll(output.toString()).count()

    /** Count of left-button press/release pairs in the output. */
    private fun clickReports(): Int = Regex("\u001B\\[<0;\\d+;\\d+M" + "\u001B\\[<0;\\d+;\\d+m").findAll(output.toString()).count()

    /**
     * Tap once in the middle of the terminal.
     *
     * Event time is advanced well past the double-tap timeout first, so
     * consecutive calls stay separate taps rather than becoming a word
     * selection, and the touch is released quickly enough not to become a long
     * press.
     */
    private fun tap() {
        composeTestRule.onRoot().performTouchInput {
            advanceEventTime(GRACE_PERIOD_MS * 10)
            down(0, center)
            advanceEventTime(DRAG_STEP_MS)
            up(0)
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000)
        composeTestRule.waitForIdle()
    }

    private fun showTerminal(emulator: TerminalEmulator): ScrollController {
        var scrollController: ScrollController? = null
        composeTestRule.setContent {
            TerminalWithAccessibility(
                terminalEmulator = emulator,
                modifier = Modifier.size(800.dp, 1200.dp),
                onScrollControllerAvailable = { scrollController = it },
            )
        }

        composeTestRule.waitForIdle()
        (emulator as? TerminalEmulatorImpl)?.processPendingUpdates()
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil { scrollController != null }
        return scrollController!!
    }

    /**
     * Drag downwards, which scrolls back towards earlier output.
     *
     * Several moves, not one: the local scroll path anchors its offset at the
     * moment the gesture is classified, so it only travels on moves after that
     * point.
     */
    private fun dragDown(sample: () -> Int = { 0 }): DragSamples {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onRoot().performTouchInput { down(0, center) }
        // Past the multi-touch grace period, so the move is taken as a scroll.
        composeTestRule.onRoot().performTouchInput { advanceEventTime(GRACE_PERIOD_MS) }
        composeTestRule.mainClock.advanceTimeBy(GRACE_PERIOD_MS)

        // Event time, not just the frame clock, has to advance across the moves:
        // it is what the velocity tracker reads, and a fling needs real velocity.
        repeat(DRAG_STEPS) { step ->
            composeTestRule.onRoot().performTouchInput {
                advanceEventTime(DRAG_STEP_MS)
                moveTo(0, center + Offset(0f, DRAG_STEP_PX * (step + 1)))
            }
            composeTestRule.mainClock.advanceTimeBy(DRAG_STEP_MS)
            composeTestRule.waitForIdle()
        }

        // Sampled before the finger lifts, so the fling cannot contribute.
        val afterDrag = sample()

        composeTestRule.onRoot().performTouchInput { up(0) }

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000)
        composeTestRule.waitForIdle()

        return DragSamples(afterDrag = afterDrag, afterFling = sample())
    }

    /**
     * A measurement taken at the end of the drag and again once the fling settles.
     *
     * The two are equal in practice here: injected touch input does not carry
     * enough velocity through this harness for a decay animation to run, on
     * either the wheel path or the local one. Sampling at the end of the drag is
     * still what makes these tests specific — without it, reports produced only
     * by a fling would be indistinguishable from reports produced by the drag.
     *
     * That the fling is invisible here is why WheelScroller owns its decay: it
     * can then be driven directly from a test frame clock, which is what
     * WheelScrollerTest does. This harness covers the routing decision; it is
     * not the place the fling itself gets tested.
     */
    private data class DragSamples(val afterDrag: Int, val afterFling: Int)

    @Test
    fun testScrollGoesToApplicationWhenTrackingEnabled() {
        val emulator = emulatorWithContent()
        emulator.writeInput(enableMouseTracking.toByteArray())
        (emulator as? TerminalEmulatorImpl)?.processPendingUpdates()

        val controller = showTerminal(emulator)
        val initialPosition = controller.scrollbackPosition
        output.setLength(0)

        val reports = dragDown { wheelReports() }

        assertTrue(
            "Expected wheel reports from the drag itself, got: " +
                output.toString().replace("\u001B", "ESC"),
            reports.afterDrag > 0,
        )
        assertEquals(
            "Local scrollback must not move; the application owns the viewport",
            initialPosition,
            controller.scrollbackPosition,
        )
    }

    @Test
    fun testDragDownReportsWheelUp() {
        val emulator = emulatorWithContent()
        emulator.writeInput(enableMouseTracking.toByteArray())
        (emulator as? TerminalEmulatorImpl)?.processPendingUpdates()

        showTerminal(emulator)
        output.setLength(0)

        dragDown()

        // Dragging down reveals earlier output, which is a wheel-up.
        val text = output.toString()
        assertTrue("expected wheel-up reports", text.contains("\u001B[<64;"))
        assertTrue("expected no wheel-down reports", !text.contains("\u001B[<65;"))
    }

    @Test
    fun testScrollStaysLocalWhenTrackingDisabled() {
        val emulator = emulatorWithContent()

        val controller = showTerminal(emulator)
        val initialPosition = controller.scrollbackPosition
        output.setLength(0)

        val lines = dragDown { controller.scrollbackPosition }

        assertEquals("no mouse reports without tracking", 0, wheelReports())
        assertTrue(
            "the drag itself should scroll, not just the fling",
            lines.afterDrag > initialPosition,
        )
        assertTrue(
            "local scrollback should have moved (initial=$initialPosition, " +
                "current=${controller.scrollbackPosition})",
            controller.scrollbackPosition > initialPosition,
        )
    }

    @Test
    fun testTapReportsAClickWhenTrackingEnabled() {
        val emulator = emulatorWithContent()
        emulator.writeInput(enableMouseTracking.toByteArray())
        (emulator as? TerminalEmulatorImpl)?.processPendingUpdates()

        showTerminal(emulator)
        output.setLength(0)

        tap()

        assertEquals(
            "a tap should reach the application as one complete click, got: " +
                output.toString().replace("\u001B", "ESC"),
            1,
            clickReports(),
        )
    }

    @Test
    fun testTapStaysLocalWhenTrackingDisabled() {
        val emulator = emulatorWithContent()

        showTerminal(emulator)
        output.setLength(0)

        tap()

        assertEquals("no mouse reports without tracking", 0, clickReports())
        assertEquals("", output.toString())
    }

    @Test
    fun testTapDoesNotLeaveAButtonHeld() {
        // Every press the application sees has to be followed by its release, or
        // it spends the rest of the session believing the button is down.
        val emulator = emulatorWithContent()
        emulator.writeInput(enableMouseTracking.toByteArray())
        (emulator as? TerminalEmulatorImpl)?.processPendingUpdates()

        showTerminal(emulator)
        output.setLength(0)

        repeat(3) { tap() }

        val presses = Regex("\u001B\\[<0;\\d+;\\d+M").findAll(output.toString()).count()
        val releases = Regex("\u001B\\[<0;\\d+;\\d+m").findAll(output.toString()).count()
        assertEquals("every press is released", presses, releases)
        assertEquals(3, presses)
    }
}
