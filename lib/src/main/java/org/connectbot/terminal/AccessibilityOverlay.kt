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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.AnnotatedString

/**
 * Invisible overlay that provides semantic structure for screen readers.
 *
 * This "Shadow List" sits on top of the Canvas renderer and exposes terminal
 * lines as navigable text nodes. It enables TalkBack users to read history,
 * navigate by line/word/character, and access semantic actions.
 *
 * @param screenState Terminal screen state containing lines to expose
 * @param charHeight Height of one character in pixels (for sizing transparent items)
 * @param lazyListState Optional external state for scroll synchronization
 * @param modifier Modifier for the overlay container
 * @param onToggleReviewMode Callback to toggle Review Mode on/off
 * @param isReviewMode Whether Review Mode is currently active
 */
@Composable
internal fun AccessibilityOverlay(
    screenState: TerminalScreenState,
    charHeight: Float,
    lazyListState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
    onToggleReviewMode: () -> Unit = {},
    isReviewMode: Boolean = false
) {
    val density = LocalDensity.current
    val snapshot = screenState.snapshot
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    // Include both scrollback and visible lines in the list
    val allLines = snapshot.scrollback + snapshot.lines

    // Sync LazyColumn scroll with terminal scrollback position
    LaunchedEffect(screenState.scrollbackPosition) {
        if (!lazyListState.isScrollInProgress) {
            // Convert scrollbackPosition to LazyColumn index
            // scrollbackPosition = 0 means bottom (index = scrollback.size)
            // scrollbackPosition = scrollback.size means top (index = 0)
            val targetIndex = snapshot.scrollback.size - screenState.scrollbackPosition
            if (targetIndex >= 0 && targetIndex < allLines.size) {
                lazyListState.scrollToItem(targetIndex)
            }
        }
    }

    LazyColumn(
        state = lazyListState,
        modifier = modifier,
        userScrollEnabled = false
    ) {
        items(
            count = allLines.size,
            key = { index -> allLines[index].row to allLines[index].lastModified }
        ) { index ->
            val line = allLines[index]

            // Get the first visible item to highlight current focus in Review Mode
            val isCurrentlyFocused = isReviewMode &&
                lazyListState.firstVisibleItemIndex == index

            // Transparent box with semantic annotations and optional highlight
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { charHeight.toDp() })
                    .then(
                        if (isCurrentlyFocused) {
                            // Subtle highlight for focused line in Review Mode
                            Modifier.background(Color(0x1A4CAF50))
                        } else {
                            Modifier
                        }
                    )
                    .semantics {
                        // Use text property for granular navigation (words/characters)
                        // Only use contentDescription for semantic segments
                        if (line.semanticSegments.isEmpty() ||
                            line.semanticSegments.all { it.semanticType == SemanticType.DEFAULT }) {
                            // Plain text - use text property for granular navigation
                            text = AnnotatedString(line.text)
                        } else {
                            // Has semantic segments - build annotated string with semantic info
                            text = buildSemanticAnnotatedString(line)
                        }

                        // Custom accessibility actions
                        customActions = buildList {
                            // Copy Line
                            add(CustomAccessibilityAction("Copy Line") {
                                clipboardManager.setText(buildAnnotatedString { append(line.text) })
                                true
                            })

                            // Toggle Review Mode
                            // TODO(Terminal): Figure out how to make Review Mode work better
//                            add(CustomAccessibilityAction("Toggle Review Mode") {
//                                onToggleReviewMode()
//                                true
//                            })

                            // Jump to Next Prompt
                            add(CustomAccessibilityAction("Jump to Next Prompt") {
                                coroutineScope.launch {
                                    val nextPromptIndex = findNextLineWithSegmentType(
                                        allLines,
                                        currentIndex = index,
                                        semanticType = SemanticType.PROMPT,
                                        forward = true
                                    )
                                    if (nextPromptIndex != -1) {
                                        lazyListState.animateScrollToItem(nextPromptIndex)
                                    }
                                }
                                true
                            })

                            // Jump to Previous Prompt
                            add(CustomAccessibilityAction("Jump to Previous Prompt") {
                                coroutineScope.launch {
                                    val prevPromptIndex = findNextLineWithSegmentType(
                                        allLines,
                                        currentIndex = index,
                                        semanticType = SemanticType.PROMPT,
                                        forward = false
                                    )
                                    if (prevPromptIndex != -1) {
                                        lazyListState.animateScrollToItem(prevPromptIndex)
                                    }
                                }
                                true
                            })

                            // Jump to Next Command Output (if applicable)
                            if (line.getSegmentsOfType(SemanticType.COMMAND_OUTPUT).isNotEmpty()) {
                                add(CustomAccessibilityAction("Jump to Next Output") {
                                    coroutineScope.launch {
                                        val nextOutputIndex = findNextLineWithSegmentType(
                                            allLines,
                                            currentIndex = index,
                                            semanticType = SemanticType.COMMAND_OUTPUT,
                                            forward = true
                                        )
                                        if (nextOutputIndex != -1) {
                                            lazyListState.animateScrollToItem(nextOutputIndex)
                                        }
                                    }
                                    true
                                })
                            }
                        }
                    }
            )
        }
    }
}

/**
 * Build an annotated string with semantic information for screen readers.
 * This allows granular navigation while preserving semantic context.
 *
 * @param line The terminal line to annotate
 * @return AnnotatedString with semantic annotations
 */
private fun buildSemanticAnnotatedString(line: TerminalLine): AnnotatedString {
    return buildAnnotatedString {
        var lastEndCol = 0

        for (segment in line.semanticSegments.sortedBy { it.startCol }) {
            // Add any gap text before this segment
            if (segment.startCol > lastEndCol) {
                val gapText = line.text.substring(
                    lastEndCol,
                    segment.startCol.coerceAtMost(line.text.length)
                )
                append(gapText)
            }

            // Get text for this segment
            val segmentText = line.text.substring(
                segment.startCol.coerceAtMost(line.text.length),
                segment.endCol.coerceAtMost(line.text.length)
            )

            // Append text with semantic annotation
            when (segment.semanticType) {
                SemanticType.PROMPT -> {
                    append("Prompt: ")
                    append(segmentText)
                }
                SemanticType.COMMAND_INPUT -> {
                    append("Command: ")
                    append(segmentText)
                }
                SemanticType.COMMAND_OUTPUT -> {
                    append("Output: ")
                    append(segmentText)
                }
                SemanticType.COMMAND_FINISHED -> {
                    val exitCode = segment.metadata
                    if (exitCode != null) {
                        append("Command finished with exit code $exitCode")
                    } else {
                        append("Command finished")
                    }
                }
                SemanticType.ANNOTATION -> {
                    append("Annotation: ")
                    append(segmentText)
                }
                SemanticType.HYPERLINK -> {
                    append("Link: ")
                    append(segmentText)
                    val url = segment.metadata
                    if (url != null) {
                        append(", URL: ")
                        append(url)
                    }
                }
                SemanticType.DEFAULT -> append(segmentText)
            }

            if (segment != line.semanticSegments.last()) {
                append(" ")
            }

            lastEndCol = segment.endCol
        }

        // Add any remaining text after last segment
        if (lastEndCol < line.text.length) {
            val remainingText = line.text.substring(lastEndCol)
            append(remainingText)
        }
    }
}

/**
 * Build a semantic description for a line based on its segments.
 *
 * For lines with multiple semantic segments, this creates a description
 * that announces each segment's type and content.
 *
 * Examples:
 * - "user@host$ ls -l" -> "Prompt: user@host$ Command: ls -l"
 * - "output text" -> "output text" (if no segments or all DEFAULT)
 */
private fun buildSemanticDescription(line: TerminalLine): String {
    if (line.semanticSegments.isEmpty()) {
        return line.text
    }

    // Check if all segments are DEFAULT
    if (line.semanticSegments.all { it.semanticType == SemanticType.DEFAULT }) {
        return line.text
    }

    // Build description with segment annotations
    return buildString {
        var lastEndCol = 0

        for (segment in line.semanticSegments.sortedBy { it.startCol }) {
            // Add any gap text before this segment (shouldn't happen normally)
            if (segment.startCol > lastEndCol) {
                val gapText = line.text.substring(
                    lastEndCol,
                    segment.startCol.coerceAtMost(line.text.length)
                )
                if (gapText.isNotBlank()) {
                    append(gapText)
                }
            }

            // Get text for this segment
            val segmentText = line.text.substring(
                segment.startCol.coerceAtMost(line.text.length),
                segment.endCol.coerceAtMost(line.text.length)
            )

            // Add semantic prefix for non-DEFAULT segments
            when (segment.semanticType) {
                SemanticType.PROMPT -> append("Prompt: ")
                SemanticType.COMMAND_INPUT -> append("Command: ")
                SemanticType.COMMAND_OUTPUT -> append("Output: ")
                SemanticType.COMMAND_FINISHED -> {
                    val exitCode = segment.metadata
                    if (exitCode != null) {
                        append("Command finished with exit code $exitCode: ")
                    } else {
                        append("Command finished: ")
                    }
                }
                SemanticType.ANNOTATION -> append("Annotation: ")
                SemanticType.HYPERLINK -> append("Link: ")
                SemanticType.DEFAULT -> { /* No prefix */ }
            }

            append(segmentText)

            // For hyperlinks, also append the URL for accessibility
            if (segment.semanticType == SemanticType.HYPERLINK && segment.metadata != null) {
                append(", URL: ")
                append(segment.metadata)
            }

            // Add space between segments
            if (isNotEmpty() && !endsWith(' ')) {
                append(' ')
            }

            lastEndCol = segment.endCol
        }

        // Add any remaining text after last segment
        if (lastEndCol < line.text.length) {
            val remainingText = line.text.substring(lastEndCol)
            if (remainingText.isNotBlank()) {
                append(remainingText)
            }
        }
    }.trim()
}

/**
 * Find the next line containing a specific semantic type.
 *
 * @param lines List of all terminal lines (scrollback + visible)
 * @param currentIndex Current line index
 * @param semanticType Type to search for
 * @param forward True to search forward, false for backward
 * @return Index of next matching line, or -1 if not found
 */
private fun findNextLineWithSegmentType(
    lines: List<TerminalLine>,
    currentIndex: Int,
    semanticType: SemanticType,
    forward: Boolean
): Int {
    val range = if (forward) {
        (currentIndex + 1 until lines.size)
    } else {
        (currentIndex - 1 downTo 0)
    }

    for (i in range) {
        if (lines[i].getSegmentsOfType(semanticType).isNotEmpty()) {
            return i
        }
    }

    return -1
}
