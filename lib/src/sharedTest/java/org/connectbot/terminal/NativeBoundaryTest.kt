/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Exercises text, cell, lifetime, and range handling on the host JVM and Android ART. */
class NativeBoundaryTest {
    @Test
    fun directBatchValidatesRangesAndSupportsUnalignedSlices() {
        TerminalNative(Callbacks()).use { terminal ->
            terminal.resize(3, 80)
            terminal.writeInput("\u001B[31mA\u001B[32mB\u001B[0m🎉".toByteArray())
            val storage = ByteBuffer.allocateDirect(CellData.BUFFER_BYTES + 1)
            storage.position(1)
            val buffer = storage.slice().order(java.nio.ByteOrder.nativeOrder())
            buffer.putInt(0, 0).putInt(4, 0).putInt(8, 80)
            buffer.putInt(16, 1).putInt(20, 0).putInt(24, 80)
            assertEquals(160, terminal.getCells(buffer, 2))
            val cells = PackedCells.Builder(80).apply { read(buffer, CellData.HEADER_BYTES, 80) }.build()
            assertTrue(cells.text().startsWith("AB🎉"))
            assertEquals(2, cells.width(2))
            assertEquals(0, cells.width(3))
            assertThrows(IllegalArgumentException::class.java) { terminal.getCells(buffer.asReadOnlyBuffer(), 1) }
            assertThrows(IllegalArgumentException::class.java) { terminal.getCells(ByteBuffer.allocateDirect(16), 1) }
            buffer.putInt(8, Int.MAX_VALUE)
            assertThrows(IllegalArgumentException::class.java) { terminal.getCells(buffer, 1) }
            buffer.putInt(8, 80).putInt(0, -1)
            assertThrows(IllegalArgumentException::class.java) { terminal.getCells(buffer, 1) }
        }
    }

    @Test
    fun scrollbackChunksKeepWideCellsAcrossBufferBoundary() {
        val callbacks = Callbacks()
        TerminalNative(callbacks).use { terminal ->
            terminal.resize(2, 3300)
            val text = "a".repeat(CellData.BUFFER_BYTES / CellData.BYTES - 1) + "🎉\u0301Z"
            terminal.writeInput((text + "\r\nsecond\r\n").toByteArray())
            val saved = callbacks.scrollback.first() as PackedCells
            assertTrue(saved.text().startsWith(text))
            terminal.resize(3, 3300)
            val transfer = ScreenTransfer()
            val builder = PackedCells.Builder(3300)
            transfer.fetch(terminal, intArrayOf(0, 3300, 0, 0, 0, 0)) { _, _, count, buffer, offset, _ ->
                builder.read(buffer, offset, count)
            }
            assertTrue(builder.build().text().startsWith(text))
        }
    }

    @Test
    fun packedRowsPreserveEveryAttributeWithoutRetainingScratch() {
        val buffer = CellData.buffer()
        buffer.putInt(0, 0x1F389).putInt(4, 0x301).putInt(24, 2)
        buffer.putInt(28, 0x123456).putInt(32, 0x654321).putInt(36, 0x3FFFF)
        val cells = CellData.read(buffer, 2)
        buffer.putInt(0, 'X'.code)
        assertEquals("🎉\u0301", cells.text())
        assertEquals("🎉\u0301", cells.text(1, 2))
        assertEquals("", cells.text(1, 1))
        cells.writeRecord(buffer, 0, 0)
        assertEquals(0x1F389, buffer.getInt(0))
        assertEquals(0x3FFFF, buffer.getInt(36))
    }

    private open class Callbacks : TerminalCallbacks {
        val decoder = TerminalTextDecoder()
        val texts = mutableListOf<Pair<Int, String>>()
        val scrollback = mutableListOf<List<TerminalLine.Cell>>()
        private val buffer = CellData.buffer()
        private var builder: PackedCells.Builder? = null
        override fun cellBuffer(): ByteBuffer = buffer
        override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int) = 0
        override fun moverect(dest: TermRect, src: TermRect) = 0
        override fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean) = 0
        override fun setTermProp(prop: Int, value: TerminalProperty) = 0
        override fun bell() = 0
        override fun clearScrollback() = 0
        override fun onKeyboardInput(data: ByteArray) = 0
        override fun pushScrollbackLine(cols: Int, start: Int, count: Int, cells: ByteBuffer, softWrapped: Boolean): Int {
            if (start == 0) builder = PackedCells.Builder(cols)
            builder!!.read(cells, 0, count)
            if (start + count == cols) {
                scrollback.add(builder!!.build())
                builder = null
            }
            return 1
        }
        override fun popScrollbackLine(cols: Int, start: Int, count: Int, cells: ByteBuffer): Int {
            if (scrollback.isEmpty()) return 0
            CellData.writeRange(cells, start, count, PackedCells.from(scrollback.last()), Color.White, Color.Black)
            if (start + count == cols) scrollback.removeAt(scrollback.lastIndex)
            return 1
        }
        override fun onTextFragment(kind: Int, command: Int, data: ByteArray, initial: Boolean, final: Boolean, cursorRow: Int, cursorCol: Int): Int {
            decoder.accept(kind, command, data, initial, final)?.let { texts.add(command to it) }
            return 1
        }
    }

    @Test
    fun decodingWaitsForCompleteMessagesAndPreservesNul() {
        val bytes = "a\u0000é日🎉z".toByteArray()
        for (split in 0..bytes.size) {
            val decoder = TerminalTextDecoder()
            assertNull(decoder.accept(0, 1337, bytes.copyOfRange(0, split), true, false))
            assertEquals("a\u0000é日🎉z", decoder.accept(0, 1337, bytes.copyOfRange(split, bytes.size), false, true))
        }
        val decoder = TerminalTextDecoder()
        assertEquals("ab\uFFFDcd", decoder.accept(0, 1337, byteArrayOf(97, 98, -128, 99, 100), true, true))
        assertEquals("", decoder.accept(0, 1337, byteArrayOf(), true, true))
    }

    @Test
    fun limitsDiscardWholeMessagesAndRecover() {
        for ((kind, limit) in listOf(0 to TerminalTextDecoder.TEXT_LIMIT, 2 to TerminalTextDecoder.CLIPBOARD_LIMIT)) {
            val decoder = TerminalTextDecoder()
            val full = ByteArray(limit) { 97 }
            assertEquals(limit, decoder.accept(kind, 52, full, true, true)!!.length)
            assertNull(decoder.accept(kind, 52, full, true, false))
            assertNull(decoder.accept(kind, 52, byteArrayOf(98), false, true))
            assertEquals("ok", decoder.accept(kind, 52, "ok".toByteArray(), true, true))
            decoder.accept(kind, 52, "old".toByteArray(), true, false)
            assertEquals("new", decoder.accept(kind, 52, "new".toByteArray(), true, true))
        }
    }

    @Test
    fun nativeTitleAndOscFragmentsPreserveUnicode() {
        val callbacks = Callbacks()
        TerminalNative(callbacks).use { terminal ->
            for (command in listOf(0, 2, 1337)) {
                val bytes = "\u001B]$command;é日🎉\u0007".toByteArray()
                for (split in 1 until bytes.size) {
                    callbacks.texts.clear()
                    terminal.writeInput(bytes, 0, split)
                    assertTrue(callbacks.texts.isEmpty())
                    terminal.writeInput(bytes, split, bytes.size - split)
                    assertTrue(callbacks.texts.any { it.second == "é日🎉" })
                }
            }
            callbacks.texts.clear()
            terminal.writeInput("\u001B]2;ab".toByteArray() + byteArrayOf(-128) + "cd\u0007".toByteArray())
            assertEquals("ab\uFFFDcd", callbacks.texts.last().second)
        }
    }

    @Test
    fun longRowsKeepCompleteCellsAndDoNotReuseStaleCharacters() {
        TerminalNative(Callbacks()).use { terminal ->
            terminal.resize(3, 520)
            val text = "a".repeat(255) + "🎉\u0301" + "Z"
            terminal.writeInput(text.toByteArray())
            val cells = readRow(terminal, 520)
            assertEquals("a".repeat(255), cells.text(0, 255))
            assertEquals("🎉\u0301", cells.text(255, 257))
            assertEquals(2, cells.width(255))
            assertEquals('Z', cells.charAt(257))
            terminal.writeInput("\u001B[1;256H\u001B[31mQ\u001B[0m".toByteArray())
            assertEquals("Q", readRow(terminal, 520).text(255, 256))
        }
    }

    @Test
    fun scrollbackRoundTripPreservesCodePointsWidthsAndStyles() {
        val callbacks = Callbacks()
        TerminalNative(callbacks).use { terminal ->
            terminal.resize(2, 12)
            terminal.writeInput("\u001B[1;3;4;5m🎉\u0301Z\u001B[0m\r\nsecond\r\n".toByteArray())
            val saved = callbacks.scrollback.first()
            assertEquals(2, saved.first().width)
            assertTrue(saved.first().bold && saved.first().italic && saved.first().blink)
            terminal.resize(3, 12)
            assertEquals(saved, readRow(terminal, 12))
        }
    }

    private fun readRow(terminal: TerminalNative, columns: Int): PackedCells {
        val builder = PackedCells.Builder(columns)
        ScreenTransfer().fetch(terminal, intArrayOf(0, columns)) { _, _, count, buffer, offset, _ ->
            builder.read(buffer, offset, count)
        }
        return builder.build()
    }

    @Test
    fun invalidRangesAreRejectedBeforeNativeAccess() {
        val terminal = TerminalNative(Callbacks())
        terminal.use {
            for ((offset, length) in listOf(-1 to 1, 0 to -1, 1 to Int.MAX_VALUE, Int.MAX_VALUE to 1)) {
                assertThrows(IllegalArgumentException::class.java) { terminal.writeInput(ByteArray(4), offset, length) }
            }
            assertThrows(IllegalArgumentException::class.java) { terminal.writeInput(ByteBuffer.allocate(4), 4) }
            assertThrows(IllegalArgumentException::class.java) { terminal.writeInput(ByteBuffer.allocateDirect(4), 5) }
            assertThrows(IllegalArgumentException::class.java) { terminal.writeInput(ByteBuffer.allocateDirect(4), -1) }
            assertThrows(IllegalArgumentException::class.java) { terminal.resize(0, 4) }
            assertThrows(IllegalArgumentException::class.java) { terminal.setPaletteColors(intArrayOf(), -1) }
            assertThrows(IllegalArgumentException::class.java) { terminal.dispatchCharacter(0, 0xD800) }
            val buffer = ByteBuffer.allocateDirect(4).put("abcd".toByteArray())
            assertEquals(2, terminal.writeInput(buffer, 2))
            assertEquals(4, buffer.position())
        }
        terminal.close()
        assertThrows(IllegalStateException::class.java) { terminal.writeInput(byteArrayOf(1)) }
    }

    @Test
    fun callbackReentryFailsWithoutDeadlockOrSwallowedException() {
        var terminal: TerminalNative? = null
        val callbacks = object : Callbacks() {
            override fun bell(): Int {
                terminal!!.writeInput(byteArrayOf(65))
                return 0
            }
        }
        terminal = TerminalNative(callbacks)
        terminal.use {
            assertThrows(IllegalStateException::class.java) { it.writeInput(byteArrayOf(7)) }
            assertEquals(1, it.writeInput(byteArrayOf(65)))
        }
    }

    @Test
    fun closeWaitsForActiveNativeCall() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val callbacks = object : Callbacks() {
            override fun bell(): Int {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return 0
            }
        }
        val terminal = TerminalNative(callbacks)
        val writer = thread {
            try {
                terminal.writeInput(byteArrayOf(7))
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closer = thread {
            try {
                terminal.close()
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                closed.countDown()
            }
        }
        try {
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        writer.join(5000)
        closer.join(5000)
        assertEquals(0L, closed.count)
        assertNull(failure.get())
    }
}
