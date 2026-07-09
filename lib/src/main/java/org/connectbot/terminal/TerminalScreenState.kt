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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Compose-specific state adapter for terminal screen rendering.
 *
 * This class bridges the gap between the Service-layer TerminalEmulator (which
 * emits immutable snapshots via StateFlow) and the Compose UI layer. It manages
 * UI-only state such as scroll position while observing terminal state changes.
 *
 * Separation of concerns:
 * - Terminal state (lines, cursor, etc.): Owned by TerminalEmulator
 * - UI state (scroll position, zoom, etc.): Owned by TerminalScreenState
 * - Selection state: Owned by SelectionManager
 *
 * @property snapshot The current immutable terminal snapshot
 */
@Stable
internal class TerminalScreenState(
    initialSnapshot: TerminalSnapshot,
) {
    private data class DetectedUrl(
        val row: Int,
        val range: IntRange,
        val url: String,
    )

    private var cachedSequenceNumber = -1L
    private var cachedScrollbackPosition = -1
    private var cachedAutoDetect = false
    private var cachedUrlGrid = Array(0) { arrayOfNulls<String>(0) }

    /**
     * The current immutable terminal snapshot.
     * Updated via updateSnapshot() to preserve scroll position across snapshot changes.
     */
    var snapshot by mutableStateOf(initialSnapshot)
        private set

    /**
     * Current scroll position in the scrollback buffer.
     * 0 = bottom (current screen), >0 = scrolled back in history
     */
    var scrollbackPosition by mutableStateOf(0)
        private set

    /**
     * Total number of lines (scrollback + visible screen).
     */
    val totalLines: Int get() = snapshot.scrollback.size + snapshot.rows

    /**
     * Get a line at the specified index, accounting for scrollback.
     *
     * @param index Line index (0 = oldest scrollback, totalLines-1 = last visible line)
     * @return The terminal line at the specified index
     */
    fun getLine(index: Int): TerminalLine = if (index < snapshot.scrollback.size) {
        snapshot.scrollback[index]
    } else {
        val screenIndex = index - snapshot.scrollback.size
        if (screenIndex in snapshot.lines.indices) {
            snapshot.lines[screenIndex]
        } else {
            // Return empty line if index out of bounds
            TerminalLine.empty(
                row = screenIndex,
                cols = snapshot.cols,
                defaultFg = androidx.compose.ui.graphics.Color.White,
                defaultBg = androidx.compose.ui.graphics.Color.Black,
            )
        }
    }

    /**
     * Get the visible line at the specified row, accounting for scroll position.
     *
     * @param row Row in the visible viewport (0-based)
     * @return The terminal line to display at this row
     */
    fun getVisibleLine(row: Int): TerminalLine {
        if (scrollbackPosition > 0) {
            // Calculate actual row in scrollback/screen
            val actualIndex = snapshot.scrollback.size - scrollbackPosition + row
            return getLine(actualIndex.coerceIn(0, totalLines - 1))
        }
        // Not scrolled - show current screen
        return if (row in snapshot.lines.indices) {
            snapshot.lines[row]
        } else {
            TerminalLine.empty(
                row = row,
                cols = snapshot.cols,
                defaultFg = androidx.compose.ui.graphics.Color.White,
                defaultBg = androidx.compose.ui.graphics.Color.Black,
            )
        }
    }

    /**
     * Get the hyperlink URL at a visible row/col.
     *
     * OSC 8 semantic hyperlinks always win. When [autoDetectUrls] is enabled,
     * plain-text URL detection also handles URLs split across adjacent visual
     * rows by terminal wrapping or tool-added continuation prefixes.
     */
    fun getHyperlinkUrlAt(row: Int, col: Int, autoDetectUrls: Boolean = false): String? {
        if (row !in 0 until snapshot.rows || col !in 0 until snapshot.cols) return null

        val line = getVisibleLine(row)
        val osc8 = line.semanticSegments.firstOrNull {
            it.semanticType == SemanticType.HYPERLINK && it.contains(col)
        }?.metadata
        if (osc8 != null) return osc8

        if (!autoDetectUrls) return null

        if (snapshot.sequenceNumber != cachedSequenceNumber ||
            scrollbackPosition != cachedScrollbackPosition ||
            autoDetectUrls != cachedAutoDetect
        ) {
            rebuildUrlCache(autoDetect = true)
        }

        return if (row in cachedUrlGrid.indices && col in cachedUrlGrid[row].indices) {
            cachedUrlGrid[row][col]
        } else {
            null
        }
    }

    private fun rebuildUrlCache(autoDetect: Boolean) {
        cachedSequenceNumber = snapshot.sequenceNumber
        cachedScrollbackPosition = scrollbackPosition
        cachedAutoDetect = autoDetect

        val numRows = snapshot.rows
        val cols = snapshot.cols
        if (cachedUrlGrid.size != numRows || (numRows > 0 && cachedUrlGrid[0].size != cols)) {
            cachedUrlGrid = Array(numRows) { arrayOfNulls<String>(cols) }
        } else {
            for (r in 0 until numRows) {
                cachedUrlGrid[r].fill(null)
            }
        }

        val list = mutableListOf<DetectedUrl>()
        if (!autoDetect) {
            return
        }

        val visited = Array(numRows) { BooleanArray(cols) }

        for (row in 0 until numRows) {
            val line = getVisibleLine(row)

            for ((anchorCol, _, _) in line.autoDetectedUrls) {
                if (anchorCol !in 0 until cols) continue
                if (visited[row][anchorCol]) continue

                val spans = buildWrappedUrlSpans(row, anchorCol)
                if (spans.isNotEmpty()) {
                    val joined = StringBuilder()
                    for ((spanRow, span) in spans.toSortedMap()) {
                        joined.append(readUrlSpan(spanRow, span))
                        for (c in span) {
                            if (spanRow in 0 until numRows && c in 0 until cols) {
                                visited[spanRow][c] = true
                            }
                        }
                    }

                    val trimmedUrl = TerminalLine.URL_REGEX.findAll(joined).firstOrNull()?.value?.trimDetectedUrl()
                    if (!trimmedUrl.isNullOrEmpty()) {
                        mapUrlToSpans(spans, trimmedUrl, list)
                    }
                }
            }
        }

        for (detected in list) {
            if (detected.row in 0 until numRows) {
                for (col in detected.range) {
                    if (col in 0 until cols) {
                        cachedUrlGrid[detected.row][col] = detected.url
                    }
                }
            }
        }
    }

    private fun mapUrlToSpans(spans: Map<Int, IntRange>, trimmedUrl: String, list: MutableList<DetectedUrl>) {
        var remainingLength = trimmedUrl.length
        for ((spanRow, span) in spans.toSortedMap()) {
            if (remainingLength <= 0) break
            val spanLength = span.last - span.first + 1
            val takeLength = minOf(remainingLength, spanLength)
            if (takeLength > 0) {
                val range = span.first until (span.first + takeLength)
                list.add(DetectedUrl(spanRow, range, trimmedUrl))
            }
            remainingLength -= takeLength
        }
    }

    private fun buildWrappedUrlSpans(anchorRow: Int, anchorCol: Int): Map<Int, IntRange> {
        val spans = linkedMapOf<Int, IntRange>()
        var row = anchorRow
        var startCol = anchorCol

        while (row < snapshot.rows && (row - anchorRow) < MAX_URL_CONTINUATION_ROWS) {
            val text = getVisibleLine(row).columnText
            if (startCol !in text.indices || !text[startCol].isUrlSafe()) break

            var endCol = startCol
            while (endCol < text.length && text[endCol].isUrlSafe()) {
                endCol++
            }

            val nextStart = continuationStart(row, endCol)
            if (nextStart != null) {
                spans[row] = startCol until endCol
                row++
                startCol = nextStart
            } else {
                val runStr = text.substring(startCol, endCol)
                val trimmed = runStr.trimDetectedUrl()
                if (trimmed.isNotEmpty()) {
                    spans[row] = startCol until (startCol + trimmed.length)
                }
                break
            }
        }

        return spans
    }

    private fun continuationStart(previousRow: Int, previousEndCol: Int): Int? {
        if (previousRow + 1 >= snapshot.rows) return null

        val previousLine = getVisibleLine(previousRow)
        val previousText = previousLine.columnText

        val previousTrimmedEnd = previousText.indexOfLast { it != '\u0000' && !it.isWhitespace() } + 1
        if (previousTrimmedEnd <= 0 || previousEndCol < previousTrimmedEnd) return null

        val rowFilled = previousEndCol >= snapshot.cols || previousLine.softWrapped
        val nextRow = previousRow + 1
        val nextText = getVisibleLine(nextRow).columnText
        val start = firstUrlSafeAfterPrefix(nextText) ?: return null

        var end = start
        while (end < nextText.length && nextText[end].isUrlSafe()) {
            end++
        }
        val run = nextText.substring(start, end)
        val trimmedRun = run.trimDetectedUrl()
        if (trimmedRun.isEmpty()) return null

        val prevEndsWithDelimiter = previousEndCol > 0 && previousText[previousEndCol - 1] in "/?&=#"
        val nextStartsWithQueryOrFragment = run.isNotEmpty() && run[0] in "?&#"
        val previousRun = previousText.substring(0, previousEndCol)
        val previousWouldTrim = previousRun.trimDetectedUrl().length < previousRun.length
        val rowFilledContinuation = rowFilled &&
            (!previousWouldTrim || prevEndsWithDelimiter || nextStartsWithQueryOrFragment)
        val continues = rowFilledContinuation || prevEndsWithDelimiter || nextStartsWithQueryOrFragment

        return if (continues) start else null
    }

    private fun firstUrlSafeAfterPrefix(text: String): Int? {
        for (index in text.indices) {
            val ch = text[index]
            if (ch == '\u0000' || ch.isWhitespace()) continue
            if (ch.isUrlPrefixDecoration()) continue
            return if (ch.isUrlSafe()) index else null
        }
        return null
    }

    private fun readUrlSpan(row: Int, span: IntRange): String {
        val text = getVisibleLine(row).columnText
        val end = (span.last + 1).coerceAtMost(text.length)
        return if (span.first < end) text.substring(span.first, end) else ""
    }

    /**
     * Scroll to the bottom (current screen).
     */
    fun scrollToBottom() {
        scrollbackPosition = 0
    }

    /**
     * Scroll to the top (oldest scrollback).
     */
    fun scrollToTop() {
        scrollbackPosition = snapshot.scrollback.size
    }

    /**
     * Scroll by a relative amount.
     *
     * @param delta Lines to scroll (positive = up/back, negative = down/forward)
     */
    fun scrollBy(delta: Int) {
        scrollbackPosition = (scrollbackPosition + delta).coerceIn(0, snapshot.scrollback.size)
    }

    fun scrollToRow(row: Int) {
        val targetPosition = (snapshot.scrollback.size - row).coerceIn(0, snapshot.scrollback.size)
        scrollbackPosition = targetPosition
    }

    /**
     * Check if currently scrolled to the bottom.
     */
    fun isAtBottom(): Boolean = scrollbackPosition == 0

    /**
     * Update the snapshot while preserving UI state (scroll position).
     *
     * When a user is scrolled up reading history and new content arrives (or a
     * resize / reconnect changes the scrollback size), the content at their
     * current viewport must stay visible. That requires adjusting
     * [scrollbackPosition] by the delta between old and new scrollback sizes —
     * otherwise the visible lines shift with each update.
     *
     * A user already at the bottom ([scrollbackPosition] == 0) stays at the
     * bottom by definition and needs no adjustment.
     *
     * @param newSnapshot The new snapshot to use
     */
    internal fun updateSnapshot(newSnapshot: TerminalSnapshot) {
        val oldScrollbackSize = snapshot.scrollback.size
        val newScrollbackSize = newSnapshot.scrollback.size
        snapshot = newSnapshot
        if (scrollbackPosition != 0) {
            val delta = newScrollbackSize - oldScrollbackSize
            scrollbackPosition = (scrollbackPosition + delta).coerceIn(0, newScrollbackSize)
        }
    }
}

private const val MAX_URL_CONTINUATION_ROWS = 6

/**
 * Remember a TerminalScreenState that observes the given TerminalEmulator.
 *
 * This composable function creates a TerminalScreenState that automatically
 * updates when the TerminalEmulator emits new snapshots via StateFlow.
 *
 * @param terminalEmulator The terminal emulator to observe
 * @return A TerminalScreenState that tracks the current terminal snapshot
 */
@Composable
internal fun rememberTerminalScreenState(
    terminalEmulator: TerminalEmulatorImpl,
): TerminalScreenState {
    // Create state instance once per emulator, using the current value as initial
    val state = remember(terminalEmulator) {
        TerminalScreenState(terminalEmulator.snapshot.value)
    }

    // Collecting in a LaunchedEffect keeps this adapter composable stable instead of
    // recomposing it because of Flow collection in this function. Updates to
    // state.snapshot still invalidate/recompose any composables that read it.
    LaunchedEffect(terminalEmulator) {
        terminalEmulator.snapshot.collect { newSnapshot ->
            state.updateSnapshot(newSnapshot)
        }
    }

    return state
}
