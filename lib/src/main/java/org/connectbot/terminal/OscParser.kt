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
 * Parser for OSC (Operating System Command) sequences.
 * Handles shell integration (OSC 133) and iTerm2 extensions (OSC 1337).
 */
internal class OscParser {
    // Track current prompt ID for grouping command blocks
    private var currentPromptId = 0

    // Track the column where the current semantic segment starts
    private var currentSegmentStartCol = 0

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
            133 -> handleOsc133(payload, cursorRow, cursorCol)
            1337 -> handleOsc1337(payload, cursorRow, cursorCol, cols)
            else -> emptyList()
        }
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
