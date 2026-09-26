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
     * Select all retained scrollback and current screen text.
     */
    fun selectAll()

    /** Select only the rows currently displayed in the terminal viewport. */
    fun selectAllVisible() = selectAll()

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
        val absoluteRow = row + (snapshot?.scrollback?.size?.minus(scrollbackPosition) ?: 0)
        selectionRange = SelectionRange(absoluteRow, col, absoluteRow, col)
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

    internal fun restoreSelectionRange(range: SelectionRange) {
        selectionRange = range
    }

    /** UI navigation uses visual columns; the selection and copied text stay logical. */
    fun moveVisually(dx: Int, dy: Int, state: TerminalScreenState, paint: TerminalTextPaint, cellWidth: Float) {
        val range = selectionRange ?: return
        fun move(row: Int, col: Int): Pair<Int, Int> {
            val firstVisible = state.visibleLineIndex(0)
            val viewportRow = (row - firstVisible).coerceIn(0, state.snapshot.rows - 1)
            val visual = paint.visualColumn(state, viewportRow, col, cellWidth)
            val nextRow = (row + dy).coerceIn(0, state.totalLines - 1)
            val nextVisual = (visual + dx).coerceIn(0, state.snapshot.cols - 1)
            val nextViewportRow = (nextRow - firstVisible).coerceIn(0, state.snapshot.rows - 1)
            return nextRow to paint.logicalColumn(state, nextViewportRow, nextVisual, cellWidth)
        }
        val end = move(range.endRow, range.endCol)
        val start = if (isSelecting) range.startRow to range.startCol else move(range.startRow, range.startCol)
        selectionRange = SelectionRange(start.first, start.second, end.first, end.second)
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

    fun selectAll(rows: Int, cols: Int, scrollbackRows: Int = 0) {
        mode = SelectionMode.CHARACTER
        isSelecting = false
        selectionRange = SelectionRange(0, 0, scrollbackRows + rows - 1, cols - 1)
    }

    fun selectAllVisible(rows: Int, cols: Int, firstVisibleRow: Int) {
        mode = SelectionMode.CHARACTER
        isSelecting = false
        selectionRange = SelectionRange(firstVisibleRow, 0, firstVisibleRow + rows - 1, cols - 1)
    }

    /** Keep absolute history anchors attached to their cells as snapshots change. */
    internal fun onSnapshotChanged(old: TerminalSnapshot, new: TerminalSnapshot) {
        val range = selectionRange ?: return
        if (old.alternateScreen != new.alternateScreen) {
            clearSelection()
            return
        }
        if (old.rows != new.rows || old.cols != new.cols) {
            val oldStream = SelectionCellStream(old)
            val newStream = SelectionCellStream(new)
            val startDistance = oldStream.distanceFromEnd(range.startRow, range.startCol)
            val endDistance = oldStream.distanceFromEnd(range.endRow, range.endCol)
            fun retained(row: Int) = old.scrollback.getOrNull(row)?.let { oldLine ->
                new.scrollback.any { it === oldLine }
            } == true
            if (newStream.total > 0 && startDistance >= newStream.total && endDistance >= newStream.total &&
                !retained(range.startRow) && !retained(range.endRow)
            ) {
                clearSelection()
                return
            }
            fun remap(row: Int, col: Int): Pair<Int, Int> {
                old.scrollback.getOrNull(row)?.let { oldLine ->
                    val retainedIndex = new.scrollback.indexOfFirst { it === oldLine }
                    if (retainedIndex >= 0) {
                        return retainedIndex to col.coerceIn(0, new.scrollback[retainedIndex].cells.lastIndex)
                    }
                }
                val distanceFromEnd = oldStream.distanceFromEnd(row, col)
                return newStream.positionFromEnd(distanceFromEnd)
            }
            val start = remap(range.startRow, range.startCol)
            val end = remap(range.endRow, range.endCol)
            selectionRange = SelectionRange(start.first, start.second, end.first, end.second)
            if (mode == SelectionMode.LINE) adjustSelectionForMode(new.cols, new)
            return
        }

        val oldHistory = old.scrollback
        val newHistory = new.scrollback
        val removedFromFront = when {
            oldHistory.isEmpty() -> 0

            newHistory.isEmpty() -> oldHistory.size

            else -> {
                val index = oldHistory.indexOfFirst { it === newHistory.first() }
                if (index >= 0) {
                    index
                } else {
                    // The history was replaced, so old anchors no longer identify content.
                    clearSelection()
                    return
                }
            }
        }
        if (removedFromFront == 0) return
        val last = new.scrollback.size + new.rows - 1
        if (range.startRow < removedFromFront && range.endRow < removedFromFront) {
            clearSelection()
            return
        }
        selectionRange = range.copy(
            startRow = (range.startRow - removedFromFront).coerceIn(0, last),
            startCol = if (range.startRow < removedFromFront) 0 else range.startCol,
            endRow = (range.endRow - removedFromFront).coerceIn(0, last),
            endCol = if (range.endRow < removedFromFront) 0 else range.endCol,
        )
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

    private fun getSnapshotLine(snapshot: TerminalSnapshot, row: Int, scrollbackPosition: Int = 0): TerminalLine? = if (row < snapshot.scrollback.size) {
        snapshot.scrollback.getOrNull(row)
    } else {
        snapshot.lines.getOrNull(row - snapshot.scrollback.size)
    }

    private fun isWordChar(char: Char): Boolean = char.isLetterOrDigit() || char == '_'

    private fun findWordBoundaries(line: TerminalLine, col: Int): Pair<Int, Int> {
        val cells = line.cells
        if (cells.isEmpty()) return Pair(0, 0)
        val safeCol = col.coerceIn(0, cells.lastIndex)

        // If the touch is in trailing whitespace with no word to the right, snap to the last word.
        if (!isWordChar(cells.charAt(safeCol))) {
            val lastWordEnd = cells.indices.lastOrNull { isWordChar(cells.charAt(it)) }
            if (lastWordEnd != null && lastWordEnd < safeCol) {
                var start = lastWordEnd
                while (start > 0 && isWordChar(cells.charAt(start - 1))) start--
                return Pair(start, lastWordEnd)
            }
        }

        val startChar = cells.charAt(safeCol)
        val targetingWord = isWordChar(startChar)

        var start = safeCol
        while (start > 0 && isWordChar(cells.charAt(start - 1)) == targetingWord) {
            start--
        }

        var end = safeCol
        while (end < cells.size - 1 && isWordChar(cells.charAt(end + 1)) == targetingWord) {
            end++
        }

        return Pair(start, end)
    }

    private fun lastContentCol(line: TerminalLine): Int {
        var last = line.cells.lastIndex
        while (last > 0 && (line.cells.width(last) == 0 || line.cells.blank(last))) last--
        return last
    }

    fun getSelectedText(snapshot: TerminalSnapshot, scrollbackPosition: Int = 0): String {
        val range = selectionRange ?: return ""

        val minRow = minOf(range.startRow, range.endRow)
        val maxRow = maxOf(range.startRow, range.endRow)

        // Anchor-aware, so the clipboard matches what SelectionRange.contains
        // drew: the first row starts at the anchor the selection began from and
        // the last row ends at the one it finished on. Using minOf/maxOf on the
        // two columns agreed with the highlight only for a down-and-right drag
        // — dragging down and LEFT copied text the user never highlighted (a
        // selection from row 0 col 8 to row 1 col 2 highlighted "IJ"/"abc" but
        // copied "CDEFGHIJ"/"abcdefghi").
        val (_, selStartCol) = range.getStartPosition()
        val (_, selEndCol) = range.getEndPosition()

        return buildString {
            for (row in minRow..maxRow) {
                val line = getSnapshotLine(snapshot, row)

                if (line == null) continue

                when (mode) {
                    SelectionMode.LINE -> {
                        // Build line text and trim trailing whitespace.
                        val lineText = line.text.trimEnd()
                        append(lineText)
                        if (row < maxRow && !line.softWrapped) append('\n')
                    }

                    SelectionMode.CHARACTER, SelectionMode.WORD -> {
                        val startCol = when (row) {
                            minRow -> selStartCol
                            else -> 0
                        }
                        val endCol = when (row) {
                            maxRow -> selEndCol
                            else -> line.cells.size - 1
                        }

                        // Build line text and trim trailing whitespace
                        val lineText = line.cells.text(startCol.coerceIn(0, line.cells.size), (endCol + 1).coerceIn(startCol.coerceIn(0, line.cells.size), line.cells.size)).trimEnd()
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

/** A resize preserves this ordered cell stream while changing its visual line breaks. */
private class SelectionCellStream(snapshot: TerminalSnapshot) {
    private val lines = snapshot.scrollback + snapshot.lines
    private val lastContentRow = lines.indexOfLast { line -> line.cells.indices.any { !line.cells.blank(it) } }
    private val lengths = IntArray(lines.size) { row ->
        if (row > lastContentRow) {
            0
        } else {
            val line = lines[row]
            if (line.softWrapped) {
                line.cells.size
            } else {
                line.cells.indices.lastOrNull { !line.cells.blank(it) }?.plus(1) ?: 0
            }
        }
    }
    private val spans = IntArray(lines.size) { row -> lengths[row] + if (row < lastContentRow && !lines[row].softWrapped) 1 else 0 }
    val total = spans.sum()

    fun distanceFromEnd(row: Int, col: Int): Int {
        if (total == 0) return 0
        val safeRow = row.coerceIn(0, lines.lastIndex)
        val before = (0 until safeRow).sumOf { spans[it] }
        val within = if (lengths[safeRow] == 0) 0 else col.coerceIn(0, lengths[safeRow] - 1)
        return (total - 1 - before - within).coerceAtLeast(0)
    }

    fun positionFromEnd(distance: Int): Pair<Int, Int> {
        if (total == 0) return 0 to 0
        var remaining = (total - 1 - distance).coerceIn(0, total - 1)
        for (row in spans.indices) {
            if (remaining < spans[row]) {
                return row to remaining.coerceAtMost((lengths[row] - 1).coerceAtLeast(0))
            }
            remaining -= spans[row]
        }
        return lastContentRow.coerceAtLeast(0) to (lengths[lastContentRow.coerceAtLeast(0)] - 1).coerceAtLeast(0)
    }
}
