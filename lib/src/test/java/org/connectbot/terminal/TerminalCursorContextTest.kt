/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalCursorContextTest {
    @Test
    fun wideHangulUsesOneCharacterPerGlyphBeforeCursor() {
        val snapshot = snapshot(
            cells = listOf(cell('밭', width = 2), cell('ㅁ', width = 2), cell(' ')),
            cursorCol = 4,
        )

        assertEquals("밭ㅁ", snapshot.textBeforeCursor())
    }

    @Test
    fun cursorInsideLineOnlyIncludesPrecedingCells() {
        val snapshot = snapshot(
            cells = listOf(cell('a'), cell('밭', width = 2), cell('b')),
            cursorCol = 3,
        )

        assertEquals("a밭", snapshot.textBeforeCursor())
    }

    private fun snapshot(cells: List<TerminalLine.Cell>, cursorCol: Int): TerminalSnapshot = TerminalSnapshot(
        lines = listOf(TerminalLine(row = 0, cells = cells)),
        scrollback = emptyList(),
        cursorRow = 0,
        cursorCol = cursorCol,
        cursorVisible = true,
        cursorBlink = true,
        cursorShape = CursorShape.BLOCK,
        terminalTitle = "",
        rows = 1,
        cols = cells.sumOf { it.width },
        timestamp = 0,
        sequenceNumber = 0,
    )

    private fun cell(char: Char, width: Int = 1) = TerminalLine.Cell(
        char = char,
        fgColor = Color.White,
        bgColor = Color.Black,
        width = width,
    )
}
