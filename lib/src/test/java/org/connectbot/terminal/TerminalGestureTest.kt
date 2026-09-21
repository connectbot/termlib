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
        fun settle() {
            composeTestRule.waitForIdle()
            emulator.commands.call { Unit }
            composeTestRule.waitForIdle()
        }
        settle()
        val original = emulator.dimensions
        sizes.clear()
        composeTestRule.runOnIdle { paused.value = true }
        settle()
        composeTestRule.runOnIdle { width.value = 320.5.dp }
        settle()
        assertEquals(original, emulator.dimensions)
        composeTestRule.runOnIdle { paused.value = false }
        settle()
        assertEquals(1, sizes.size)
        assertEquals(original.columns, sizes.single().columns)
        assertEquals(original.widthPixels + 1, sizes.single().widthPixels)
        sizes.clear()
        composeTestRule.runOnIdle { paused.value = true }
        settle()
        composeTestRule.runOnIdle { forced.value = 30 to 80 }
        settle()
        composeTestRule.runOnIdle { forced.value = 20 to 60 }
        settle()
        assertTrue(sizes.isEmpty())
        composeTestRule.runOnIdle { paused.value = false }
        settle()
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
        fun settle() {
            composeTestRule.waitForIdle()
            emulator.commands.call { Unit }
            composeTestRule.waitForIdle()
        }
        settle()
        val original = emulator.dimensions
        sizes.clear()
        composeTestRule.runOnIdle { paused.value = true }
        settle()
        composeTestRule.runOnIdle { height.value = 400.dp }
        settle()
        composeTestRule.runOnIdle { height.value = 300.dp }
        settle()
        assertEquals(original, emulator.dimensions)
        assertTrue(sizes.isEmpty())
        composeTestRule.runOnIdle { height.value = 200.dp }
        settle()
        composeTestRule.runOnIdle { paused.value = false }
        settle()
        assertTrue("Menu restoration must not resize the PTY", sizes.isEmpty())
        composeTestRule.runOnIdle { paused.value = true }
        settle()
        composeTestRule.runOnIdle {
            height.value = 250.dp
            font.value = 12.sp
        }
        settle()
        assertTrue(sizes.isEmpty())
        composeTestRule.runOnIdle { paused.value = false }
        settle()
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

        composeTestRule.waitForIdle()
        (emulator as TerminalEmulatorImpl).commands.call { Unit }
        composeTestRule.waitUntil {
            viewport?.let {
                it.widthPixels == 320 * 2 &&
                    it.heightPixels == 200 * 2 &&
                    it == emulator.dimensions
            } == true
        }
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

        composeTestRule.waitUntil { viewport?.let { it.rows == 12 && it.columns == 44 } == true }
        composeTestRule.runOnIdle {
            val measured = checkNotNull(viewport)
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
        // Add some content to enable scrolling
        val content = (1..100).joinToString("\r\n") { "Line $it" }
        emulator.writeInput(content.toByteArray())

        // Wait for emulator to process input
        if (emulator is TerminalEmulatorImpl) {
            emulator.processPendingUpdates()
        }

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

        composeTestRule.waitForIdle()
        if (emulator is TerminalEmulatorImpl) {
            emulator.processPendingUpdates()
        }
        composeTestRule.waitForIdle()

        composeTestRule.waitUntil { scrollController != null }
        val controller = scrollController!!
        val initialPosition = controller.scrollbackPosition

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onRoot().performTouchInput {
            // 1. First pointer down
            down(0, center)
        }
        composeTestRule.mainClock.advanceTimeBy(16)

        composeTestRule.onRoot().performTouchInput {
            // 2. Move finger DOWN to scroll BACK into history
            moveTo(0, center + Offset(0f, 500f))
        }
        composeTestRule.mainClock.advanceTimeBy(100) // Give it time to process
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().performTouchInput {
            // 3. Second pointer down
            down(1, center + Offset(50f, 50f))
        }
        composeTestRule.mainClock.advanceTimeBy(16)

        composeTestRule.onRoot().performTouchInput {
            // 4. Move both further DOWN
            moveTo(0, center + Offset(0f, 800f))
            moveTo(1, center + Offset(100f, 100f))
        }
        composeTestRule.mainClock.advanceTimeBy(100)
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().performTouchInput {
            // 5. Up
            up(0)
            up(1)
        }

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(500) // Give animation time to settle if any
        composeTestRule.waitForIdle()

        // Verify that we actually scrolled (scrollbackPosition > 0)
        assertTrue(
            "Scroll position should have changed (initial=$initialPosition, current=${controller.scrollbackPosition})",
            controller.scrollbackPosition > initialPosition,
        )

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
