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
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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
class TerminalMouseWheelTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val output = StringBuilder()
    private lateinit var scroll: ScrollController
    private lateinit var emulator: TerminalEmulatorImpl

    private fun createEmulator() {
        emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { output.append(it.toString(Charsets.US_ASCII)) },
        ) as TerminalEmulatorImpl
    }

    private fun write(text: String) {
        emulator.writeInput(text.toByteArray())
        composeTestRule.waitForTerminalIdle(emulator)
    }

    private fun showTerminal() {
        createEmulator()
        composeTestRule.setContent {
            TerminalWithAccessibility(
                terminalEmulator = emulator,
                modifier = Modifier.size(600.dp, 800.dp),
                onScrollControllerAvailable = { scroll = it },
            )
        }
        composeTestRule.waitForTerminalIdle(emulator)
        // Local scrollback exists, so a drag that scrolled locally would be observable.
        write((1..200).joinToString("\r\n") { "Line $it" })
        composeTestRule.waitUntil { scroll.maxScrollback > 0 }
    }

    private fun wheel(code: Int, col: Int, row: Int) = "\u001b[<$code;$col;${row}M"

    @Test
    fun wheelStepsUseTheApplicationsMouseProtocol() {
        createEmulator()
        write("\u001b[?1000h\u001b[?1006h")
        emulator.dispatchMouseWheel(row = 2, col = 5, up = true)
        emulator.dispatchMouseWheel(row = 2, col = 5, up = false)
        composeTestRule.waitForTerminalIdle(emulator)
        assertEquals(wheel(64, 6, 3) + wheel(65, 6, 3), output.toString())
    }

    @Test
    fun wheelStepsAreSilentWithoutMouseTracking() {
        createEmulator()
        emulator.dispatchMouseWheel(row = 2, col = 5, up = true)
        composeTestRule.waitForTerminalIdle(emulator)
        assertEquals("", output.toString())
    }

    @Test
    fun dragSendsOneWheelStepPerRowAtTheTouchedCellWithoutScrollingLocally() {
        showTerminal()
        write("\u001b[?1000h\u001b[?1006h")
        val rowHeight = emulator.imageStore.cellHeight.toFloat()
        val colWidth = emulator.imageStore.cellWidth.toFloat()
        // Cell (row 3, col 7) zero-based, reported one-based.
        val start = Offset(colWidth * 7.5f, rowHeight * 3.5f)
        val up = wheel(64, 8, 4)
        val down = wheel(65, 8, 4)

        composeTestRule.onRoot().performTouchInput {
            down(start)
            advanceEventTime(100)
            moveTo(start + Offset(0f, rowHeight * 4.5f))
        }
        composeTestRule.waitForTerminalIdle(emulator)
        assertEquals(up.repeat(4), output.toString())

        // Net steps track whole rows of travel, so moving back to 2.5 rows sends two steps down.
        composeTestRule.onRoot().performTouchInput {
            moveTo(start + Offset(0f, rowHeight * 2.5f))
        }
        composeTestRule.waitForTerminalIdle(emulator)
        assertEquals(up.repeat(4) + down.repeat(2), output.toString())

        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.waitForTerminalIdle(emulator)
        assertEquals(0, scroll.scrollbackPosition)
    }

    @Test
    fun flingKeepsSendingWheelSteps() {
        showTerminal()
        write("\u001b[?1000h\u001b[?1006h")
        val rowHeight = emulator.imageStore.cellHeight.toFloat()
        val distanceRows = 10
        composeTestRule.onRoot().performTouchInput {
            val start = Offset(width * 0.5f, height * 0.2f)
            swipe(start, start + Offset(0f, rowHeight * distanceRows), durationMillis = 50)
        }
        composeTestRule.waitForTerminalIdle(emulator)
        val steps = Regex("\u001b\\[<64;\\d+;\\d+M").findAll(output).count()
        assertTrue("expected fling beyond $distanceRows dragged rows, got $steps", steps > distanceRows)
        assertEquals(0, scroll.scrollbackPosition)
    }

    @Test
    fun disablingMouseTrackingRestoresLocalScrollback() {
        showTerminal()
        write("\u001b[?1000h\u001b[?1006h\u001b[?1000l")
        composeTestRule.onRoot().performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.2f), Offset(width * 0.5f, height * 0.8f))
        }
        composeTestRule.waitForTerminalIdle(emulator)
        assertEquals("", output.toString())
        assertTrue(scroll.scrollbackPosition > 0)
    }
}
