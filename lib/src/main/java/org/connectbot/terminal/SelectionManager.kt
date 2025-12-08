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
     * @param mode The selection mode to use (BLOCK or LINE)
     */
    fun startSelection(mode: SelectionMode = SelectionMode.BLOCK)

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
     * Toggle between BLOCK and LINE selection modes.
     */
    fun toggleSelectionMode()

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

enum class SelectionMode {
    NONE,
    BLOCK,
    LINE
}

internal data class SelectionRange(
    val startRow: Int,
    val startCol: Int,
    val endRow: Int,
    val endCol: Int
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
    var mode by mutableStateOf(SelectionMode.NONE)
        private set

    var selectionRange by mutableStateOf<SelectionRange?>(null)
        private set

    var isSelecting by mutableStateOf(false)
        private set

    fun startSelection(row: Int, col: Int, mode: SelectionMode = SelectionMode.BLOCK) {
        this.mode = mode
        isSelecting = true
        selectionRange = SelectionRange(row, col, row, col)
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

    fun toggleMode(cols: Int) {
        mode = when (mode) {
            SelectionMode.BLOCK -> SelectionMode.LINE
            SelectionMode.LINE -> SelectionMode.BLOCK
            SelectionMode.NONE -> SelectionMode.BLOCK
        }

        // Adjust selection range for line mode
        if (mode == SelectionMode.LINE && selectionRange != null) {
            val range = selectionRange!!
            selectionRange = range.copy(
                startCol = 0,
                endCol = cols - 1
            )
        }
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
                        line.cells.forEach { cell ->
                            append(cell.char)
                            cell.combiningChars.forEach { append(it) }
                        }
                        if (row < maxRow) append('\n')
                    }
                    SelectionMode.BLOCK -> {
                        val startCol = when (row) {
                            minRow -> minOf(range.startCol, range.endCol)
                            else -> 0
                        }
                        val endCol = when (row) {
                            maxRow -> maxOf(range.startCol, range.endCol)
                            else -> line.cells.size - 1
                        }

                        for (col in startCol..minOf(endCol, line.cells.lastIndex)) {
                            val cell = line.cells[col]
                            append(cell.char)
                            cell.combiningChars.forEach { append(it) }
                        }
                        if (row < maxRow) append('\n')
                    }
                    SelectionMode.NONE -> {}
                }
            }
        }.trim()
    }

    fun isCellSelected(row: Int, col: Int): Boolean {
        val range = selectionRange ?: return false
        return when (mode) {
            SelectionMode.LINE -> {
                val minRow = minOf(range.startRow, range.endRow)
                val maxRow = maxOf(range.startRow, range.endRow)
                row in minRow..maxRow
            }
            SelectionMode.BLOCK -> range.contains(row, col)
            SelectionMode.NONE -> false
        }
    }
}
