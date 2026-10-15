/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOutputJournalTest {
    @Test fun readsTopRowsAndDoesNotRepeatUnchangedText() {
        val initial = accessibilitySnapshot(0, 8).copy(lines = List(8) { accessibilityLine("") })
        val journal = TerminalOutputJournal(initial)
        val updated = initial.copy(lines = initial.lines.toMutableList().apply { set(0, accessibilityLine("top output")) })
        journal.update(updated)
        assertEquals(listOf("top output"), journal.drain().output)
        journal.update(updated)
        assertTrue(journal.drain().output.isEmpty())
    }

    @Test fun capturesRowsBeforeHistoryEvictionAndBoundsPendingSpeech() {
        val journal = TerminalOutputJournal(accessibilitySnapshot(0))
        repeat(2000) { journal.push(accessibilityLine("Output $it")) }
        val batch = journal.drain()
        assertTrue(batch.overflow)
        assertTrue(batch.output.sumOf { it.length } <= 8192)
        assertEquals("Output 1999", batch.output.last())
        assertFalse(journal.drain().overflow)
    }

    @Test fun scrollingDoesNotReannounceAlreadyReadRows() {
        val snapshot = accessibilitySnapshot(0)
        val journal = TerminalOutputJournal(snapshot)
        journal.push(snapshot.lines.first())
        journal.move(TermRect(0, 2, 0, 40), TermRect(1, 3, 0, 40), 40)
        journal.update(snapshot.copy(lines = snapshot.lines.drop(1) + accessibilityLine("new output")))
        assertEquals(listOf("new output"), journal.drain().output)
    }

    @Test fun capturesAppendAndCursorOnlyChangesSeparately() {
        val snapshot = accessibilitySnapshot(0)
        val journal = TerminalOutputJournal(snapshot)
        journal.update(snapshot.copy(cursorCol = 2))
        assertEquals("r", journal.drain().cursor)
        journal.update(snapshot.copy(lines = snapshot.lines.toMutableList().apply { set(0, accessibilityLine("Screen 0 appended")) }, cursorCol = 3))
        val batch = journal.drain()
        assertEquals(listOf(" appended"), batch.output)
        assertNull(batch.cursor)
    }

    @Test fun progressRewritesCoalesceWithinAWindow() {
        val initial = accessibilitySnapshot(0, 1).copy(lines = listOf(accessibilityLine("")))
        val journal = TerminalOutputJournal(initial)
        journal.update(initial.copy(lines = listOf(accessibilityLine("10%"))))
        journal.update(initial.copy(lines = listOf(accessibilityLine("20%"))))
        journal.update(initial.copy(lines = listOf(accessibilityLine("30%"))))
        assertEquals(listOf("30%"), journal.drain().output)
    }

    @Test fun cursorMovementReturningToItsOriginDoesNotAnnounce() {
        val initial = accessibilitySnapshot(0)
        val journal = TerminalOutputJournal(initial)
        journal.update(initial.copy(cursorCol = 2))
        journal.update(initial)
        assertNull(journal.drain().cursor)
    }

    @Test fun mergedScrollWithoutMoveDoesNotReuseEvictedLineIdentity() {
        val initial = accessibilitySnapshot(0, 2).copy(lines = List(2) { accessibilityLine("") })
        val journal = TerminalOutputJournal(initial)
        repeat(5) { journal.push(accessibilityLine("row $it")) }
        journal.update(initial.copy(lines = listOf(accessibilityLine("row 5"), accessibilityLine("row 6"))))
        assertEquals((0..6).map { "row $it" }, journal.drain().output)
    }

    @Test fun overflowDoesNotSplitSurrogatePairs() {
        val journal = TerminalOutputJournal(accessibilitySnapshot(0))
        journal.push(accessibilityLine("\uD83D\uDE00".repeat(4096) + "z"))
        val batch = journal.drain()
        assertTrue(batch.overflow)
        assertEquals("\uD83D\uDE00".repeat(4095) + "z", batch.output.single())
    }
}
