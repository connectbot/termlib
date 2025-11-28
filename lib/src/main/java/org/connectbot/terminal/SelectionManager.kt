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

enum class SelectionMode {
    NONE,
    BLOCK,
    LINE
}

data class SelectionRange(
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

class SelectionManager {
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

    fun getSelectedText(snapshot: TerminalSnapshot): String {
        val range = selectionRange ?: return ""

        val minRow = minOf(range.startRow, range.endRow)
        val maxRow = maxOf(range.startRow, range.endRow)

        return buildString {
            for (row in minRow..maxRow) {
                val line = snapshot.lines.getOrNull(row) ?: continue

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
