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

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Manages the terminal screen buffer as Compose state.
 *
 * This class:
 * - Converts native terminal data (via CellRun) to Compose-friendly TerminalLine objects
 * - Tracks which lines have been modified (damage tracking)
 * - Exposes terminal content as observable Compose State
 * - Handles cursor position updates
 */
@Stable
class TerminalBuffer(
    val terminal: Terminal,
    initialRows: Int,
    initialCols: Int,
    val defaultForeground: Color = Color.White,
    val defaultBackground: Color = Color.Black,
    private val userCallbacks: TerminalCallbacks? = null
) {
    // Terminal dimensions
    var rows by mutableStateOf(initialRows)
        private set
    var cols by mutableStateOf(initialCols)
        private set

    // Terminal lines - using StateMap for automatic recomposition
    private val _lines = mutableStateMapOf<Int, TerminalLine>()
    val lines: Map<Int, TerminalLine> = _lines

    // Scrollback buffer - stores lines that have scrolled off screen
    private val _scrollback = mutableStateListOf<TerminalLine>()
    val scrollback: List<TerminalLine> = _scrollback
    private val maxScrollbackLines = 10000  // Maximum scrollback history

    // Scroll position (0 = bottom/current screen, >0 = scrolled back)
    var scrollbackPosition by mutableStateOf(0)

    // Cursor state
    var cursorRow by mutableStateOf(0)
        private set
    var cursorCol by mutableStateOf(0)
        private set
    var cursorVisible by mutableStateOf(true)
        private set

    // Terminal properties
    var terminalTitle by mutableStateOf("")
        private set

    // Reusable CellRun for fetching cell data
    private val cellRun = CellRun()

    /**
     * Update a region of the terminal based on damage notification.
     * This is called from the damage callback.
     */
    fun updateRegion(startRow: Int, endRow: Int, startCol: Int, endCol: Int) {
        for (row in startRow until minOf(endRow, rows)) {
            updateLine(row)
        }
    }

    /**
     * Update a single line by fetching cell data from the terminal.
     */
    private fun updateLine(row: Int) {
        val cells = mutableListOf<TerminalLine.Cell>()
        var col = 0

        while (col < cols) {
            cellRun.reset()
            val runLength = terminal.getCellRun(row, col, cellRun)

            if (runLength <= 0) {
                // Fill remaining with empty cells
                while (col < cols) {
                    cells.add(
                        TerminalLine.Cell(
                            char = ' ',
                            fgColor = defaultForeground,
                            bgColor = defaultBackground
                        )
                    )
                    col++
                }
                break
            }

            // Convert CellRun colors to Compose Color
            val fgColor = Color(cellRun.fgRed, cellRun.fgGreen, cellRun.fgBlue)
            val bgColor = Color(cellRun.bgRed, cellRun.bgGreen, cellRun.bgBlue)

            // Process characters in the run
            var charIndex = 0
            var cellsInRun = 0

            while (charIndex < cellRun.chars.size && cellsInRun < runLength) {
                val char = cellRun.chars[charIndex]
                if (char == 0.toChar()) break

                val combiningChars = mutableListOf<Char>()
                charIndex++

                // Collect combining characters
                while (charIndex < cellRun.chars.size && isCombiningCharacter(cellRun.chars[charIndex])) {
                    combiningChars.add(cellRun.chars[charIndex])
                    charIndex++
                }

                // Determine cell width (for fullwidth characters)
                val width = if (isFullwidthCharacter(char)) 2 else 1

                cells.add(
                    TerminalLine.Cell(
                        char = char,
                        combiningChars = combiningChars,
                        fgColor = fgColor,
                        bgColor = bgColor,
                        bold = cellRun.bold,
                        italic = cellRun.italic,
                        underline = cellRun.underline,
                        blink = cellRun.blink,
                        reverse = cellRun.reverse,
                        strike = cellRun.strike,
                        width = width
                    )
                )

                cellsInRun++

                // Skip next cell if this is fullwidth
                if (width == 2) {
                    cellsInRun++
                }
            }

            col += cellsInRun
        }

        // Update the line in state
        _lines[row] = TerminalLine(row, cells)
    }

    /**
     * Update cursor position.
     */
    fun updateCursor(row: Int, col: Int, visible: Boolean) {
        cursorRow = row
        cursorCol = col
        cursorVisible = visible
    }

    /**
     * Update terminal property.
     */
    fun updateProperty(prop: Int, value: TerminalProperty) {
        when (value) {
            is TerminalProperty.StringValue -> {
                // Property 3 is VTERM_PROP_TITLE
                if (prop == 3) {
                    terminalTitle = value.value
                }
            }
            is TerminalProperty.BoolValue -> {
                // Property 0 is VTERM_PROP_CURSORVISIBLE
                if (prop == 0) {
                    cursorVisible = value.value
                }
            }
            else -> {
                // Other properties can be handled as needed
            }
        }
    }

    /**
     * Resize the terminal.
     */
    fun resize(newRows: Int, newCols: Int, scrollbackRows: Int = 100) {
        rows = newRows
        cols = newCols
        terminal.resize(newRows, newCols, scrollbackRows)

        // Clear and update all lines after resize
        _lines.clear()
        updateRegion(0, newRows, 0, newCols)
    }

    /**
     * Get a specific line, creating an empty one if it doesn't exist.
     * If scrollbackPosition > 0, returns line from scrollback buffer.
     */
    fun getLine(row: Int): TerminalLine {
        if (scrollbackPosition > 0) {
            // Calculate actual row in scrollback
            val scrollbackRow = row - scrollbackPosition
            if (scrollbackRow < 0) {
                // This row is in scrollback buffer
                val scrollbackIndex = _scrollback.size + scrollbackRow
                if (scrollbackIndex >= 0 && scrollbackIndex < _scrollback.size) {
                    return _scrollback[scrollbackIndex]
                }
            } else if (scrollbackRow < rows) {
                // This row is in visible screen
                return _lines[scrollbackRow] ?: TerminalLine.empty(scrollbackRow, cols, defaultForeground, defaultBackground)
            }
        }
        return _lines[row] ?: TerminalLine.empty(row, cols, defaultForeground, defaultBackground)
    }

    /**
     * Push a line to scrollback buffer (called from callback when line scrolls off top).
     * Converts raw ScreenCell data from libvterm into TerminalLine.
     */
    internal fun pushScrollbackLine(cols: Int, cells: Array<ScreenCell>) {
        val cellList = cells.take(cols).map { screenCell ->
            TerminalLine.Cell(
                char = screenCell.char,
                combiningChars = screenCell.combiningChars.filter { it != '\u0000' },
                fgColor = Color(screenCell.fgRed, screenCell.fgGreen, screenCell.fgBlue),
                bgColor = Color(screenCell.bgRed, screenCell.bgGreen, screenCell.bgBlue),
                bold = screenCell.bold,
                italic = screenCell.italic,
                underline = screenCell.underline,
                reverse = screenCell.reverse,
                strike = screenCell.strike
            )
        }
        val line = TerminalLine(row = -1, cells = cellList)  // row -1 for scrollback

        _scrollback.add(line)

        // Limit scrollback size
        if (_scrollback.size > maxScrollbackLines) {
            _scrollback.removeAt(0)
        }
    }

    /**
     * Scroll up (view older content).
     * @param lines Number of lines to scroll up
     */
    fun scrollUp(lines: Int = 1) {
        val maxScroll = _scrollback.size
        scrollbackPosition = (scrollbackPosition + lines).coerceIn(0, maxScroll)
    }

    /**
     * Scroll down (view newer content).
     * @param lines Number of lines to scroll down
     */
    fun scrollDown(lines: Int = 1) {
        scrollbackPosition = (scrollbackPosition - lines).coerceAtLeast(0)
    }

    /**
     * Scroll to bottom (current screen).
     */
    fun scrollToBottom() {
        scrollbackPosition = 0
    }

    /**
     * Scroll to top (oldest scrollback).
     */
    fun scrollToTop() {
        scrollbackPosition = _scrollback.size
    }

    /**
     * Clear scrollback buffer (useful when switching screens or clearing terminal).
     */
    fun clearScrollback() {
        _scrollback.clear()
        scrollbackPosition = 0
    }

    /**
     * Check if a character is a Unicode combining character.
     */
    private fun isCombiningCharacter(char: Char): Boolean {
        return UCharacter.hasBinaryProperty(char.code, UProperty.GRAPHEME_EXTEND)
    }

    /**
     * Check if a character is fullwidth (East Asian).
     */
    private fun isFullwidthCharacter(char: Char): Boolean {
        val eastAsianWidth = UCharacter.getIntPropertyValue(char.code, UProperty.EAST_ASIAN_WIDTH)
        return eastAsianWidth == UCharacter.EastAsianWidth.FULLWIDTH ||
               eastAsianWidth == UCharacter.EastAsianWidth.WIDE
    }

    companion object {
        /**
         * Create a TerminalBuffer with Terminal and automatic scrollback handling.
         *
         * @param initialRows Initial number of rows
         * @param initialCols Initial number of columns
         * @param defaultForeground Default foreground color
         * @param defaultBackground Default background color
         * @param onKeyboardInput Optional callback for keyboard input (for PTY integration)
         * @param onBell Optional callback for terminal bell
         * @return TerminalBuffer with Terminal configured
         */
        fun create(
            initialRows: Int,
            initialCols: Int,
            defaultForeground: Color = Color.White,
            defaultBackground: Color = Color.Black,
            onKeyboardInput: ((ByteArray) -> Unit)? = null,
            onBell: (() -> Unit)? = null
        ): TerminalBuffer {
            var buffer: TerminalBuffer? = null

            val callbacks = object : TerminalCallbacks {
                override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int): Int {
                    buffer?.updateRegion(startRow, endRow, startCol, endCol)
                    return 0
                }

                override fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean): Int {
                    buffer?.updateCursor(pos.row, pos.col, visible)
                    return 0
                }

                override fun setTermProp(prop: Int, value: TerminalProperty): Int {
                    buffer?.updateProperty(prop, value)
                    return 0
                }

                override fun bell(): Int {
                    onBell?.invoke()
                    return 0
                }

                override fun pushScrollbackLine(cols: Int, cells: Array<ScreenCell>): Int {
                    buffer?.pushScrollbackLine(cols, cells)
                    return 0
                }

                override fun popScrollbackLine(cols: Int, cells: Array<ScreenCell>): Int {
                    return 0
                }

                override fun onKeyboardInput(data: ByteArray): Int {
                    onKeyboardInput?.invoke(data)
                    return 0
                }
            }

            val terminal = Terminal(callbacks)
            buffer = TerminalBuffer(
                terminal = terminal,
                initialRows = initialRows,
                initialCols = initialCols,
                defaultForeground = defaultForeground,
                defaultBackground = defaultBackground
            )

            return buffer
        }
    }
}