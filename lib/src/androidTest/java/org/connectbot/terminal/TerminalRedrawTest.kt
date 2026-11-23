/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TerminalRedrawTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun captureWindow(): Bitmap {
        if (Build.VERSION.SDK_INT >= 26) return compose.onRoot().captureToImage().asAndroidBitmap()
        // Compose's Window PixelCopy overload starts at API 26. UiAutomation can
        // still capture the actual hardware-rendered window on our minimum API.
        compose.waitForIdle()
        val wasDrawingEnabled = HardwareRendererCompat.isDrawingEnabled()
        try {
            // Match Compose's PixelCopy helper: instrumentation may suppress HWUI draws.
            HardwareRendererCompat.setDrawingEnabled(true)
            val frames = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                compose.activity.window.decorView.invalidate()
                val choreographer = Choreographer.getInstance()
                choreographer.postFrameCallback {
                    choreographer.postFrameCallback { frames.countDown() }
                }
            }
            assertTrue("Window did not present a frame", frames.await(5, TimeUnit.SECONDS))
            val bounds = compose.onRoot().fetchSemanticsNode().boundsInWindow
            val screen = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            return Bitmap.createBitmap(screen, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
        } finally {
            HardwareRendererCompat.setDrawingEnabled(wasDrawingEnabled)
        }
    }

    private fun terminal(text: String): TerminalEmulatorImpl = (TerminalEmulatorFactory.create(initialRows = 5, initialCols = 24) as TerminalEmulatorImpl).apply {
        writeInput(("\u001B[?25l" + text).toByteArray())
        processPendingUpdates()
    }

    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun positionedAsciiMatchesScalarTextInHardwareWindow() {
        val optimized = mutableStateOf(true)
        val paint = TerminalTextPaint(Typeface.MONOSPACE, 21f)
        val scalar = TextPaint(paint)
        val width = paint.measureText("M") + 0.25f
        val line = terminal("AVffi.,0123 xyz!").use { it.snapshot.value.lines.first() }
        compose.setContent {
            Canvas(Modifier.size(240.dp, 60.dp)) {
                assertTrue(drawContext.canvas.nativeCanvas.isHardwareAccelerated)
                val textPaint = if (optimized.value) paint else scalar
                drawLine(line, 0, width, 40f, 27.5f, textPaint, Paint(), Color.White, Color.Black, null)
            }
        }
        val actual = captureWindow()
        compose.runOnIdle { optimized.value = false }
        val expected = captureWindow()
        assertTrue("Reference must contain text", (0 until 200).any { x -> (0 until 30).any { y -> expected.getPixel(x, y) != expected.getPixel(0, 0) } })
        assertTrue("Positioned glyphs changed hardware window pixels", actual.sameAs(expected))
    }

    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun recordedShapedGlyphsMatchDirectDrawInHardwareWindow() {
        val recorded = mutableStateOf(true)
        val paint = TerminalTextPaint(Typeface.MONOSPACE, 23f)
        val direct = TextPaint(paint)
        val width = paint.measureText("M") + 0.25f
        val cells = terminal("سلام क्षि မြန်မာ").use { it.snapshot.value.lines.first().cells }
        val layout = checkNotNull(paint.layout(cells, width))
        compose.setContent {
            Canvas(Modifier.size(260.dp, 90.dp)) {
                drawRect(Color.Black)
                val canvas = drawContext.canvas.nativeCanvas
                assertTrue(canvas.isHardwareAccelerated)
                val selectedPaint = if (recorded.value) paint else direct
                layout.prepareDraw(canvas)
                for (baseline in listOf(27.5f, 53.75f)) {
                    for (column in cells.indices) {
                        if (cells.width(column) == 0) continue
                        selectedPaint.color = if (column % 2 == 0) android.graphics.Color.WHITE else android.graphics.Color.CYAN
                        layout.drawCell(canvas, column, baseline, selectedPaint, width, cells.width(column))
                    }
                }
            }
        }
        val actual = captureWindow()
        compose.runOnIdle { recorded.value = false }
        val expected = captureWindow()
        assertTrue("Reference must contain text", (0 until 200).any { x -> (0 until 65).any { y -> expected.getPixel(x, y) != expected.getPixel(0, 0) } })
        assertTrue("Recorded glyphs changed hardware window pixels", actual.sameAs(expected))
    }

    @Test
    fun cursorAndSelectionInvertRetainedWindowPixelsWithoutTrails() {
        val terminal = terminal("\u001B[?12l\u001B[2 q\u001B[32;44m█ colored 表 🎉\u001B[H")
        var selection: SelectionController? = null
        compose.setContent {
            Terminal(terminal, forcedSize = 5 to 24, keyboardEnabled = false, onSelectionControllerAvailable = { selection = it })
        }
        compose.waitForTerminalIdle(terminal)
        fun capture() = captureWindow()
        fun input(text: String) {
            compose.runOnIdle {
                terminal.writeInput(text.toByteArray())
                terminal.processPendingUpdates()
            }
            compose.waitForTerminalIdle(terminal)
        }
        val original = capture()
        input("\u001B[?25h")
        val cursor = capture()
        if (original.sameAs(cursor)) {
            val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            for ((name, bitmap) in listOf("original" to original, "cursor" to cursor)) {
                File(cache, "inversion-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        assertFalse("Cursor must change colored pixels", original.sameAs(cursor))
        compose.runOnIdle { selection!!.startSelection(SelectionMode.CHARACTER) }
        assertTrue("Cursor and selection should cancel on the same cell", original.sameAs(capture()))
        input("\u001B[?25l")
        assertTrue("Selection alone should match cursor alone", cursor.sameAs(capture()))
        compose.runOnIdle { selection!!.clearSelection() }
        assertTrue("Clearing selection must restore the original frame", original.sameAs(capture()))
        input("\u001B[?25h\u001B[1;2H")
        assertFalse(original.sameAs(capture()))
        input("\u001B[?25l")
        assertTrue("Moving and hiding the cursor must leave no trail", original.sameAs(capture()))
    }

    @Test
    fun retainedRowsClearOldInkAndMatchFreshScreens() {
        val current = mutableStateOf(terminal(""))
        compose.setContent {
            key(current.value) {
                Terminal(current.value, forcedSize = 5 to 24, keyboardEnabled = false)
            }
        }
        val cases = listOf(
            "\u001B[3;44mffff WWWW\u001B[41m   " to "\u001B[2J\u001B[H",
            "👨‍👩‍👧‍👦⚠️表\r\nold" to "\u001B[H\u001B[0mplain\u001B[K",
            "⚠️👨‍👩‍👧‍👦\r\nline" to "\r\n1\r\n2\r\n3\r\n4\r\n5",
        )
        for ((before, update) in cases) {
            compose.runOnIdle { current.value = terminal(before) }
            compose.waitForTerminalIdle(current.value)
            compose.runOnIdle {
                current.value.writeInput(update.toByteArray())
                current.value.processPendingUpdates()
            }
            compose.waitForTerminalIdle(current.value)
            val changed = captureWindow()
            compose.runOnIdle { current.value = terminal(before + update) }
            compose.waitForTerminalIdle(current.value)
            val fresh = captureWindow()
            assertTrue("Incremental output differs from a fresh surface", changed.sameAs(fresh))
        }
    }
}
