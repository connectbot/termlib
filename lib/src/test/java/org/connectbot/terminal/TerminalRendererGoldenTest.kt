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

import android.graphics.Typeface
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w520dp-h320dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalRendererGoldenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun terminalRenderingMatchesGoldenImage() {
        ShadowLog.stream = System.out
        val emulator = TerminalEmulatorFactory.create(initialRows = GOLDEN_ROWS, initialCols = GOLDEN_COLS)
        emulator.writeInput(goldenTerminalContent().toByteArray(Charsets.UTF_8))
        (emulator as TerminalEmulatorImpl).processPendingUpdates()

        var selectionController: SelectionController? = null
        compose.setContent {
            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.requiredSize(520.dp, 320.dp),
                typeface = Typeface.MONOSPACE,
                initialFontSize = 16.sp,
                backgroundColor = Color.Black,
                foregroundColor = Color.White,
                forcedSize = GOLDEN_ROWS to GOLDEN_COLS,
                onSelectionControllerAvailable = { selectionController = it },
            )
        }
        // Capture after layout, the terminal command queue, and selection have settled.
        compose.waitForTerminalIdle(emulator)
        compose.runOnIdle {
            val controller = checkNotNull(selectionController)
            controller.startSelection(SelectionMode.CHARACTER)
            repeat(18) { controller.moveSelectionRight() }
            repeat(2) { controller.moveSelectionDown() }
            repeat(8) { controller.moveSelectionRight() }
            assertTrue(controller.isSelectionActive)
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(filePath = "src/test/roborazzi/terminal-renderer-golden.png")
    }

    private fun goldenTerminalContent(): String = buildString {
        append("\u001B[2J\u001B[H\u001B[?12l\u001B[2 q")
        append("Plain ")
        append("\u001B[31mRed\u001B[0m ")
        append("\u001B[32;44mGreenOnBlue\u001B[0m ")
        append("\u001B[1mBold\u001B[0m\r\n")
        append("\u001B[3mItalic\u001B[0m ")
        append("\u001B[9mStrike\u001B[0m ")
        append("\u001B[7mReverse\u001B[0m ")
        append("\u001B[38;5;196m256Red\u001B[0m\r\n")
        append("\u001B[4:1mSingle underline\u001B[0m\r\n")
        append("\u001B[4:2mDouble underline\u001B[0m\r\n")
        append("\u001B[4:3mCurly underline\u001B[0m\r\n")
        append("Combining: e\u0301 a\u0308 n\u0303  Wide: 表語\r\n")
        append("\u001B[48;2;36;54;90m\u001B[38;2;255;210;90mRGB fg/bg sample\u001B[0m\r\n")
        append("\u001B]8;;https://example.com\u0007OSC8 hyperlink\u001B]8;;\u0007 plain\r\n")
        append("Box: \u250c\u2500\u252c\u2500\u2510 \u2502 \u2514\u2500\u2534\u2500\u2518\r\n")
        append("Emoji: ⚠⚠︎⚠️🎉👍🏽\r\n")
        append("Joined:🏳️‍🌈👨‍👩‍👧‍👦👩🏽‍❤️‍💋‍👨🏻\r\n")
        append("Flags: 🇺🇸🇯🇵 Keycaps: 1️⃣ 2️⃣\r\n")
        append("\u001B[3;44mfff WWW\u001B[41m   \u001B[0m italic overhang\u001B[H")
    }

    private companion object {
        const val GOLDEN_ROWS = 14
        const val GOLDEN_COLS = 48
    }
}
