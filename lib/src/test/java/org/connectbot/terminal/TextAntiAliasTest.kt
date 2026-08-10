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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tests for the textAntiAlias parameter of [Terminal]: with anti-aliasing
 * disabled, white-on-black text must render using only pure black and pure
 * white pixels (no gray blend pixels at glyph edges) — the rendering wanted
 * on e-ink / 1-bit displays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h300dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextAntiAliasTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /** Emulator showing text with the cursor hidden (the block cursor draws with alpha). */
    private fun newEmulator(): TerminalEmulatorImpl {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 20)
        val impl = emulator as TerminalEmulatorImpl
        // Some glyphs with diagonal strokes, then DECTCEM reset to hide the cursor.
        emulator.writeInput("WAXY mow\u001B[?25l".toByteArray())
        impl.processPendingUpdates()
        assertFalse("cursor should be hidden", impl.snapshot.value.cursorVisible)
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
    fun antiAliasDisabledRendersOnlyPureColors() {
        val impl = newEmulator()

        composeTestRule.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Terminal(
                    terminalEmulator = impl,
                    modifier = Modifier.size(200.dp, 100.dp),
                    textAntiAlias = false,
                )
            }
        }
        composeTestRule.waitForIdle()

        // Exactly two colors: the background and the glyph color. Any third
        // color would be an anti-aliased blend pixel at a glyph edge.
        val colors = capturePixels().distinct().map { "%08X".format(it) }.sorted()
        assertEquals("expected only background + glyph colors, got $colors", 2, colors.size)
    }

    @Test
    fun antiAliasEnabledRendersBlendedEdges() {
        val impl = newEmulator()

        composeTestRule.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Terminal(
                    terminalEmulator = impl,
                    modifier = Modifier.size(200.dp, 100.dp),
                )
            }
        }
        composeTestRule.waitForIdle()

        // Background + glyph color + blend pixels at glyph edges.
        val colors = capturePixels().distinct()
        assertTrue(
            "expected anti-aliased blend pixels at glyph edges, got ${colors.size} colors",
            colors.size > 2,
        )
    }
}
