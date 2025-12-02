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
    val terminalNative: TerminalNative,
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
        // Pre-size ArrayList to column count to avoid reallocations (typically 80-120 cells)
        val cells = ArrayList<TerminalLine.Cell>(cols)
        var col = 0

        while (col < cols) {
            cellRun.reset()
            val runLength = terminalNative.getCellRun(row, col, cellRun)

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

            // Convert CellRun colors to Compose Color using cache to prevent allocations
            val fgColor = ColorCache.get(cellRun.fgRed, cellRun.fgGreen, cellRun.fgBlue)
            val bgColor = ColorCache.get(cellRun.bgRed, cellRun.bgGreen, cellRun.bgBlue)

            // Process characters in the run
            var charIndex = 0
            var cellsInRun = 0

            while (charIndex < cellRun.chars.size && cellsInRun < runLength) {
                val char = cellRun.chars[charIndex]
                if (char == 0.toChar()) break

                // Use shared empty list by default, only allocate when actually needed
                var combiningChars: List<Char> = TerminalLine.EMPTY_COMBINING_CHARS
                charIndex++

                // Handle surrogate pairs (characters > U+FFFF like emoji)
                if (char.isHighSurrogate() && charIndex < cellRun.chars.size) {
                    val nextChar = cellRun.chars[charIndex]
                    if (nextChar.isLowSurrogate()) {
                        // This is a surrogate pair - store low surrogate in combiningChars
                        // NOW allocate mutable list
                        combiningChars = mutableListOf(nextChar)
                        charIndex++
                    }
                }

                // Collect combining characters
                while (charIndex < cellRun.chars.size && isCombiningCharacter(cellRun.chars[charIndex])) {
                    // Lazily allocate on first combining char
                    if (combiningChars === TerminalLine.EMPTY_COMBINING_CHARS) {
                        combiningChars = mutableListOf()
                    }
                    (combiningChars as MutableList).add(cellRun.chars[charIndex])
                    charIndex++
                }

                // Determine cell width (for fullwidth characters)
                // For surrogate pairs, check the full codepoint
                val width = if (combiningChars.isNotEmpty() && combiningChars[0].isLowSurrogate()) {
                    // Reconstruct codepoint from surrogate pair
                    val codepoint = Character.toCodePoint(char, combiningChars[0])
                    if (isFullwidthCodepoint(codepoint)) 2 else 1
                } else {
                    if (isFullwidthCharacter(char)) 2 else 1
                }

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
     * Move/copy a rectangular region (optimization for scrolling).
     * This is called when libvterm wants to scroll content by copying it.
     */
    internal fun moveRect(dest: TermRect, src: TermRect) {
        // Copy lines from src to dest
        // This is typically used for scrolling - moving existing lines up or down
        val numRows = src.endRow - src.startRow

        if (src.startCol == 0 && src.endCol == cols && dest.startCol == 0 && dest.endCol == cols) {
            // Full-width move - we can copy entire lines
            if (dest.startRow < src.startRow) {
                // Moving up - copy from top to bottom
                for (i in 0 until numRows) {
                    val srcRow = src.startRow + i
                    val destRow = dest.startRow + i
                    _lines[destRow] = _lines[srcRow]?.copy(row = destRow)
                        ?: TerminalLine.empty(destRow, cols, defaultForeground, defaultBackground)
                }
            } else {
                // Moving down - copy from bottom to top
                for (i in (numRows - 1) downTo 0) {
                    val srcRow = src.startRow + i
                    val destRow = dest.startRow + i
                    _lines[destRow] = _lines[srcRow]?.copy(row = destRow)
                        ?: TerminalLine.empty(destRow, cols, defaultForeground, defaultBackground)
                }
            }
        } else {
            // Partial-width move - would need to copy cells within lines
            // For now, just mark the destination region as damaged and let it re-render
            updateRegion(dest.startRow, dest.endRow, dest.startCol, dest.endCol)
        }
    }

    /**
     * Resize the terminal.
     */
    fun resize(newRows: Int, newCols: Int, scrollbackRows: Int = 100) {
        rows = newRows
        cols = newCols
        terminalNative.resize(newRows, newCols, scrollbackRows)

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
                fgColor = ColorCache.get(screenCell.fgRed, screenCell.fgGreen, screenCell.fgBlue),
                bgColor = ColorCache.get(screenCell.bgRed, screenCell.bgGreen, screenCell.bgBlue),
                bold = screenCell.bold,
                italic = screenCell.italic,
                underline = screenCell.underline,
                reverse = screenCell.reverse,
                strike = screenCell.strike,
                width = screenCell.width
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
     * Pop a line from scrollback buffer (called during resize when terminal needs to restore lines).
     * Converts TerminalLine back to raw ScreenCell data for libvterm.
     * Returns true if a line was available and popped, false otherwise.
     */
    internal fun popScrollbackLine(cols: Int, cells: Array<ScreenCell>): Boolean {
        if (_scrollback.isEmpty()) {
            return false
        }

        // Remove the last line from scrollback (most recent)
        val line = _scrollback.removeAt(_scrollback.size - 1)

        // Adjust scrollback position if we're currently scrolled
        if (scrollbackPosition > 0) {
            scrollbackPosition = (scrollbackPosition - 1).coerceAtLeast(0)
        }

        // Copy the line's cells into the provided array
        val colsToCopy = minOf(cols, line.cells.size)
        for (i in 0 until colsToCopy) {
            val cell = line.cells[i]
            cells[i] = ScreenCell(
                char = cell.char,
                combiningChars = cell.combiningChars,
                fgRed = (cell.fgColor.red * 255).toInt(),
                fgGreen = (cell.fgColor.green * 255).toInt(),
                fgBlue = (cell.fgColor.blue * 255).toInt(),
                bgRed = (cell.bgColor.red * 255).toInt(),
                bgGreen = (cell.bgColor.green * 255).toInt(),
                bgBlue = (cell.bgColor.blue * 255).toInt(),
                bold = cell.bold,
                italic = cell.italic,
                underline = cell.underline,
                reverse = cell.reverse,
                strike = cell.strike,
                width = cell.width
            )
        }

        // Fill remaining cells with empty cells if line was shorter than cols
        for (i in colsToCopy until cols) {
            cells[i] = ScreenCell(
                char = ' ',
                combiningChars = emptyList(),
                fgRed = (defaultForeground.red * 255).toInt(),
                fgGreen = (defaultForeground.green * 255).toInt(),
                fgBlue = (defaultForeground.blue * 255).toInt(),
                bgRed = (defaultBackground.red * 255).toInt(),
                bgGreen = (defaultBackground.green * 255).toInt(),
                bgBlue = (defaultBackground.blue * 255).toInt(),
                bold = false,
                italic = false,
                underline = 0,
                reverse = false,
                strike = false,
                width = 1
            )
        }

        return true
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

    /**
     * Check if a codepoint (including those > U+FFFF) is fullwidth.
     */
    private fun isFullwidthCodepoint(codepoint: Int): Boolean {
        val eastAsianWidth = UCharacter.getIntPropertyValue(codepoint, UProperty.EAST_ASIAN_WIDTH)
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

                override fun moverect(dest: TermRect, src: TermRect): Int {
                    buffer?.moveRect(dest, src)
                    return 1
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
                    val success = buffer?.popScrollbackLine(cols, cells) ?: false
                    return if (success) 1 else 0
                }

                override fun onKeyboardInput(data: ByteArray): Int {
                    onKeyboardInput?.invoke(data)
                    return 0
                }
            }

            val terminalNative = TerminalNative(callbacks)
            buffer = TerminalBuffer(
                terminalNative = terminalNative,
                initialRows = initialRows,
                initialCols = initialCols,
                defaultForeground = defaultForeground,
                defaultBackground = defaultBackground
            )

            return buffer
        }
    }
}