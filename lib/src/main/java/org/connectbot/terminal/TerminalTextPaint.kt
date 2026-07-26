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
 * Complex clusters and other scripts retain the normal shaping path.
 */
internal class TerminalTextPaint(typeface: Typeface, size: Float) : TextPaint() {
    private val glyphAdvances = FloatArray(512) { Float.NaN }

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
