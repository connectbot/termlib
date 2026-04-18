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
 * Public API interface for controlling and monitoring scroll state in the terminal.
 */
internal interface ScrollController {
    /**
     * The current scrollback position. 0 means we are at the bottom (showing the active screen).
     * Positive values indicate how many lines we have scrolled back into the history.
     */
    val scrollbackPosition: Int

    /**
     * The maximum number of lines available in the scrollback buffer.
     */
    val maxScrollback: Int

    /**
     * Scroll to the bottom of the buffer (show active screen).
     */
    fun scrollToBottom()

    /**
     * Scroll to the top of the scrollback buffer.
     */
    fun scrollToTop()

    /**
     * Scroll by a specific number of lines.
     * Positive values scroll back into history; negative values scroll towards the bottom.
     */
    fun scrollBy(lines: Int)
}
