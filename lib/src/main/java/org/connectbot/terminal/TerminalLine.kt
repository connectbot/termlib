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
 * Represents a single line in the terminal screen.
 *
 * Each line is immutable and tracks its last modification time for efficient redraws.
 * This is part of the architecture where each terminal line is a separate Kotlin class.
 */
data class TerminalLine(
    val row: Int,
    val cells: List<Cell>,
    val lastModified: Long = System.nanoTime()
) {
    /**
     * Get the text content of this line as a string.
     */
    val text: String by lazy {
        buildString {
            cells.forEach { cell ->
                append(cell.char)
                cell.combiningChars.forEach { append(it) }
            }
        }
    }

    /**
     * A single cell in the terminal line with character and formatting.
     */
    data class Cell(
        val char: Char,
        val combiningChars: List<Char> = emptyList(),
        val fgColor: Color,
        val bgColor: Color,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Int = 0,  // 0=none, 1=single, 2=double
        val blink: Boolean = false,
        val reverse: Boolean = false,
        val strike: Boolean = false,
        val width: Int = 1  // 1 for normal, 2 for fullwidth (CJK)
    )

    companion object {
        /**
         * Shared empty list to avoid allocation for 99% of cells without combining chars.
         * This single shared instance prevents ~1,920 empty list allocations per frame.
         */
        val EMPTY_COMBINING_CHARS = emptyList<Char>()

        /**
         * Create an empty line with default cells.
         */
        fun empty(row: Int, cols: Int, defaultFg: Color = Color.White, defaultBg: Color = Color.Black): TerminalLine {
            return TerminalLine(
                row = row,
                cells = List(cols) {
                    Cell(
                        char = ' ',
                        fgColor = defaultFg,
                        bgColor = defaultBg
                    )
                }
            )
        }
    }
}
