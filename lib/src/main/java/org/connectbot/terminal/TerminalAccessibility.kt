/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Feedback preferences. Direct terminal input remains the default input mode. */
data class TerminalAccessibilityOptions(
    val announceOutput: Boolean = true,
    val announceCursor: Boolean = true,
)

/** Main-thread controls for inspecting a frozen copy of terminal output. */
interface TerminalAccessibilityController {
    val isReviewing: Boolean
    fun startReview()
    fun refreshReview()
    fun resumeLiveOutput()
    fun returnToInput()
}

/** A bounded, immutable document. Indices are stable for its entire review lifetime. */
internal class TerminalReviewDocument(val snapshot: TerminalSnapshot) {
    val lines = if (snapshot.alternateScreen) snapshot.lines else snapshot.scrollback + snapshot.lines
    val cursorIndex = (if (snapshot.alternateScreen) 0 else snapshot.scrollback.size) + snapshot.cursorRow
    private val textRows = mutableMapOf<Int, TerminalAccessibleText>()
    fun text(row: Int): TerminalAccessibleText = textRows.getOrPut(row) { TerminalAccessibleText(lines[row]) }
    val selection by lazy { TerminalReviewSelection(this) }

    fun prompt(from: Int, forward: Boolean): Int? {
        val currentId = lines.getOrNull(from)?.semanticSegments?.firstOrNull { it.semanticType == SemanticType.PROMPT }?.promptId
        val range = if (forward) from + 1 until lines.size else from - 1 downTo 0
        val target = range.firstOrNull { row ->
            lines[row].semanticSegments.any {
                it.semanticType == SemanticType.PROMPT && (currentId == null || currentId < 0 || it.promptId != currentId)
            }
        } ?: return null
        val targetId = lines[target].semanticSegments.first { it.semanticType == SemanticType.PROMPT }.promptId
        if (targetId < 0) return target
        var start = target
        while (start > 0 && lines[start - 1].semanticSegments.any { it.semanticType == SemanticType.PROMPT && it.promptId == targetId }) start--
        return start
    }

    private val commandOutput by lazy { getLastCommandOutput(lines) }
    fun lastOutput(): String? = commandOutput

    /** Search logical text, joining soft wraps without inventing line breaks. */
    fun search(query: String): List<Pair<Int, Int>> {
        if (query.isEmpty()) return emptyList()
        val text = StringBuilder()
        val starts = IntArray(lines.size)
        for ((index, line) in lines.withIndex()) {
            starts[index] = text.length
            text.append(if (line.softWrapped) line.text else line.text.trimEnd())
            if (!line.softWrapped) text.append('\n')
        }
        val result = mutableListOf<Pair<Int, Int>>()
        var offset = text.indexOf(query, ignoreCase = true)
        while (offset >= 0) {
            val row = starts.binarySearch(offset).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
            result.add(row to offset - starts[row])
            offset = text.indexOf(query, offset + query.length, ignoreCase = true)
        }
        return result
    }
}

internal class TerminalReviewSession(private val latest: () -> TerminalSnapshot) : TerminalAccessibilityController {
    var document by mutableStateOf<TerminalReviewDocument?>(null)
        private set
    override val isReviewing: Boolean get() = document != null
    var firstRow by mutableStateOf(0)
        private set
    var firstColumn by mutableStateOf(0)
        private set
    var focusedRow by mutableStateOf<Int?>(null)
        private set
    var viewportRows = 1
        private set
    var viewportColumns = 1
        private set
    val screenState = TerminalScreenState(latest())
    var onReturnToInput: () -> Unit = {}
    var onDocumentChanged: () -> Unit = {}

    override fun startReview() {
        if (!isReviewing) begin(latest())
    }

    fun begin(snapshot: TerminalSnapshot, row: Int? = null, top: Int? = null) {
        if (isReviewing) return
        document = TerminalReviewDocument(snapshot)
        focusedRow = row ?: document!!.cursorIndex.coerceIn(document!!.lines.indices)
        document!!.selection.move(ReviewPosition(focusedRow!!, document!!.text(focusedRow!!).offset(if (row == null) snapshot.cursorCol else 0)))
        firstRow = top ?: (focusedRow!! - viewportRows + 1).coerceAtLeast(0)
        firstColumn = 0
        syncViewport()
        onDocumentChanged()
    }

    override fun refreshReview() {
        document = null
        begin(latest())
    }

    override fun resumeLiveOutput() {
        document = null
        focusedRow = null
        firstColumn = 0
        onDocumentChanged()
    }

    override fun returnToInput() {
        resumeLiveOutput()
        onReturnToInput()
    }

    fun resize(rows: Int, columns: Int) {
        if (viewportRows == rows && viewportColumns == columns) return
        viewportRows = rows.coerceAtLeast(1)
        viewportColumns = columns.coerceAtLeast(1)
        syncViewport()
    }

    fun focus(row: Int): Boolean {
        val doc = document ?: return false
        if (row !in doc.lines.indices) return false
        focusedRow = row
        if (row < firstRow) firstRow = row
        if (row >= firstRow + viewportRows) firstRow = row - viewportRows + 1
        syncViewport()
        return true
    }

    fun scroll(rows: Int = 0, columns: Int = 0): Boolean {
        if (!isReviewing) startReview()
        val old = firstRow to firstColumn
        firstRow += rows
        firstColumn += columns
        syncViewport()
        return old != (firstRow to firstColumn)
    }

    private fun syncViewport() {
        val doc = document ?: return
        firstRow = firstRow.coerceIn(0, (doc.lines.size - viewportRows).coerceAtLeast(0))
        firstColumn = firstColumn.coerceIn(0, (doc.snapshot.cols - viewportColumns).coerceAtLeast(0))
        val rows = doc.lines.drop(firstRow).take(viewportRows)
        screenState.updateSnapshot(
            doc.snapshot.copy(
                lines = rows,
                scrollback = emptyList(),
                rows = rows.size,
                cursorVisible = false,
                sequenceNumber = screenState.snapshot.sequenceNumber + 1,
            ),
        )
    }
}
