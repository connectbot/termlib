/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Renderer-owned paint with fixed font geometry. Construct a new instance when
 * typeface or size changes; only color, bold, skew and decorations vary per cell.
 * A bounded 2 KiB cache avoids reshaping single Latin-1 glyphs in every new row.
 * Complex-script layouts are allocated lazily on supported Android versions;
 * ordinary scripts retain the per-cell renderer and its measurement cache.
 */
internal class TerminalTextPaint(typeface: Typeface, size: Float) : TextPaint() {
    val glyphDisplayLists by lazy(LazyThreadSafetyMode.NONE) { GlyphDisplayLists() }
    private val glyphAdvances = FloatArray(512) { Float.NaN }
    val boxDrawing = TerminalBoxDrawing()
    val usesSystemMonospace = typeface === Typeface.MONOSPACE
    private var ascii: TerminalAsciiGlyphs? = null

    @androidx.annotation.RequiresApi(31)
    fun drawAscii(canvas: android.graphics.Canvas, cells: PackedCells, col: Int, width: Float, baseline: Float): Int {
        val glyphs = ascii ?: TerminalAsciiGlyphs().also { ascii = it }
        return glyphs.draw(canvas, this, cells, col, width, baseline)
    }

    private var shaping: TerminalShaping? = null
    private var screen: TerminalScreenState? = null
    var preparedViewport: PreparedViewport? = null

    fun viewport(state: TerminalScreenState) {
        screen = state
        shaping?.viewport(state)
    }

    fun clearShaping() {
        shaping?.clear()
        shaping = null
        screen = null
        preparedViewport = null
    }

    fun layout(cells: PackedCells, cellWidth: Float): ShapedLine? {
        if (android.os.Build.VERSION.SDK_INT < 31 || !cells.needsShaping()) return null
        val engine = shaping ?: TerminalShaping(this).also {
            shaping = it
            screen?.let(it::viewport)
        }
        return engine.layout(cells, cellWidth)
    }

    fun layout(state: TerminalScreenState, row: Int, cellWidth: Float, forDrawing: Boolean = false): ShapedLine? {
        if (android.os.Build.VERSION.SDK_INT < 31) return null
        val prepared = preparedViewport
        if (prepared != null && prepared.cellWidth == cellWidth && row in prepared.layouts.indices &&
            Snapshot.withoutReadObservation { state.snapshot === prepared.snapshot && state.scrollbackPosition == 0 }
        ) {
            val line = prepared.layouts[row]
            return if (forDrawing) line.drawing else line.contextual
        }
        val engine = shaping ?: TerminalShaping(this).also {
            shaping = it
            it.viewport(state)
        }
        return engine.layout(state, row, cellWidth)
    }

    fun visualColumn(state: TerminalScreenState, row: Int, col: Int, cellWidth: Float): Int = layout(state, row, cellWidth)?.visualColumn(col) ?: col

    fun logicalColumn(state: TerminalScreenState, row: Int, col: Int, cellWidth: Float): Int = layout(state, row, cellWidth)?.logicalColumn(col) ?: col

    fun resolvedRtl(state: TerminalScreenState, row: Int, col: Int, cellWidth: Float): Boolean = layout(state, row, cellWidth)?.resolvedRtl(col) == true

    init {
        this.typeface = typeface
        textSize = size
        isAntiAlias = true
    }

    fun measureCell(text: CharArray, start: Int, count: Int, bold: Boolean = isFakeBoldText): Float {
        if (count != 1 || text[start].code >= 256) return measureText(text, start, count)
        val index = text[start].code + if (bold) 256 else 0
        val cached = glyphAdvances[index]
        if (!cached.isNaN()) return cached
        val skew = textSkewX
        textSkewX = 0f
        val advance = measureText(text, start, count)
        textSkewX = skew
        glyphAdvances[index] = advance
        return advance
    }
}
