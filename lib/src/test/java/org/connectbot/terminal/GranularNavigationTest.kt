/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class GranularNavigationTest {
    @Test
    fun testFindNextPromptForward() {
        val lines = createTestLinesWithPrompts()

        // From line 0, find next prompt (should be line 3)
        val nextIndex = findNextLineWithSegmentType(lines, 0, SemanticType.PROMPT, forward = true)
        assertEquals(3, nextIndex)
    }

    @Test
    fun testFindNextPromptBackward() {
        val lines = createTestLinesWithPrompts()

        // From line 5, find previous prompt (should be line 3)
        val prevIndex = findNextLineWithSegmentType(lines, 5, SemanticType.PROMPT, forward = false)
        assertEquals(3, prevIndex)
    }

    @Test
    fun testFindPromptNotFound() {
        val lines = createTestLinesWithoutPrompts()

        // No prompts in list
        val nextIndex = findNextLineWithSegmentType(lines, 0, SemanticType.PROMPT, forward = true)
        assertEquals(-1, nextIndex)
    }

    @Test
    fun testFindCommandOutput() {
        val lines = createTestLinesWithOutput()

        // Find next command output
        val outputIndex = findNextLineWithSegmentType(lines, 0, SemanticType.COMMAND_OUTPUT, forward = true)
        assertTrue(outputIndex > 0)
    }

    @Test
    fun testNavigationAtBoundaries() {
        val lines = createTestLinesWithPrompts()

        // At end, searching forward should return -1
        val atEnd = findNextLineWithSegmentType(lines, lines.size - 1, SemanticType.PROMPT, forward = true)
        assertEquals(-1, atEnd)

        // At start, searching backward should return -1
        val atStart = findNextLineWithSegmentType(lines, 0, SemanticType.PROMPT, forward = false)
        assertEquals(-1, atStart)
    }

    @Test
    fun testMultiplePromptsInSequence() {
        val lines = createTestLinesWithMultiplePrompts()

        // Should find each prompt in sequence
        var index = -1
        var promptCount = 0

        while (true) {
            index = findNextLineWithSegmentType(lines, index, SemanticType.PROMPT, forward = true)
            if (index == -1) break
            promptCount++
        }

        assertEquals(3, promptCount)
    }

    // Helper functions to find segments (would normally be in AccessibilityOverlay.kt)
    private fun findNextLineWithSegmentType(
        lines: List<TerminalLine>,
        currentIndex: Int,
        semanticType: SemanticType,
        forward: Boolean
    ): Int {
        val range = if (forward) {
            (currentIndex + 1 until lines.size)
        } else {
            (currentIndex - 1 downTo 0)
        }

        for (i in range) {
            if (lines[i].getSegmentsOfType(semanticType).isNotEmpty()) {
                return i
            }
        }

        return -1
    }

    private fun createTestLinesWithPrompts(): List<TerminalLine> {
        return listOf(
            // Line 0: Plain text
            TerminalLine(
                row = 0,
                cells = createCells("Plain text"),
                semanticSegments = emptyList()
            ),
            // Line 1: Output
            TerminalLine(
                row = 1,
                cells = createCells("Output line"),
                semanticSegments = listOf(
                    SemanticSegment(0, 11, SemanticType.COMMAND_OUTPUT)
                )
            ),
            // Line 2: More output
            TerminalLine(
                row = 2,
                cells = createCells("More output"),
                semanticSegments = listOf(
                    SemanticSegment(0, 11, SemanticType.COMMAND_OUTPUT)
                )
            ),
            // Line 3: Prompt + command
            TerminalLine(
                row = 3,
                cells = createCells("$ command"),
                semanticSegments = listOf(
                    SemanticSegment(0, 2, SemanticType.PROMPT),
                    SemanticSegment(2, 9, SemanticType.COMMAND_INPUT)
                )
            ),
            // Line 4: Output
            TerminalLine(
                row = 4,
                cells = createCells("Result"),
                semanticSegments = listOf(
                    SemanticSegment(0, 6, SemanticType.COMMAND_OUTPUT)
                )
            ),
            // Line 5: More output
            TerminalLine(
                row = 5,
                cells = createCells("More results"),
                semanticSegments = listOf(
                    SemanticSegment(0, 12, SemanticType.COMMAND_OUTPUT)
                )
            )
        )
    }

    private fun createTestLinesWithoutPrompts(): List<TerminalLine> {
        return listOf(
            TerminalLine(0, createCells("No prompts here")),
            TerminalLine(1, createCells("Just plain text")),
            TerminalLine(2, createCells("Nothing special"))
        )
    }

    private fun createTestLinesWithOutput(): List<TerminalLine> {
        return listOf(
            TerminalLine(0, createCells("Start")),
            TerminalLine(
                row = 1,
                cells = createCells("Output"),
                semanticSegments = listOf(
                    SemanticSegment(0, 6, SemanticType.COMMAND_OUTPUT)
                )
            )
        )
    }

    private fun createTestLinesWithMultiplePrompts(): List<TerminalLine> {
        return listOf(
            // Prompt 1
            TerminalLine(
                row = 0,
                cells = createCells("$ cmd1"),
                semanticSegments = listOf(SemanticSegment(0, 2, SemanticType.PROMPT))
            ),
            // Prompt 2
            TerminalLine(
                row = 1,
                cells = createCells("$ cmd2"),
                semanticSegments = listOf(SemanticSegment(0, 2, SemanticType.PROMPT))
            ),
            // Prompt 3
            TerminalLine(
                row = 2,
                cells = createCells("$ cmd3"),
                semanticSegments = listOf(SemanticSegment(0, 2, SemanticType.PROMPT))
            )
        )
    }

    private fun createCells(text: String): List<TerminalLine.Cell> {
        return text.map { char ->
            TerminalLine.Cell(
                char = char,
                fgColor = Color.White,
                bgColor = Color.Black
            )
        }
    }
}
