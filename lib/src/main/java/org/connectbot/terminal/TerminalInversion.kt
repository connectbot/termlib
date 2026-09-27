/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import androidx.compose.ui.graphics.Color

internal fun inverseSelection(background: Color, foreground: Color): Boolean = background == Color.Unspecified && foreground == Color.Unspecified

/** A reusable pixel mask shared by the terminal, standalone rows, and magnifier. */
internal class TerminalInversion {
    private val mask = Path()
    private val cursor = Path()
    private val bounds = RectF()
    private val paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }

    fun reset() = mask.rewind()

    fun selectLine(line: TerminalLine, row: Int, selectionRow: Int, selection: SelectionManager?, shaped: ShapedLine?, charWidth: Float, charHeight: Float) {
        if (selection?.selectionRange == null) return
        val cells = line.cells
        var start = -1
        var end = 0
        fun flush() {
            if (start >= 0) mask.addRect(start * charWidth, row * charHeight, end * charWidth, (row + 1) * charHeight, Path.Direction.CW)
            start = -1
        }
        for (visual in 0 until cells.size) {
            val col = shaped?.logicalColumn(visual) ?: visual
            val width = cells.width(col)
            if (width == 0) continue
            val selected = selection.isCellSelected(selectionRow, col, line) || (width == 2 && selection.isCellSelected(selectionRow, col + 1, line))
            if (selected) {
                if (start < 0) start = visual
                end = visual + width
            } else {
                flush()
            }
        }
        flush()
    }

    fun cursor(rect: RectF) {
        cursor.rewind()
        cursor.addRect(rect, Path.Direction.CW)
        // Inverting a selected pixel twice restores its original color.
        check(mask.op(cursor, Path.Op.XOR))
    }

    @Suppress("DEPRECATION") // Region.Op.DIFFERENCE also supports API 24–25.
    fun draw(canvas: Canvas, content: () -> Unit) {
        if (mask.isEmpty) {
            content()
            return
        }
        val normal = canvas.save()
        try {
            canvas.clipPath(mask, Region.Op.DIFFERENCE)
            content()
        } finally {
            canvas.restoreToCount(normal)
        }
        mask.computeBounds(bounds, true)
        val inverted = canvas.save()
        try {
            canvas.clipPath(mask)
            // Filter completed pixels, including antialiasing, color emoji and images.
            canvas.saveLayer(bounds, paint)
            content()
        } finally {
            canvas.restoreToCount(inverted)
        }
    }
}

internal fun cursorLeadColumn(line: TerminalLine, col: Int): Int {
    var lead = col.coerceIn(0, (line.cells.size - 1).coerceAtLeast(0))
    while (lead > 0 && line.cells.width(lead) == 0) lead--
    return lead
}

internal fun cursorBounds(row: Int, visualColumn: Int, cellColumns: Int, charWidth: Float, charHeight: Float, shape: CursorShape, rtl: Boolean): RectF {
    val x = visualColumn * charWidth
    val y = row * charHeight
    val width = cellColumns * charWidth
    return when (shape) {
        CursorShape.BLOCK -> RectF(x, y, x + width, y + charHeight)

        CursorShape.UNDERLINE -> RectF(x, y + charHeight * 0.85f, x + width, y + charHeight)

        CursorShape.BAR_LEFT -> {
            val left = if (rtl) x + width - charWidth * 0.15f else x
            RectF(left, y, left + charWidth * 0.15f, y + charHeight)
        }
    }
}
