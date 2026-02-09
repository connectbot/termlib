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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Internal state class for compose mode buffered text input.
 *
 * When compose mode is active, typed text is buffered locally and displayed
 * as an overlay at the cursor position. Enter commits the text; Escape cancels.
 * This gives safe, typo-friendly text entry.
 */
internal class ComposeMode {
    var isActive by mutableStateOf(false)
        private set

    var buffer by mutableStateOf("")
        private set

    fun activate() {
        buffer = ""
        isActive = true
    }

    fun deactivate() {
        buffer = ""
        isActive = false
    }

    fun appendChar(char: Char) {
        if (!isActive) return
        buffer += char
    }

    fun appendText(text: String) {
        if (!isActive) return
        buffer += text
    }

    fun deleteLastChar() {
        if (!isActive || buffer.isEmpty()) return
        buffer = buffer.dropLast(1)
    }

    /**
     * Commit the composed text. Returns the buffer text and deactivates compose mode.
     * Returns null if inactive or buffer is empty.
     */
    fun commit(): String? {
        if (!isActive || buffer.isEmpty()) {
            deactivate()
            return null
        }
        val text = buffer
        deactivate()
        return text
    }

    fun cancel() {
        deactivate()
    }
}
