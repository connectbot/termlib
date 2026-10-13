/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalAccessibleOutputTest {
    @Test fun capturesBurstBeforeAnyUiFrameOrHistoryEviction() {
        // Two-row merged scrolls leave a gap between source and destination; a
        // larger burst omits moverect entirely and exceeds the retained history.
        for (count in listOf(4, 1100)) {
            val emulator = TerminalEmulatorFactory.create(initialRows = 3, initialCols = 10) as TerminalEmulatorImpl
            emulator.observeAccessibleOutput()
            emulator.writeInput((0 until count).joinToString("") { "x$it\r\n" }.toByteArray())
            emulator.processPendingUpdates()
            val batch = emulator.drainAccessibleOutput()!!
            assertFalse(batch.overflow)
            assertEquals((0 until count).map { "x$it" }, batch.output)
            assertEquals(minOf(1000, count - 2), emulator.snapshot.value.scrollback.size)
            emulator.stopObservingAccessibleOutput()
            assertNull(emulator.drainAccessibleOutput())
        }
    }

    @Test fun staleImeAndPhysicalInputAreBlockedDuringReview() {
        val emulator = TerminalEmulatorFactory.create()
        val keyboard = KeyboardHandler(emulator)
        var processed = 0
        keyboard.onInputProcessed = { processed++ }
        val view = ImeInputView(ApplicationProvider.getApplicationContext<Context>(), keyboard)
        val connection = view.onCreateInputConnection(EditorInfo())
        keyboard.inputEnabled = false
        connection.commitText("should not reach shell", 1)
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        connection.deleteSurroundingText(1, 0)
        keyboard.onCharacterInput('x')
        keyboard.onCommittedText("y")
        keyboard.onImeDeleteBackward()
        assertEquals(0, processed)
    }
}
