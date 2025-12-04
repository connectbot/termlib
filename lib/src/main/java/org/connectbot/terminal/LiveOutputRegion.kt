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

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay

/**
 * Invisible live region that announces new terminal output to screen readers.
 *
 * This component monitors the last few lines of terminal output and announces
 * changes using ARIA-style live regions. It includes debouncing to prevent
 * overwhelming the screen reader with rapid updates.
 *
 * @param screenState Terminal screen state
 * @param enabled Whether live announcements are active (typically only in Input Mode)
 * @param debounceMs Milliseconds to wait before announcing (batches rapid updates)
 * @param linesToMonitor Number of bottom lines to monitor for changes
 * @param modifier Modifier for the invisible container
 */
@Composable
internal fun LiveOutputRegion(
    screenState: TerminalScreenState,
    enabled: Boolean = true,
    debounceMs: Long = 300L,
    linesToMonitor: Int = 3,
    modifier: Modifier = Modifier
) {
    val snapshot = screenState.snapshot

    // Track the last announced content to detect changes
    var lastAnnouncedText by remember { mutableStateOf("") }
    var pendingText by remember { mutableStateOf("") }

    // Debounced announcement effect
    LaunchedEffect(snapshot.lines, enabled) {
        if (!enabled) return@LaunchedEffect

        // Get the last N lines
        val recentLines = snapshot.lines.takeLast(linesToMonitor)
        val currentText = recentLines.joinToString("\n") { it.text }

        if (currentText != lastAnnouncedText) {
            // New content detected - set pending
            pendingText = currentText

            // Wait for debounce period
            delay(debounceMs)

            // Announce the accumulated changes
            lastAnnouncedText = pendingText
        }
    }

    // Invisible box with live region semantics
    Box(
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            text = AnnotatedString(lastAnnouncedText)
        }
    )
}
