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
package org.connectbot.terminal.wasm

import org.connectbot.terminal.CellRun
import org.connectbot.terminal.CursorPosition
import org.connectbot.terminal.ScreenCell
import org.connectbot.terminal.TermRect
import org.connectbot.terminal.TerminalCallbacks
import org.connectbot.terminal.TerminalNative
import org.connectbot.terminal.TerminalProperty
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow

/**
 * Robolectric shadow that replaces [TerminalNative] with [TerminalWasm] for host JVM tests.
 *
 * Add to your test configuration:
 * ```kotlin
 * @Config(shadows = [ShadowTerminalNative::class])
 * ```
 * or globally in `robolectric.properties`:
 * ```
 * shadows=org.connectbot.terminal.wasm.ShadowTerminalNative
 * ```
 */
@Implements(TerminalNative::class)
class ShadowTerminalNative {
    @RealObject
    private lateinit var realNative: TerminalNative

    private lateinit var wasm: TerminalWasm
    private var rows = 24
    private var cols = 80

    private inner class BridgeCallbacks(
        private val callbacks: TerminalCallbacks,
    ) : WasmCallbacks {
        override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int): Int = callbacks.damage(startRow, endRow, startCol, endCol)

        override fun moverect(
            dstStartRow: Int,
            dstEndRow: Int,
            dstStartCol: Int,
            dstEndCol: Int,
            srcStartRow: Int,
            srcEndRow: Int,
            srcStartCol: Int,
            srcEndCol: Int,
        ): Int = callbacks.moverect(
            TermRect(dstStartRow, dstEndRow, dstStartCol, dstEndCol),
            TermRect(srcStartRow, srcEndRow, srcStartCol, srcEndCol),
        )

        override fun moveCursor(row: Int, col: Int, oldRow: Int, oldCol: Int, visible: Boolean): Int = callbacks.moveCursor(CursorPosition(row, col), CursorPosition(oldRow, oldCol), visible)

        override fun setTermProp(prop: Int, type: Int, iVal: Int, str: String?): Int {
            val value: TerminalProperty = when (type) {
                1 -> TerminalProperty.BoolValue(iVal != 0)

                2 -> TerminalProperty.IntValue(iVal)

                3 -> TerminalProperty.StringValue(str ?: "")

                4 -> TerminalProperty.ColorValue(
                    (iVal shr 16) and 0xFF,
                    (iVal shr 8) and 0xFF,
                    iVal and 0xFF,
                )

                else -> return 0
            }
            return callbacks.setTermProp(prop, value)
        }

        override fun bell(): Int = callbacks.bell()

        override fun pushScrollbackLine(cells: List<WasmScreenCell>, softWrapped: Boolean): Int {
            val screenCells = Array(cells.size) { i ->
                val c = cells[i]
                val cp = c.chars.firstOrNull { it != 0 } ?: ' '.code
                val (char, combining) = if (cp > 0xFFFF) {
                    val high = Character.highSurrogate(cp)
                    val low = Character.lowSurrogate(cp)
                    high to listOf(low)
                } else {
                    cp.toChar() to emptyList()
                }
                ScreenCell(
                    char = char,
                    combiningChars = combining,
                    fgRed = c.fgRed,
                    fgGreen = c.fgGreen,
                    fgBlue = c.fgBlue,
                    bgRed = c.bgRed,
                    bgGreen = c.bgGreen,
                    bgBlue = c.bgBlue,
                    bold = c.bold,
                    italic = c.italic,
                    underline = c.underline,
                    reverse = c.reverse,
                    strike = c.strike,
                    width = c.width,
                )
            }
            return callbacks.pushScrollbackLine(cells.size, screenCells, softWrapped)
        }

        override fun popScrollbackLine(cols: Int): List<WasmScreenCell>? {
            val cells = Array(cols) {
                ScreenCell(char = ' ', fgRed = 0, fgGreen = 0, fgBlue = 0, bgRed = 0, bgGreen = 0, bgBlue = 0)
            }
            val result = callbacks.popScrollbackLine(cols, cells)
            if (result == 0) return null
            return cells.map { c ->
                val cp = if (c.combiningChars.isNotEmpty() && c.combiningChars[0].isLowSurrogate()) {
                    Character.toCodePoint(c.char, c.combiningChars[0])
                } else {
                    c.char.code
                }
                WasmScreenCell(
                    chars = intArrayOf(cp, 0, 0, 0, 0, 0),
                    fgRed = c.fgRed,
                    fgGreen = c.fgGreen,
                    fgBlue = c.fgBlue,
                    bgRed = c.bgRed,
                    bgGreen = c.bgGreen,
                    bgBlue = c.bgBlue,
                    bold = c.bold,
                    italic = c.italic,
                    underline = c.underline,
                    reverse = c.reverse,
                    strike = c.strike,
                    width = c.width,
                )
            }
        }

        override fun onKeyboardOutput(data: ByteArray) {
            callbacks.onKeyboardInput(data)
        }

        override fun onOscSequence(command: Int, payload: String, cursorRow: Int, cursorCol: Int): Int = callbacks.onOscSequence(command, payload, cursorRow, cursorCol)
    }

    @Implementation
    @Suppress("ktlint:standard:function-naming")
    fun __constructor__(callbacks: TerminalCallbacks) {
        wasm = TerminalWasm(rows, cols, BridgeCallbacks(callbacks))
    }

    @Implementation
    fun writeInput(data: ByteArray, offset: Int, length: Int): Int = wasm.writeInput(data, offset, length)

    @Implementation
    fun resize(rows: Int, cols: Int): Int {
        this.rows = rows
        this.cols = cols
        return wasm.resize(rows, cols)
    }

    @Implementation
    fun dispatchKey(modifiers: Int, key: Int): Boolean = wasm.dispatchKey(modifiers, key)

    @Implementation
    fun dispatchCharacter(modifiers: Int, codepoint: Int): Boolean = wasm.dispatchCharacter(modifiers, codepoint)

    @Implementation
    fun getCellRun(row: Int, col: Int, run: CellRun): Int = wasm.getCellRun(row, col, run)

    @Implementation
    fun setPaletteColors(colors: IntArray, count: Int): Int = wasm.setPaletteColors(colors, count)

    @Implementation
    fun setDefaultColors(foreground: Int, background: Int): Int = wasm.setDefaultColors(foreground, background)

    @Implementation
    fun getLineContinuation(row: Int): Boolean = wasm.getLineContinuation(row)

    @Implementation
    fun setBoldHighbright(enabled: Boolean): Int = wasm.setBoldHighbright(enabled)

    @Implementation
    fun close() = wasm.close()

    companion object {
        @Implementation
        @JvmStatic
        @Suppress("ktlint:standard:function-naming")
        fun __staticInitializer__() {
            // Suppress System.loadLibrary("jni_cb_term") — WASM backend needs no native library.
        }

        @JvmStatic
        fun getShadow(native: TerminalNative): ShadowTerminalNative = Shadow.extract(native)
    }
}
