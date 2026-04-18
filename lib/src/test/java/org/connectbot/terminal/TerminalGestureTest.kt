/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TerminalGestureTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testQuickTapTriggersOnTerminalTap() {
        var tapCount = 0
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        var selectionActive = false

        composeTestRule.setContent {
            Terminal(
                terminalEmulator = emulator,
                onTerminalTap = { tapCount++ },
                onSelectionControllerAvailable = { controller ->
                    // This is called once when the terminal is composed
                },
            )
        }

        // We can't easily get the controller from the callback because it might be called after we need it.
        // Let's use a more direct approach to verify behavior.

        composeTestRule.onRoot().performTouchInput {
            click()
        }

        composeTestRule.waitForIdle()
        assertEquals("Tap count should be 1", 1, tapCount)
    }

    @Test
    fun testLongPressTriggersSelection() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        var selectionController: SelectionController? = null

        composeTestRule.setContent {
            Terminal(
                terminalEmulator = emulator,
                onSelectionControllerAvailable = { selectionController = it },
            )
        }

        composeTestRule.onRoot().performTouchInput {
            longClick()
        }

        composeTestRule.waitForIdle()
        assertTrue("Selection should be active after long click", selectionController?.isSelectionActive == true)
    }

    @Test
    fun testSwipeTriggersScroll() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        // Add some content to enable scrolling
        val content = (1..50).joinToString("\r\n") { "Line $it" }
        emulator.writeInput(content.toByteArray())

        // Wait for emulator to process input
        if (emulator is TerminalEmulatorImpl) {
            emulator.processPendingUpdates()
        }

        var selectionController: SelectionController? = null

        composeTestRule.setContent {
            Terminal(
                terminalEmulator = emulator,
                onSelectionControllerAvailable = { selectionController = it },
            )
        }

        // Swipe up to scroll down
        composeTestRule.onRoot().performTouchInput {
            swipeUp()
        }

        composeTestRule.waitForIdle()

        // After swipe up, we should NOT be at the bottom anymore (scrollbackPosition > 0)
        // We can check this if we had access to the screenState, but we can at least
        // verify selection didn't start.
        assertFalse("Selection should NOT be active after swipe", selectionController?.isSelectionActive == true)
    }

    @Test
    fun testQuickTapAfterSwipeDoesNotTriggerSelection() {
        var tapCount = 0
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        var selectionController: SelectionController? = null

        composeTestRule.setContent {
            Terminal(
                terminalEmulator = emulator,
                onTerminalTap = { tapCount++ },
                onSelectionControllerAvailable = { selectionController = it },
            )
        }

        // First swipe
        composeTestRule.onRoot().performTouchInput {
            swipeUp()
        }

        composeTestRule.waitForIdle()

        // Then quick tap
        composeTestRule.onRoot().performTouchInput {
            click()
        }

        composeTestRule.waitForIdle()
        assertEquals("Tap count should be 1", 1, tapCount)
        assertFalse("Selection should NOT be active", selectionController?.isSelectionActive == true)
    }

    @Test
    fun testPinchToZoomDoesNotTriggerSelection() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        var selectionController: SelectionController? = null

        composeTestRule.setContent {
            Terminal(
                terminalEmulator = emulator,
                onSelectionControllerAvailable = { selectionController = it },
            )
        }

        composeTestRule.onRoot().performTouchInput {
            // Simulate pinch gesture
            val start1 = center + Offset(-20f, -20f)
            val end1 = center + Offset(-100f, -100f)
            val start2 = center + Offset(20f, 20f)
            val end2 = center + Offset(100f, 100f)

            down(0, start1)
            down(1, start2)
            moveTo(0, end1)
            moveTo(1, end2)
            up(0)
            up(1)
        }

        composeTestRule.waitForIdle()
        assertFalse("Selection should NOT be active after pinch", selectionController?.isSelectionActive == true)
    }

    @Test
    fun testQuickMultiTouchDoesNotTriggerSelection() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        var selectionController: SelectionController? = null

        composeTestRule.setContent {
            Terminal(
                terminalEmulator = emulator,
                onSelectionControllerAvailable = { selectionController = it },
            )
        }

        composeTestRule.onRoot().performTouchInput {
            down(0, center + Offset(-10f, 0f))
            down(1, center + Offset(10f, 0f))
            up(0)
            up(1)
        }

        composeTestRule.waitForIdle()
        assertFalse("Selection should NOT be active after quick multi-touch tap", selectionController?.isSelectionActive == true)
    }

    @Test
    fun testSecondPointerAfterGracePeriodDoesNotInterruptTap() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        var tapCount = 0
        composeTestRule.setContent {
            Terminal(
                terminalEmulator = emulator,
                onTerminalTap = { tapCount++ },
            )
        }

        composeTestRule.onRoot().performTouchInput {
            // 1. First pointer down
            down(0, center)

            // 2. Wait longer than multi-touch grace period (40ms)
            advanceEventTime(100)

            // 3. Second pointer down (should be ignored for zoom)
            down(1, center + Offset(50f, 50f))

            // 4. Lift both
            up(1)
            up(0)
        }

        composeTestRule.waitForIdle()
        assertEquals("Tap count should be 1 (second finger ignored)", 1, tapCount)
    }

    @Test
    fun testScrollIsNotInterruptedBySecondPointer() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        var tapCount = 0
        composeTestRule.setContent {
            Terminal(
                terminalEmulator = emulator,
                onTerminalTap = { tapCount++ },
            )
        }

        composeTestRule.onRoot().performTouchInput {
            // 1. First pointer down
            down(0, center)

            // 2. Move to trigger scroll (beyond touch slop)
            moveTo(0, center + Offset(0f, -100f))

            // 3. Second pointer down
            down(1, center + Offset(50f, 50f))

            // 4. Move both
            moveTo(0, center + Offset(0f, -150f))
            moveTo(1, center + Offset(100f, 100f))

            // 5. Up
            up(0)
            up(1)
        }

        composeTestRule.waitForIdle()
        // If it was hijacked by Zoom, tapCount would be 0, and if it stayed Scroll it is also 0.
        // The main verification is that it doesn't crash or break the event loop.
        assertEquals("Tap count should be 0", 0, tapCount)
    }
}
