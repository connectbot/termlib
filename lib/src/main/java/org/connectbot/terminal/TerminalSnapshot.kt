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

import androidx.compose.ui.graphics.Color

/**
 * Cursor shape types matching libvterm's VTERM_PROP_CURSORSHAPE values.
 */
enum class CursorShape {
    BLOCK,       // VTERM_PROP_CURSORSHAPE_BLOCK = 1
    UNDERLINE,   // VTERM_PROP_CURSORSHAPE_UNDERLINE = 2
    BAR_LEFT     // VTERM_PROP_CURSORSHAPE_BAR_LEFT = 3
}

/**
 * Immutable snapshot of complete terminal state.
 *
 * This data class provides a point-in-time view of the entire terminal state,
 * including all visible lines, scrollback buffer, cursor state, and terminal
 * properties. It is designed to be transferred efficiently via StateFlow from
 * a background Service to the UI layer.
 *
 * Note: This class is not Parcelable because it contains non-Parcelable types
 * (TerminalLine with Compose Color). For cross-process communication, use
 * StateFlow which doesn't require Parcelable.
 *
 * @property lines The current visible screen lines (rows × cols)
 * @property scrollback Historical lines that have scrolled off the top
 * @property cursorRow Current cursor row position (0-based)
 * @property cursorCol Current cursor column position (0-based)
 * @property cursorVisible Whether the cursor is currently visible
 * @property cursorShape The shape of the cursor (block, underline, or bar)
 * @property terminalTitle The terminal window title (set by escape sequences)
 * @property rows Number of rows in the visible terminal
 * @property cols Number of columns in the visible terminal
 * @property timestamp Timestamp when this snapshot was created (System.currentTimeMillis())
 * @property sequenceNumber Monotonically increasing sequence number for ordering snapshots
 */
data class TerminalSnapshot(
    val lines: List<TerminalLine>,
    val scrollback: List<TerminalLine>,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val cursorBlink: Boolean,
    val cursorShape: CursorShape,
    val terminalTitle: String,
    val rows: Int,
    val cols: Int,
    val timestamp: Long,
    val sequenceNumber: Long
) {
    companion object {
        /**
         * Create an empty snapshot with default values.
         */
        fun empty(
            rows: Int = 24,
            cols: Int = 80,
            defaultFg: Color = Color.White,
            defaultBg: Color = Color.Black
        ): TerminalSnapshot {
            return TerminalSnapshot(
                lines = List(rows) { row ->
                    TerminalLine.empty(row, cols, defaultFg, defaultBg)
                },
                scrollback = emptyList(),
                cursorRow = 0,
                cursorCol = 0,
                cursorVisible = true,
                cursorBlink = true,
                cursorShape = CursorShape.BLOCK,
                terminalTitle = "",
                rows = rows,
                cols = cols,
                timestamp = System.currentTimeMillis(),
                sequenceNumber = 0L
            )
        }
    }
}
