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

/**
 * Callbacks invoked by the native terminal layer when terminal state changes.
 *
 * IMPORTANT: These callbacks may be invoked while native Terminal state is guarded by
 * a non-reentrant mutex. Implementations MUST NOT synchronously call back into
 * TerminalNative or TerminalEmulator methods that enter native code, such as
 * writeInput(), resize(), dispatchKey(), dispatchCharacter(), or getCells().
 * Post or otherwise defer that work until after the callback
 * returns.
 */
internal interface TerminalCallbacks {
    /** Binary image transport. Result packs cursor rows/columns and reservation policy. */
    fun onImageFragment(kitty: Boolean, data: ByteArray, initial: Boolean, final: Boolean, row: Int, col: Int): Long = -1

    /** Exact edits (0), scrolling (1), and full-screen image clearing (2). */
    fun onImageEdit(kind: Int, top: Int, bottom: Int, left: Int, right: Int, downward: Int, rightward: Int) = Unit
    fun onImageQuery(query: Int) = Unit

    /** Fixed 64 KiB Kotlin-owned scratch, borrowed only during synchronous callbacks. */
    fun cellBuffer(): java.nio.ByteBuffer

    /**
     * Called when a region of the screen needs to be redrawn.
     *
     * @param startRow First row that changed (inclusive)
     * @param endRow Last row that changed (exclusive)
     * @param startCol First column that changed (inclusive)
     * @param endCol Last column that changed (exclusive)
     * @return 0 on success
     */
    fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int): Int

    /**
     * Called when a rectangular region needs to be moved/scrolled.
     * This is an optimization hint - the implementation can copy the content from src to dest
     * instead of redrawing. If not implemented, return 0 and libvterm will use damage events.
     *
     * @param dest Destination rectangle
     * @param src Source rectangle
     * @return 1 if handled, 0 to fall back to damage events
     */
    fun moverect(dest: TermRect, src: TermRect): Int

    /**
     * Called when cursor position changes.
     *
     * @param pos Current cursor position
     * @param oldPos Previous cursor position
     * @param visible Whether cursor should be visible
     * @return 0 on success
     */
    fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean): Int

    /**
     * Called when a terminal property changes (title, cursor shape, etc.).
     *
     * @param prop Property identifier
     * @param value Property value
     * @return 0 on success
     */
    fun setTermProp(prop: Int, value: TerminalProperty): Int

    /**
     * Called when the terminal bell should be triggered.
     *
     * @return 0 on success
     */
    fun bell(): Int

    /**
     * Called when a line is pushed to scrollback buffer.
     *
     * @param cols Number of columns in the line
     * @param cells Array of screen cells
     * @param softWrapped True if this line ended with a soft wrap (visual line break due to
     *                    terminal width), false if it ended with a hard break (actual newline).
     *                    This is used when copying text to avoid inserting spurious newlines
     *                    in wrapped long commands.
     * @return 0 on success
     */
    // Chunks are sequential: start == 0 begins a row, start + count == cols completes it.
    // The buffer contains count records at byte offset zero and is overwritten on return.
    fun pushScrollbackLine(cols: Int, start: Int, count: Int, cells: java.nio.ByteBuffer, softWrapped: Boolean): Int

    /**
     * Called when a line should be popped from scrollback buffer.
     *
     * @param cols Number of columns expected
     * @param cells Array to fill with screen cells
     * @return 0 on success
     */
    // Do not remove the stored row until its final chunk has been written.
    fun popScrollbackLine(cols: Int, start: Int, count: Int, cells: java.nio.ByteBuffer): Int

    /**
     * Called when the entire scrollback buffer should be cleared.
     *
     * @return 0 on success
     */
    fun clearScrollback(): Int

    /**
     * Called when keyboard input is generated (user types, terminal generates escape sequences).
     * The caller should write this data to the PTY/transport.
     *
     * @param data Byte array containing the data to write to PTY
     * @return 0 on success
     */
    fun onKeyboardInput(data: ByteArray): Int

    /**
     * Called when an OSC (Operating System Command) sequence is received.
     * Used for shell integration (OSC 133), iTerm2-style annotations (OSC 1337), and hyperlinks (OSC 8).
     *
     * @param command The OSC command number (e.g., 8, 133, 1337)
     * @param payload The payload string (e.g., "A" for OSC 133;A, "AddAnnotation=..." for OSC 1337)
     * @param cursorRow Current cursor row from native terminal
     * @param cursorCol Current cursor column from native terminal
     * @return 1 if handled, 0 otherwise
     */
    fun onTextFragment(kind: Int, command: Int, data: ByteArray, initial: Boolean, final: Boolean, cursorRow: Int, cursorCol: Int): Int
}

/**
 * Rectangular region in the terminal.
 */
internal data class TermRect(
    val startRow: Int,
    val endRow: Int,
    val startCol: Int,
    val endCol: Int,
)

/**
 * Cursor position in the terminal.
 */
internal data class CursorPosition(
    val row: Int,
    val col: Int,
)

/**
 * Terminal property values (title, colors, cursor state, etc.).
 */
internal sealed class TerminalProperty {
    data class BoolValue(val value: Boolean) : TerminalProperty()
    data class IntValue(val value: Int) : TerminalProperty()
    data class StringValue(val value: String) : TerminalProperty()
    data class ColorValue(val red: Int, val green: Int, val blue: Int) : TerminalProperty()
}
