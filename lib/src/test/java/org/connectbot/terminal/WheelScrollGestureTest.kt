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
     * Two moves, not one: the local scroll path anchors its offset at the moment
     * the gesture is classified, so it only travels on moves after that point.
     */
    private fun dragDown() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onRoot().performTouchInput { down(0, center) }
        // Past the multi-touch grace period, so the move is taken as a scroll.
        composeTestRule.mainClock.advanceTimeBy(100)

        composeTestRule.onRoot().performTouchInput {
            moveTo(0, center + Offset(0f, 200f))
        }
        composeTestRule.mainClock.advanceTimeBy(100)
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().performTouchInput {
            moveTo(0, center + Offset(0f, 400f))
        }
        composeTestRule.mainClock.advanceTimeBy(100)
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().performTouchInput { up(0) }

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000)
        composeTestRule.waitForIdle()
    }

    @Test
    fun testScrollGoesToApplicationWhenTrackingEnabled() {
        val emulator = emulatorWithContent()
        emulator.writeInput(enableMouseTracking.toByteArray())
        (emulator as? TerminalEmulatorImpl)?.processPendingUpdates()

        val controller = showTerminal(emulator)
        val initialPosition = controller.scrollbackPosition
        output.setLength(0)

        dragDown()

        assertTrue(
            "Expected wheel reports, got: ${output.toString().replace("\u001B", "ESC")}",
            wheelReports() > 0,
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

        dragDown()

        assertEquals("no mouse reports without tracking", 0, wheelReports())
        assertTrue(
            "local scrollback should have moved (initial=$initialPosition, " +
                "current=${controller.scrollbackPosition})",
            controller.scrollbackPosition > initialPosition,
        )
    }
}
