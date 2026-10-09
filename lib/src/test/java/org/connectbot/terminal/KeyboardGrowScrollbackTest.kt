package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-user keyboard hide/show on a full-screen TUI living in the primary
 * buffer (opencode over the UML console): showing the keyboard shrinks the
 * grid (rows overflow into scrollback), hiding it grows the grid again — and
 * the app's repaint then lands in the wrong place, leaving stale rows
 * stranded mid-screen ("keyboard hide/show has no effect").
 *
 * A cursor-tracking TUI repaints relative to the cursor position its own
 * model holds, which is where its last write left the cursor. After a grow
 * the physical (native) cursor must therefore still be at that cell for the
 * app's repaint to land where the app thinks it is writing.
 *
 * The Kotlin snapshot cursor is not the right probe for this: resize emits
 * no movecursor callback, so it never moves on a grow and cannot catch the
 * desync. The probe is DSR (CSI 6n) — the emulator answers from the native
 * state cursor over the keyboard path, the same position the app's escape
 * semantics resolve against.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardGrowScrollbackTest {

    /** DSR responses arrive here as if typed; native parse is synchronous. */
    private val keyboardOut = mutableListOf<ByteArray>()

    private fun createEmulator(initialRows: Int, initialCols: Int): TerminalEmulatorImpl = TerminalEmulatorFactory.create(
        initialRows = initialRows,
        initialCols = initialCols,
        onKeyboardInput = { data -> synchronized(keyboardOut) { keyboardOut.add(data) } },
    ) as TerminalEmulatorImpl

    private fun visibleText(e: TerminalEmulatorImpl): String {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        e.processPendingUpdates()
        return e.snapshot.value.lines.joinToString("\n") { it.text.trimEnd() }
    }

    /** CSI 6n → native answers "ESC[row;colR" (1-based) on the keyboard path. */
    private fun nativeCursor(e: TerminalEmulatorImpl): Pair<Int, Int> {
        synchronized(keyboardOut) { keyboardOut.clear() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // onKeyboardInput posts to the main handler — trigger on the main
        // thread and let the looper drain before reading the collector.
        instrumentation.runOnMainSync {
            e.writeInput("\u001b[6n".toByteArray())
        }
        instrumentation.waitForIdleSync()
        val text = synchronized(keyboardOut) { keyboardOut.joinToString("") { String(it) } }
        val m = Regex("\u001b\\[(\\d+);(\\d+)R").find(text)
            ?: throw AssertionError("no DSR response in keyboard output: ${text.escapeRepr()}")
        // CPR is 1-based; convert to 0-based to match the snapshot cursor.
        return (m.groupValues[1].toInt() - 1) to (m.groupValues[2].toInt() - 1)
    }

    private fun String.escapeRepr() = map { if (it.code < 32) "\\u%04x".format(it.code) else it }.joinToString("")

    @Test
    fun `rows-only grow with scrollback keeps the cursor at its pre-grow cell`() = runBlocking {
        val emulator = createEmulator(initialRows = 23, initialCols = 51)
        // 43 labelled lines: 20 flow into scrollback, 20..42 sit on the
        // 23-row screen, cursor ends just below the last line.
        for (i in 0..42) {
            emulator.writeInput("L%02d".format(i).toByteArray())
            if (i < 42) emulator.writeInput("\r\n".toByteArray())
        }
        delay(120)

        val beforeText = visibleText(emulator)
        org.junit.Assert.assertEquals(
            "precondition: screen should hold L20..L42",
            (20..42).joinToString("\n") { "L%02d".format(it) }.lines(),
            beforeText.lines(),
        )
        // Read the snapshot AFTER processing so scrollback/cursor are fresh.
        val before = emulator.snapshot.value
        val beforeScrollback = before.scrollback.size
        val beforeNative = nativeCursor(emulator)
        org.junit.Assert.assertEquals(
            "precondition: native cursor must sit at the pre-grow cell (22,3)",
            22 to 3,
            beforeNative,
        )

        // Keyboard hides: the grid grows back to 40 rows with scrollback
        // available to backfill from.
        emulator.resize(40, 51)
        delay(120)

        val afterNative = nativeCursor(emulator)
        val after = emulator.snapshot.value
        println("GROW-TEST scrollback $beforeScrollback -> ${after.scrollback.size}")
        println("GROW-TEST native $beforeNative -> $afterNative")
        println("GROW-TEST display (${before.cursorRow},${before.cursorCol}) -> (${after.cursorRow},${after.cursorCol})")
        val lines = visibleText(emulator).lines()
        lines.forEachIndexed { i, l -> println("GROW-TEST row %02d: %s".format(i, l)) }

        // The cursor must not have moved with the resize: a TUI that is
        // about to repaint relative to its tracked position needs the
        // physical cursor where its model thinks it is.
        assertEquals(
            "rows-only grow must leave the native cursor at its pre-grow cell",
            beforeNative,
            afterNative,
        )
        assertEquals(
            "display cursor must agree with the native cursor after the grow",
            afterNative,
            after.cursorRow to after.cursorCol,
        )
    }
}
