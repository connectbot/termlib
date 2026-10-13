/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.delay

/** Fixed windows keep continuous output from starving feedback. */
@Composable
internal fun AccessibleOutputFeedback(
    emulator: TerminalEmulatorImpl,
    enabled: Boolean,
    live: Boolean,
    options: TerminalAccessibilityOptions,
    onMessage: (String) -> Unit,
) {
    val resources by rememberUpdatedState(LocalResources.current)
    val currentLive by rememberUpdatedState(live)
    val currentOptions by rememberUpdatedState(options)
    val currentMessage by rememberUpdatedState(onMessage)
    DisposableEffect(emulator, enabled) {
        if (enabled) emulator.observeAccessibleOutput()
        onDispose { if (enabled) emulator.stopObservingAccessibleOutput() }
    }
    LaunchedEffect(emulator, enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            delay(300)
            val batch = emulator.drainAccessibleOutput() ?: continue
            if (!currentLive) continue
            val text = buildList {
                if (currentOptions.announceOutput) {
                    if (batch.overflow) add(resources.getString(R.string.terminal_a11y_overflow))
                    addAll(batch.output)
                    for (code in batch.completions) add(if (code == null) resources.getString(R.string.terminal_a11y_finished) else resources.getString(R.string.terminal_a11y_finished_code, code))
                }
                if (currentOptions.announceCursor && batch.output.isEmpty()) batch.cursor?.let { add(it.ifBlank { resources.getString(R.string.terminal_a11y_blank) }) }
            }.joinToString("\n")
            if (text.isNotEmpty()) currentMessage(text)
        }
    }
}
