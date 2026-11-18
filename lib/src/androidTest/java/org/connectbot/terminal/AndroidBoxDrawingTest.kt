/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.Canvas as ComposeCanvas

@RunWith(AndroidJUnit4::class)
class AndroidBoxDrawingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun cachedBoxRowsMatchVectorGeometryInTheHardwareWindow() {
        val renderer = TerminalBoxDrawing()
        val reference = TerminalBoxDrawing(cacheRows = false)
        val cachedAtLeft = mutableStateOf(true)
        compose.setContent {
            ComposeCanvas(Modifier.size(320.dp, 128.dp)) {
                val canvas = drawContext.canvas.nativeCanvas
                assertTrue("Window canvas must use hardware acceleration", canvas.isHardwareAccelerated)
                canvas.drawColor(Color.BLACK)
                for ((offset, drawing) in listOf(0f to if (cachedAtLeft.value) renderer else reference, 160f to reference)) {
                    val saved = canvas.save()
                    canvas.translate(offset, 0f)
                    canvas.clipRect(0f, 0f, 160f, 128f)
                    drawing.prepareDraw(canvas)
                    val rows = List(8) { row ->
                        val color = if (row % 2 == 0) 0xFF44AAFF.toInt() else Color.WHITE
                        PackedCells.from(List(20) { col -> TerminalLine.Cell((0x2500 + row * 20 + col).toChar(), fgColor = androidx.compose.ui.graphics.Color(color), bgColor = androidx.compose.ui.graphics.Color.Black) })
                    }
                    for (row in rows.indices) {
                        val cells = rows[row]
                        val color = if (row % 2 == 0) 0xFF44AAFF.toInt() else Color.WHITE
                        val above = rows.getOrNull(row - 1)
                        val below = rows.getOrNull(row + 1)
                        val cached = drawing.drawRow(canvas, cells, cells.text(), above, below, row * 16f, 8f, 16f, 16f)
                        if (!cached) {
                            drawing.beginBatch(canvas, color)
                            for (col in cells.indices) drawing.draw(canvas, cells.boxCodePoint(col).toChar(), col * 8f, row * 16f, 8f, 16f, color, 16f, TerminalBoxRows.neighboringDashes(cells, col, above, below), clipPrepared = true)
                            drawing.endBatch()
                        }
                    }
                    canvas.restoreToCount(saved)
                }
            }
        }
        val pixels = captureWindowPixels()
        val actual = Bitmap.createBitmap(pixels, 0, 0, 160, 128)
        // Compare at the same device coordinates: GPU diagonal coverage can
        // differ by a quantization step when identical vectors are translated.
        compose.runOnIdle { cachedAtLeft.value = false }
        val referencePixels = captureWindowPixels()
        val expected = Bitmap.createBitmap(referencePixels, 0, 0, 160, 128)
        assertEquals("Capture must contain the colored full block", 0xFF44AAFF.toInt(), expected.getPixel(132, 104))
        val differences = ArrayList<String>()
        var differingPixels = 0
        var translatedVectorDifferences = 0
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                if (expected.getPixel(x, y) != referencePixels.getPixel(x + 160, y)) translatedVectorDifferences++
                if (actual.getPixel(x, y) != expected.getPixel(x, y)) {
                    differingPixels++
                    if (differences.size < 12) differences.add("($x,$y) actual=${actual.getPixel(x, y).toUInt().toString(16)} expected=${expected.getPixel(x, y).toUInt().toString(16)}")
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("gpuComparison", "cached-vs-vector same-position differences=$differingPixels; vector-vs-vector translated differences=$translatedVectorDifferences")
            },
        )
        if (differingPixels != 0) {
            val directory = InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null)!!
            for ((name, bitmap) in listOf("box-row-actual" to actual, "box-row-expected" to expected)) {
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        assertEquals("Cached rows differ from hardware vector geometry: ${differences.joinToString()}", 0, differingPixels)
        actual.recycle()
        expected.recycle()
        pixels.recycle()
        referencePixels.recycle()
    }

    private fun captureWindowPixels(): Bitmap {
        compose.waitForIdle()
        val wasDrawingEnabled = HardwareRendererCompat.isDrawingEnabled()
        val pixels: Bitmap
        try {
            HardwareRendererCompat.setDrawingEnabled(true)
            val frames = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                compose.activity.window.decorView.invalidate()
                Choreographer.getInstance().postFrameCallback {
                    Choreographer.getInstance().postFrameCallback { frames.countDown() }
                }
            }
            assertTrue("Window did not present a frame", frames.await(5, TimeUnit.SECONDS))
            val bounds = compose.onRoot().fetchSemanticsNode().boundsInWindow
            pixels = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            var result = -1
            PixelCopy.request(
                compose.activity.window,
                Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()),
                pixels,
                {
                    result = it
                    copied.countDown()
                },
                Handler(Looper.getMainLooper()),
            )
            assertTrue("Window capture timed out", copied.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
        } finally {
            HardwareRendererCompat.setDrawingEnabled(wasDrawingEnabled)
        }
        return pixels
    }

    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun foregroundPicturesMatchDirectHardwareGlyphsAndPatterns() {
        val terminal = TerminalEmulatorFactory.create(initialRows = 4, initialCols = 26) as TerminalEmulatorImpl
        terminal.writeInput("ABC نص (حكيم) 123\r\nDEF שלום (עולם) 456\r\n┌─┼┐ نص (حكيم) ░▒▓ 123\r\n\u001b[1;38;2;68;170;255mنص حكيم ABC".toByteArray())
        terminal.processPendingUpdates()
        val state = TerminalScreenState(terminal.snapshot.value)
        val paint = TerminalTextPaint(android.graphics.Typeface.MONOSPACE, 20f)
        val layouts = List(4) { paint.layout(state, it, 12f)!! }
        compose.setContent {
            ComposeCanvas(Modifier.size(640.dp, 240.dp)) {
                val canvas = drawContext.canvas.nativeCanvas
                assertTrue(canvas.isHardwareAccelerated)
                canvas.drawColor(0xFFAA2244.toInt())
                for ((offset, textPaint) in listOf(0f to paint, 320f to android.text.TextPaint(paint))) {
                    val saved = canvas.save()
                    canvas.translate(offset, 0f)
                    canvas.clipRect(0f, 0f, 320f, 240f)
                    for (row in 0 until 4) {
                        drawLine(state.getVisibleLine(row), row, 12f, 40f, 28f, textPaint, android.graphics.Paint(), androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.Black, null, backgroundsOnly = false, shapedLine = layouts[row], aboveLine = state.getVisibleLine(row - 1), belowLine = state.getVisibleLine(row + 1))
                    }
                    canvas.restoreToCount(saved)
                }
            }
        }
        val pixels = captureWindowPixels()
        val actual = Bitmap.createBitmap(pixels, 0, 0, 320, 240)
        val expected = Bitmap.createBitmap(pixels, 320, 0, 320, 240)
        assertEquals(0xFFAA2244.toInt(), expected.getPixel(310, 230))
        assertTrue("Capture must contain foreground ink", (0 until 160).any { y -> (0 until 300).any { x -> expected.getPixel(x, y) != 0xFFAA2244.toInt() } })
        assertTrue("Foreground commands were not retained", paint.foregroundRows.retainedBytes > 0)
        assertTrue("Native foreground recording changed window pixels", actual.sameAs(expected))
        actual.recycle()
        expected.recycle()
        pixels.recycle()
    }

    @Test
    fun everyCharacterRendersAndFullBlocksMeetOnAndroidCanvas() {
        val renderer = TerminalBoxDrawing()
        for (size in listOf(13f, 16f, 21f)) {
            val bitmap = Bitmap.createBitmap(16, 25, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            for (cp in 0x2500..0x259F) {
                bitmap.eraseColor(Color.BLACK)
                renderer.draw(canvas, cp.toChar(), 0f, 0f, 16f, 25f, Color.WHITE, size)
                assertTrue("Blank U+${cp.toString(16)}", (0 until 25).any { y -> (0 until 16).any { x -> bitmap.getPixel(x, y) != Color.BLACK } })
            }
        }
        val bitmap = Bitmap.createBitmap(38, 63, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (row in 0..2) {
            for (col in 0..3) {
                renderer.draw(canvas, '█', col * 9.5f, row * 21f, 9.5f, 21f, Color.WHITE, 16f)
            }
        }
        for (y in 0 until 63) for (x in 0 until 38) assertEquals(Color.WHITE, bitmap.getPixel(x, y))
    }
}
