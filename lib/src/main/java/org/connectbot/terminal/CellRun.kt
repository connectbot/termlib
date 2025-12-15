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
 * A run of consecutive cells with identical formatting attributes.
 * Used to efficiently batch cell data transfer across the JNI boundary.
 *
 * This class is reusable - call reset() before each getCellRun() call.
 */
internal class CellRun {
    // Foreground color (RGB)
    var fgRed: Int = 0
    var fgGreen: Int = 0
    var fgBlue: Int = 0

    // Background color (RGB)
    var bgRed: Int = 0
    var bgGreen: Int = 0
    var bgBlue: Int = 0

    // Text attributes
    var bold: Boolean = false
    var underline: Int = 0  // 0=none, 1=single, 2=double, 3=curly
    var italic: Boolean = false
    var blink: Boolean = false
    var reverse: Boolean = false
    var strike: Boolean = false
    var font: Int = 0

    // Line attributes
    var dwl: Boolean = false  // Double-width line
    var dhl: Int = 0  // Double-height line (0=none, 1=top, 2=bottom)

    // Characters in this run (UTF-16, may include surrogate pairs)
    var chars: CharArray = CharArray(256)

    // Number of cells this run covers (not necessarily chars.size due to wide chars)
    var runLength: Int = 0

    /**
     * Reset this cell run for reuse.
     */
    fun reset() {
        runLength = 0
        bold = false
        underline = 0
        italic = false
        blink = false
        reverse = false
        strike = false
        font = 0
        dwl = false
        dhl = 0
    }

    /**
     * Get the characters as a String.
     */
    fun getCharsAsString(): String {
        return String(chars, 0, chars.size)
    }
}
