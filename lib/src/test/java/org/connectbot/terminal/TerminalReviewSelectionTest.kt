/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@org.robolectric.annotation.Config(sdk = [24, 34])
@RunWith(AndroidJUnit4::class)
class TerminalReviewSelectionTest {
    private fun document(vararg lines: TerminalLine) = TerminalReviewDocument(accessibilitySnapshot(0, lines.size).copy(lines = lines.toList()))

    @Test fun charactersCrossHistoryAndWrapsWithoutSplittingGraphemes() {
        val doc = document(accessibilityLine("ab").copy(softWrapped = true), accessibilityLine("c\u0301😀"), accessibilityLine("next"))
        val selection = doc.selection
        selection.move(ReviewPosition(0, 2))
        val accented = selection.next(ReviewUnit.CHARACTER, true)!!
        assertEquals(ReviewPosition(1, 2), accented)
        selection.move(accented)
        val emoji = selection.next(ReviewUnit.CHARACTER, true)!!
        assertEquals(ReviewPosition(1, 4), emoji)
        selection.move(emoji)
        assertEquals(ReviewPosition(2, 1), selection.next(ReviewUnit.CHARACTER, true))
        selection.move(ReviewPosition(2, 0))
        assertEquals(ReviewPosition(1, 2), selection.next(ReviewUnit.CHARACTER, false))
        selection.move(ReviewPosition(1, 0))
        assertEquals(ReviewPosition(0, 1), selection.next(ReviewUnit.CHARACTER, false))
    }

    @Test fun wordsSpanSoftWrapsAndParagraphsFollowHardBreaks() {
        val selection = document(accessibilityLine("hel").copy(softWrapped = true), accessibilityLine("lo world"), accessibilityLine("last")).selection
        assertEquals(ReviewPosition(1, 2), selection.next(ReviewUnit.WORD, true))
        selection.move(ReviewPosition(1, 8))
        assertEquals(ReviewPosition(1, 3), selection.next(ReviewUnit.WORD, false))
        assertEquals(ReviewPosition(2, 0), selection.next(ReviewUnit.PARAGRAPH, true))
        selection.move(ReviewPosition(2, 0))
        assertEquals(ReviewPosition(0, 0), selection.next(ReviewUnit.PARAGRAPH, false))
        assertEquals(ReviewPosition(1, 0), selection.next(ReviewUnit.LINE, false))
    }

    @Test fun copyKeepsHardBreaksAndBlankRowsButJoinsWraps() {
        val selection = document(accessibilityLine("abc").copy(softWrapped = true), accessibilityLine("def   "), accessibilityLine("   "), accessibilityLine("ghi")).selection
        selection.set(ReviewPosition(3, 2), ReviewPosition(0, 1))
        assertEquals("bcdef\n\ngh", selection.copyText())
        selection.switchEndpoint()
        assertEquals(ReviewPosition(3, 2), selection.active)
        assertEquals("bcdef\n\ngh", selection.copyText())
        assertEquals(0 to 6, selection.intersection(1))
        selection.clear()
        assertTrue(selection.isEmpty)
        assertEquals("", selection.copyText())
    }

    @Test fun verticalMovementKeepsPreferredCellThroughShortAndWideRows() {
        val wide = TerminalLine(0, listOf(TerminalLine.Cell('界', width = 2, fgColor = Color.White, bgColor = Color.Black), TerminalLine.Cell('x', fgColor = Color.White, bgColor = Color.Black)))
        val selection = document(accessibilityLine("abcd"), accessibilityLine("a"), wide, accessibilityLine("abcd")).selection
        selection.move(ReviewPosition(0, 2))
        selection.marking = true
        assertTrue(selection.vertical(1))
        assertEquals(ReviewPosition(1, 1), selection.active)
        assertTrue(selection.vertical(1))
        assertEquals(ReviewPosition(2, 1), selection.active)
        assertTrue(selection.vertical(1))
        assertEquals(ReviewPosition(3, 2), selection.active)
        assertEquals(ReviewPosition(0, 2), selection.anchor)
    }

    @Test fun emptyLinesAndDocumentLimitsAreReachable() {
        val selection = document(accessibilityLine("a"), accessibilityLine(""), accessibilityLine("b")).selection
        assertNull(selection.next(ReviewUnit.CHARACTER, false))
        selection.move(ReviewPosition(0, 1))
        assertEquals(ReviewPosition(1, 0), selection.next(ReviewUnit.CHARACTER, true))
        selection.move(ReviewPosition(1, 0))
        assertEquals(ReviewPosition(2, 1), selection.next(ReviewUnit.CHARACTER, true))
        selection.move(selection.last)
        assertNull(selection.next(ReviewUnit.CHARACTER, true))
    }

    @Test fun bidirectionalTextRetainsLogicalCopyOrder() {
        val selection = document(accessibilityLine("ab אב").copy(softWrapped = true), accessibilityLine("גד cd")).selection
        selection.selectAll()
        assertEquals("ab אבגד cd", selection.copyText())
        selection.switchEndpoint()
        assertEquals("ab אבגד cd", selection.copyText())
    }

    @Test fun formattingUsesEffectiveColorsAndReportsMixedAttributes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val normal = TerminalLine.Cell('a', fgColor = Color.Red, bgColor = Color.Blue, bold = true, underline = 2)
        val reversed = normal.copy(char = 'b', reverse = true, italic = true)
        val doc = document(TerminalLine(0, listOf(normal, reversed)))
        doc.selection.move(ReviewPosition(0, 1))
        val single = describeReviewFormatting(context, doc)
        assertTrue(single.contains("Foreground: #0000FF"))
        assertTrue(single.contains("Background: #FF0000"))
        assertTrue(single.contains("Underline: double"))
        assertTrue(single.contains("Reverse video: on"))
        doc.selection.selectAll()
        val mixed = describeReviewFormatting(context, doc)
        assertTrue(mixed.contains("Foreground: mixed"))
        assertTrue(mixed.contains("Italic: mixed"))
        assertTrue(mixed.contains("Bold: on"))
    }
}
