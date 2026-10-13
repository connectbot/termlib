/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.icu.text.BreakIterator

/** Half-open UTF-16 positions in the frozen grid, independent of viewport and virtual IDs. */
internal data class ReviewPosition(val row: Int, val offset: Int) : Comparable<ReviewPosition> {
    override fun compareTo(other: ReviewPosition): Int = compareValuesBy(this, other, { it.row }, { it.offset })
}

internal enum class ReviewUnit { CHARACTER, WORD, LINE, PARAGRAPH }

internal class TerminalReviewSelection(private val document: TerminalReviewDocument) {
    var anchor = ReviewPosition(0, 0)
        private set
    var active = anchor
        private set
    var marking = false
    private var preferredColumn: Int? = null
    val start get() = minOf(anchor, active)
    val end get() = maxOf(anchor, active)
    val isEmpty get() = anchor == active
    val last get() = ReviewPosition(document.lines.lastIndex, document.lines.last().text.length)

    fun valid(position: ReviewPosition): Boolean = position.row in document.lines.indices && position.offset in 0..document.lines[position.row].text.length

    fun set(anchor: ReviewPosition, active: ReviewPosition) {
        require(valid(anchor) && valid(active))
        this.anchor = anchor
        this.active = active
        preferredColumn = null
    }

    fun move(position: ReviewPosition, extend: Boolean = marking) {
        set(if (extend) anchor else position, position)
    }

    fun clear() {
        marking = false
        move(active, false)
    }

    fun switchEndpoint() {
        set(active, anchor)
    }
    fun selectAll() {
        set(ReviewPosition(0, 0), last)
    }

    /** Inclusive row coverage; the returned offsets are half-open and may be collapsed. */
    fun intersection(row: Int): Pair<Int, Int>? {
        if (row !in start.row..end.row) return null
        return (if (row == start.row) start.offset else 0) to
            (if (row == end.row) end.offset else document.lines[row].text.length)
    }

    fun copyText(): String = buildString {
        if (isEmpty) return@buildString
        for (row in start.row..end.row) {
            val (first, last) = intersection(row)!!
            val line = document.lines[row]
            val part = line.text.substring(first, last)
            append(if (row < end.row && !line.softWrapped) part.trimEnd(' ', '\u0000') else part)
            if (row < end.row && !line.softWrapped) append('\n')
        }
    }

    fun vertical(rows: Int, extend: Boolean = marking): Boolean {
        val column = preferredColumn ?: document.text(active.row).column(active.offset)
        val row = (active.row + rows).coerceIn(document.lines.indices)
        val target = ReviewPosition(row, document.text(row).offset(column))
        if (target == active) return false
        move(target, extend)
        preferredColumn = column
        return true
    }

    /** A wrapped logical line is cached once; movement never scans the entire history. */
    private data class Paragraph(val first: Int, val last: Int, val text: String, val starts: IntArray)
    private var cachedParagraph: Paragraph? = null
    private fun paragraph(row: Int): Paragraph {
        cachedParagraph?.takeIf { row in it.first..it.last }?.let { return it }
        var first = row
        while (first > 0 && document.lines[first - 1].softWrapped) first--
        var last = row
        while (last < document.lines.lastIndex && document.lines[last].softWrapped) last++
        val starts = IntArray(last - first + 1)
        val text = buildString {
            for (index in first..last) {
                starts[index - first] = length
                append(document.lines[index].text)
            }
        }
        return Paragraph(first, last, text, starts).also { cachedParagraph = it }
    }

    fun next(unit: ReviewUnit, forward: Boolean): ReviewPosition? {
        if (unit == ReviewUnit.LINE) {
            val row = active.row + if (forward) 1 else -1
            return row.takeIf { it in document.lines.indices }?.let { ReviewPosition(it, 0) }
        }
        var paragraph = paragraph(active.row)
        if (unit == ReviewUnit.PARAGRAPH) {
            val row = if (forward) paragraph.last + 1 else paragraph.first - 1
            if (row !in document.lines.indices) return null
            return ReviewPosition(paragraph(row).first, 0)
        }
        var offset = paragraph.starts[active.row - paragraph.first] + active.offset
        if ((forward && offset == paragraph.text.length) || (!forward && offset == 0)) {
            val row = if (forward) paragraph.last + 1 else paragraph.first - 1
            if (row !in document.lines.indices) return null
            paragraph = paragraph(row)
            offset = if (forward) 0 else paragraph.text.length
            // Keep empty hard lines reachable.
            if (paragraph.text.isEmpty()) return ReviewPosition(row, 0)
        }
        val iterator = if (unit == ReviewUnit.WORD) BreakIterator.getWordInstance() else BreakIterator.getCharacterInstance()
        iterator.setText(paragraph.text)
        var next = if (forward) iterator.following(offset) else iterator.preceding(offset)
        if (unit == ReviewUnit.WORD) {
            // Skip whitespace before the next word, but retain punctuation as a reading unit.
            while (next != BreakIterator.DONE && paragraph.text.substring(minOf(offset, next), maxOf(offset, next)).isBlank()) {
                offset = next
                next = if (forward) iterator.following(offset) else iterator.preceding(offset)
            }
            if (next == BreakIterator.DONE) next = offset
        }
        if (next == BreakIterator.DONE) return null
        // At a soft seam, forward movement lands at the end of the text just read;
        // backwards movement lands at the start of the text just read.
        val index = if (forward) {
            paragraph.starts.indexOfLast { it < next }.coerceAtLeast(0)
        } else {
            paragraph.starts.indexOfLast { it <= next }.coerceAtLeast(0)
        }
        return ReviewPosition(paragraph.first + index, next - paragraph.starts[index]).takeUnless { it == active }
    }
}
