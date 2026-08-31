/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Typeface
import android.text.TextPaint

/**
 * Renderer-owned paint with fixed font geometry. Construct a new instance when
 * typeface or size changes; only color, bold, skew and decorations vary per cell.
 * A bounded 2 KiB cache avoids reshaping single Latin-1 glyphs in every new row.
 * Complex-script layouts are allocated lazily on supported Android versions;
 * ordinary scripts retain the per-cell renderer and its measurement cache.
 */
internal class TerminalTextPaint(typeface: Typeface, size: Float) : TextPaint() {
    private val glyphAdvances = FloatArray(512) { Float.NaN }
    val boxDrawing = TerminalBoxDrawing()
    private var shaping: TerminalShaping? = null
    private var screen: TerminalScreenState? = null

    fun viewport(state: TerminalScreenState) {
        screen = state
        shaping?.viewport(state)
    }

    fun clearShaping() {
        shaping?.clear()
        shaping = null
        screen = null
    }

    fun layout(cells: PackedCells, cellWidth: Float): ShapedLine? {
        if (android.os.Build.VERSION.SDK_INT < 31 || !cells.needsShaping()) return null
        val engine = shaping ?: TerminalShaping(this).also {
            shaping = it
            screen?.let(it::viewport)
        }
        return engine.layout(cells, cellWidth)
    }

    fun visualColumn(state: TerminalScreenState, row: Int, col: Int, cellWidth: Float): Int = layout(state.getVisibleLine(row).cells, cellWidth)?.visualColumn(col) ?: col

    fun logicalColumn(state: TerminalScreenState, row: Int, col: Int, cellWidth: Float): Int = layout(state.getVisibleLine(row).cells, cellWidth)?.logicalColumn(col) ?: col

    init {
        this.typeface = typeface
        textSize = size
        isAntiAlias = true
    }

    fun measureCell(text: CharArray, start: Int, count: Int): Float {
        if (count != 1 || text[start].code >= 256) return measureText(text, start, count)
        val index = text[start].code + if (isFakeBoldText) 256 else 0
        val cached = glyphAdvances[index]
        if (!cached.isNaN()) return cached
        return measureText(text, start, count).also { glyphAdvances[index] = it }
    }
}
