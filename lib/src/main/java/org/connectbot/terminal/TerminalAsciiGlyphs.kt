/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Canvas
import android.graphics.fonts.Font
import android.graphics.text.TextRunShaper
import android.os.Build
import androidx.annotation.RequiresApi

/** Shape each printable ASCII cell independently, then record neighboring glyphs
 * at the same absolute positions as separate drawText calls. Owned by a single
 * regular, unskewed font/size paint. Never retain rows, canvases or snapshots.
 */
@RequiresApi(31)
internal class TerminalAsciiGlyphs {
    private class Glyph(val id: Int, val font: Font, val x: Float, val y: Float, val advance: Float)
    private val glyphs = arrayOfNulls<Glyph>(127)
    private val unavailable = BooleanArray(127)
    private val ids = IntArray(128)
    private val positions = FloatArray(ids.size * 2)

    fun draw(canvas: Canvas, paint: TerminalTextPaint, cells: PackedCells, start: Int, width: Float, baseline: Float): Int {
        val flags = cells.flags(start)
        val color = cells.foreground(start)
        var font: Font? = null
        var count = 0
        var col = start
        while (col < cells.size && count < ids.size && cells.flags(col) == flags && cells.foreground(col) == color) {
            val ch = cells.singleAscii(col)
            if (ch < 0 || unavailable[ch]) break
            if (ch != 32) {
                val glyph = glyphs[ch] ?: shape(ch, paint) ?: break
                if (glyph.advance > width || (font != null && font != glyph.font)) break
                font = glyph.font
                ids[count] = glyph.id
                positions[count * 2] = col * width + glyph.x
                positions[count * 2 + 1] = baseline + glyph.y
                count++
            }
            col++
        }
        if (font == null) return start
        canvas.drawGlyphs(ids, 0, positions, 0, count, font, paint)
        return col
    }

    private fun shape(ch: Int, paint: TerminalTextPaint): Glyph? {
        val text = charArrayOf(ch.toChar())
        val shaped = TextRunShaper.shapeTextRun(text, 0, 1, 0, 1, 0f, 0f, false, paint)
        if (shaped.glyphCount() != 1 || (
                Build.VERSION.SDK_INT >= 35 &&
                    (
                        shaped.getFakeBold(0) || shaped.getFakeItalic(0) ||
                            shaped.getWeightOverride(0) != Float.MIN_VALUE || shaped.getItalicOverride(0) != Float.MIN_VALUE
                        )
                )
        ) {
            unavailable[ch] = true
            return null
        }
        return Glyph(
            shaped.getGlyphId(0),
            shaped.getFont(0),
            shaped.getGlyphX(0),
            shaped.getGlyphY(0),
            paint.measureCell(text, 0, 1, false),
        ).also { glyphs[ch] = it }
    }
}
