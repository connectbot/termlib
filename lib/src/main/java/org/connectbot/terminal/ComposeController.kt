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
 * Public API interface for controlling compose mode in the terminal.
 *
 * Compose mode buffers typed text locally and displays it as an overlay
 * at the cursor position. Enter commits the text; Escape cancels.
 */
interface ComposeController {
    /**
     * Whether compose mode is currently active.
     */
    val isComposeModeActive: Boolean

    /**
     * Start compose mode. Clears any active selection first.
     */
    fun startComposeMode()

    /**
     * Stop compose mode, discarding any buffered text.
     */
    fun stopComposeMode()

    /**
     * Toggle compose mode on/off.
     */
    fun toggleComposeMode()

    /**
     * Get the currently composed (buffered) text.
     */
    fun getComposedText(): String
}
