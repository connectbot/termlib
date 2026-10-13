/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

/** Cell geometry and UTF-16 offsets for one row of an occurrence. */
internal data class ReviewLinkRange(val cells: TerminalLinkRange, val start: Int, val end: Int)

internal data class TerminalReviewLink(val url: String, val label: String, val ranges: List<ReviewLinkRange>) {
    val start get() = ReviewPosition(ranges.first().cells.row, ranges.first().start)
    fun contains(position: ReviewPosition): Boolean = ranges.any {
        it.cells.row == position.row && position.offset in it.start until it.end
    }
}

internal fun TerminalReviewDocument.resolveLinks(autoDetect: Boolean): List<TerminalReviewLink> = resolveTerminalLinks(lines, autoDetect).map { link ->
    val ranges = link.ranges.map { range -> ReviewLinkRange(range, text(range.row).offset(range.start), text(range.row).offset(range.end)) }
    TerminalReviewLink(link.url, ranges.joinToString("") { lines[it.cells.row].text.substring(it.start, it.end) }, ranges)
}
