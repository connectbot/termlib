/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Run the same final-pixel assertions on Android and Robolectric native graphics. */
abstract class TerminalInversionChecks {
    private val line = TerminalLine(
        0,
        listOf(
            TerminalLine.Cell('A', fgColor = Color.Yellow, bgColor = Color.Blue, italic = true, underline = 1),
            TerminalLine.Cell('█', fgColor = Color.Red, bgColor = Color.Green),
            TerminalLine.Cell(' ', fgColor = Color.Cyan, bgColor = Color(0xFF24365A), reverse = true),
            TerminalLine.Cell('表', fgColor = Color.Green, bgColor = Color.Magenta, width = 2),
            TerminalLine.Cell('\u0000', fgColor = Color.Green, bgColor = Color.Magenta, width = 0),
            TerminalLine.Cell('Z', fgColor = Color.White, bgColor = Color.Black),
        ),
    )

    private fun selection(start: Int = 0, end: Int = 5) = SelectionManager().apply {
        startSelection(0, start, 6)
        updateSelection(0, end)
    }

    private fun render(
        selection: SelectionManager? = null,
        cursor: RectF? = null,
        bg: Color = Color.Unspecified,
        fg: Color = Color.Unspecified,
        target: Bitmap = Bitmap.createBitmap(72, 24, Bitmap.Config.ARGB_8888),
        source: TerminalLine = line,
        selectionRow: Int = 0,
    ): Bitmap {
        val paint = TerminalTextPaint(Typeface.MONOSPACE, 18f)
        val inversion = TerminalInversion()
        if (inverseSelection(bg, fg)) inversion.selectLine(source, 0, selectionRow, selection, paint.layout(source.cells, 12f), 12f, 24f)
        cursor?.let(inversion::cursor)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target.asImageBitmap()), Size(72f, 24f)) {
            inversion.draw(drawContext.canvas.nativeCanvas) {
                for (backgrounds in listOf(true, false)) {
                    drawLine(source, 0, 12f, 24f, 18f, paint, Paint(), Color.White, Color.Black, selection, selectionBackgroundColor = bg, selectionForegroundColor = fg, backgroundsOnly = backgrounds, selectionRow = selectionRow)
                }
            }
        }
        return target
    }

    private fun assertInverted(original: Int, actual: Int) {
        assertEquals(original ushr 24, actual ushr 24)
        for (shift in listOf(0, 8, 16)) {
            assertTrue("Expected inverse of ${original.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}", abs(255 - ((original ushr shift) and 255) - ((actual ushr shift) and 255)) <= 1)
        }
    }

    @Test
    fun selectionComplementsFinalPixelsIncludingAntialiasingAndReverseVideo() {
        val original = render()
        val selected = render(selection())
        for (y in 0 until 24) for (x in 0 until 72) assertInverted(original.getPixel(x, y), selected.getPixel(x, y))
    }

    @Test
    fun cursorInvertsAgainInsideSelectionForEveryShape() {
        val original = render()
        for (shape in CursorShape.entries) {
            val cursor = cursorBounds(0, 0, 1, 12f, 24f, shape, false)
            val combined = render(selection(), cursor)
            val selected = render(selection())
            for (y in 0 until 24) {
                for (x in 0 until 72) {
                    // Exclude the fractional edge of the narrow cursor geometry.
                    if (abs(x + 0.5f - cursor.right) < 1 || abs(y + 0.5f - cursor.top) < 1) continue
                    val expected = if (cursor.contains(x + 0.5f, y + 0.5f)) original else selected
                    assertEquals(expected.getPixel(x, y), combined.getPixel(x, y))
                }
            }
        }
    }

    @Test
    fun selectionOfWideContinuationIncludesWholeGlyphAndUsesAbsoluteRows() {
        val original = render()
        val wide = selection(4, 4)
        val selected = render(wide)
        for (y in 0 until 24) {
            for (x in 0 until 72) {
                if (x in 36 until 60) assertInverted(original.getPixel(x, y), selected.getPixel(x, y)) else assertEquals(original.getPixel(x, y), selected.getPixel(x, y))
            }
        }
        assertEquals(3, cursorLeadColumn(line, 4))
        assertEquals(RectF(36f, 0f, 60f, 24f), cursorBounds(0, 3, 2, 12f, 24f, CursorShape.BLOCK, false))
        assertEquals(60f, cursorBounds(0, 3, 2, 12f, 24f, CursorShape.BAR_LEFT, true).right)
        assertTrue(original.sameAs(render(wide, selectionRow = 10)))
        wide.startSelection(10, 4, 6)
        assertTrue(selected.sameAs(render(wide, selectionRow = 10)))
    }

    @Test
    fun explicitColorsKeepLegacyFallbacksAndCursorStillInverts() {
        val customBackground = render(selection(), bg = Color.Red)
        assertEquals(Color.Red.toArgb(), customBackground.getPixel(30, 2))
        assertEquals(Color.Black.toArgb(), customBackground.getPixel(18, 12)) // Full-block foreground.
        val customForeground = render(selection(), fg = Color.Green)
        assertEquals(Color(0xFFB3D7FF).toArgb(), customForeground.getPixel(30, 2))
        assertEquals(Color.Green.toArgb(), customForeground.getPixel(18, 12))
        val cursor = render(selection(), RectF(0f, 0f, 12f, 24f), bg = Color.Red)
        for (y in 0 until 24) for (x in 0 until 12) assertInverted(customBackground.getPixel(x, y), cursor.getPixel(x, y))
    }

    @Test
    fun clearingAndMovingMasksMatchesFreshRendering() {
        val target = render(selection(), RectF(0f, 0f, 12f, 24f))
        render(selection(3, 4), RectF(60f, 0f, 72f, 24f), target = target)
        assertTrue(target.sameAs(render(selection(3, 4), RectF(60f, 0f, 72f, 24f))))
        render(target = target)
        assertTrue(target.sameAs(render()))
    }

    @Test
    fun colorFilterPreservesAlpha() {
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = Paint().apply { color = 0x800000FF.toInt() }
        val inversion = TerminalInversion()
        inversion.cursor(RectF(0f, 0f, 1f, 1f))
        inversion.draw(canvas) { canvas.drawRect(0f, 0f, 2f, 1f, paint) }
        assertInverted(bitmap.getPixel(1, 0), bitmap.getPixel(0, 0))
    }

    @Test
    fun selectionUsesVisualColumnsForBidirectionalText() {
        val source = TerminalLine(0, "aאבג z".map { TerminalLine.Cell(it, fgColor = Color.Yellow, bgColor = Color.Blue) })
        val paint = TerminalTextPaint(Typeface.MONOSPACE, 18f)
        val visual = paint.layout(source.cells, 12f)?.visualColumn(1) ?: 1
        val original = render(source = source)
        val selected = render(selection(1, 1), source = source)
        for (y in 0 until 24) {
            for (x in 0 until 72) {
                if (x in visual * 12 until (visual + 1) * 12) {
                    assertInverted(original.getPixel(x, y), selected.getPixel(x, y))
                } else {
                    assertEquals(original.getPixel(x, y), selected.getPixel(x, y))
                }
            }
        }
    }

    @Test
    fun inlineImagesAndEmojiAreInvertedAfterRendering() {
        val terminal = TerminalEmulatorFactory.create(initialRows = 2, initialCols = 6, inlineImages = InlineImages.On()) as TerminalEmulatorImpl
        // Keep the image inside the textual selection; character selection trims trailing blanks.
        terminal.writeInput("🎉\u001b_Ga=T,f=32,s=1,v=1,i=1,c=2,r=1,C=1;/wAA/w==\u001b\\\u001b[1;6HX".toByteArray())
        terminal.processPendingUpdates()
        terminal.imageStore.assets.values.forEach { asset ->
            asset.bitmap = asset.frames[0].decode(asset.width, asset.height)
            asset.presentation.publish(asset.bitmap, null)
        }
        val source = terminal.snapshot.value.lines[0]
        assertTrue(source.images.isNotEmpty())
        val original = render(source = source)
        val selected = render(selection(), source = source)
        assertEquals(Color.Red.toArgb(), original.getPixel(30, 12))
        for (y in 0 until 24) for (x in 0 until 72) assertInverted(original.getPixel(x, y), selected.getPixel(x, y))
    }
}
