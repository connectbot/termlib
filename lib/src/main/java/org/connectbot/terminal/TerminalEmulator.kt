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
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * Service-compatible terminal state manager.
 *
 * This class manages terminal state independently of the UI layer, making it
 * suitable for running in a background Android Service. It wraps the native
 * Terminal implementation and exposes state changes via StateFlow.
 *
 * Key features:
 * - No Compose dependencies (can run in background Service)
 * - Thread-safe callback handling with proper synchronization
 * - Snapshot-based state emission via StateFlow
 * - Accumulates damage and escapes native mutex before processing
 *
 * Threading model:
 * - JNI callbacks run on native thread and accumulate damage
 * - Handler posts to specified Looper to escape native mutex
 * - Snapshot building happens on Handler thread
 * - StateFlow emission is thread-safe
 *
 * @param looper The Looper to use for callback handling (typically main looper)
 * @param initialRows Initial number of rows
 * @param initialCols Initial number of columns
 * @param defaultForeground Default foreground color
 * @param defaultBackground Default background color
 * @param onKeyboardInput Callback for keyboard output (to write to PTY)
 * @param onBell Optional callback for terminal bell
 */
class TerminalEmulator(
    private val looper: Looper = Looper.getMainLooper(),
    initialRows: Int = 24,
    initialCols: Int = 80,
    private val defaultForeground: Color = Color.White,
    private val defaultBackground: Color = Color.Black,
    private val onKeyboardInput: (ByteArray) -> Unit = {},
    private val onBell: (() -> Unit)? = null
) : TerminalCallbacks {

    // Handler for escaping native mutex
    private val handler = Handler(looper)

    // Damage accumulation (thread-safe) - MUST be initialized before terminalNative
    private val damageLock = Object()
    private val pendingDamageRegions = mutableListOf<DamageRegion>()
    private var damagePosted = false
    private var cursorMoved = false
    private var propertyChanged = false

    // StateFlow for reactive state propagation
    private val _snapshot = MutableStateFlow(
        TerminalSnapshot.empty(initialRows, initialCols, defaultForeground, defaultBackground)
    )
    val snapshot: StateFlow<TerminalSnapshot> = _snapshot.asStateFlow()

    // Sequence number for ordering snapshots
    private var sequenceNumber = 0L

    // Terminal dimensions
    private var rows = initialRows
    private var cols = initialCols

    // Cursor state
    private var cursorRow = 0
    private var cursorCol = 0
    private var cursorVisible = true
    private var cursorShape = CursorShape.BLOCK
    private var cursorBlink = false

    // Terminal properties
    private var terminalTitle = ""

    // Scrollback buffer
    private val scrollback = mutableListOf<TerminalLine>()
    private val maxScrollbackLines = 10000

    // Reusable CellRun for fetching cell data
    private val cellRun = CellRun()

    // Current screen lines cache
    private var currentLines = List(initialRows) { row ->
        TerminalLine.empty(row, initialCols, defaultForeground, defaultBackground)
    }

    // Native terminal instance - MUST be initialized AFTER damageLock and other state
    private val terminalNative: TerminalNative = TerminalNative(this)

    init {
        // Initialize terminal with specified dimensions
        terminalNative.resize(initialRows, initialCols, maxScrollbackLines)
    }

    // ================================================================================
    // Public API
    // ================================================================================

    /**
     * Write data to the terminal (from PTY/transport).
     */
    fun writeInput(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        terminalNative.writeInput(data, offset, length)
    }

    /**
     * Write data to the terminal using ByteBuffer (more efficient for large data).
     */
    fun writeInput(buffer: ByteBuffer, length: Int) {
        terminalNative.writeInput(buffer, length)
    }

    /**
     * Resize the terminal.
     */
    fun resize(newRows: Int, newCols: Int, scrollRows: Int = maxScrollbackLines) {
        rows = newRows
        cols = newCols
        terminalNative.resize(newRows, newCols, scrollRows)

        // Resize currentLines to match new dimensions
        currentLines = List(newRows) { row ->
            TerminalLine.empty(row, newCols, defaultForeground, defaultBackground)
        }

        // Rebuild all lines after resize
        synchronized(damageLock) {
            pendingDamageRegions.clear()
            pendingDamageRegions.add(DamageRegion(0, newRows, 0, newCols))
            if (!damagePosted) {
                handler.post { processPendingUpdates() }
                damagePosted = true
            }
        }
    }

    /**
     * Dispatch a key event to the terminal.
     */
    fun dispatchKey(modifiers: Int, key: Int) {
        terminalNative.dispatchKey(modifiers, key)
    }

    /**
     * Dispatch a character to the terminal.
     */
    fun dispatchCharacter(modifiers: Int, character: Char) {
        terminalNative.dispatchCharacter(modifiers, character.code)
    }

    /**
     * Clears the terminal emulator screen.
     */
    fun clearScreen() = writeInput("\u001B[2J\u001B[H".toByteArray())

    // ================================================================================
    // TerminalCallbacks implementation
    // ================================================================================

    override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int): Int {
        synchronized(damageLock) {
            pendingDamageRegions.add(DamageRegion(startRow, endRow, startCol, endCol))
            if (!damagePosted) {
                handler.post { processPendingUpdates() }
                damagePosted = true
            }
        }
        return 0
    }

    override fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean): Int {
        synchronized(damageLock) {
            cursorRow = pos.row
            cursorCol = pos.col
            cursorVisible = visible
            cursorMoved = true
            if (!damagePosted) {
                handler.post { processPendingUpdates() }
                damagePosted = true
            }
        }
        return 0
    }

    override fun setTermProp(prop: Int, value: TerminalProperty): Int {
        synchronized(damageLock) {
            when (value) {
                is TerminalProperty.StringValue -> {
                    // Property 7 is VTERM_PROP_TITLE (from vterm.h line 257)
                    if (prop == 7) {
                        terminalTitle = value.value
                        propertyChanged = true
                    }
                }
                is TerminalProperty.BoolValue -> {
                    when (prop) {
                        // Property 1 is VTERM_PROP_CURSORVISIBLE (from vterm.h line 254)
                        1 -> {
                            cursorVisible = value.value
                            propertyChanged = true
                        }
                        // Property 2 is VTERM_PROP_CURSORBLINK (from vterm.h line 255)
                        2 -> {
                            cursorBlink = value.value
                            propertyChanged = true
                        }
                    }
                }
                is TerminalProperty.IntValue -> {
                    // Property 6 is VTERM_PROP_CURSORSHAPE (from vterm.h line 260)
                    if (prop == 6) {
                        cursorShape = when (value.value) {
                            1 -> CursorShape.BLOCK       // VTERM_PROP_CURSORSHAPE_BLOCK
                            2 -> CursorShape.UNDERLINE   // VTERM_PROP_CURSORSHAPE_UNDERLINE
                            3 -> CursorShape.BAR_LEFT    // VTERM_PROP_CURSORSHAPE_BAR_LEFT
                            else -> CursorShape.BLOCK
                        }
                        propertyChanged = true
                    }
                }
                else -> {
                    // Other properties not handled
                }
            }
            if (propertyChanged && !damagePosted) {
                handler.post { processPendingUpdates() }
                damagePosted = true
            }
        }
        return 0
    }

    override fun bell(): Int {
        // Bell callback - post to handler to avoid blocking native thread
        handler.post {
            onBell?.invoke()
        }
        return 0
    }

    override fun pushScrollbackLine(cols: Int, cells: Array<ScreenCell>): Int {
        // Convert ScreenCell array to TerminalLine
        val cellList = cells.take(cols).map { screenCell ->
            TerminalLine.Cell(
                char = screenCell.char,
                combiningChars = screenCell.combiningChars.filter { it != '\u0000' },
                fgColor = Color(screenCell.fgRed, screenCell.fgGreen, screenCell.fgBlue),
                bgColor = Color(screenCell.bgRed, screenCell.bgGreen, screenCell.bgBlue),
                bold = screenCell.bold,
                italic = screenCell.italic,
                underline = screenCell.underline,
                reverse = screenCell.reverse,
                strike = screenCell.strike,
                width = screenCell.width
            )
        }
        val line = TerminalLine(row = -1, cells = cellList)

        synchronized(damageLock) {
            scrollback.add(line)
            if (scrollback.size > maxScrollbackLines) {
                scrollback.removeAt(0)
            }
            propertyChanged = true
            if (!damagePosted) {
                handler.post { processPendingUpdates() }
                damagePosted = true
            }
        }
        return 0
    }

    override fun popScrollbackLine(cols: Int, cells: Array<ScreenCell>): Int {
        // Not implemented - scrollback is managed separately
        return 0
    }

    override fun onKeyboardInput(data: ByteArray): Int {
        // Keyboard output callback - post to handler
        handler.post {
            onKeyboardInput.invoke(data)
        }
        return 0
    }

    // ================================================================================
    // Internal snapshot building
    // ================================================================================

    /**
     * Process pending updates and emit new snapshot.
     * This runs on the Handler thread, NOT in the JNI callback.
     */
    private fun processPendingUpdates() {
        // Collect pending changes
        val damageRegions: List<DamageRegion>
        val needsUpdate: Boolean
        synchronized(damageLock) {
            damageRegions = pendingDamageRegions.toList()
            pendingDamageRegions.clear()
            damagePosted = false
            needsUpdate = damageRegions.isNotEmpty() || cursorMoved || propertyChanged
            cursorMoved = false
            propertyChanged = false
        }

        if (!needsUpdate) return

        // Update damaged lines (safe to call getCellRun now - not in callback)
        for (region in damageRegions) {
            // Ensure row is within bounds [0, rows)
            val startRow = region.startRow.coerceIn(0, rows - 1)
            val endRow = region.endRow.coerceIn(startRow, rows)  // endRow is exclusive
            for (row in startRow until endRow) {
                updateLine(row)
            }
        }

        // Build and emit new snapshot
        val newSnapshot = buildSnapshot()
        _snapshot.value = newSnapshot
    }

    /**
     * Update a single line by fetching cell data from the terminal.
     */
    private fun updateLine(row: Int) {
        // Safety check: ensure row is within bounds
        if (row < 0 || row >= rows) {
            return
        }

        val cells = mutableListOf<TerminalLine.Cell>()
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

            // Convert CellRun colors to Compose Color
            val fgColor = Color(cellRun.fgRed, cellRun.fgGreen, cellRun.fgBlue)
            val bgColor = Color(cellRun.bgRed, cellRun.bgGreen, cellRun.bgBlue)

            // Process characters in the run
            var charIndex = 0
            var cellsInRun = 0

            while (charIndex < cellRun.chars.size && cellsInRun < runLength) {
                val char = cellRun.chars[charIndex]
                if (char == 0.toChar()) break

                val combiningChars = mutableListOf<Char>()
                charIndex++

                // Handle surrogate pairs (characters > U+FFFF like emoji)
                if (char.isHighSurrogate() && charIndex < cellRun.chars.size) {
                    val nextChar = cellRun.chars[charIndex]
                    if (nextChar.isLowSurrogate()) {
                        combiningChars.add(nextChar)
                        charIndex++
                    }
                }

                // Collect combining characters
                while (charIndex < cellRun.chars.size && isCombiningCharacter(cellRun.chars[charIndex])) {
                    combiningChars.add(cellRun.chars[charIndex])
                    charIndex++
                }

                // Determine cell width
                val width = if (combiningChars.isNotEmpty() && combiningChars[0].isLowSurrogate()) {
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
                if (width == 2) {
                    cellsInRun++
                }
            }

            col += cellsInRun
        }

        // Update cached line
        currentLines = currentLines.toMutableList().apply {
            this[row] = TerminalLine(row, cells)
        }
    }

    /**
     * Build a complete snapshot of terminal state.
     */
    private fun buildSnapshot(): TerminalSnapshot {
        return TerminalSnapshot(
            lines = currentLines.toList(),  // Immutable copy
            scrollback = synchronized(damageLock) { scrollback.toList() },  // Immutable copy
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            cursorVisible = cursorVisible,
            cursorShape = cursorShape,
            cursorBlink = cursorBlink,
            terminalTitle = terminalTitle,
            rows = rows,
            cols = cols,
            timestamp = System.currentTimeMillis(),
            sequenceNumber = sequenceNumber++
        )
    }

    // ================================================================================
    // Helper methods
    // ================================================================================

    private fun isCombiningCharacter(char: Char): Boolean {
        return UCharacter.hasBinaryProperty(char.code, UProperty.GRAPHEME_EXTEND)
    }

    private fun isFullwidthCharacter(char: Char): Boolean {
        val eastAsianWidth = UCharacter.getIntPropertyValue(char.code, UProperty.EAST_ASIAN_WIDTH)
        return eastAsianWidth == UCharacter.EastAsianWidth.FULLWIDTH ||
               eastAsianWidth == UCharacter.EastAsianWidth.WIDE
    }

    private fun isFullwidthCodepoint(codepoint: Int): Boolean {
        val eastAsianWidth = UCharacter.getIntPropertyValue(codepoint, UProperty.EAST_ASIAN_WIDTH)
        return eastAsianWidth == UCharacter.EastAsianWidth.FULLWIDTH ||
               eastAsianWidth == UCharacter.EastAsianWidth.WIDE
    }

    /**
     * Cleanup resources.
     */
    fun cleanup() {
        // Future: cleanup native resources if needed
    }
}

/**
 * Represents a damaged region that needs updating.
 */
private data class DamageRegion(
    val startRow: Int,
    val endRow: Int,
    val startCol: Int,
    val endCol: Int
)
