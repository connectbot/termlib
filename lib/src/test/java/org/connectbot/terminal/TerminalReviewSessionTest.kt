/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun accessibilityLine(text: String, row: Int = 0): TerminalLine = TerminalLine(
    row,
    text.map {
        TerminalLine.Cell(it, fgColor = Color.White, bgColor = Color.Black)
    },
)

internal fun accessibilitySnapshot(history: Int = 12, rows: Int = 3): TerminalSnapshot = TerminalSnapshot.empty(rows, 40).copy(
    scrollback = List(history) { accessibilityLine("History $it") },
    lines = List(rows) { accessibilityLine("Screen $it", it) },
)

class TerminalReviewSessionTest {
    @Test fun outputEvictionAndAlternateScreenDoNotChangeReview() {
        var latest = accessibilitySnapshot()
        val review = TerminalReviewSession { latest }
        review.resize(3, 20)
        review.begin(latest, row = 2, top = 1)
        val document = review.document
        latest = accessibilitySnapshot(1000).copy(alternateScreen = true)
        assertSame(document, review.document)
        assertEquals("History 1", review.screenState.getVisibleLine(0).text)
        assertEquals(2, review.focusedRow)
        review.refreshReview()
        assertEquals(3, review.document!!.lines.size)
        assertEquals("Screen 0", review.document!!.lines.first().text)
        review.resumeLiveOutput()
        assertFalse(review.isReviewing)
    }

    @Test fun viewportAndHorizontalBoundsPreserveSnapshot() {
        val snapshot = accessibilitySnapshot()
        val review = TerminalReviewSession { snapshot }
        review.resize(3, 20)
        review.begin(snapshot, 5, 4)
        review.scroll(-100, 100)
        assertEquals(0, review.firstRow)
        assertEquals(20, review.firstColumn)
        assertFalse(review.scroll(-1, 1))
        review.focus(14)
        assertEquals(12, review.firstRow)
        review.resize(8, 40)
        assertEquals(7, review.firstRow)
        assertEquals(0, review.firstColumn)
        assertEquals("Screen 2", review.screenState.getVisibleLine(7).text)
        assertSame(snapshot, review.document!!.snapshot)
    }

    @Test fun searchingAcrossSoftWrapPreservesRowOffsets() {
        val first = accessibilityLine("echo ").copy(softWrapped = true)
        val next = accessibilityLine("hello")
        val document = TerminalReviewDocument(accessibilitySnapshot(0, 2).copy(lines = listOf(first, next)))
        assertEquals(listOf(0 to 0), document.search("echo hello"))
        assertEquals(listOf(1 to 0), document.search("HELLO"))
        assertTrue(document.search("").isEmpty())
    }

    @Test fun returnToInputIsExplicit() {
        val review = TerminalReviewSession { accessibilitySnapshot() }
        var returns = 0
        review.onReturnToInput = { returns++ }
        review.startReview()
        review.resumeLiveOutput()
        assertEquals(0, returns)
        review.startReview()
        review.returnToInput()
        assertEquals(1, returns)
    }
}
