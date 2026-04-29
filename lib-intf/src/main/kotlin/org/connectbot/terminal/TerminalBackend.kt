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
 * Common interface for terminal emulator backends.
 */
interface TerminalBackend : AutoCloseable {
    fun writeInput(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): Int

    fun resize(
        rows: Int,
        cols: Int,
    ): Int

    fun dispatchKey(
        modifiers: Int,
        key: Int,
    ): Boolean

    fun dispatchCharacter(
        modifiers: Int,
        codepoint: Int,
    ): Boolean

    fun getCellRun(
        row: Int,
        col: Int,
        run: CellRun,
    ): Int

    /**
     * Iterates all cell runs in [row], invoking [block] for each run.
     * Default implementation loops via [getCellRun]; backends may override
     * with a single cross-boundary call that fetches the entire row at once.
     */
    fun scanRow(
        row: Int,
        cols: Int,
        run: CellRun,
        block: (CellRun) -> Unit,
    ) {
        var col = 0
        while (col < cols) {
            val n = getCellRun(row, col, run)
            if (n <= 0) break
            block(run)
            col += n
        }
    }

    /**
     * Iterates all cell runs across every row, invoking [block] for each run.
     * [rowStart] is called before each row with the row index.
     * Default implementation loops via [scanRow]; backends may override with
     * a single cross-boundary call that fetches the entire screen at once.
     */
    fun scanAllRows(
        rows: Int,
        cols: Int,
        run: CellRun,
        rowStart: (row: Int) -> Unit = {},
        block: (CellRun) -> Unit,
    ) {
        for (row in 0 until rows) {
            rowStart(row)
            scanRow(row, cols, run, block)
        }
    }

    fun setPaletteColors(
        colors: IntArray,
        count: Int = colors.size.coerceAtMost(16),
    ): Int

    fun setDefaultColors(
        foreground: Int,
        background: Int,
    ): Int

    fun getLineContinuation(row: Int): Boolean

    fun setBoldHighbright(enabled: Boolean): Int
}
