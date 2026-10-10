/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityBoundsTest {
    @Test
    fun truncatedLineClampsAllSegmentAndGapBounds() {
        val result = annotated(
            "abc",
            listOf(
                SemanticSegment(0, 9, SemanticType.DEFAULT),
                SemanticSegment(10, 12, SemanticType.DEFAULT),
            ),
        )
        assertEquals("abc", result.text.trim())
    }

    @Test
    fun overlappingSegmentsDoNotRepeatText() {
        val result = annotated(
            "abcdef",
            listOf(
                SemanticSegment(0, 4, SemanticType.DEFAULT),
                SemanticSegment(2, 6, SemanticType.DEFAULT),
            ),
        )
        assertEquals("abcdef", result.text.replace(" ", ""))
    }

    @Test
    fun emptyLineAndInvalidSegmentsAreSafe() {
        assertEquals("", annotated("", listOf(SemanticSegment(-2, 7, SemanticType.DEFAULT))).text)
        assertEquals("abc", annotated("abc", listOf(SemanticSegment(2, 1, SemanticType.DEFAULT))).text)
    }

    @Test
    fun cellColumnsPreserveWideAndCombiningText() {
        val cells = listOf(
            TerminalLine.Cell('界', fgColor = Color.White, bgColor = Color.Black, width = 2),
            TerminalLine.Cell('e', listOf('\u0301'), Color.White, Color.Black),
            TerminalLine.Cell('Z', fgColor = Color.White, bgColor = Color.Black),
        )
        val line = TerminalLine(
            0,
            cells,
            semanticSegments = listOf(
                SemanticSegment(0, 2, SemanticType.DEFAULT),
                SemanticSegment(2, 3, SemanticType.COMMAND_INPUT),
                SemanticSegment(3, 4, SemanticType.DEFAULT),
            ),
        )
        assertEquals("界 Command: e\u0301 Z", annotated(line).text)
    }

    private fun annotated(text: String, segments: List<SemanticSegment>): AnnotatedString = annotated(
        TerminalLine(
            0,
            text.map { TerminalLine.Cell(it, fgColor = Color.White, bgColor = Color.Black) },
            semanticSegments = segments,
        ),
    )

    private fun annotated(line: TerminalLine): AnnotatedString {
        val method = Class.forName("org.connectbot.terminal.AccessibilityOverlayKt")
            .getDeclaredMethod("buildSemanticAnnotatedString", TerminalLine::class.java)
            .apply { isAccessible = true }
        return method.invoke(null, line) as AnnotatedString
    }
}
