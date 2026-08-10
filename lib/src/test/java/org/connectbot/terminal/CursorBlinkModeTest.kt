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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tests for the cursorBlinkMode parameter of [Terminal]: with
 * [CursorBlinkMode.Never] the cursor stays solid even when the terminal
 * program requests a blinking cursor, so an idle terminal produces no
 * repaints (important on e-ink displays).
 *
 * Blink is observed by capturing the rendered output before and after
 * advancing the compose clock past the blink half-period.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h300dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CursorBlinkModeTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /** Emulator with some text and an explicitly blink-enabled cursor. */
    private fun newBlinkingEmulator(): TerminalEmulatorImpl {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 20)
        val impl = emulator as TerminalEmulatorImpl
        // "CSI ? 12 h" (start cursor blink) plus DECSCUSR "CSI 1 SP q"
        // (blinking block) — either alone should enable blink.
        emulator.writeInput("hello\u001B[?12h\u001B[1 q".toByteArray())
        impl.processPendingUpdates()
        assertTrue("terminal program requested blink", impl.snapshot.value.cursorBlink)
        return impl
    }

    private fun capturePixels(): IntArray {
        var pixels: IntArray? = null
        composeTestRule.activityRule.scenario.onActivity { activity ->
            val content = activity.findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
            content.draw(Canvas(bitmap))
            val px = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels = px
        }
        return pixels!!
    }

    @Test
    fun terminalModeBlinksWhenProgramRequestsIt() {
        val impl = newBlinkingEmulator()

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Terminal(
                    terminalEmulator = impl,
                    modifier = Modifier.size(200.dp, 100.dp),
                    cursorBlinkMode = CursorBlinkMode.Terminal,
                )
            }
        }

        composeTestRule.mainClock.advanceTimeBy(100)
        val before = capturePixels()
        // One blink half-period later the cursor has toggled off.
        composeTestRule.mainClock.advanceTimeBy(500)
        val after = capturePixels()

        assertFalse("cursor should have blinked", before.contentEquals(after))
    }

    @Test
    fun neverModeKeepsCursorSolid() {
        val impl = newBlinkingEmulator()

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Terminal(
                    terminalEmulator = impl,
                    modifier = Modifier.size(200.dp, 100.dp),
                    cursorBlinkMode = CursorBlinkMode.Never,
                )
            }
        }

        composeTestRule.mainClock.advanceTimeBy(100)
        val before = capturePixels()
        // Advance across several would-be blink periods.
        composeTestRule.mainClock.advanceTimeBy(1600)
        val after = capturePixels()

        assertTrue("cursor should not blink", before.contentEquals(after))
    }
}
