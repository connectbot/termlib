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
 * Semantic type for terminal content, derived from shell integration sequences.
 *
 * These types enable accessible navigation features like "jump to next prompt"
 * and provide context for screen readers.
 */
internal enum class SemanticType {
    /** Default content with no special semantic meaning */
    DEFAULT,

    /** Shell prompt (OSC 133;A) - e.g., "user@host$ " */
    PROMPT,

    /** Command input (OSC 133;B) - user-typed command text */
    COMMAND_INPUT,

    /** Command output (OSC 133;C) - output from command execution */
    COMMAND_OUTPUT,

    /** Command finished marker (OSC 133;D with exit code) */
    COMMAND_FINISHED,

    /** Annotated content (OSC 1337;AddAnnotation) */
    ANNOTATION
}

/**
 * Represents a contiguous segment of a terminal line with semantic meaning.
 *
 * A single terminal line can contain multiple segments. For example:
 * "user@host$ ls -l" contains two segments:
 * - [0, 11): PROMPT ("user@host$ ")
 * - [11, 16): COMMAND_INPUT ("ls -l")
 *
 * @param startCol Starting column (inclusive)
 * @param endCol Ending column (exclusive)
 * @param semanticType The semantic type of this segment
 * @param metadata Optional metadata (e.g., exit code for COMMAND_FINISHED)
 * @param promptId Groups segments that belong to the same command execution
 */
internal data class SemanticSegment(
    val startCol: Int,
    val endCol: Int,
    val semanticType: SemanticType,
    val metadata: String? = null,
    val promptId: Int = -1
) {
    /**
     * Check if a column is within this segment's range.
     */
    fun contains(col: Int): Boolean = col >= startCol && col < endCol

    /**
     * Get the length of this segment in columns.
     */
    val length: Int get() = endCol - startCol
}
