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

import kotlin.io.encoding.Base64;

/**
 * Progress state for OSC 9;4 progress reporting.
 * Used by Windows Terminal and other terminals to display progress in tab headers.
 */
enum class ProgressState {
    HIDDEN,
    DEFAULT,
    ERROR,
    INDETERMINATE,
    WARNING
}

/**
 * Parser for OSC (Operating System Command) sequences.
 * Handles clipboard operations (OSC 52), shell integration (OSC 133),
 * iTerm2 extensions (OSC 1337), hyperlinks (OSC 8), and progress reporting (OSC 9;4).
 */
internal class OscParser {
    // Track current prompt ID for grouping command blocks
    private var currentPromptId = 0

    // Track the column where the current semantic segment starts
    private var currentSegmentStartCol = 0

    // Track active hyperlink state
    private var activeHyperlinkUrl: String? = null
    private var activeHyperlinkId: String? = null
    private var hyperlinkStartRow: Int = 0
    private var hyperlinkStartCol: Int = 0

    sealed class Action {
        data class AddSegment(
            val row: Int,
            val startCol: Int,
            val endCol: Int,
            val type: SemanticType,
            val metadata: String? = null,
            val promptId: Int = -1
        ) : Action()

        data class SetCursorShape(val shape: CursorShape) : Action()

        /**
         * Action to copy data to the system clipboard via OSC 52.
         *
         * @param selection The clipboard selection target (e.g., "c" for clipboard, "p" for primary)
         * @param data The decoded data to copy to the clipboard
         */
        data class ClipboardCopy(
            val selection: String,
            val data: String
        ) : Action()

        /**
         * Action to report progress status via OSC 9;4.
         *
         * @param state The progress state (HIDDEN, DEFAULT, ERROR, INDETERMINATE, WARNING)
         * @param progress The progress percentage (0-100)
         */
        data class SetProgress(
            val state: ProgressState,
            val progress: Int
        ) : Action()
    }

    /**
     * Parse an OSC command and return a list of actions to apply to the terminal state.
     *
     * @param command The OSC command number (e.g., 133, 1337)
     * @param payload The payload string
     * @param cursorRow Current cursor row
     * @param cursorCol Current cursor column
     * @param cols Total number of columns in the terminal
     */
    fun parse(
        command: Int,
        payload: String,
        cursorRow: Int,
        cursorCol: Int,
        cols: Int
    ): List<Action> {
        return when (command) {
            8 -> handleOsc8(payload, cursorRow, cursorCol)
            9 -> handleOsc9(payload)
            52 -> handleOsc52(payload)
            133 -> handleOsc133(payload, cursorRow, cursorCol)
            1337 -> handleOsc1337(payload, cursorRow, cursorCol, cols)
            else -> emptyList()
        }
    }

    /**
     * Handle OSC 52 clipboard sequence.
     *
     * Format: OSC 52 ; Pc ; Pd ST
     * - Pc: clipboard selection target (c=clipboard, p=primary, s=select, etc.)
     * - Pd: base64-encoded data to copy, or "?" to query clipboard (not supported)
     *
     * For security, reading clipboard (Pd = "?") is not supported.
     *
     * Note: When coming from libvterm's selection callback, the data is already
     * base64-decoded. We handle both cases by trying base64 decode first, and
     * falling back to using the raw data if decoding fails.
     */
    private fun handleOsc52(payload: String): List<Action> {
        // Payload format: "selection;data" (data may be base64-encoded or pre-decoded)
        val separatorIndex = payload.indexOf(';')
        if (separatorIndex < 0) return emptyList()

        val selection = payload.substring(0, separatorIndex)
        val data = payload.substring(separatorIndex + 1)

        // Do not support clipboard read requests (security concern)
        if (data == "?") return emptyList()

        // Empty data is allowed (means empty clipboard copy)
        if (data.isEmpty()) {
            return listOf(Action.ClipboardCopy(selection, ""))
        }

        // Try to decode as base64 first. If it fails, the data is likely
        // already decoded (coming from libvterm's selection callback).
        val decodedData = try {
            Base64.Default.decode(data).toString(Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            // Not valid base64 - assume data is already decoded
            data
        }

        return listOf(Action.ClipboardCopy(selection, decodedData))
    }

    /**
     * Handle OSC 9;4 progress reporting sequence.
     *
     * Format: OSC 9 ; 4 ; <state> ; <progress> ST
     * - state: Progress state (0=hidden, 1=default, 2=error, 3=indeterminate, 4=warning)
     * - progress: Progress percentage (0-100), ignored for indeterminate state
     *
     * This is a Windows Terminal extension that allows terminal applications to
     * report progress status for display in the tab header or taskbar.
     */
    private fun handleOsc9(payload: String): List<Action> {
        // Payload format: "4;<state>;<progress>"
        if (!payload.startsWith("4;")) return emptyList()

        val parts = payload.substring(2).split(";")
        if (parts.isEmpty()) return emptyList()

        val stateValue = parts[0].toIntOrNull() ?: return emptyList()
        val state = when (stateValue) {
            0 -> ProgressState.HIDDEN
            1 -> ProgressState.DEFAULT
            2 -> ProgressState.ERROR
            3 -> ProgressState.INDETERMINATE
            4 -> ProgressState.WARNING
            else -> return emptyList()
        }

        val progress = if (parts.size > 1) {
            parts[1].toIntOrNull()?.coerceIn(0, 100) ?: 0
        } else {
            0
        }

        return listOf(Action.SetProgress(state, progress))
    }

    /**
     * Handle OSC 8 hyperlink sequence.
     *
     * Format: OSC 8 ; params ; URL ST
     * - params: Optional key=value pairs separated by colons (e.g., "id=link1")
     * - URL: The hyperlink URL (empty to end hyperlink)
     *
     * Example start: ESC ] 8 ; id=example ; https://example.com ESC \
     * Example end:   ESC ] 8 ; ; ESC \
     *
     * This enables clickable links in the terminal while maintaining the display
     * text separately from the URL for accessibility.
     */
    private fun handleOsc8(payload: String, cursorRow: Int, cursorCol: Int): List<Action> {
        val actions = mutableListOf<Action>()

        // Payload format: "params;URL"
        val separatorIndex = payload.indexOf(';')
        if (separatorIndex < 0) return emptyList()

        val params = payload.substring(0, separatorIndex)
        val url = payload.substring(separatorIndex + 1)

        if (url.isEmpty()) {
            // End hyperlink - create segment if we have an active hyperlink
            val activeUrl = activeHyperlinkUrl
            if (activeUrl != null) {
                // Handle single-line hyperlink
                if (hyperlinkStartRow == cursorRow && hyperlinkStartCol < cursorCol) {
                    actions.add(
                        Action.AddSegment(
                            row = cursorRow,
                            startCol = hyperlinkStartCol,
                            endCol = cursorCol,
                            type = SemanticType.HYPERLINK,
                            metadata = activeUrl,
                            promptId = currentPromptId
                        )
                    )
                }
                // Clear active hyperlink state
                activeHyperlinkUrl = null
                activeHyperlinkId = null
            }
        } else {
            // Start new hyperlink
            // If we have an active hyperlink, close it first (shouldn't happen normally)
            val activeUrl = activeHyperlinkUrl
            if (activeUrl != null && hyperlinkStartRow == cursorRow && hyperlinkStartCol < cursorCol) {
                actions.add(
                    Action.AddSegment(
                        row = cursorRow,
                        startCol = hyperlinkStartCol,
                        endCol = cursorCol,
                        type = SemanticType.HYPERLINK,
                        metadata = activeUrl,
                        promptId = currentPromptId
                    )
                )
            }

            // Parse optional id from params
            activeHyperlinkId = parseHyperlinkId(params)
            activeHyperlinkUrl = url
            hyperlinkStartRow = cursorRow
            hyperlinkStartCol = cursorCol
        }

        return actions
    }

    /**
     * Parse the hyperlink ID from OSC 8 params.
     * Params are colon-separated key=value pairs (e.g., "id=link1:foo=bar").
     */
    private fun parseHyperlinkId(params: String): String? {
        if (params.isEmpty()) return null
        for (param in params.split(':')) {
            val eqIndex = param.indexOf('=')
            if (eqIndex > 0) {
                val key = param.substring(0, eqIndex)
                val value = param.substring(eqIndex + 1)
                if (key == "id") return value
            }
        }
        return null
    }

    private fun handleOsc133(payload: String, cursorRow: Int, cursorCol: Int): List<Action> {
        val actions = mutableListOf<Action>()

        when {
            payload == "A" -> {
                // Prompt start
                currentPromptId++
                currentSegmentStartCol = cursorCol
            }
            payload == "B" -> {
                // Command input start (end of prompt)
                val promptEndCol = cursorCol
                if (currentSegmentStartCol < promptEndCol) {
                    actions.add(
                        Action.AddSegment(
                            row = cursorRow,
                            startCol = currentSegmentStartCol,
                            endCol = promptEndCol,
                            type = SemanticType.PROMPT,
                            promptId = currentPromptId
                        )
                    )
                }
                currentSegmentStartCol = cursorCol
            }
            payload == "C" -> {
                // Command output start (end of input)
                val inputEndCol = cursorCol
                if (currentSegmentStartCol < inputEndCol) {
                    actions.add(
                        Action.AddSegment(
                            row = cursorRow,
                            startCol = currentSegmentStartCol,
                            endCol = inputEndCol,
                            type = SemanticType.COMMAND_INPUT,
                            promptId = currentPromptId
                        )
                    )
                }
            }
            payload.startsWith("D") -> {
                // Command finished
                val exitCode = if (payload.length > 2) payload.substring(2) else "0"
                actions.add(
                    Action.AddSegment(
                        row = cursorRow,
                        startCol = cursorCol,
                        endCol = cursorCol, // Zero-width marker
                        type = SemanticType.COMMAND_FINISHED,
                        metadata = exitCode,
                        promptId = currentPromptId
                    )
                )
            }
        }
        return actions
    }

    private fun handleOsc1337(
        payload: String,
        cursorRow: Int,
        cursorCol: Int,
        cols: Int
    ): List<Action> {
        val actions = mutableListOf<Action>()

        when {
            payload.startsWith("AddAnnotation=") -> {
                val message = payload.substring("AddAnnotation=".length)
                actions.add(
                    Action.AddSegment(
                        row = cursorRow,
                        startCol = 0,
                        endCol = cols,
                        type = SemanticType.ANNOTATION,
                        metadata = message,
                        promptId = currentPromptId
                    )
                )
            }
            payload.startsWith("SetCursorShape=") -> {
                val shapeParam = payload.substring("SetCursorShape=".length)
                val shape = when (shapeParam) {
                    "0" -> CursorShape.BLOCK
                    "1" -> CursorShape.BAR_LEFT
                    "2" -> CursorShape.UNDERLINE
                    else -> CursorShape.BLOCK
                }
                actions.add(Action.SetCursorShape(shape))
            }
        }
        return actions
    }
}
