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
import android.util.Log
import android.view.Choreographer
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * URL discovered in terminal output.
 *
 * @property url The target URL.
 * @property source How the URL was discovered.
 */
data class TerminalUrl(
    val url: String,
    val source: TerminalUrlSource,
)

sealed class TerminalUrlSource {
    data object Osc8 : TerminalUrlSource()

    data object AutoDetected : TerminalUrlSource()
}

/**
 * Line range to scan for URLs.
 *
 * With the current terminal state model, both scopes scan primary scrollback
 * plus the visible primary screen while the primary screen is active. While the
 * alternate screen is active, both scopes scan only the alternate screen because
 * the alternate screen does not have scrollback.
 */
sealed class UrlScanScope {
    /**
     * Scan what users currently see as terminal output: primary scrollback plus
     * visible primary screen, or only the active alternate screen.
     */
    data object CurrentView : UrlScanScope()

    /**
     * Scan the currently active screen plus its scrollback, when that screen
     * has scrollback.
     */
    data object ScreenAndScrollback : UrlScanScope()
}

/**
 * Terminal emulator interface. This has no dependency on any UI framework
 * so it may be run in a Service on Android. It handles the management of
 * the terminal emulation state.
 */
sealed interface TerminalEmulator {
    /**
     * Write data to the terminal (from PTY/transport).
     */
    fun writeInput(data: ByteArray, offset: Int = 0, length: Int = data.size)

    /**
     * Write data to the terminal using ByteBuffer (more efficient for large data).
     */
    fun writeInput(buffer: ByteBuffer, length: Int)

    /**
     * Resize the terminal.
     */
    fun resize(newRows: Int, newCols: Int)

    /**
     * Dispatch a key event to the terminal.
     */
    fun dispatchKey(modifiers: Int, key: Int)

    /**
     * Dispatch a character to the terminal.
     */
    @Deprecated(
        message = "Use dispatchCharacter(modifiers, codepoint) for full Unicode code point support",
        replaceWith = ReplaceWith("dispatchCharacter(modifiers, ch.code)"),
    )
    fun dispatchCharacter(modifiers: Int, ch: Char) = dispatchCharacter(modifiers, ch.code)

    /**
     * Dispatch a character to the terminal.
     */
    fun dispatchCharacter(modifiers: Int, codepoint: Int)

    /**
     * Clears the terminal emulator screen.
     */
    fun clearScreen()

    /**
     * Set ANSI palette colors (indices 0-15).
     *
     * This configures the 16 ANSI colors used by terminal escape sequences.
     * Changing the palette triggers a full redraw with new colors.
     *
     * @param ansiColors IntArray of ARGB colors (size 16 for all ANSI colors)
     * @return Number of colors set, or -1 on error
     */
    fun setAnsiPalette(ansiColors: IntArray): Int

    /**
     * Set default terminal colors.
     *
     * These colors are used when terminal content explicitly requests
     * "default" foreground or background (different from ANSI color 7/0).
     * Changing default colors triggers a full redraw.
     *
     * @param foreground ARGB foreground color
     * @param background ARGB background color
     * @return 0 on success, -1 on error
     */
    fun setDefaultColors(foreground: Int, background: Int): Int

    /**
     * Apply a complete color scheme to the terminal.
     *
     * Convenience method that sets both ANSI palette and default colors
     * from a color scheme. This is the recommended way to apply themes.
     *
     * @param ansiColors IntArray of 16 ARGB colors for ANSI palette
     * @param defaultForeground ARGB color for default foreground
     * @param defaultBackground ARGB color for default background
     */
    fun applyColorScheme(
        ansiColors: IntArray,
        defaultForeground: Int,
        defaultBackground: Int,
    )

    val dimensions: TerminalDimensions

    /**
     * Whether plain-text URL auto-detection is enabled.
     *
     * When true, hyperlink hit-testing continuously scans visible line text for URLs
     * in addition to OSC 8 hyperlink segments. When false, hit-testing only uses OSC 8
     * segments. This does not affect [getUrls], which always performs an explicit
     * one-shot scan when called.
     */
    val autoDetectUrls: Boolean

    /**
     * Whether bold text using low-intensity ANSI colors (0–7) promotes to the
     * corresponding bright palette color (8–15), matching xterm's boldColors behavior.
     */
    val boldAsBright: Boolean

    /**
     * Get the text output of the last completed command.
     *
     * Uses OSC 133 semantic segments to find the boundaries of the most recent
     * completed command output. Requires shell integration (OSC 133) to be
     * enabled in the user's shell.
     *
     * @return The command output text, or null if no completed command is found
     */
    fun getLastCommandOutput(): String?

    /**
     * Extract URLs from terminal output.
     *
     * This always performs explicit OSC 8 and plain-text regex URL extraction,
     * independent of [autoDetectUrls]. Plain-text URL extraction includes URLs
     * split across wrapped adjacent rows.
     *
     * Primary-screen scans include scrollback before visible screen lines.
     * While the alternate screen is active, primary scrollback is not scanned.
     */
    fun getUrls(scope: UrlScanScope = UrlScanScope.CurrentView): List<TerminalUrl>
}

class TerminalEmulatorFactory {
    companion object {
        /**
         * Creates the default implementation of TerminalEmulator.
         *
         * @param looper The Looper to use for callback handling (typically main looper)
         * @param initialRows Initial number of rows
         * @param initialCols Initial number of columns
         * @param defaultForeground Default foreground color
         * @param defaultBackground Default background color
         * @param onKeyboardInput Callback for keyboard output (to write to PTY)
         * @param onBell Optional callback for terminal bell
         * @param onResize Optional callback for terminal resize
         * @param onClipboardCopy Optional callback for OSC 52 clipboard copy operations.
         *                        The callback receives the decoded text to copy.
         * @param onProgressChange Optional callback for OSC 9;4 progress reporting.
         *                         The callback receives the progress state and percentage (0-100).
         * @param autoDetectUrls Whether to continuously scan visible terminal line text for
         *                       plain-text URLs and expose them via hit-testing as a fallback
         *                       when no OSC 8 hyperlink covers the column. Defaults to false.
         *                       [TerminalEmulator.getUrls] always performs its own one-shot
         *                       regex URL scan regardless of this setting.
         * @param boldAsBright Whether bold text using low-intensity ANSI colors (0–7) promotes to
         *                     the corresponding bright palette color (8–15), matching xterm's
         *                     default boldColors behavior. Defaults to true.
         */
        fun create(
            looper: Looper = Looper.getMainLooper(),
            initialRows: Int = 24,
            initialCols: Int = 80,
            defaultForeground: Color = Color.White,
            defaultBackground: Color = Color.Black,
            onKeyboardInput: (ByteArray) -> Unit = {},
            onBell: (() -> Unit)? = null,
            onResize: ((TerminalDimensions) -> Unit)? = null,
            onClipboardCopy: ((String) -> Unit)? = null,
            onProgressChange: ((ProgressState, Int) -> Unit)? = null,
            autoDetectUrls: Boolean = false,
            boldAsBright: Boolean = true,
        ): TerminalEmulator = TerminalEmulatorImpl(
            looper = looper,
            initialRows = initialRows,
            initialCols = initialCols,
            defaultForeground = defaultForeground,
            defaultBackground = defaultBackground,
            onKeyboardInput = onKeyboardInput,
            onBell = onBell,
            onResize = onResize,
            onClipboardCopy = onClipboardCopy,
            onProgressChange = onProgressChange,
            autoDetectUrls = autoDetectUrls,
            boldAsBright = boldAsBright,
        )
    }
}

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
 * @param onResize Optional callback for terminal resize
 * @param onClipboardCopy Optional callback for OSC 52 clipboard copy operations
 * @param onProgressChange Optional callback for OSC 9;4 progress reporting
 */
internal class TerminalEmulatorImpl(
    private val looper: Looper = Looper.getMainLooper(),
    initialRows: Int = 24,
    initialCols: Int = 80,
    defaultForeground: Color = Color.White,
    defaultBackground: Color = Color.Black,
    private val onKeyboardInput: (ByteArray) -> Unit = {},
    private val onBell: (() -> Unit)? = null,
    private val onResize: ((TerminalDimensions) -> Unit)? = null,
    private val onClipboardCopy: ((String) -> Unit)? = null,
    private val onProgressChange: ((ProgressState, Int) -> Unit)? = null,
    override val autoDetectUrls: Boolean = false,
    override val boldAsBright: Boolean = true,
) : TerminalEmulator,
    TerminalCallbacks {

    // Handler for escaping native mutex
    private val handler = Handler(looper)

    // Default colors (can be updated via setDefaultColors)
    private var currentDefaultForeground: Color = defaultForeground
    private var currentDefaultBackground: Color = defaultBackground

    // Damage accumulation (thread-safe) - MUST be initialized before terminalNative
    private val damageLock = Object()
    private val pendingDamageRegions = mutableListOf<DamageRegion>()
    private var damagePosted = false
    private var cursorMoved = false
    private var propertyChanged = false

    // Pending semantic segments to apply during processPendingUpdates
    private val pendingSemanticSegments = mutableListOf<PendingSemanticSegment>()
    private val movedSegmentRows = mutableSetOf<Int>()
    private val semanticSegmentTexts = mutableMapOf<SemanticSegmentKey, String>()

    // StateFlow for reactive state propagation
    private val _snapshot = MutableStateFlow(
        TerminalSnapshot.empty(initialRows, initialCols, currentDefaultForeground, currentDefaultBackground),
    )
    internal val snapshot: StateFlow<TerminalSnapshot> = _snapshot.asStateFlow()

    // Sequence number for ordering snapshots
    private var sequenceNumber = 0L

    // Terminal dimensions
    override val dimensions: TerminalDimensions
        get() = TerminalDimensions(rows = rows, columns = cols)

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
    private var isAltScreenActive = false

    // Scrollback buffer
    private val scrollback = mutableListOf<TerminalLine>()
    private val maxScrollbackLines = 1000

    // Cached immutable copy of scrollback - only recreate when scrollback changes
    private var scrollbackSnapshot: List<TerminalLine> = emptyList()
    private var scrollbackDirty = false

    // Reusable CellRun for fetching cell data
    private val cellRun = CellRun()

    // Current screen lines cache
    private var currentLines = List(initialRows) { row ->
        TerminalLine.empty(row, initialCols, currentDefaultForeground, currentDefaultBackground)
    }

    // Native terminal instance - MUST be initialized AFTER damageLock and other state
    private val terminalNative by lazy {
        TerminalNative(this).apply {
            resize(initialRows, initialCols)
            if (setBoldHighbright(boldAsBright) != 0) {
                Log.e(TAG, "Failed to set boldAsBright=$boldAsBright")
            }
        }
    }

    // Parser for OSC sequences
    private val oscParser = OscParser()

    // ================================================================================
    // Public API
    // ================================================================================

    /**
     * Write data to the terminal (from PTY/transport).
     */
    override fun writeInput(data: ByteArray, offset: Int, length: Int) {
        terminalNative.writeInput(data, offset, length)
    }

    /**
     * Write data to the terminal using ByteBuffer (more efficient for large data).
     */
    override fun writeInput(buffer: ByteBuffer, length: Int) {
        terminalNative.writeInput(buffer, length)
    }

    /**
     * Resize the terminal.
     */
    override fun resize(newRows: Int, newCols: Int) {
        rows = newRows
        cols = newCols
        terminalNative.resize(newRows, newCols)

        synchronized(damageLock) {
            val currentDefaultFg = currentDefaultForeground
            val currentDefaultBg = currentDefaultBackground

            currentLines = List(newRows) { row ->
                TerminalLine.empty(row, newCols, currentDefaultFg, currentDefaultBg)
            }

            pendingDamageRegions.clear()
            pendingDamageRegions.add(
                DamageRegion(
                    startRow = 0,
                    endRow = newRows,
                    startCol = 0,
                    endCol = newCols,
                    preserveSegments = false,
                ),
            )
            requestProcessPendingUpdatesLocked()
        }

        handler.post {
            onResize?.invoke(TerminalDimensions(rows = rows, columns = cols))
        }
    }

    /**
     * Dispatch a key event to the terminal.
     */
    override fun dispatchKey(modifiers: Int, key: Int) {
        terminalNative.dispatchKey(modifiers, key)
    }

    /**
     * Dispatch a character to the terminal.
     */
    override fun dispatchCharacter(modifiers: Int, codepoint: Int) {
        terminalNative.dispatchCharacter(modifiers, codepoint)
    }

    /**
     * Clears the terminal emulator screen.
     */
    override fun clearScreen() = writeInput("\u001B[2J\u001B[H".toByteArray())

    /**
     * Get the text output of the last completed command.
     */
    override fun getLastCommandOutput(): String? {
        val currentSnapshot = _snapshot.value
        val allLines = currentSnapshot.scrollback + currentSnapshot.lines
        return getLastCommandOutput(allLines)
    }

    override fun getUrls(scope: UrlScanScope): List<TerminalUrl> {
        val currentSnapshot = _snapshot.value
        val altScreenActive = synchronized(damageLock) {
            isAltScreenActive
        }
        return extractUrls(currentSnapshot.linesForUrlScan(scope, altScreenActive))
    }

    /**
     * Set ANSI palette colors (indices 0-15).
     *
     * This configures the 16 ANSI colors used by terminal escape sequences.
     * Changing the palette triggers a full redraw with new colors.
     *
     * @param ansiColors IntArray of ARGB colors (size 16 for all ANSI colors)
     * @return Number of colors set, or -1 on error
     */
    override fun setAnsiPalette(ansiColors: IntArray): Int {
        require(ansiColors.size >= 16) {
            "ANSI palette must contain 16 colors"
        }
        val result = terminalNative.setPaletteColors(ansiColors, 16)
        invalidateDisplay()
        return result
    }

    /**
     * Set default terminal colors.
     *
     * These colors are used when terminal content explicitly requests
     * "default" foreground or background (different from ANSI color 7/0).
     * Changing default colors triggers a full redraw.
     *
     * @param foreground ARGB foreground color
     * @param background ARGB background color
     * @return 0 on success, -1 on error
     */
    override fun setDefaultColors(foreground: Int, background: Int): Int {
        synchronized(damageLock) {
            currentDefaultForeground = Color(foreground)
            currentDefaultBackground = Color(background)
        }
        val result = terminalNative.setDefaultColors(foreground, background)
        invalidateDisplay()
        return result
    }

    /**
     * Apply a complete color scheme to the terminal.
     *
     * Convenience method that sets both ANSI palette and default colors
     * from a color scheme. This is the recommended way to apply themes.
     *
     * @param ansiColors IntArray of 16 ARGB colors for ANSI palette
     * @param defaultForeground ARGB color for default foreground
     * @param defaultBackground ARGB color for default background
     */
    override fun applyColorScheme(
        ansiColors: IntArray,
        defaultForeground: Int,
        defaultBackground: Int,
    ) {
        require(ansiColors.size >= 16) {
            "Color scheme must provide 16 ANSI colors"
        }

        setAnsiPalette(ansiColors)
        setDefaultColors(defaultForeground, defaultBackground)
    }

    // ================================================================================
    // TerminalCallbacks implementation
    // ================================================================================

    override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int): Int {
        synchronized(damageLock) {
            addDamageRegion(startRow, endRow, startCol, endCol)
            requestProcessPendingUpdatesLocked()
        }
        return 0
    }

    // Track the last moverect source region so pushScrollbackLine knows
    // whether it was a full-screen or partial scroll region scroll.
    private var lastMoveRectSrc: TermRect? = null

    override fun moverect(dest: TermRect, src: TermRect): Int {
        // Save source rect — pushScrollbackLine uses it to limit segment shifting
        // to lines within the scroll region (avoiding corruption of tmux status bars etc.)
        lastMoveRectSrc = src
        // Treat moverect as display damage on the destination. Semantic segments
        // are shifted alongside the moved text elsewhere, so preserve them here.
        synchronized(damageLock) {
            for (row in dest.startRow until dest.endRow) {
                movedSegmentRows.add(row)
            }
            addDamageRegion(dest.startRow, dest.endRow, dest.startCol, dest.endCol, preserveSegments = true)
            requestProcessPendingUpdatesLocked()
        }
        return 0
    }

    override fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean): Int {
        synchronized(damageLock) {
            cursorRow = pos.row
            cursorCol = pos.col
            cursorVisible = visible
            cursorMoved = true
            requestProcessPendingUpdatesLocked()
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

                        // Property 3 is VTERM_PROP_ALTSCREEN (from vterm.h line 256)
                        3 -> {
                            isAltScreenActive = value.value
                            propertyChanged = true
                        }
                    }
                }

                is TerminalProperty.IntValue -> {
                    // Property 6 is VTERM_PROP_CURSORSHAPE (from vterm.h line 260)
                    if (prop == 6) {
                        cursorShape = when (value.value) {
                            1 -> CursorShape.BLOCK

                            // VTERM_PROP_CURSORSHAPE_BLOCK
                            2 -> CursorShape.UNDERLINE

                            // VTERM_PROP_CURSORSHAPE_UNDERLINE
                            3 -> CursorShape.BAR_LEFT

                            // VTERM_PROP_CURSORSHAPE_BAR_LEFT
                            else -> CursorShape.BLOCK
                        }
                        propertyChanged = true
                    }
                }

                else -> {
                    // Other properties not handled
                }
            }
            if (propertyChanged) {
                requestProcessPendingUpdatesLocked()
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

    override fun pushScrollbackLine(cols: Int, cells: Array<ScreenCell>, softWrapped: Boolean): Int {
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
                width = screenCell.width,
            )
        }

        synchronized(damageLock) {
            // FIRST: Preserve semantic segments from line 0 (the line being scrolled out)
            // This must happen BEFORE we shift segments, since moverect was already called
            val line0Segments = if (currentLines.isNotEmpty()) {
                currentLines[0].semanticSegments
            } else {
                emptyList()
            }

            val line = TerminalLine(row = -1, cells = cellList, softWrapped = softWrapped, semanticSegments = line0Segments)

            scrollback.add(line)
            if (scrollback.size > maxScrollbackLines) {
                scrollback.removeAt(0)
            }
            scrollbackDirty = true

            // Shift semantic segments up within the scroll region only.
            // Lines outside the region (e.g. tmux status bar) keep their segments.
            val moveRect = lastMoveRectSrc
            lastMoveRectSrc = null
            if (currentLines.size > 1) {
                val shiftEnd = if (moveRect != null) {
                    // Partial scroll region: only shift within the region
                    moveRect.endRow.coerceAtMost(currentLines.size)
                } else {
                    // Full-screen scroll
                    currentLines.size
                }
                val newLines = currentLines.toMutableList()
                for (row in 0 until shiftEnd - 1) {
                    shiftStoredSegmentTexts(fromRow = row + 1, toRow = row)
                    newLines[row] = currentLines[row + 1].copy(row = row)
                }
                // Clear segments for the last line in the scroll region
                if (shiftEnd > 0 && shiftEnd <= currentLines.size) {
                    removeStoredSegmentTexts(shiftEnd - 1)
                    newLines[shiftEnd - 1] = currentLines[shiftEnd - 1].copy(
                        semanticSegments = emptyList(),
                    )
                }
                currentLines = newLines
            }

            propertyChanged = true
            requestProcessPendingUpdatesLocked()
        }
        return 0
    }

    override fun clearScrollback(): Int {
        synchronized(damageLock) {
            scrollback.clear()
            scrollbackDirty = true
            propertyChanged = true
            requestProcessPendingUpdatesLocked()
        }
        return 0
    }

    override fun popScrollbackLine(cols: Int, cells: Array<ScreenCell>): Int {
        synchronized(damageLock) {
            if (scrollback.isEmpty()) return 0

            val line = scrollback.removeAt(scrollback.size - 1)
            scrollbackDirty = true

            // Convert TerminalLine.Cell back to ScreenCell (reverse of pushScrollbackLine)
            for (i in 0 until minOf(cols, cells.size)) {
                val cell = line.cells.getOrNull(i)
                if (cell != null) {
                    cells[i] = ScreenCell(
                        char = cell.char,
                        combiningChars = cell.combiningChars,
                        fgRed = (cell.fgColor.red * 255).toInt(),
                        fgGreen = (cell.fgColor.green * 255).toInt(),
                        fgBlue = (cell.fgColor.blue * 255).toInt(),
                        bgRed = (cell.bgColor.red * 255).toInt(),
                        bgGreen = (cell.bgColor.green * 255).toInt(),
                        bgBlue = (cell.bgColor.blue * 255).toInt(),
                        bold = cell.bold,
                        italic = cell.italic,
                        underline = cell.underline,
                        reverse = cell.reverse,
                        strike = cell.strike,
                        width = cell.width,
                    )
                } else {
                    // Fill remaining columns with empty cells using current defaults
                    cells[i] = ScreenCell(
                        char = ' ',
                        fgRed = (currentDefaultForeground.red * 255).toInt(),
                        fgGreen = (currentDefaultForeground.green * 255).toInt(),
                        fgBlue = (currentDefaultForeground.blue * 255).toInt(),
                        bgRed = (currentDefaultBackground.red * 255).toInt(),
                        bgGreen = (currentDefaultBackground.green * 255).toInt(),
                        bgBlue = (currentDefaultBackground.blue * 255).toInt(),
                    )
                }
            }

            propertyChanged = true
            requestProcessPendingUpdatesLocked()
        }
        return 1
    }

    override fun onKeyboardInput(data: ByteArray): Int {
        // Keyboard output callback - post to handler
        handler.post {
            onKeyboardInput.invoke(data)
        }
        return 0
    }

    override fun onOscSequence(command: Int, payload: String, cursorRow: Int, cursorCol: Int): Int {
        // Use the native cursor position from libvterm for OSC sequence processing
        val actions = synchronized(damageLock) {
            oscParser.parse(command, payload, cursorRow, cursorCol, cols)
        }

        synchronized(damageLock) {
            for (action in actions) {
                when (action) {
                    is OscParser.Action.AddSegment -> {
                        addSemanticSegment(
                            action.row,
                            action.startCol,
                            action.endCol,
                            action.type,
                            action.metadata,
                            action.promptId,
                        )
                    }

                    is OscParser.Action.SetCursorShape -> {
                        cursorShape = action.shape
                        propertyChanged = true
                        requestProcessPendingUpdatesLocked()
                    }

                    is OscParser.Action.ClipboardCopy -> {
                        // Post clipboard copy to handler thread to avoid blocking native callback
                        handler.post {
                            onClipboardCopy?.invoke(action.data)
                        }
                    }

                    is OscParser.Action.SetProgress -> {
                        // Post progress change to handler thread to avoid blocking native callback
                        handler.post {
                            onProgressChange?.invoke(action.state, action.progress)
                        }
                    }
                }
            }
        }
        return 1
    }

    /**
     * Apply a semantic segment immediately to the current line.
     * Segments are applied immediately so they can be properly shifted during scroll.
     */
    private fun addSemanticSegment(
        row: Int,
        startCol: Int,
        endCol: Int,
        semanticType: SemanticType,
        metadata: String?,
        promptId: Int,
    ) {
        synchronized(damageLock) {
            // Apply immediately to currentLines so segments are shifted correctly during scroll
            if (row < 0 || row >= currentLines.size) {
                return
            }

            val line = currentLines[row]
            val newSegment = SemanticSegment(
                startCol = startCol,
                endCol = endCol,
                semanticType = semanticType,
                metadata = metadata,
                promptId = promptId,
            )

            val updatedSegments = (line.semanticSegments + newSegment).sortedBy { it.startCol }
            currentLines = currentLines.toMutableList().apply {
                this[row] = line.copy(semanticSegments = updatedSegments)
            }
            pendingSemanticSegments.add(
                PendingSemanticSegment(
                    row = row,
                    startCol = startCol,
                    endCol = endCol,
                    semanticType = semanticType,
                    metadata = metadata,
                    promptId = promptId,
                ),
            )

            // Mark for update so processPendingUpdates runs
            propertyChanged = true
            requestProcessPendingUpdatesLocked()
        }
    }

    // ================================================================================
    // Internal snapshot building
    // ================================================================================

    /**
     * Process pending updates and emit new snapshot.
     * This runs on the Handler thread, NOT in the JNI callback.
     */
    @VisibleForTesting
    fun processPendingUpdates() {
        // Collect pending changes
        val damageRegions: List<DamageRegion>
        val needsUpdate: Boolean
        val movedRows: Set<Int>
        synchronized(damageLock) {
            damageRegions = pendingDamageRegions.toList()
            pendingDamageRegions.clear()
            movedRows = movedSegmentRows.toSet()
            movedSegmentRows.clear()
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
            val endRow = region.endRow.coerceIn(startRow, rows) // endRow is exclusive
            for (row in startRow until endRow) {
                updateLine(row, region, preserveMovedSegments = row in movedRows)
            }
        }

        // Apply pending semantic segments now that text content is available
        val segmentsToApply: List<PendingSemanticSegment>
        synchronized(damageLock) {
            segmentsToApply = pendingSemanticSegments.toList()
            pendingSemanticSegments.clear()
        }

        for (segment in segmentsToApply) {
            applySemanticSegment(segment)
        }

        // Build and emit new snapshot
        val newSnapshot = buildSnapshot()
        _snapshot.value = newSnapshot
    }

    /**
     * Apply a semantic segment to a line.
     * This is called during processPendingUpdates when the actual text is available.
     */
    private fun applySemanticSegment(segment: PendingSemanticSegment) = synchronized(damageLock) {
        val row = segment.row

        // Ensure row is valid
        if (row < 0 || row >= currentLines.size) {
            return@synchronized
        }

        val line = currentLines[row]
        if (segment.semanticType == SemanticType.HYPERLINK) {
            if (!isValidCellRange(segment.startCol, segment.endCol, line.cells.size)) return@synchronized
            val linkedText = cellText(line.cells, segment.startCol, segment.endCol)
            if (linkedText.all { it == '\u0000' || it.isWhitespace() }) return@synchronized
        }

        // Create new segment
        val newSegment = SemanticSegment(
            startCol = segment.startCol,
            endCol = segment.endCol,
            semanticType = segment.semanticType,
            metadata = segment.metadata,
            promptId = segment.promptId,
        )

        if (newSegment in line.semanticSegments) {
            storeSegmentText(row, newSegment, line)
            return@synchronized
        }

        // Add to existing segments (sorted by startCol)
        val updatedSegments = (line.semanticSegments + newSegment)
            .sortedBy { it.startCol }

        // Update the line with new segments
        currentLines = currentLines.toMutableList().apply {
            this[row] = line.copy(semanticSegments = updatedSegments)
        }
        storeSegmentText(row, newSegment, line)
    }

    /**
     * Update a single line by fetching cell data from the terminal.
     */
    private fun updateLine(row: Int, damageRegion: DamageRegion, preserveMovedSegments: Boolean) {
        // Safety check: ensure row is within bounds
        if (row !in 0..<rows) {
            return
        }

        // Capture current default colors (thread-safe)
        val currentDefaultFg: Color
        val currentDefaultBg: Color
        synchronized(damageLock) {
            currentDefaultFg = currentDefaultForeground
            currentDefaultBg = currentDefaultBackground
        }

        val cells = ArrayList<TerminalLine.Cell>(cols)
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
                            fgColor = currentDefaultFg,
                            bgColor = currentDefaultBg,
                        ),
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

                var combiningChars: MutableList<Char>? = null
                charIndex++

                // Handle surrogate pairs (characters > U+FFFF like emoji)
                if (char.isHighSurrogate() && charIndex < cellRun.chars.size) {
                    val nextChar = cellRun.chars[charIndex]
                    if (nextChar.isLowSurrogate()) {
                        combiningChars = mutableListOf(nextChar)
                        charIndex++
                    }
                }

                // Collect combining characters
                while (charIndex < cellRun.chars.size && isCombiningCharacter(cellRun.chars[charIndex])) {
                    if (combiningChars == null) {
                        combiningChars = mutableListOf()
                    }
                    combiningChars.add(cellRun.chars[charIndex])
                    charIndex++
                }

                // Determine cell width
                val extraChars = combiningChars ?: TerminalLine.EMPTY_COMBINING_CHARS
                val width = if (extraChars.isNotEmpty() && extraChars[0].isLowSurrogate()) {
                    val codepoint = Character.toCodePoint(char, extraChars[0])
                    if (isFullwidthCodepoint(codepoint)) 2 else 1
                } else {
                    if (isFullwidthCharacter(char)) 2 else 1
                }

                cells.add(
                    TerminalLine.Cell(
                        char = char,
                        combiningChars = extraChars,
                        fgColor = fgColor,
                        bgColor = bgColor,
                        bold = cellRun.bold,
                        italic = cellRun.italic,
                        underline = cellRun.underline,
                        blink = cellRun.blink,
                        reverse = cellRun.reverse,
                        strike = cellRun.strike,
                        width = width,
                    ),
                )

                cellsInRun++
                if (width == 2) {
                    cellsInRun++
                }
            }

            col += cellsInRun
        }

        // Check if this line is soft-wrapped (the next line is a continuation).
        // A line is soft-wrapped if the next row has continuation=true.
        val softWrapped = if (row + 1 < rows) {
            terminalNative.getLineContinuation(row + 1)
        } else {
            // Last visible row - we can't know if it's wrapped until it scrolls
            false
        }

        // Update cached line, preserving existing semantic segments only when they
        // were not touched by terminal text damage. This prevents stale OSC 8
        // links from surviving line redraws while allowing display-only
        // invalidations, such as palette changes, to keep semantic metadata.
        // Must synchronize to ensure visibility of segments added by addSemanticSegment
        synchronized(damageLock) {
            currentLines = currentLines.toMutableList().apply {
                val previousLine = this[row]
                val existingSegments = previousLine.semanticSegments
                val preservedSegments = if (damageRegion.preserveSegments || preserveMovedSegments) {
                    existingSegments.filter { segment ->
                        segment.endCol <= cells.size &&
                            (!preserveMovedSegments || segmentTextStillMatches(row, segment, cells))
                    }
                } else {
                    existingSegments.filter { segment ->
                        segment.endCol <= cells.size &&
                            !segment.overlaps(damageRegion.startCol, damageRegion.endCol)
                    }
                }
                replaceStoredSegmentTexts(row, preservedSegments)
                this[row] = TerminalLine(row, cells, softWrapped = softWrapped, semanticSegments = preservedSegments)
            }
        }
    }

    /**
     * Build a complete snapshot of terminal state.
     */
    private fun buildSnapshot(): TerminalSnapshot {
        // Read all mutable state under damageLock to ensure cross-thread visibility.
        // addSemanticSegment writes currentLines on the JNI callback thread; without
        // the lock here, the snapshot-building thread might see a stale reference.
        val lines: List<TerminalLine>
        val scrollbackCopy: List<TerminalLine>
        synchronized(damageLock) {
            if (scrollbackDirty) {
                scrollbackSnapshot = scrollback.toList()
                scrollbackDirty = false
            }
            lines = currentLines.toList() // Immutable copy (24 references)
            scrollbackCopy = scrollbackSnapshot // Reuse cached immutable copy
        }

        return TerminalSnapshot(
            lines = lines,
            scrollback = scrollbackCopy,
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            cursorVisible = cursorVisible,
            cursorShape = cursorShape,
            cursorBlink = cursorBlink,
            terminalTitle = terminalTitle,
            rows = rows,
            cols = cols,
            timestamp = System.currentTimeMillis(),
            sequenceNumber = sequenceNumber++,
        )
    }

    private fun TerminalSnapshot.linesForUrlScan(
        scope: UrlScanScope,
        altScreenActive: Boolean,
    ): List<TerminalLine> {
        if (altScreenActive) return lines
        return when (scope) {
            UrlScanScope.CurrentView -> scrollback + lines
            UrlScanScope.ScreenAndScrollback -> scrollback + lines
        }
    }

    private fun extractUrls(lines: List<TerminalLine>): List<TerminalUrl> {
        val seenUrls = linkedSetOf<String>()
        val urls = mutableListOf<TerminalUrl>()

        lines.forEachIndexed { row, line ->
            for (url in extractUrls(lines, row, line)) {
                if (seenUrls.add(url.url)) {
                    urls.add(url)
                }
            }
        }

        return urls
    }

    private fun extractUrls(lines: List<TerminalLine>, row: Int, line: TerminalLine): List<TerminalUrl> {
        val osc8Segments = line.getSegmentsOfType(SemanticType.HYPERLINK)
        val osc8Urls = osc8Segments
            .mapNotNull { segment ->
                val url = segment.metadata
                if (url.isNullOrEmpty() || segment.startCol < 0 || segment.endCol <= segment.startCol) {
                    null
                } else {
                    segment.startCol to TerminalUrl(
                        url = url,
                        source = TerminalUrlSource.Osc8,
                    )
                }
            }

        val autoDetectedUrls = line.autoDetectedUrls
            .mapNotNull { (startCol, endCol, url) ->
                val spans = buildWrappedUrlSpans(lines, row, startCol)
                val detectedUrl = readWrappedUrl(lines, spans) ?: url
                val firstSpan = spans[row] ?: (startCol until endCol)
                if (spansOverlapOsc8(lines, spans.ifEmpty { mapOf(row to firstSpan) })) {
                    null
                } else {
                    startCol to TerminalUrl(
                        url = detectedUrl,
                        source = TerminalUrlSource.AutoDetected,
                    )
                }
            }

        return (osc8Urls + autoDetectedUrls).sortedWith(
            compareBy<Pair<Int, TerminalUrl>> { it.first }
                .thenBy { it.second.source.priority },
        ).map { it.second }
    }

    private val TerminalUrlSource.priority: Int
        get() = when (this) {
            TerminalUrlSource.Osc8 -> 0
            TerminalUrlSource.AutoDetected -> 1
        }

    private fun buildWrappedUrlSpans(lines: List<TerminalLine>, anchorRow: Int, anchorCol: Int): Map<Int, IntRange> {
        val spans = linkedMapOf<Int, IntRange>()
        var row = anchorRow
        var startCol = anchorCol

        while (row < lines.size && (row - anchorRow) < MAX_URL_SCAN_CONTINUATION_ROWS) {
            val text = lines[row].columnText
            if (startCol !in text.indices || !text[startCol].isUrlSafe()) break

            var endCol = startCol
            while (endCol < text.length && text[endCol].isUrlSafe()) {
                endCol++
            }

            val nextStart = continuationStart(lines, row, endCol)
            if (nextStart != null) {
                spans[row] = startCol until endCol
                row++
                startCol = nextStart
            } else {
                val trimmed = text.substring(startCol, endCol).trimDetectedUrl()
                if (trimmed.isNotEmpty()) {
                    spans[row] = startCol until (startCol + trimmed.length)
                }
                break
            }
        }

        return spans
    }

    private fun continuationStart(lines: List<TerminalLine>, previousRow: Int, previousEndCol: Int): Int? {
        if (previousRow + 1 >= lines.size) return null

        val previousLine = lines[previousRow]
        val previousText = previousLine.columnText
        val previousTrimmedEnd = previousText.indexOfLast { it != '\u0000' && !it.isWhitespace() } + 1
        if (previousTrimmedEnd <= 0 || previousEndCol < previousTrimmedEnd) return null

        val rowFilled = previousEndCol >= previousLine.cells.size || previousLine.softWrapped
        val nextText = lines[previousRow + 1].columnText
        val start = firstUrlSafeAfterPrefix(nextText) ?: return null

        var end = start
        while (end < nextText.length && nextText[end].isUrlSafe()) {
            end++
        }
        val run = nextText.substring(start, end)
        if (run.trimDetectedUrl().isEmpty()) return null

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

    private fun readWrappedUrl(lines: List<TerminalLine>, spans: Map<Int, IntRange>): String? {
        if (spans.isEmpty()) return null
        val joined = buildString {
            for ((spanRow, span) in spans.toSortedMap()) {
                val text = lines[spanRow].columnText
                val end = (span.last + 1).coerceAtMost(text.length)
                if (span.first < end) append(text.substring(span.first, end))
            }
        }
        return TerminalLine.URL_REGEX.findAll(joined).firstOrNull()?.value?.trimDetectedUrl()?.takeIf { it.isNotEmpty() }
    }

    private fun spansOverlapOsc8(lines: List<TerminalLine>, spans: Map<Int, IntRange>): Boolean = spans.any { (row, span) ->
        val endCol = span.last + 1
        lines.getOrNull(row)?.getSegmentsOfType(SemanticType.HYPERLINK)?.any {
            it.startCol < endCol && span.first < it.endCol
        } == true
    }

    // ================================================================================
    // Helper methods
    // ================================================================================

    /**
     * Trigger a full display redraw.
     * Used when global display properties change (colors, etc.).
     */
    private fun invalidateDisplay() {
        synchronized(damageLock) {
            pendingDamageRegions.clear()
            pendingDamageRegions.add(DamageRegion(0, rows, 0, cols, preserveSegments = true))
            requestProcessPendingUpdatesLocked()
        }
    }

    /**
     * Schedule snapshot work at display-frame cadence.
     *
     * libvterm can report many small damage/cursor callbacks while a single PTY read is
     * processed. Running updateLine/buildSnapshot for every callback burst can outpace
     * vsync and make Compose redraw the terminal multiple times for one displayed frame.
     *
     * MUST be called with damageLock held.
     */
    private fun requestProcessPendingUpdatesLocked() {
        if (damagePosted) return
        damagePosted = true
        if (looper == Looper.getMainLooper()) {
            handler.post {
                Choreographer.getInstance().postFrameCallback {
                    processPendingUpdates()
                }
            }
        } else {
            handler.post {
                processPendingUpdates()
            }
        }
    }

    /**
     * Add a damage region, coalescing with existing regions where possible.
     *
     * This prevents unbounded growth of damage regions during rapid updates
     * (like cacafire). Regions are coalesced if they overlap or touch on row boundaries.
     *
     * MUST be called with damageLock held.
     */
    private fun addDamageRegion(
        startRow: Int,
        endRow: Int,
        startCol: Int,
        endCol: Int,
        preserveSegments: Boolean = false,
    ) {
        // If list is getting large, coalesce more aggressively
        if (pendingDamageRegions.size > 100) {
            // Just mark entire screen as damaged to avoid O(n²) coalescing
            pendingDamageRegions.clear()
            pendingDamageRegions.add(DamageRegion(0, rows, 0, cols, preserveSegments = preserveSegments))
            return
        }

        // Try to merge with existing regions
        var merged = false
        for (i in pendingDamageRegions.indices) {
            val existing = pendingDamageRegions[i]

            // Check if regions overlap or touch on row boundaries
            val rowsOverlap = !(endRow < existing.startRow || startRow > existing.endRow)

            if (rowsOverlap && existing.preserveSegments == preserveSegments) {
                // Merge the regions
                val newStartRow = minOf(startRow, existing.startRow)
                val newEndRow = maxOf(endRow, existing.endRow)
                val newStartCol = minOf(startCol, existing.startCol)
                val newEndCol = maxOf(endCol, existing.endCol)

                pendingDamageRegions[i] = DamageRegion(
                    newStartRow,
                    newEndRow,
                    newStartCol,
                    newEndCol,
                    preserveSegments = preserveSegments,
                )
                merged = true
                break
            }
        }

        if (!merged) {
            pendingDamageRegions.add(DamageRegion(startRow, endRow, startCol, endCol, preserveSegments = preserveSegments))
        }
    }

    private fun isCombiningCharacter(char: Char): Boolean = UCharacter.hasBinaryProperty(char.code, UProperty.GRAPHEME_EXTEND)

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

    private fun storeSegmentText(row: Int, segment: SemanticSegment, line: TerminalLine) {
        synchronized(damageLock) {
            if (!isValidCellRange(segment.startCol, segment.endCol, line.cells.size)) return@synchronized
            semanticSegmentTexts[SemanticSegmentKey(row, segment)] = cellText(line.cells, segment.startCol, segment.endCol)
        }
    }

    private fun segmentTextStillMatches(row: Int, segment: SemanticSegment, cells: List<TerminalLine.Cell>): Boolean {
        return synchronized(damageLock) {
            val expected = semanticSegmentTexts[SemanticSegmentKey(row, segment)] ?: return@synchronized true
            if (!isValidCellRange(segment.startCol, segment.endCol, cells.size)) return@synchronized false
            val actual = cellText(cells, segment.startCol, segment.endCol)
            actual == expected
        }
    }

    private fun isValidCellRange(startCol: Int, endCol: Int, cellCount: Int): Boolean = startCol >= 0 && endCol >= startCol && endCol <= cellCount

    private fun cellText(cells: List<TerminalLine.Cell>, startCol: Int, endCol: Int): String = buildString {
        for (col in startCol until endCol) {
            append(cells[col].char)
            cells[col].combiningChars.forEach { append(it) }
        }
    }

    private fun replaceStoredSegmentTexts(row: Int, segments: List<SemanticSegment>) {
        synchronized(damageLock) {
            val keep = segments.mapTo(mutableSetOf()) { SemanticSegmentKey(row, it) }
            semanticSegmentTexts.keys.removeAll { it.row == row && it !in keep }
        }
    }

    private fun removeStoredSegmentTexts(row: Int) {
        synchronized(damageLock) {
            semanticSegmentTexts.keys.removeAll { it.row == row }
        }
    }

    private fun shiftStoredSegmentTexts(fromRow: Int, toRow: Int) {
        synchronized(damageLock) {
            removeStoredSegmentTexts(toRow)
            val moved = semanticSegmentTexts.entries
                .filter { it.key.row == fromRow }
                .map { (key, value) -> key.copy(row = toRow) to value }
            removeStoredSegmentTexts(fromRow)
            semanticSegmentTexts.putAll(moved)
        }
    }

    companion object {
        private const val TAG = "TerminalEmulatorImpl"
        private const val MAX_URL_SCAN_CONTINUATION_ROWS = 6
    }
}

/**
 * Represents a damaged region that needs updating.
 */
private data class DamageRegion(
    val startRow: Int,
    val endRow: Int,
    val startCol: Int,
    val endCol: Int,
    val preserveSegments: Boolean = false,
)

/**
 * Represents a semantic segment waiting to be applied to a line.
 * Segments are queued during OSC processing and applied during processPendingUpdates
 * when the actual text content is available.
 */
private data class PendingSemanticSegment(
    val row: Int,
    val startCol: Int,
    val endCol: Int,
    val semanticType: SemanticType,
    val metadata: String?,
    val promptId: Int,
)

private data class SemanticSegmentKey(
    val row: Int,
    val startCol: Int,
    val endCol: Int,
    val semanticType: SemanticType,
    val metadata: String?,
    val promptId: Int,
) {
    constructor(row: Int, segment: SemanticSegment) : this(
        row = row,
        startCol = segment.startCol,
        endCol = segment.endCol,
        semanticType = segment.semanticType,
        metadata = segment.metadata,
        promptId = segment.promptId,
    )
}

/**
 * Represents the size of the terminal in characters.
 */
data class TerminalDimensions(
    val rows: Int,
    val columns: Int,
)
