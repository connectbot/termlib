/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GranularNavigationTest {
    @Test fun promptNavigationSkipsWrappedPartsOfSameCommand() {
        val lines = List(5) { accessibilityLine("row $it") }.toMutableList()
        lines[0] = lines[0].copy(semanticSegments = listOf(SemanticSegment(0, 5, SemanticType.PROMPT, promptId = 1)))
        lines[1] = lines[1].copy(semanticSegments = listOf(SemanticSegment(0, 5, SemanticType.PROMPT, promptId = 1)))
        lines[4] = lines[4].copy(semanticSegments = listOf(SemanticSegment(0, 5, SemanticType.PROMPT, promptId = 2)))
        val document = TerminalReviewDocument(accessibilitySnapshot(0, 5).copy(lines = lines))
        assertEquals(4, document.prompt(0, true))
        assertEquals(0, document.prompt(4, false))
        assertNull(document.prompt(4, true))
        assertNull(document.prompt(0, false))
    }

    @Test fun characterOffsetsMapToCellsWithoutSplittingWideOrCombiningGlyphs() {
        val cells = listOf(
            TerminalLine.Cell('界', fgColor = androidx.compose.ui.graphics.Color.White, bgColor = androidx.compose.ui.graphics.Color.Black, width = 2),
            TerminalLine.Cell('e', listOf('\u0301'), androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.Black),
            TerminalLine.Cell('x', fgColor = androidx.compose.ui.graphics.Color.White, bgColor = androidx.compose.ui.graphics.Color.Black),
        )
        val text = TerminalAccessibleText(TerminalLine(0, cells))
        assertEquals("界e\u0301x", text.text)
        assertEquals(listOf(0, 2, 2, 3), text.text.indices.map(text::column))
    }
}
