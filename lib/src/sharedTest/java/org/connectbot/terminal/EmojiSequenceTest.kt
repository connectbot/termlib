/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class EmojiSequenceTest {
    private class Callbacks : TerminalCallbacks {
        var cursor = CursorPosition(0, 0)
        private val buffer = CellData.buffer()
        override fun cellBuffer(): ByteBuffer = buffer
        override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int) = 0
        override fun moverect(dest: TermRect, src: TermRect) = 0
        override fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean): Int {
            cursor = pos
            return 0
        }
        override fun setTermProp(prop: Int, value: TerminalProperty) = 0
        override fun bell() = 0
        override fun clearScrollback() = 0
        override fun onKeyboardInput(data: ByteArray) = 0
        override fun pushScrollbackLine(cols: Int, start: Int, count: Int, cells: ByteBuffer, softWrapped: Boolean) = 0
        override fun popScrollbackLine(cols: Int, start: Int, count: Int, cells: ByteBuffer) = 0
        override fun onTextFragment(kind: Int, command: Int, data: ByteArray, initial: Boolean, final: Boolean, cursorRow: Int, cursorCol: Int) = 0
    }

    private fun screen(text: String, columns: Int = 24, split: Int = -1): Pair<List<PackedCells>, CursorPosition> {
        val callbacks = Callbacks()
        return TerminalNative(callbacks).use { native ->
            native.resize(3, columns)
            val bytes = text.toByteArray()
            if (split < 0) {
                native.writeInput(bytes)
            } else {
                if (split > 0) native.writeInput(bytes.copyOfRange(0, split))
                if (split < bytes.size) native.writeInput(bytes.copyOfRange(split, bytes.size))
            }
            val rows = List(3) { PackedCells.Builder(columns) }
            ScreenTransfer().fetch(native, IntArray(6) { if (it % 2 == 0) 0 else columns }) { row, _, count, buffer, offset, _ ->
                rows[row].read(buffer, offset, count)
            }
            rows.map { it.build() } to callbacks.cursor
        }
    }

    @Test
    fun presentationAndSequencesHaveTerminalWidths() {
        val cases = mapOf(
            "⚠" to 1, "⚠︎" to 1, "⚠️" to 2, "🎉" to 2,
            "🏳️‍🌈" to 2, "👨‍👩‍👧‍👦" to 2, "👩🏽‍❤️‍💋‍👨🏻" to 2,
            "🇺🇸" to 2, "1️⃣" to 2, "👍🏽" to 2,
            "🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F" to 2,
            "é" to 1, "表" to 2,
        )
        for ((text, width) in cases) {
            val input = "$text TEST"
            val expected = screen(input)
            assertEquals(text, expected.first[0].text(0, width))
            assertEquals(width, expected.first[0].width(0))
            assertEquals('T', expected.first[0].charAt(width + 1))
            assertEquals(width + 5, expected.second.col)
            for (split in 0..input.toByteArray().size) {
                assertEquals("split $split of $text", expected, screen(input, split = split))
            }
        }
    }

    @Test
    fun lateSelectorsAndJoinersAtMarginsDoNotDependOnWrites() {
        for (prefix in listOf("", "\u001B[?7l", "\u001B[3;1H")) {
            for (text in listOf("abc⚠️X", "abc⌚︎X", "ab👨‍👩‍👧‍👦X", "abc⚠️\bZ")) {
                val input = prefix + text
                val expected = screen(input, columns = 4)
                for (split in 0..input.toByteArray().size) {
                    assertEquals("split $split of $input", expected, screen(input, columns = 4, split = split))
                }
                assertTrue(expected.second.col in 0..3)
            }
        }
        val wrapped = screen("abc⚠️X", columns = 4)
        assertEquals("abc ", wrapped.first[0].text())
        assertEquals("⚠️X ", wrapped.first[1].text())
    }

    @Test
    fun singleColumnTerminalStillWraps() {
        val result = screen("abc", columns = 1)
        assertEquals(listOf("a", "b", "c"), result.first.map { it.text() })
    }

    @Test
    fun overwritingEitherHalfOfWideCellRemovesTheOldGlyph() {
        val leading = screen("🎉\rX").first[0]
        assertEquals(1, leading.width(0))
        assertTrue(leading.text().startsWith("X "))
        val trailing = screen("🎉\u001B[1;2HX").first[0]
        assertTrue(trailing.text().startsWith(" X"))
    }

    @Test
    fun erasingEitherHalfRemovesTheWholeWideGlyph() {
        for (column in 1..2) {
            val row = screen("🎉X\u001B[1;${column}H\u001B[X").first[0]
            assertTrue(row.text().startsWith("  X"))
            assertEquals(1, row.width(0))
            assertEquals(1, row.width(1))
        }
    }

    @Test
    fun longCombiningInputIsPreservedAndCommandsBreakAttachment() {
        val text = "a" + "\u0301".repeat(30)
        assertTrue(screen(text).first[0].text().startsWith(text))
        val row = screen("👨\u001B[31m‍👩").first[0]
        assertEquals("👨", row.text(0, 2))
    }
}
