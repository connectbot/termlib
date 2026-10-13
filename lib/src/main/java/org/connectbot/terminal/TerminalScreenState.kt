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

    /** Called before a new snapshot replaces the one used by selection anchors. */
    internal var onSnapshotChanged: ((TerminalSnapshot, TerminalSnapshot) -> Unit)? = null

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

    /** Absolute history index for a viewport row. */
    internal fun visibleLineIndex(row: Int): Int {
        val first = snapshot.scrollback.size - scrollbackPosition
        return (first + row).coerceIn(0, totalLines - 1)
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

        if (!autoDetect) return
        val lines = (0 until numRows).map(::getVisibleLine)
        for (link in resolveTerminalLinks(lines, autoDetect = true)) {
            for (range in link.ranges) {
                for (col in range.start until minOf(range.end, cols)) {
                    cachedUrlGrid[range.row][col] = link.url
                }
            }
        }
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
        onSnapshotChanged?.invoke(snapshot, newSnapshot)
        snapshot = newSnapshot
        if (scrollbackPosition != 0) {
            val delta = newScrollbackSize - oldScrollbackSize
            scrollbackPosition = (scrollbackPosition + delta).coerceIn(0, newScrollbackSize)
        }
    }
}

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
            if (newSnapshot === state.snapshot) return@collect
            androidx.compose.runtime.withFrameNanos {
                state.updateSnapshot(terminalEmulator.snapshot.value)
            }
        }
    }

    return state
}
