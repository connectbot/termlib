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
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1000dp-h1200dp-xhdpi")
class TerminalPageGestureTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val pages = mutableListOf<Int>()
    private lateinit var selection: SelectionController
    private lateinit var scroll: ScrollController
    private lateinit var emulator: TerminalEmulatorImpl

    private fun showTerminal(paging: Boolean = true) {
        emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80) as TerminalEmulatorImpl
        composeTestRule.setContent {
            TerminalWithAccessibility(
                terminalEmulator = emulator,
                modifier = Modifier.size(600.dp, 800.dp),
                onPageGesture = if (paging) ({ pages.add(it) }) else null,
                onSelectionControllerAvailable = { selection = it },
                onScrollControllerAvailable = { scroll = it },
            )
        }
        composeTestRule.waitForTerminalIdle(emulator)
        emulator.writeInput((1..200).joinToString("\r\n") { "Line $it" }.toByteArray())
        composeTestRule.waitForTerminalIdle(emulator)
        composeTestRule.waitUntil { scroll.maxScrollback > 0 }
    }

    @Test
    fun pageThresholdCarriesRemainderAndSupportsDirectionChanges() {
        showTerminal()
        val row = emulator.imageStore.cellHeight.toFloat()
        composeTestRule.onRoot().performTouchInput {
            val start = Offset(width * 0.1f, height * 0.2f)
            down(start)
            advanceEventTime(100)
            moveTo(start + Offset(0f, row * 4f))
        }
        composeTestRule.waitForIdle()
        assertTrue(pages.isEmpty())
        composeTestRule.onRoot().performTouchInput {
            moveTo(Offset(width * 0.1f, height * 0.2f + row * 6f))
        }
        composeTestRule.waitForIdle()
        assertEquals(listOf(VTermKey.PAGEUP), pages)
        composeTestRule.onRoot().performTouchInput {
            moveTo(Offset(width * 0.1f, height * 0.2f + row * 11f))
        }
        composeTestRule.waitForIdle()
        assertEquals(listOf(VTermKey.PAGEUP, VTermKey.PAGEUP), pages)
        composeTestRule.onRoot().performTouchInput {
            moveTo(Offset(width * 0.1f, height * 0.2f + row * 4f))
            up()
        }
        composeTestRule.waitForIdle()
        assertEquals(listOf(VTermKey.PAGEUP, VTermKey.PAGEUP, VTermKey.PAGEDOWN), pages)
        assertEquals(0, scroll.scrollbackPosition)
    }

    @Test
    fun horizontalDragInPagingStripDoesNotPage() {
        showTerminal()
        composeTestRule.onRoot().performTouchInput {
            swipe(Offset(width * 0.1f, height * 0.5f), Offset(width * 0.8f, height * 0.5f))
        }
        composeTestRule.waitForIdle()
        assertTrue(pages.isEmpty())
    }

    @Test
    fun upwardDragPagesDownWithoutScrollingOrSelecting() {
        showTerminal()
        composeTestRule.onRoot().performTouchInput {
            swipe(Offset(width * 0.1f, height * 0.8f), Offset(width * 0.1f, height * 0.2f), durationMillis = 1000)
        }
        composeTestRule.waitForIdle()
        assertTrue(pages.size > 1)
        assertTrue(pages.all { it == VTermKey.PAGEDOWN })
        assertEquals(0, scroll.scrollbackPosition)
        assertFalse(selection.isSelectionActive)
    }

    @Test
    fun downwardDragPagesUpWithoutScrolling() {
        showTerminal()
        composeTestRule.onRoot().performTouchInput {
            swipe(Offset(width * 0.1f, height * 0.2f), Offset(width * 0.1f, height * 0.8f))
        }
        composeTestRule.waitForIdle()
        assertTrue(pages.isNotEmpty())
        assertTrue(pages.all { it == VTermKey.PAGEUP })
        assertEquals(0, scroll.scrollbackPosition)
    }

    @Test
    fun rightSideKeepsScrollback() {
        showTerminal()
        composeTestRule.onRoot().performTouchInput {
            swipe(Offset(width * 0.8f, height * 0.2f), Offset(width * 0.8f, height * 0.8f))
        }
        composeTestRule.waitForIdle()
        assertTrue(pages.isEmpty())
        assertTrue(scroll.scrollbackPosition > 0)
    }

    @Test
    fun nullCallbackKeepsScrollbackInLeftThird() {
        showTerminal(paging = false)
        composeTestRule.onRoot().performTouchInput {
            swipe(Offset(width * 0.1f, height * 0.2f), Offset(width * 0.1f, height * 0.8f))
        }
        composeTestRule.waitForIdle()
        assertTrue(scroll.scrollbackPosition > 0)
    }

    @Test
    fun longPressAndSelectionDragDoNotPage() {
        showTerminal()
        composeTestRule.onRoot().performTouchInput {
            longClick(Offset(width * 0.1f, height * 0.5f))
        }
        composeTestRule.waitForIdle()
        assertTrue(selection.isSelectionActive)
        composeTestRule.onRoot().performTouchInput {
            swipe(Offset(width * 0.1f, height * 0.5f), Offset(width * 0.1f, height * 0.8f))
        }
        composeTestRule.waitForIdle()
        assertTrue(pages.isEmpty())
        assertTrue(selection.isSelectionActive)
    }

    @Test
    fun doubleTapInPagingStripKeepsWordSelection() {
        showTerminal()
        composeTestRule.onRoot().performTouchInput {
            doubleClick(Offset(width * 0.1f, height * 0.5f))
        }
        composeTestRule.waitForIdle()
        assertTrue(selection.isSelectionActive)
        assertTrue(pages.isEmpty())
    }

    @Test
    fun pinchStartingInPagingStripDoesNotPage() {
        showTerminal()
        composeTestRule.onRoot().performTouchInput {
            down(0, Offset(width * 0.1f, height * 0.4f))
            down(1, Offset(width * 0.2f, height * 0.6f))
            moveTo(0, Offset(width * 0.1f, height * 0.1f))
            moveTo(1, Offset(width * 0.2f, height * 0.9f))
            up(0)
            up(1)
        }
        composeTestRule.waitForIdle()
        assertTrue(pages.isEmpty())
        assertFalse(selection.isSelectionActive)
    }

    @Test
    fun secondFingerStopsAnExistingPageGesture() {
        showTerminal()
        composeTestRule.onRoot().performTouchInput {
            down(0, Offset(width * 0.1f, height * 0.2f))
            advanceEventTime(100)
            moveTo(0, Offset(width * 0.1f, height * 0.4f))
        }
        composeTestRule.waitForIdle()
        val count = pages.size
        assertTrue(count > 0)
        composeTestRule.onRoot().performTouchInput {
            down(1, Offset(width * 0.2f, height * 0.5f))
            moveTo(0, Offset(width * 0.1f, height * 0.6f))
            up(1)
            moveTo(0, Offset(width * 0.1f, height * 0.8f))
            up(0)
        }
        composeTestRule.waitForIdle()
        assertEquals(count, pages.size)
        assertEquals(0, scroll.scrollbackPosition)
    }
}
