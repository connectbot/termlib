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

class TerminalNative(
    callbacks: TerminalCallbacks,
) : TerminalBackend {
    private var nativePtr: Long = 0

    init {
        nativePtr = nativeInit(callbacks)
        if (nativePtr == 0L) {
            throw RuntimeException("Failed to initialize native terminal")
        }
    }

    fun writeInput(
        buffer: ByteBuffer,
        length: Int,
    ): Int {
        checkNotClosed()
        return nativeWriteInputBuffer(nativePtr, buffer, length)
    }

    override fun writeInput(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        checkNotClosed()
        return nativeWriteInputArray(nativePtr, data, offset, length)
    }

    override fun resize(
        rows: Int,
        cols: Int,
    ): Int {
        checkNotClosed()
        return nativeResize(nativePtr, rows, cols)
    }

    override fun dispatchKey(
        modifiers: Int,
        key: Int,
    ): Boolean {
        checkNotClosed()
        return nativeDispatchKey(nativePtr, modifiers, key)
    }

    override fun dispatchCharacter(
        modifiers: Int,
        codepoint: Int,
    ): Boolean {
        checkNotClosed()
        return nativeDispatchCharacter(nativePtr, modifiers, codepoint)
    }

    override fun getCellRun(
        row: Int,
        col: Int,
        run: CellRun,
    ): Int {
        checkNotClosed()
        return nativeGetCellRun(nativePtr, row, col, run)
    }

    override fun setPaletteColors(
        colors: IntArray,
        count: Int,
    ): Int {
        checkNotClosed()
        require(count <= 16) { "Can only set up to 16 ANSI palette colors" }
        require(colors.size >= count) { "Color array too small for requested count" }
        return nativeSetPaletteColors(nativePtr, colors, count)
    }

    override fun setDefaultColors(
        foreground: Int,
        background: Int,
    ): Int {
        checkNotClosed()
        return nativeSetDefaultColors(nativePtr, foreground, background)
    }

    override fun getLineContinuation(row: Int): Boolean {
        checkNotClosed()
        return nativeGetLineContinuation(nativePtr, row)
    }

    override fun setBoldHighbright(enabled: Boolean): Int {
        checkNotClosed()
        return nativeSetBoldHighbright(nativePtr, enabled)
    }

    override fun close() {
        if (nativePtr != 0L) {
            nativeDestroy(nativePtr)
            nativePtr = 0
        }
    }

    private fun checkNotClosed() {
        if (nativePtr == 0L) throw IllegalStateException("Terminal has been closed")
    }

    @Suppress("unused")
    protected fun finalize() = close()

    private external fun nativeInit(callbacks: TerminalCallbacks): Long

    private external fun nativeDestroy(ptr: Long): Int

    private external fun nativeWriteInputBuffer(
        ptr: Long,
        buffer: ByteBuffer,
        length: Int,
    ): Int

    private external fun nativeWriteInputArray(
        ptr: Long,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    private external fun nativeResize(
        ptr: Long,
        rows: Int,
        cols: Int,
    ): Int

    private external fun nativeDispatchKey(
        ptr: Long,
        modifiers: Int,
        key: Int,
    ): Boolean

    private external fun nativeDispatchCharacter(
        ptr: Long,
        modifiers: Int,
        character: Int,
    ): Boolean

    private external fun nativeGetCellRun(
        ptr: Long,
        row: Int,
        col: Int,
        run: CellRun,
    ): Int

    private external fun nativeSetPaletteColors(
        ptr: Long,
        colors: IntArray,
        count: Int,
    ): Int

    private external fun nativeSetDefaultColors(
        ptr: Long,
        fgColor: Int,
        bgColor: Int,
    ): Int

    private external fun nativeGetLineContinuation(
        ptr: Long,
        row: Int,
    ): Boolean

    private external fun nativeSetBoldHighbright(
        ptr: Long,
        enabled: Boolean,
    ): Int

    companion object {
        init {
            try {
                System.loadLibrary("jni_cb_term")
            } catch (e: Exception) {
                System.err.println("Failed to load JNI library: ${e.message}")
            }
        }
    }
}
