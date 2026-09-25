/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import androidx.compose.ui.test.IdlingResource
import androidx.compose.ui.test.junit4.ComposeTestRule
import java.util.concurrent.atomic.AtomicBoolean

/** Waits for commands submitted before this resource was created. */
internal class TerminalEmulatorIdlingResource(terminal: TerminalEmulator) : IdlingResource {
    private val idle = AtomicBoolean(false)

    init {
        require(terminal is TerminalEmulatorImpl) { "Unsupported terminal emulator implementation" }
        terminal.commands.execute { idle.set(true) }
    }

    override val isIdleNow: Boolean get() = idle.get()

    override fun getDiagnosticMessageIfBusy(): String = "Waiting for terminal command queue"
}

/** Waits for Compose to submit commands, then for those commands and their UI effects. */
internal fun ComposeTestRule.waitForTerminalIdle(terminal: TerminalEmulator) {
    waitForIdle()
    val resource = TerminalEmulatorIdlingResource(terminal)
    registerIdlingResource(resource)
    try {
        waitForIdle()
    } finally {
        unregisterIdlingResource(resource)
    }
}
