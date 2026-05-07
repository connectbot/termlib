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

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Represents a single line in the terminal screen.
 *
 * Each line is immutable and tracks its last modification time for efficient redraws.
 * This is part of the architecture where each terminal line is a separate Kotlin class.
 */
@Immutable
internal data class TerminalLine(
    val row: Int,
    val cells: List<Cell>,
    val lastModified: Long = System.nanoTime(),
    val semanticSegments: List<SemanticSegment> = emptyList(),
    /**
     * True if this line ended with a soft wrap (visual line break due to terminal width),
     * false if it ended with a hard break (actual newline character).
     *
     * This is used when copying text to avoid inserting spurious newlines in wrapped
     * long commands. For example, a command like "echo foo bar baz..." that wraps to
     * multiple lines should be copied as a single line without embedded newlines.
     */
    val softWrapped: Boolean = false,
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
     * Get the semantic type at a specific column.
     * Returns DEFAULT if no segment covers that column.
     */
    fun getSemanticTypeAt(col: Int): SemanticType = semanticSegments.firstOrNull { it.contains(col) }?.semanticType
        ?: SemanticType.DEFAULT

    /**
     * Get all segments of a specific semantic type.
     */
    fun getSegmentsOfType(type: SemanticType): List<SemanticSegment> = semanticSegments.filter { it.semanticType == type }

    /**
     * Check if this line contains any prompt segments.
     */
    fun hasPrompt(): Boolean = semanticSegments.any { it.semanticType == SemanticType.PROMPT }

    /**
     * Auto-detected URLs in the line text (not from OSC 8 semanticSegments).
     * Lazily computed and cached per TerminalLine instance.
     * Each triple is (startCol, endColExclusive, url).
     */
    internal val autoDetectedUrls: List<Triple<Int, Int, String>> by lazy {
        if (cells.isEmpty()) return@lazy emptyList()
        URL_REGEX.findAll(text).map { match ->
            Triple(match.range.first, match.range.last + 1, match.value)
        }.toList()
    }

    /**
     * Get the hyperlink URL at a specific column, if any.
     * Checks OSC 8 semantic segments first. If no OSC 8 segment covers the column and
     * [autoDetectUrls] is true, falls back to plain-text URL auto-detection.
     * Returns null if no hyperlink covers that column.
     *
     * @param col The zero-based column index to query.
     * @param autoDetectUrls Whether to fall back to auto-detected plain-text URLs when no
     *                       OSC 8 segment is present. Defaults to false.
     */
    fun getHyperlinkUrlAt(col: Int, autoDetectUrls: Boolean = false): String? {
        // OSC 8 segments take priority
        val osc8 = semanticSegments.firstOrNull {
            it.semanticType == SemanticType.HYPERLINK && it.contains(col)
        }?.metadata
        if (osc8 != null) return osc8
        // Fallback: auto-detected plain-text URLs (only when enabled)
        if (!autoDetectUrls) return null
        return autoDetectedUrls.firstOrNull { col >= it.first && col < it.second }?.third
    }

    /**
     * Get the prompt ID for this line (from the first segment that has one).
     */
    val promptId: Int
        get() = semanticSegments.firstOrNull { it.promptId >= 0 }?.promptId ?: -1

    /**
     * A single cell in the terminal line with character and formatting.
     */
    @Immutable
    data class Cell(
        val char: Char,
        val combiningChars: List<Char> = emptyList(),
        val fgColor: Color,
        val bgColor: Color,
        val bold: Boolean = false,
        val italic: Boolean = false,
        // 0=none, 1=single, 2=double, 3=curly
        val underline: Int = 0,
        val blink: Boolean = false,
        val reverse: Boolean = false,
        val strike: Boolean = false,
        // 1 for normal, 2 for fullwidth (CJK)
        val width: Int = 1,
    )

    companion object {
        /**
         * Shared empty list to avoid allocation for 99% of cells without combining chars.
         * This single shared instance prevents ~1,920 empty list allocations per frame.
         */
        val EMPTY_COMBINING_CHARS = emptyList<Char>()

        /**
         * URL regex for auto-detection in terminal text. Matches:
         * - http://, https://, and ftp:// URLs (with any host including IP:port)
         * - Bare domain names with common TLDs, optional :port and /path
         * - IP:port patterns (e.g. 192.168.1.1:8080, 10.0.0.1:3000)
         *
         * Pure Kotlin regex (no android.util.Patterns dependency) for JUnit testability.
         */
        internal val URL_REGEX = Regex(
            // Scheme URLs: http(s)://... or ftp://...
            """(?:https?://|ftp://)[^\s<>"{}|\\^`\[\]]+""" +
                // Bare domains with common TLDs, optional :port and /path
                """|(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+""" +
                """(?:com|org|net|edu|gov|io|dev|app|co|uk|de|fr|jp|ru|br|in|au|us|info|biz|me|tv|cc)""" +
                """(?::\d{1,5})?""" +
                """(?:/[^\s<>"{}|\\^`\[\]]*)?""" +
                // IP:port (e.g. 192.168.1.1:8080) — require port to avoid matching version numbers
                """|(?:\d{1,3}\.){3}\d{1,3}:\d{1,5}(?:/[^\s<>"{}|\\^`\[\]]*)?""",
        )

        /**
         * Create an empty line with default cells.
         */
        fun empty(row: Int, cols: Int, defaultFg: Color = Color.White, defaultBg: Color = Color.Black): TerminalLine = TerminalLine(
            row = row,
            cells = List(cols) {
                Cell(
                    char = '\u0000',
                    fgColor = defaultFg,
                    bgColor = defaultBg,
                )
            },
            softWrapped = false,
        )
    }
}
