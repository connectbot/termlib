/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScrollbackHistoryTest {
    @Test
    fun retainedSnapshotsSurviveEvictionResizeAndClear() {
        val random = Random(42)
        for (limit in listOf(0, 1, 31, 32, 33, 1000)) {
            val history = ScrollbackHistory(limit)
            val expected = ArrayDeque<TerminalLine>()
            val retained = mutableListOf<Pair<List<TerminalLine>, List<TerminalLine>>>()
            val lines = List(129) { TerminalLine.empty(it, 0) }

            fun append(index: Int) {
                val line = lines[index % lines.size]
                history.append(line)
                expected.addLast(line)
                if (expected.size > limit) expected.removeFirst()
            }

            repeat(limit + 97) { append(it) }
            retained += history.snapshot() to expected.toList()
            repeat(97) { step ->
                append(step)
                if (step % 13 == 0) retained += history.snapshot() to expected.toList()
            }
            retained += history.snapshot() to expected.toList()
            repeat(minOf(35, limit)) {
                history.removeLast()
                expected.removeLast()
            }
            repeat(35) { append(it + 37) }
            assertEquals(expected.toList(), history.snapshot())
            repeat(600) { step ->
                when (random.nextInt(10)) {
                    0 -> {
                        history.clear()
                        expected.clear()
                    }
                    1, 2 -> if (expected.isNotEmpty()) {
                        history.removeLast()
                        expected.removeLast()
                    }
                    else -> append(step)
                }
                assertEquals(expected.lastOrNull(), history.lastOrNull())
                val snapshot = history.snapshot()
                assertEquals(expected.toList(), snapshot)
                assertSame(snapshot, history.snapshot())
                if (step % 31 == 0) retained += snapshot to expected.toList()
            }
            history.clear()
            for ((snapshot, contents) in retained) assertEquals(contents, snapshot)
        }
    }
}
