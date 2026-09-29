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

import java.io.File
import java.nio.ByteBuffer

/**
 * Terminal emulator using libvterm via JNI.
 *
 * This class provides terminal emulation without PTY management.
 * The caller is responsible for:
 * - Creating and managing the PTY
 * - Reading data from PTY and feeding to writeInput()
 * - Handling onKeyboardInput() callback and writing to PTY
 *
 * Thread Safety:
 * - All native calls are protected by a non-reentrant mutex
 * - Callbacks MUST NOT call back into Terminal methods (will deadlock)
 * - Safe to call from multiple threads (serialized by native mutex)
 */
internal class TerminalNative(callbacks: TerminalCallbacks) : AutoCloseable {
    private var nativePtr: Long = 0
    private val lifetimeLock = Any()
    private var inNativeCall = false

    private inline fun <T> withNative(block: () -> T): T = synchronized(lifetimeLock) {
        checkNotClosed()
        check(!inNativeCall) { "Synchronous native reentry from a terminal callback is prohibited" }
        inNativeCall = true
        try {
            block()
        } finally {
            inNativeCall = false
        }
    }

    init {
        nativePtr = nativeInit(callbacks)
        if (nativePtr == 0L) {
            throw RuntimeException("Failed to initialize native terminal")
        }
    }

    /**
     * Feed input data from PTY to the terminal emulator.
     * This processes the byte stream and updates the terminal state.
     *
     * @param buffer Direct ByteBuffer containing data
     * @param length Number of bytes to read
     * @return Number of bytes consumed
     */
    fun writeInput(buffer: ByteBuffer, length: Int): Int {
        require(buffer.isDirect && length >= 0 && length <= buffer.capacity()) { "Invalid direct buffer range" }
        return withNative { nativeWriteInputBuffer(nativePtr, buffer, length) }
    }

    /**
     * Feed input data from PTY to the terminal emulator.
     * This processes the byte stream and updates the terminal state.
     *
     * @param data Byte array containing data
     * @param offset Starting offset in array
     * @param length Number of bytes to read
     * @return Number of bytes consumed
     */
    fun writeInput(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0 && offset <= data.size && length >= 0 && length <= data.size - offset) { "Invalid input slice" }
        return withNative { nativeWriteInputArray(nativePtr, data, offset, length) }
    }

    /**
     * Resize the terminal.
     *
     * @param rows Number of rows
     * @param cols Number of columns
     * @return 0 on success
     */
    fun resize(rows: Int, cols: Int): Int {
        require(rows > 0 && cols > 0 && cols <= Int.MAX_VALUE / CellData.STRIDE && rows <= Int.MAX_VALUE / cols) {
            "Invalid terminal dimensions"
        }
        return withNative { nativeResize(nativePtr, rows, cols) }
    }

    /**
     * Dispatch a keyboard key event to the terminal.
     * This generates appropriate escape sequences via onKeyboardInput() callback.
     *
     * @param modifiers Bitmask: 1=Shift, 2=Alt, 4=Ctrl
     * @param key VTermKey value
     * @return true if handled
     */
    fun dispatchKey(modifiers: Int, key: Int): Boolean = withNative { nativeDispatchKey(nativePtr, modifiers, key) }

    /**
     * Dispatch a character input to the terminal.
     * This generates appropriate escape sequences via onKeyboardInput() callback.
     *
     * @param modifiers Bitmask: 1=Shift, 2=Alt, 4=Ctrl
     * @param character Unicode codepoint
     * @return true if handled
     */
    fun dispatchCharacter(modifiers: Int, character: Int): Boolean {
        require(CellData.isScalar(character)) { "Invalid Unicode code point" }
        return withNative { nativeDispatchCharacter(nativePtr, modifiers, character) }
    }

    /** Fill bounded row/column requests in Kotlin-owned direct scratch. */
    fun getCells(buffer: ByteBuffer, requests: Int): Int {
        require(buffer.isDirect && !buffer.isReadOnly && buffer.capacity() >= CellData.BUFFER_BYTES)
        require(buffer.order() == java.nio.ByteOrder.nativeOrder() && requests in 0..CellData.MAX_REQUESTS)
        return withNative { nativeGetCells(nativePtr, buffer, requests) }
    }

    /**
     * Move the mouse cursor to a cell.
     *
     * Records the position used by subsequent [mouseButton] reports. A motion
     * report is emitted via onKeyboardInput() only when the application has
     * requested drag tracking (DECSET 1002, while a button is held) or any-motion
     * tracking (DECSET 1003). Moving to the cell the mouse already occupies is a
     * no-op, so repeated calls at the same cell do not flood the application.
     *
     * @param row Row index (0-based); clamped to the screen natively
     * @param col Column index (0-based); clamped to the screen natively
     * @param modifiers Bitmask: 1=Shift, 2=Alt, 4=Ctrl
     * @return true if handled
     */
    fun mouseMove(row: Int, col: Int, modifiers: Int): Boolean {
        return withNative { nativeMouseMove(nativePtr, row, col, modifiers) }
    }

    /**
     * Dispatch a mouse button press or release at a cell.
     *
     * The move to [row]/[col] and the button report are made under a single
     * native lock, so a concurrent report for another gesture cannot land
     * between the two and send this button at the wrong position.
     *
     * Nothing is emitted unless the application has enabled mouse tracking. The
     * report encoding follows the protocol the application selected (X10, UTF-8,
     * SGR or rxvt).
     *
     * @param row Row index (0-based)
     * @param col Column index (0-based)
     * @param button 1=left, 2=middle, 3=right, 4=wheel up, 5=wheel down,
     *               6=wheel left, 7=wheel right
     * @param pressed true for press, false for release. Wheel buttons only
     *                report presses; a release is not expected.
     * @param modifiers Bitmask: 1=Shift, 2=Alt, 4=Ctrl
     * @return true if handled
     */
    fun mouseButton(row: Int, col: Int, button: Int, pressed: Boolean, modifiers: Int): Boolean {
        return withNative { nativeMouseButton(nativePtr, row, col, button, pressed, modifiers) }
    }

    /**
     * Dispatch a press and its matching release at a cell.
     *
     * Both reports are emitted under a single native lock, so a click cannot be
     * left half-delivered — an application that saw the press always sees the
     * release, whatever else is happening on other threads.
     *
     * @param row Row index (0-based)
     * @param col Column index (0-based)
     * @param button 1=left, 2=middle, 3=right
     * @param modifiers Bitmask: 1=Shift, 2=Alt, 4=Ctrl
     * @return true if handled
     */
    fun mouseClick(row: Int, col: Int, button: Int, modifiers: Int): Boolean {
        return withNative { nativeMouseClick(nativePtr, row, col, button, modifiers) }
    }

    /**
     * Dispatch [steps] presses of a wheel button at a cell.
     *
     * Equivalent to [steps] calls to [mouseButton] with a wheel button, but the
     * whole burst is emitted under a single native lock so it cannot be
     * interleaved with another gesture's reports. The native layer bounds the
     * burst, so no caller can hold that lock for an arbitrary length of time.
     *
     * @param row Row index (0-based)
     * @param col Column index (0-based)
     * @param button 4=wheel up, 5=wheel down, 6=wheel left, 7=wheel right
     * @param steps Number of detents to report; values below 1 send nothing
     * @param modifiers Bitmask: 1=Shift, 2=Alt, 4=Ctrl
     * @return true if handled
     */
    fun scrollWheel(row: Int, col: Int, button: Int, steps: Int, modifiers: Int): Boolean {
        return withNative { nativeScrollWheel(nativePtr, row, col, button, steps, modifiers) }
    }


    /**
     * Set ANSI palette colors (indices 0-15).
     *
     * This configures the 16 ANSI colors used by terminal escape sequences.
     * Changing the palette triggers a full redraw with the new colors.
     *
     * @param colors IntArray of ARGB colors (must have at least 'count' elements)
     * @param count Number of colors to set (max 16, default: min(colors.size, 16))
     * @return Number of colors set, or -1 on error
     */
    fun setPaletteColors(colors: IntArray, count: Int = colors.size.coerceAtMost(16)): Int {
        require(count in 0..16) { "Can only set up to 16 ANSI palette colors" }
        require(colors.size >= count) { "Color array too small for requested count" }
        return withNative { nativeSetPaletteColors(nativePtr, colors, count) }
    }

    /**
     * Set default foreground and background colors.
     *
     * These colors are used when terminal content explicitly requests "default" color
     * (different from ANSI color 7/0). Changing default colors triggers a full redraw.
     *
     * @param foreground ARGB foreground color
     * @param background ARGB background color
     * @return 0 on success, -1 on error
     */
    fun setDefaultColors(foreground: Int, background: Int): Int = withNative { nativeSetDefaultColors(nativePtr, foreground, background) }

    /**
     * Enable or disable bold-as-bright color promotion.
     *
     * When enabled, bold text using low-intensity ANSI colors (0–7) promotes
     * to the corresponding bright palette color (8–15), matching xterm behavior.
     *
     * @param enabled true to enable bold-as-bright, false to disable
     * @return 0 on success, -1 on error
     */
    fun setBoldHighbright(enabled: Boolean): Int = withNative { nativeSetBoldHighbright(nativePtr, enabled) }

    /**
     * Close the terminal and release native resources.
     * After calling this, the Terminal instance cannot be used.
     */
    override fun close() {
        synchronized(lifetimeLock) {
            check(!inNativeCall) { "Cannot close a terminal from its native callback" }
            if (nativePtr != 0L) {
                nativeDestroy(nativePtr)
                nativePtr = 0
            }
        }
    }

    private fun checkNotClosed() {
        if (nativePtr == 0L) {
            throw IllegalStateException("Terminal has been closed")
        }
    }

    @Suppress("unused")
    protected fun finalize() {
        // Failsafe cleanup
        close()
    }

    // Native method declarations
    private external fun nativeInit(callbacks: TerminalCallbacks): Long
    private external fun nativeDestroy(ptr: Long): Int
    private external fun nativeWriteInputBuffer(ptr: Long, buffer: ByteBuffer, length: Int): Int
    private external fun nativeWriteInputArray(ptr: Long, data: ByteArray, offset: Int, length: Int): Int
    private external fun nativeResize(ptr: Long, rows: Int, cols: Int): Int
    private external fun nativeDispatchKey(ptr: Long, modifiers: Int, key: Int): Boolean
    private external fun nativeDispatchCharacter(ptr: Long, modifiers: Int, character: Int): Boolean
    private external fun nativePaste(ptr: Long, data: ByteArray)

    fun pasteText(data: ByteArray) = withNative { nativePaste(nativePtr, data) }
    private external fun nativeGetCells(ptr: Long, buffer: ByteBuffer, requests: Int): Int

    private external fun nativeMouseMove(ptr: Long, row: Int, col: Int, modifiers: Int): Boolean
    private external fun nativeMouseButton(ptr: Long, row: Int, col: Int, button: Int, pressed: Boolean, modifiers: Int): Boolean
    private external fun nativeMouseClick(ptr: Long, row: Int, col: Int, button: Int, modifiers: Int): Boolean
    private external fun nativeScrollWheel(ptr: Long, row: Int, col: Int, button: Int, steps: Int, modifiers: Int): Boolean
    private external fun nativeSetPaletteColors(ptr: Long, colors: IntArray, count: Int): Int
    private external fun nativeSetDefaultColors(ptr: Long, fgColor: Int, bgColor: Int): Int
    private external fun nativeSetBoldHighbright(ptr: Long, enabled: Boolean): Int

    companion object {
        private const val LIBRARY_NAME = "jni_cb_term"

        init {
            loadNativeLibrary()
        }

        private fun loadNativeLibrary() {
            try {
                System.loadLibrary(LIBRARY_NAME)
            } catch (e: UnsatisfiedLinkError) {
                if (!e.message.orEmpty().contains("already loaded in another classloader")) {
                    throw e
                }

                loadCopiedNativeLibrary(e)
            }
        }

        private fun loadCopiedNativeLibrary(cause: UnsatisfiedLinkError) {
            val mappedName = System.mapLibraryName(LIBRARY_NAME)
            val source = System.getProperty("java.library.path")
                .orEmpty()
                .split(File.pathSeparator)
                .asSequence()
                .filter { it.isNotBlank() }
                .map { File(it, mappedName) }
                .firstOrNull { it.isFile }
                ?: throw cause

            val target = File.createTempFile("${LIBRARY_NAME}-", "-$mappedName")
            target.deleteOnExit()
            source.copyTo(target, overwrite = true)
            System.load(target.absolutePath)
        }
    }
}
