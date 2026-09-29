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

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1200dp-xhdpi")
class TerminalGestureTest {
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun suspensionIncludesPixelOnlyAndForcedSizeChanges() {
        val paused = mutableStateOf(false)
        val width = mutableStateOf(320.dp)
        val forced = mutableStateOf<Pair<Int, Int>?>(null)
        val sizes = CopyOnWriteArrayList<TerminalDimensions>()
        val emulator = TerminalEmulatorFactory.create(onResize = { sizes.add(it) }) as TerminalEmulatorImpl
        composeTestRule.setContent {
            Terminal(emulator, Modifier.size(width.value, 200.dp), forcedSize = forced.value, resizeSuspended = paused.value)
        }
        composeTestRule.waitForTerminalIdle(emulator)
        val original = emulator.dimensions
        sizes.clear()
        composeTestRule.runOnIdle { paused.value = true }
        composeTestRule.waitForTerminalIdle(emulator)
        composeTestRule.runOnIdle { width.value = 320.5.dp }
        composeTestRule.waitForTerminalIdle(emulator)
        assertEquals(original, emulator.dimensions)
        composeTestRule.runOnIdle { paused.value = false }
        composeTestRule.waitForTerminalIdle(emulator)
        assertEquals(1, sizes.size)
        assertEquals(original.columns, sizes.single().columns)
        assertEquals(original.widthPixels + 1, sizes.single().widthPixels)
        sizes.clear()
        composeTestRule.runOnIdle { paused.value = true }
        composeTestRule.waitForTerminalIdle(emulator)
        composeTestRule.runOnIdle { forced.value = 30 to 80 }
        composeTestRule.waitForTerminalIdle(emulator)
        composeTestRule.runOnIdle { forced.value = 20 to 60 }
        composeTestRule.waitForTerminalIdle(emulator)
        assertTrue(sizes.isEmpty())
        composeTestRule.runOnIdle { paused.value = false }
        composeTestRule.waitForTerminalIdle(emulator)
        assertEquals(1, sizes.size)
        assertEquals(20, sizes.single().rows)
        assertEquals(60, sizes.single().columns)
    }

    @Test
    fun suspendedResizeDiscardsIntermediateLayoutsAndCoalescesFinalSize() {
        val paused = mutableStateOf(false)
        val height = mutableStateOf(200.dp)
        val font = mutableStateOf(11.sp)
        val sizes = CopyOnWriteArrayList<TerminalDimensions>()
        val emulator = TerminalEmulatorFactory.create(onResize = { sizes.add(it) }) as TerminalEmulatorImpl
        composeTestRule.setContent {
            Terminal(emulator, Modifier.size(320.dp, height.value), initialFontSize = font.value, resizeSuspended = paused.value)
        }
        composeTestRule.waitForTerminalIdle(emulator)
        val original = emulator.dimensions
        sizes.clear()
        composeTestRule.runOnIdle { paused.value = true }
        composeTestRule.waitForTerminalIdle(emulator)
        composeTestRule.runOnIdle { height.value = 400.dp }
        composeTestRule.waitForTerminalIdle(emulator)
        composeTestRule.runOnIdle { height.value = 300.dp }
        composeTestRule.waitForTerminalIdle(emulator)
        assertEquals(original, emulator.dimensions)
        assertTrue(sizes.isEmpty())
        composeTestRule.runOnIdle { height.value = 200.dp }
        composeTestRule.waitForTerminalIdle(emulator)
        composeTestRule.runOnIdle { paused.value = false }
        composeTestRule.waitForTerminalIdle(emulator)
        assertTrue("Menu restoration must not resize the PTY", sizes.isEmpty())
        composeTestRule.runOnIdle { paused.value = true }
        composeTestRule.waitForTerminalIdle(emulator)
        composeTestRule.runOnIdle {
            height.value = 250.dp
            font.value = 12.sp
        }
        composeTestRule.waitForTerminalIdle(emulator)
        assertTrue(sizes.isEmpty())
        composeTestRule.runOnIdle { paused.value = false }
        composeTestRule.waitForTerminalIdle(emulator)
        assertEquals("Apply only the final viewport and font", 1, sizes.size)
        assertEquals(500, sizes.single().heightPixels)
    }

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.window.setLayout(1000, 1200)
        }
    }

    @Test
    fun testQuickTapTriggersOnTerminalTap() {
        var tapCount = 0
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)

        composeTestRule.setContent {
            Terminal(
                terminalEmulator = emulator,
                onTerminalTap = { tapCount++ },
            )
        }

        composeTestRule.onRoot().performTouchInput {
            click()
        }

        composeTestRule.waitForIdle()
        assertEquals("Tap count should be 1", 1, tapCount)
    }

    @Test
    fun reportsComposeViewportInCharactersAndPixels() {
        var viewport: TerminalDimensions? = null
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onResize = { viewport = it },
        )

        composeTestRule.setContent {
            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.size(width = 320.dp, height = 200.dp),
            )
        }

        composeTestRule.waitForTerminalIdle(emulator)
        composeTestRule.runOnIdle {
            val measured = checkNotNull(viewport)
            assertEquals(320 * 2, measured.widthPixels)
            assertEquals(200 * 2, measured.heightPixels)
            assertEquals(emulator.dimensions, measured)
        }
    }

    @Test
    fun reportsForcedTerminalContentSizeInsteadOfItsParent() {
        var viewport: TerminalDimensions? = null
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onResize = { viewport = it },
        )

        composeTestRule.setContent {
            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.size(width = 600.dp, height = 400.dp),
                forcedSize = 12 to 44,
            )
        }

        composeTestRule.waitForTerminalIdle(emulator)
        composeTestRule.runOnIdle {
            val measured = checkNotNull(viewport)
            assertEquals(12, measured.rows)
            assertEquals(44, measured.columns)
            assertTrue(measured.widthPixels in 1 until 1200)
            assertTrue(measured.heightPixels in 1 until 800)
        }
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

        composeTestRule.waitUntil { selectionController != null }

        composeTestRule.onRoot().performTouchInput {
            longClick()
        }

        composeTestRule.waitForIdle()
        assertTrue("Selection should be active after long click", selectionController?.isSelectionActive == true)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun settledSelectionSurvivesLateFingerWobble() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 12, initialCols = 30) as TerminalEmulatorImpl
        emulator.writeInput("ABCDEFGHIJKLMNOPQRSTUVWXYZABCD".toByteArray())
        emulator.processPendingUpdates()
        var controller: SelectionController? = null
        composeTestRule.setContent {
            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.size(400.dp, 300.dp),
                forcedSize = 12 to 30,
                onSelectionControllerAvailable = { controller = it },
            )
        }
        composeTestRule.waitUntil { controller != null }
        composeTestRule.waitUntil { emulator.dimensions.widthPixels > 0 && emulator.dimensions.heightPixels > 0 }
        composeTestRule.waitForIdle()
        val cellWidth = emulator.dimensions.widthPixels / 30f
        val cellHeight = emulator.dimensions.heightPixels / 12f
        assertTrue("Terminal must have measured cells: width=$cellWidth height=$cellHeight", cellWidth > 1f && cellHeight > 1f)
        fun point(col: Int) = Offset((col + 0.5f) * cellWidth, cellHeight / 2f)

        // Keep this release-filter test at the center, away from edge reach adjustments.
        composeTestRule.onRoot().performTouchInput { down(point(14)) }
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().performTouchInput { moveTo(point(15)) }
        composeTestRule.mainClock.advanceTimeBy(400)
        composeTestRule.onRoot().performTouchInput {
            moveTo(point(22))
            up()
        }

        composeTestRule.waitForIdle()
        assertEquals("OP", controller!!.copySelection())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun heldSelectionScrollsIntoHistoryAndBackTowardLiveScreen() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 12, initialCols = 30) as TerminalEmulatorImpl
        emulator.writeInput((1..50).joinToString("\r\n") { "line $it" }.toByteArray())
        emulator.processPendingUpdates()
        var selectionController: SelectionController? = null
        var scrollController: ScrollController? = null
        composeTestRule.setContent {
            TerminalWithAccessibility(
                terminalEmulator = emulator,
                modifier = Modifier.size(400.dp, 300.dp),
                forcedSize = 12 to 30,
                onSelectionControllerAvailable = { selectionController = it },
                onScrollControllerAvailable = { scrollController = it },
            )
        }
        composeTestRule.waitUntil { selectionController != null && scrollController != null }
        composeTestRule.waitForTerminalIdle(emulator)
        assertTrue(scrollController!!.maxScrollback > 0)

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onRoot().performTouchInput { down(Offset(100f, 300f)) }
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.onRoot().performTouchInput { moveTo(Offset(100f, 4f)) }
        composeTestRule.mainClock.advanceTimeBy(600)
        val historyPosition = scrollController.scrollbackPosition
        assertTrue("Holding at the top should scroll into history", historyPosition > 0)

        composeTestRule.onRoot().performTouchInput { moveTo(Offset(100f, 596f)) }
        composeTestRule.mainClock.advanceTimeBy(600)
        assertTrue("Holding at the bottom should scroll toward live output", scrollController.scrollbackPosition < historyPosition)
        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        assertTrue(selectionController!!.isSelectionActive)

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onRoot().performTouchInput { down(Offset(600f, 280f)) }
        composeTestRule.mainClock.advanceTimeBy(16)
        composeTestRule.onRoot().performTouchInput { moveTo(Offset(600f, 400f)) }
        composeTestRule.mainClock.advanceTimeBy(16)
        composeTestRule.onRoot().performTouchInput { moveTo(Offset(600f, 450f)) }
        composeTestRule.mainClock.advanceTimeBy(100)
        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        assertTrue("Manual viewport scrolling should retain the selection", selectionController.isSelectionActive)
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

        composeTestRule.waitUntil { selectionController != null }

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

        composeTestRule.waitUntil { selectionController != null }

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

        composeTestRule.waitUntil { selectionController != null }

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

        composeTestRule.waitUntil { selectionController != null }

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
        var scrollController: ScrollController? = null
        composeTestRule.setContent {
            TerminalWithAccessibility(
                terminalEmulator = emulator,
                modifier = Modifier.size(800.dp, 1200.dp),
                onTerminalTap = { tapCount++ },
                onScrollControllerAvailable = { scrollController = it },
            )
        }

        // Populate scrollback only after the initial asynchronous resize has settled.
        composeTestRule.waitForTerminalIdle(emulator)
        val content = (1..200).joinToString("\r\n") { "Line $it" }
        emulator.writeInput(content.toByteArray())
        composeTestRule.waitForTerminalIdle(emulator)

        // Snapshot publication uses a separate, frame-throttled dispatcher. Draining
        // commands and Compose does not guarantee the scrollback is visible yet.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            (scrollController?.maxScrollback ?: 0) > 0
        }
        val controller = scrollController!!
        val initialPosition = controller.scrollbackPosition
        assertTrue("Test requires scrollback content", controller.maxScrollback > initialPosition)

        composeTestRule.onRoot().performTouchInput {
            // Keep the primary pointer inside the viewport throughout the gesture.
            down(0, Offset(center.x, height * 0.1f))
            // Event time, rather than the animation clock, controls the zoom grace period.
            advanceEventTime(100)
            moveTo(0, Offset(center.x, height * 0.2f))
            // The first move crosses slop; the next move scrolls the content.
            moveTo(0, Offset(center.x, height * 0.4f))
        }
        composeTestRule.waitForIdle()
        val positionBeforeSecondPointer = controller.scrollbackPosition
        assertTrue(
            "Scroll should start before the second pointer arrives",
            positionBeforeSecondPointer > initialPosition,
        )
        assertTrue(
            "Test requires room to continue scrolling",
            positionBeforeSecondPointer < controller.maxScrollback,
        )

        composeTestRule.onRoot().performTouchInput {
            down(1, Offset(center.x + 50f, height * 0.4f))
            moveTo(0, Offset(center.x, height * 0.7f))
            moveTo(1, Offset(center.x + 50f, height * 0.7f))
        }
        composeTestRule.waitForIdle()
        assertTrue(
            "Scroll should continue after the second pointer arrives",
            controller.scrollbackPosition > positionBeforeSecondPointer,
        )

        // The assertion above checks the active drag, before any release fling.
        composeTestRule.onRoot().performTouchInput {
            up(1)
            up(0)
        }
        composeTestRule.waitForIdle()

        assertEquals("Tap count should be 0", 0, tapCount)
    }

    @Test
    fun testDoubleTapTriggersWordSelection() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        // Add a word to the terminal
        emulator.writeInput("Hello world\r\n".toByteArray())
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

        composeTestRule.waitUntil { selectionController != null }

        // Both taps in one block with controlled timing so the gap is always within
        // doubleTapTimeoutMillis and the test is deterministic on slow CI runners.
        composeTestRule.onRoot().performTouchInput {
            click(Offset(10f, 10f))
            advanceEventTime(100)
            click(Offset(12f, 12f))
        }

        composeTestRule.waitForIdle()
        assertTrue("Selection should be active after double tap", selectionController?.isSelectionActive == true)
    }
}
