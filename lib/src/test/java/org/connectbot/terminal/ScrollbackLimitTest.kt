/*
 * ConnectBot Terminal
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScrollbackLimitTest {
    @Test
    fun defaultRetainsOneThousandRows() {
        TerminalEmulatorFactory.create(initialRows = 2, initialCols = 20).use { emulator ->
            assertScrollback(emulator, 1000)
        }
    }

    @Test
    fun configuredLimitRetainsTenThousandRows() {
        TerminalEmulatorFactory.create(initialRows = 2, initialCols = 20, maxScrollbackLines = 10_000).use { emulator ->
            assertScrollback(emulator, 10_000)
        }
    }

    @Test
    fun zeroDisablesScrollback() {
        TerminalEmulatorFactory.create(initialRows = 2, initialCols = 20, maxScrollbackLines = 0).use { emulator ->
            assertScrollback(emulator, 0)
        }
    }

    @Test
    fun negativeLimitIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalEmulatorFactory.create(maxScrollbackLines = -1)
        }
    }

    private fun assertScrollback(emulator: TerminalEmulator, limit: Int) {
        val impl = emulator as TerminalEmulatorImpl
        val rows = limit + 4
        val text = buildString {
            repeat(rows) { append("row $it\r\n") }
        }
        emulator.writeInput(text.toByteArray())
        impl.processPendingUpdates()

        val snapshot = impl.snapshot.value
        assertEquals(limit, snapshot.scrollback.size)
        if (limit > 0) {
            assertEquals("row 3", snapshot.scrollback.first().text.trimEnd())
            assertEquals("row ${rows - 2}", snapshot.scrollback.last().text.trimEnd())
        }
        assertEquals("row ${rows - 1}", snapshot.lines.first().text.trimEnd())
    }
}
