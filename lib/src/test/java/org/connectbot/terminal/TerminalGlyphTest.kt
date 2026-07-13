/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.text.TextRunShaper
import android.text.TextPaint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalGlyphTest {
    private fun font(name: String): Font {
        checkNotNull(Typeface.DEFAULT) // Initialize Robolectric's system map before custom fonts.
        val data = javaClass.getResourceAsStream("/glyph-fixtures/$name.ttf")!!.use { it.readBytes() }
        return Font.Builder(
            ByteBuffer.allocateDirect(data.size).apply {
                put(data)
                flip()
            },
        ).build()
    }

    private fun customTypeface(): Typeface = Typeface.CustomFallbackBuilder(FontFamily.Builder(font("Primary")).build())
        .addCustomFallback(FontFamily.Builder(font("Fallback")).build())
        .setSystemFallback("monospace")
        .build()

    @Test
    fun customFallbackSelectsFirstCoveringFont() {
        val primary = font("Primary")
        val fallback = font("Fallback")
        val typeface = Typeface.CustomFallbackBuilder(FontFamily.Builder(primary).build())
            .addCustomFallback(FontFamily.Builder(fallback).build())
            .setSystemFallback("monospace").build()
        val paint = Paint().apply {
            this.typeface = typeface
            textSize = 20f
        }
        val glyphs = TextRunShaper.shapeTextRun("AB", 0, 2, 0, 2, 0f, 0f, false, paint)
        assertEquals(2, glyphs.glyphCount())
        assertEquals(primary.buffer, glyphs.getFont(0).buffer)
        assertEquals(fallback.buffer, glyphs.getFont(1).buffer)
    }

    private fun render(line: TerminalLine, typeface: Typeface = customTypeface(), selection: SelectionManager? = null, bitmap: Bitmap? = null): Bitmap {
        val result = bitmap ?: Bitmap.createBitmap(160, 40, Bitmap.Config.ARGB_8888)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = 20f
        }
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            androidx.compose.ui.graphics.Canvas(result.asImageBitmap()),
            Size(160f, 40f),
        ) {
            drawLine(line, 0, 12f, 40f, 25f, paint, Paint(), Color.White, Color.Black, selection)
        }
        return result
    }

    private fun cell(char: Char, italic: Boolean = false, bgColor: Color = Color.Black, width: Int = 1, underline: Int = 0) = TerminalLine.Cell(char, fgColor = Color.White, bgColor = bgColor, italic = italic, width = width, underline = underline)

    private fun line(vararg cells: TerminalLine.Cell): TerminalLine = TerminalLine(
        0,
        cells.toList() + List(10 - cells.sumOf { it.width }) { cell(' ') },
    )

    @Test
    fun oversizedFallbackAdvanceFitsWithoutMovingFollowingText() {
        val row = line(cell('B'), cell('A'))
        val result = render(row)
        // B's raw advance is 22 px, but A must start at the next 12 px column.
        assertTrue(result.getPixel(12, 20) != android.graphics.Color.BLACK)
        assertEquals(android.graphics.Color.BLACK, result.getPixel(25, 20))
        val single = render(line(cell('B')))
        assertEquals(android.graphics.Color.BLACK, single.getPixel(13, 20))
    }

    @Test
    fun backgroundsDoNotEraseItalicOverhang() {
        val row = line(
            cell('A', italic = true, bgColor = Color.Blue),
            cell(' ', bgColor = Color.Red),
        )
        val result = render(row)
        // The top of the sheared glyph extends into the red cell.
        assertTrue((12..14).any { result.getPixel(it, 12) != android.graphics.Color.RED })
    }

    @Test
    fun replacementAndSelectionRemovalMatchFreshRendering() {
        val old = line(cell('A', italic = true), cell('B', width = 2))
        val updated = line(cell(' '))
        val surface = render(old)
        render(updated, bitmap = surface)
        assertTrue(surface.sameAs(render(updated)))
        val selection = SelectionManager().apply { startSelection(0, 0, 10, SelectionMode.CHARACTER) }
        render(old, selection = selection, bitmap = surface)
        render(old, bitmap = surface)
        assertTrue(surface.sameAs(render(old)))
    }

    @Test
    fun decorationsSpanBlankCells() {
        val result = render(line(cell(' ', underline = 1)))
        assertTrue(result.getPixel(6, 27) != android.graphics.Color.BLACK)
    }
}
