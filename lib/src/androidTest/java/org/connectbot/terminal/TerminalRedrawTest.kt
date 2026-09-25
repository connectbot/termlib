/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalRedrawTest {
    @get:Rule
    val compose = createComposeRule()

    private fun terminal(text: String): TerminalEmulatorImpl = (TerminalEmulatorFactory.create(initialRows = 5, initialCols = 24) as TerminalEmulatorImpl).apply {
        writeInput(("\u001B[?25l" + text).toByteArray())
        processPendingUpdates()
    }

    @Test
    fun retainedRowsClearOldInkAndMatchFreshScreens() {
        val current = mutableStateOf(terminal(""))
        compose.setContent {
            key(current.value) {
                Terminal(current.value, forcedSize = 5 to 24, keyboardEnabled = false)
            }
        }
        val cases = listOf(
            "\u001B[3;44mffff WWWW\u001B[41m   " to "\u001B[2J\u001B[H",
            "👨‍👩‍👧‍👦⚠️表\r\nold" to "\u001B[H\u001B[0mplain\u001B[K",
            "⚠️👨‍👩‍👧‍👦\r\nline" to "\r\n1\r\n2\r\n3\r\n4\r\n5",
        )
        for ((before, update) in cases) {
            compose.runOnIdle { current.value = terminal(before) }
            compose.waitForTerminalIdle(current.value)
            compose.runOnIdle {
                current.value.writeInput(update.toByteArray())
                current.value.processPendingUpdates()
            }
            compose.waitForTerminalIdle(current.value)
            val changed = compose.onRoot().captureToImage().asAndroidBitmap()
            compose.runOnIdle { current.value = terminal(before + update) }
            compose.waitForTerminalIdle(current.value)
            val fresh = compose.onRoot().captureToImage().asAndroidBitmap()
            assertTrue("Incremental output differs from a fresh surface", changed.sameAs(fresh))
        }
    }
}
