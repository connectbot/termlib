/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.icu.text.BreakIterator

/** Accessibility offsets use UTF-16; terminal geometry uses cells. */
internal class TerminalAccessibleText(val line: TerminalLine) {
    val text = line.text
    private val columns = IntArray(text.length)

    init {
        var offset = 0
        var column = 0
        while (column < line.cells.size) {
            val width = line.cells.width(column).coerceAtLeast(1)
            val length = line.cells.text(column, (column + width).coerceAtMost(line.cells.size)).length
            repeat(length) { if (offset < columns.size) columns[offset++] = column }
            column += width
        }
    }

    private val characters by lazy { BreakIterator.getCharacterInstance().apply { setText(this@TerminalAccessibleText.text) } }

    /** Touch points must never land inside a surrogate pair or combining sequence. */
    fun graphemeOffset(column: Int): Int {
        val offset = offset(column)
        return if (characters.isBoundary(offset)) offset else characters.preceding(offset).coerceAtLeast(0)
    }

    fun column(offset: Int): Int = columns.getOrElse(offset) { line.cells.size }

    fun offset(column: Int): Int {
        val leading = if (column in line.cells.indices && line.cells.width(column) == 0 && column > 0) column - 1 else column
        return columns.indexOfFirst { it >= leading }.let { if (it < 0) text.length else it }
    }
}
