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
class TerminalNative(private val callbacks: TerminalCallbacks) : AutoCloseable {
    private var nativePtr: Long = 0

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
        checkNotClosed()
        return nativeWriteInputBuffer(nativePtr, buffer, length)
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
        checkNotClosed()
        return nativeWriteInputArray(nativePtr, data, offset, length)
    }

    /**
     * Resize the terminal.
     *
     * @param rows Number of rows
     * @param cols Number of columns
     * @param scrollRows Number of scrollback rows to maintain
     * @return 0 on success
     */
    fun resize(rows: Int, cols: Int, scrollRows: Int = 100): Int {
        checkNotClosed()
        return nativeResize(nativePtr, rows, cols, scrollRows)
    }

    /**
     * Get current number of rows.
     */
    fun getRows(): Int {
        checkNotClosed()
        return nativeGetRows(nativePtr)
    }

    /**
     * Get current number of columns.
     */
    fun getCols(): Int {
        checkNotClosed()
        return nativeGetCols(nativePtr)
    }

    /**
     * Get current scrollback size.
     */
    fun getScrollRows(): Int {
        checkNotClosed()
        return nativeGetScrollRows(nativePtr)
    }

    /**
     * Dispatch a keyboard key event to the terminal.
     * This generates appropriate escape sequences via onKeyboardInput() callback.
     *
     * @param modifiers Bitmask: 1=Shift, 2=Alt, 4=Ctrl
     * @param key VTermKey value
     * @return true if handled
     */
    fun dispatchKey(modifiers: Int, key: Int): Boolean {
        checkNotClosed()
        return nativeDispatchKey(nativePtr, modifiers, key)
    }

    /**
     * Dispatch a character input to the terminal.
     * This generates appropriate escape sequences via onKeyboardInput() callback.
     *
     * @param modifiers Bitmask: 1=Shift, 2=Alt, 4=Ctrl
     * @param character Unicode codepoint
     * @return true if handled
     */
    fun dispatchCharacter(modifiers: Int, character: Int): Boolean {
        checkNotClosed()
        return nativeDispatchCharacter(nativePtr, modifiers, character)
    }

    /**
     * Get a run of cells with identical formatting starting at the given position.
     * This is the primary method for retrieving terminal content for rendering.
     *
     * @param row Row index (0-based)
     * @param col Column index (0-based)
     * @param run CellRun object to fill (reusable, call reset() first)
     * @return Number of cells in the run
     */
    fun getCellRun(row: Int, col: Int, run: CellRun): Int {
        checkNotClosed()
        return nativeGetCellRun(nativePtr, row, col, run)
    }

    /**
     * Close the terminal and release native resources.
     * After calling this, the Terminal instance cannot be used.
     */
    override fun close() {
        if (nativePtr != 0L) {
            nativeDestroy(nativePtr)
            nativePtr = 0
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
    private external fun nativeResize(ptr: Long, rows: Int, cols: Int, scrollRows: Int): Int
    private external fun nativeGetRows(ptr: Long): Int
    private external fun nativeGetCols(ptr: Long): Int
    private external fun nativeGetScrollRows(ptr: Long): Int
    private external fun nativeDispatchKey(ptr: Long, modifiers: Int, key: Int): Boolean
    private external fun nativeDispatchCharacter(ptr: Long, modifiers: Int, character: Int): Boolean
    private external fun nativeGetCellRun(ptr: Long, row: Int, col: Int, run: CellRun): Int

    companion object {
        init {
            System.loadLibrary("jni_cb_term")
        }
    }
}
