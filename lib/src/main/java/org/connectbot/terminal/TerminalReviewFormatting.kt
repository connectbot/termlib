/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import java.util.Locale

/** Inspect terminal attributes, without substituting the review highlight's colors. */
internal fun describeReviewFormatting(context: Context, document: TerminalReviewDocument): String {
    val selection = document.selection
    var common: IntArray? = null
    val mixed = BooleanArray(8)
    fun include(row: Int, column: Int) {
        val cells = document.lines[row].cells
        if (cells.isEmpty()) return
        var col = column.coerceIn(0, cells.lastIndex)
        if (cells.width(col) == 0 && col > 0) col--
        val flags = cells.flags(col)
        val reverse = flags and 32 != 0
        val values = intArrayOf(
            (if (reverse) cells.background(col) else cells.foreground(col)).toArgb(),
            (if (reverse) cells.foreground(col) else cells.background(col)).toArgb(),
            flags and 1,
            flags and 8,
            (flags ushr 1) and 3,
            flags and 128,
            flags and 16,
            flags and 32,
        )
        val first = common
        if (first == null) common = values else for (index in values.indices) mixed[index] = mixed[index] || first[index] != values[index]
    }
    if (selection.isEmpty) {
        include(selection.active.row, document.text(selection.active.row).column(selection.active.offset))
    } else {
        for (row in selection.start.row..selection.end.row) {
            val (start, end) = selection.intersection(row)!!
            val text = document.text(row)
            var previous = -1
            for (offset in start until end) {
                val col = text.column(offset)
                if (col != previous) include(row, col)
                previous = col
            }
        }
    }
    val values = common ?: return context.getString(R.string.terminal_a11y_no_formatting)
    val labels = intArrayOf(
        R.string.terminal_a11y_foreground,
        R.string.terminal_a11y_background,
        R.string.terminal_a11y_bold,
        R.string.terminal_a11y_italic,
        R.string.terminal_a11y_underline,
        R.string.terminal_a11y_strike,
        R.string.terminal_a11y_blink,
        R.string.terminal_a11y_reverse,
    )
    return labels.indices.joinToString("; ") { index ->
        val value = when {
            mixed[index] -> context.getString(R.string.terminal_a11y_mixed)

            index < 2 -> String.format(Locale.ROOT, "#%06X", values[index] and 0xFFFFFF)

            index == 4 -> context.getString(
                when (values[index]) {
                    1 -> R.string.terminal_a11y_single
                    2 -> R.string.terminal_a11y_double
                    3 -> R.string.terminal_a11y_curly
                    else -> R.string.terminal_a11y_off
                },
            )

            else -> context.getString(if (values[index] == 0) R.string.terminal_a11y_off else R.string.terminal_a11y_on)
        }
        context.getString(R.string.terminal_a11y_attribute, context.getString(labels[index]), value)
    }
}
