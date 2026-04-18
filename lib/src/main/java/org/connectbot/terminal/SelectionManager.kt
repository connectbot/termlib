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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Interface for controlling text selection in the terminal.
 * This allows external components (UI chrome, keyboard handlers, accessibility) to control selection.
 */
interface SelectionController {
    /**
     * Check if selection mode is currently active.
     */
    val isSelectionActive: Boolean

    /**
     * Start selection mode at the current cursor position or center of screen.
     * @param mode The selection mode to use (CHARACTER, WORD, or LINE)
     */
    fun startSelection(mode: SelectionMode = SelectionMode.CHARACTER)

    /**
     * Toggle selection mode on/off. If off, turns it on. If on, turns it off.
     */
    fun toggleSelection()

    /**
     * Move the selection cursor up by one row.
     */
    fun moveSelectionUp()

    /**
     * Move the selection cursor down by one row.
     */
    fun moveSelectionDown()

    /**
     * Move the selection cursor left by one column.
     */
    fun moveSelectionLeft()

    /**
     * Move the selection cursor right by one column.
     */
    fun moveSelectionRight()

    /**
     * Toggle between CHARACTER, WORD, and LINE selection modes.
     */
    fun toggleSelectionMode()

    /**
     * Set the selection mode directly.
     */
    fun setSelectionMode(mode: SelectionMode)

    /**
     * Select all text in the terminal.
     */
    fun selectAll()

    /**
     * Finish the selection (stop extending it, but keep it active for copying).
     */
    fun finishSelection()

    /**
     * Copy the selected text to clipboard and clear the selection.
     * @return The selected text, or empty string if no selection
     */
    fun copySelection(): String

    /**
     * Clear the selection without copying.
     */
    fun clearSelection()
}

sealed class SelectionMode {
    data object NONE : SelectionMode()
    data object CHARACTER : SelectionMode()
    data object WORD : SelectionMode()
    data object LINE : SelectionMode()
}

internal data class SelectionRange(
    val startRow: Int,
    val startCol: Int,
    val endRow: Int,
    val endCol: Int,
) {
    fun contains(row: Int, col: Int): Boolean {
        val minRow = minOf(startRow, endRow)
        val maxRow = maxOf(startRow, endRow)

        if (row !in minRow..maxRow) return false

        if (startRow == endRow) {
            val minCol = minOf(startCol, endCol)
            val maxCol = maxOf(startCol, endCol)
            return col in minCol..maxCol
        }

        return when (row) {
            minRow -> col >= if (startRow < endRow) startCol else endCol
            maxRow -> col <= if (startRow < endRow) endCol else startCol
            else -> true
        }
    }

    fun getStartPosition(): Pair<Int, Int> {
        if (startRow == endRow) return Pair(startRow, minOf(startCol, endCol))
        if (startRow < endRow) return Pair(startRow, startCol)
        return Pair(endRow, endCol)
    }

    fun getEndPosition(): Pair<Int, Int> {
        if (startRow == endRow) return Pair(startRow, maxOf(startCol, endCol))
        if (startRow < endRow) return Pair(endRow, endCol)
        return Pair(startRow, startCol)
    }
}

internal class SelectionManager {
    var mode by mutableStateOf<SelectionMode>(SelectionMode.NONE)
        private set

    var selectionRange by mutableStateOf<SelectionRange?>(null)
        private set

    var isSelecting by mutableStateOf(false)
        private set

    fun startSelection(
        row: Int,
        col: Int,
        cols: Int,
        mode: SelectionMode = SelectionMode.CHARACTER,
        snapshot: TerminalSnapshot? = null,
        scrollbackPosition: Int = 0,
    ) {
        this.mode = mode
        isSelecting = true
        selectionRange = SelectionRange(row, col, row, col)
        adjustSelectionForMode(cols, snapshot, scrollbackPosition)
    }

    fun updateSelection(row: Int, col: Int) {
        if (!isSelecting) return

        val range = selectionRange ?: return
        selectionRange = range.copy(endRow = row, endCol = col)
    }

    fun updateSelectionStart(row: Int, col: Int) {
        val range = selectionRange ?: return
        selectionRange = range.copy(startRow = row, startCol = col)
    }

    fun updateSelectionEnd(row: Int, col: Int) {
        val range = selectionRange ?: return
        selectionRange = range.copy(endRow = row, endCol = col)
    }

    fun moveSelectionUp(maxRow: Int) {
        val range = selectionRange ?: return
        if (isSelecting) {
            // During selection, move the end point up
            val newRow = (range.endRow - 1).coerceAtLeast(0)
            selectionRange = range.copy(endRow = newRow)
        } else {
            // After selection is finished, move both start and end up
            val newStartRow = (range.startRow - 1).coerceAtLeast(0)
            val newEndRow = (range.endRow - 1).coerceAtLeast(0)
            selectionRange = range.copy(startRow = newStartRow, endRow = newEndRow)
        }
    }

    fun moveSelectionDown(maxRow: Int) {
        val range = selectionRange ?: return
        if (isSelecting) {
            // During selection, move the end point down
            val newRow = (range.endRow + 1).coerceAtMost(maxRow - 1)
            selectionRange = range.copy(endRow = newRow)
        } else {
            // After selection is finished, move both start and end down
            val newStartRow = (range.startRow + 1).coerceAtMost(maxRow - 1)
            val newEndRow = (range.endRow + 1).coerceAtMost(maxRow - 1)
            selectionRange = range.copy(startRow = newStartRow, endRow = newEndRow)
        }
    }

    fun moveSelectionLeft(maxCol: Int) {
        val range = selectionRange ?: return
        if (isSelecting) {
            // During selection, move the end point left
            val newCol = (range.endCol - 1).coerceAtLeast(0)
            selectionRange = range.copy(endCol = newCol)
        } else {
            // After selection is finished, move both start and end left
            val newStartCol = (range.startCol - 1).coerceAtLeast(0)
            val newEndCol = (range.endCol - 1).coerceAtLeast(0)
            selectionRange = range.copy(startCol = newStartCol, endCol = newEndCol)
        }
    }

    fun moveSelectionRight(maxCol: Int) {
        val range = selectionRange ?: return
        if (isSelecting) {
            // During selection, move the end point right
            val newCol = (range.endCol + 1).coerceAtMost(maxCol - 1)
            selectionRange = range.copy(endCol = newCol)
        } else {
            // After selection is finished, move both start and end right
            val newStartCol = (range.startCol + 1).coerceAtMost(maxCol - 1)
            val newEndCol = (range.endCol + 1).coerceAtMost(maxCol - 1)
            selectionRange = range.copy(startCol = newStartCol, endCol = newEndCol)
        }
    }

    fun endSelection() {
        isSelecting = false
    }

    fun clearSelection() {
        mode = SelectionMode.NONE
        selectionRange = null
        isSelecting = false
    }

    fun toggleMode(cols: Int, snapshot: TerminalSnapshot? = null, scrollbackPosition: Int = 0) {
        mode = when (mode) {
            SelectionMode.CHARACTER -> SelectionMode.WORD
            SelectionMode.WORD -> SelectionMode.LINE
            SelectionMode.LINE -> SelectionMode.CHARACTER
            SelectionMode.NONE -> SelectionMode.CHARACTER
        }

        adjustSelectionForMode(cols, snapshot, scrollbackPosition)
    }

    fun setMode(newMode: SelectionMode, cols: Int, snapshot: TerminalSnapshot? = null, scrollbackPosition: Int = 0) {
        mode = newMode
        adjustSelectionForMode(cols, snapshot, scrollbackPosition)
    }

    fun selectAll(rows: Int, cols: Int) {
        mode = SelectionMode.CHARACTER
        isSelecting = false
        selectionRange = SelectionRange(0, 0, rows - 1, cols - 1)
    }

    /**
     * Clamps the selection range to the given dimensions.
     * Useful when the terminal is resized.
     */
    fun clampToDimensions(rows: Int, cols: Int) {
        val range = selectionRange ?: return
        val newStartRow = range.startRow.coerceAtMost(rows - 1)
        val newEndRow = range.endRow.coerceAtMost(rows - 1)
        val newStartCol = range.startCol.coerceAtMost(cols - 1)
        val newEndCol = range.endCol.coerceAtMost(cols - 1)

        if (newStartRow != range.startRow || newEndRow != range.endRow ||
            newStartCol != range.startCol || newEndCol != range.endCol
        ) {
            selectionRange = SelectionRange(newStartRow, newStartCol, newEndRow, newEndCol)
        }
    }

    internal fun adjustSelectionForMode(cols: Int, snapshot: TerminalSnapshot?, scrollbackPosition: Int = 0) {
        val range = selectionRange ?: return

        when (mode) {
            SelectionMode.LINE -> {
                selectionRange = range.copy(
                    startCol = 0,
                    endCol = cols - 1,
                )
            }

            SelectionMode.WORD -> {
                if (snapshot != null) {
                    val startLine = getSnapshotLine(snapshot, range.startRow, scrollbackPosition)
                    val endLine = getSnapshotLine(snapshot, range.endRow, scrollbackPosition)

                    if (startLine != null && endLine != null) {
                        val (newStartCol, _) = findWordBoundaries(startLine, range.startCol)
                        val (_, newEndCol) = findWordBoundaries(endLine, range.endCol)

                        selectionRange = range.copy(
                            startCol = newStartCol,
                            endCol = newEndCol,
                        )
                    }
                }
            }

            SelectionMode.CHARACTER, SelectionMode.NONE -> {
                // No adjustment needed
            }
        }
    }

    private fun getSnapshotLine(snapshot: TerminalSnapshot, row: Int, scrollbackPosition: Int = 0): TerminalLine? = if (scrollbackPosition > 0) {
        val scrollbackIndex = snapshot.scrollback.size - scrollbackPosition + row
        snapshot.scrollback.getOrNull(scrollbackIndex)
    } else {
        snapshot.lines.getOrNull(row)
    }

    private fun isWordChar(char: Char): Boolean = char.isLetterOrDigit() || char == '_'

    private fun findWordBoundaries(line: TerminalLine, col: Int): Pair<Int, Int> {
        val cells = line.cells
        if (cells.isEmpty()) return Pair(0, 0)
        val safeCol = col.coerceIn(0, cells.lastIndex)

        // If the touch is in trailing whitespace with no word to the right, snap to the last word.
        if (!isWordChar(cells[safeCol].char)) {
            val lastWordEnd = cells.indices.lastOrNull { isWordChar(cells[it].char) }
            if (lastWordEnd != null && lastWordEnd < safeCol) {
                var start = lastWordEnd
                while (start > 0 && isWordChar(cells[start - 1].char)) start--
                return Pair(start, lastWordEnd)
            }
        }

        val startChar = cells[safeCol].char
        val targetingWord = isWordChar(startChar)

        var start = safeCol
        while (start > 0 && isWordChar(cells[start - 1].char) == targetingWord) {
            start--
        }

        var end = safeCol
        while (end < cells.size - 1 && isWordChar(cells[end + 1].char) == targetingWord) {
            end++
        }

        return Pair(start, end)
    }

    private fun isBlankCell(cell: TerminalLine.Cell): Boolean = (cell.char == ' ' || cell.char == '\u0000') && cell.combiningChars.isEmpty()

    private fun lastContentCol(line: TerminalLine): Int {
        var last = line.cells.lastIndex
        while (last > 0 && isBlankCell(line.cells[last])) last--
        return last
    }

    fun getSelectedText(snapshot: TerminalSnapshot, scrollbackPosition: Int = 0): String {
        val range = selectionRange ?: return ""

        val minRow = minOf(range.startRow, range.endRow)
        val maxRow = maxOf(range.startRow, range.endRow)

        return buildString {
            for (row in minRow..maxRow) {
                // Get line from the appropriate source based on scrollback position
                val line = if (scrollbackPosition > 0) {
                    // Viewing scrollback: get from scrollback (stored newest-first, so reverse index)
                    val scrollbackIndex = snapshot.scrollback.size - scrollbackPosition + row
                    snapshot.scrollback.getOrNull(scrollbackIndex)
                } else {
                    // Viewing current screen: get from visible lines
                    snapshot.lines.getOrNull(row)
                }

                if (line == null) continue

                when (mode) {
                    SelectionMode.LINE -> {
                        // Build line text and trim trailing whitespace.
                        val lineText = buildString {
                            line.cells.forEach { cell ->
                                append(cell.char)
                                cell.combiningChars.forEach { append(it) }
                            }
                        }.trimEnd()
                        append(lineText)
                        if (row < maxRow && !line.softWrapped) append('\n')
                    }

                    SelectionMode.CHARACTER, SelectionMode.WORD -> {
                        val startCol = when (row) {
                            minRow -> minOf(range.startCol, range.endCol)
                            else -> 0
                        }
                        val endCol = when (row) {
                            maxRow -> maxOf(range.startCol, range.endCol)
                            else -> line.cells.size - 1
                        }

                        // Build line text and trim trailing whitespace
                        val lineText = buildString {
                            for (col in startCol..minOf(endCol, line.cells.lastIndex)) {
                                val cell = line.cells[col]
                                append(cell.char)
                                cell.combiningChars.forEach { append(it) }
                            }
                        }.trimEnd()
                        append(lineText)
                        if (row < maxRow && !line.softWrapped) append('\n')
                    }

                    SelectionMode.NONE -> {}
                }
            }
        }.trim()
    }

    fun isCellSelected(row: Int, col: Int, line: TerminalLine? = null): Boolean {
        val range = selectionRange ?: return false
        return when (mode) {
            SelectionMode.LINE -> {
                val minRow = minOf(range.startRow, range.endRow)
                val maxRow = maxOf(range.startRow, range.endRow)
                row in minRow..maxRow
            }

            SelectionMode.CHARACTER, SelectionMode.WORD -> {
                if (line != null && col > lastContentCol(line)) return false
                range.contains(row, col)
            }

            SelectionMode.NONE -> false
        }
    }
}
