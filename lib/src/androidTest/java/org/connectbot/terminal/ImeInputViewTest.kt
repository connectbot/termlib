/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.connectbot.terminal

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeInputViewTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keyboardHandler: KeyboardHandler

    @Before
    fun setup() {
        val terminalEmulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
        keyboardHandler = mockk(relaxed = true)
    }

    private fun makeView(imm: InputMethodManager = mockk(relaxed = true)) =
        ImeInputView(context, keyboardHandler, imm)

    private fun ImeInputView.ic() = onCreateInputConnection(EditorInfo()) as BaseInputConnection

    // === IME editable buffer reset on key events ===

    @Test
    fun testSendEnterKeyDownClearsEditable() {
        val ic = makeView().ic()
        ic.commitText("git status", 1)

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testSendKeyUpDoesNotClearEditable() {
        val ic = makeView().ic()
        // Write directly to the editable, bypassing commitText (which clears it).
        ic.getEditable()?.append("hello")

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

        assertEquals("hello", ic.getEditable()?.toString())
    }

    @Test
    fun testSendBackspaceKeyDownClearsEditable() {
        val ic = makeView().ic()
        ic.commitText("abc", 1)

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testSecondCommandDoesNotAccumulateAfterEnter() {
        // Regression: "git status<enter>ls -l" should not appear as one suggestion candidate.
        val ic = makeView().ic()

        ic.commitText("git status", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals("", ic.getEditable()?.toString())

        ic.commitText("ls -l", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals("", ic.getEditable()?.toString())
    }

    // === commitText clears editable (regression guard) ===

    @Test
    fun testCommitTextClearsEditable() {
        val ic = makeView().ic()
        ic.commitText("some text", 1)

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testCommitTextWithActiveCompositionClearsEditable() {
        val ic = makeView().ic()
        ic.setComposingText("wor", 1)
        ic.commitText("word", 1)

        assertEquals("", ic.getEditable()?.toString())
    }

    // === finishComposingText clears editable (regression guard) ===

    @Test
    fun testFinishComposingTextClearsEditable() {
        val ic = makeView().ic()
        ic.setComposingText("partial", 1)
        ic.finishComposingText()

        assertEquals("", ic.getEditable()?.toString())
    }

    // === updateSelection is called after ACTION_DOWN key events ===

    @Test
    fun testUpdateSelectionCalledAfterEnterKeyDown() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm)
        val ic = view.ic()

        ic.commitText("git status", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        verify { imm.updateSelection(view, 0, 0, -1, -1) }
    }

    @Test
    fun testUpdateSelectionCalledAfterBackspaceKeyDown() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm)
        val ic = view.ic()

        ic.commitText("abc", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))

        verify { imm.updateSelection(view, 0, 0, -1, -1) }
    }

    @Test
    fun testUpdateSelectionNotCalledOnKeyUp() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm)
        val ic = view.ic()

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

        verify(exactly = 0) { imm.updateSelection(any(), any(), any(), any(), any()) }
    }

    // === resetImeBuffer() — used by physical keyboard paths that bypass InputConnection ===

    @Test
    fun testResetImeBufferClearsEditable() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm)
        val ic = view.ic()

        ic.commitText("git status", 1)
        view.resetImeBuffer()

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testResetImeBufferCallsUpdateSelection() {
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm)
        view.ic()

        view.resetImeBuffer()

        verify { imm.updateSelection(view, 0, 0, -1, -1) }
    }

    @Test
    fun testResetImeBufferBeforeConnectionCreatedDoesNotCrash() {
        val view = makeView()
        // No InputConnection created yet — should not throw
        view.resetImeBuffer()
    }

    @Test
    fun testResetImeBufferClearsEditableAccumulatedBySetComposingText() {
        // setComposingText (voice input path) writes to the editable but does not clear it —
        // only finishComposingText does. resetImeBuffer() must also handle this mid-composition
        // case, which can be triggered by a physical hardware key interrupting voice input.
        val imm = mockk<InputMethodManager>(relaxed = true)
        val view = makeView(imm)
        val ic = view.ic()

        ic.setComposingText("hel", 1)
        view.resetImeBuffer()

        assertEquals("", ic.getEditable()?.toString())
        verify { imm.updateSelection(view, 0, 0, -1, -1) }
    }

    // === IME duplicate character tests (connectbot/connectbot#1955) ===

    private fun createKeyboardOutputCapture(): Pair<InputConnection, MutableList<ByteArray>> {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) }
        )
        val handler = KeyboardHandler(emulator)
        var ic: InputConnection? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = ImeInputView(context, handler)
            view.isComposeModeActive = true
            view.setOnKeyListener { _, _, event ->
                handler.onKeyEvent(
                    androidx.compose.ui.input.key.KeyEvent(event)
                )
            }
            ic = view.onCreateInputConnection(EditorInfo())
        }
        return ic!! to outputs
    }

    /**
     * Compute the effective text from captured keyboard output by applying
     * BS (0x08) and DEL (0x7F) as character erasure operations.
     */
    private fun effectiveText(outputs: List<ByteArray>): String {
        val buffer = StringBuilder()
        for (data in outputs) {
            for (byte in data) {
                val code = byte.toInt() and 0xFF
                when {
                    code == 0x08 || code == 0x7F -> {
                        if (buffer.isNotEmpty()) buffer.deleteCharAt(buffer.length - 1)
                    }
                    code >= 0x20 -> buffer.append(byte.toInt().toChar())
                }
            }
        }
        return buffer.toString()
    }

    private fun drainMainLooper() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun testCommitAfterComposingDoesNotDuplicate() {
        val (ic, outputs) = createKeyboardOutputCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("a", 1)
            ic.commitText("a", 1)
        }
        drainMainLooper()
        assertEquals("a", effectiveText(outputs))
    }

    @Test
    fun testMultiCharComposingCommitDoesNotDuplicate() {
        val (ic, outputs) = createKeyboardOutputCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.setComposingText("h", 1)
            ic.setComposingText("he", 1)
            ic.setComposingText("hel", 1)
            ic.commitText("hel", 1)
        }
        drainMainLooper()
        assertEquals("hel", effectiveText(outputs))
    }

    @Test
    fun testDirectCommitWithoutComposing() {
        val (ic, outputs) = createKeyboardOutputCapture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText("x", 1)
        }
        drainMainLooper()
        assertEquals("x", effectiveText(outputs))
    }

    // === Soft-keyboard doubling regression (TYPE_NULL IME key event duplication) ===

    /**
     * Regression test for soft-keyboard input doubling when TYPE_NULL is set (non-compose mode).
     *
     * Some IMEs (e.g. Gboard) deliver soft-keyboard keys via BOTH:
     *   1. InputConnection.sendKeyEvent → dispatchKeyEvent → setOnKeyListener
     *   2. A raw KeyEvent dispatched directly on the view → setOnKeyListener
     *
     * The isDispatchingFromIme flag on ImeInputView gates the setOnKeyListener so the
     * raw duplicate is suppressed, resulting in each keystroke being sent exactly once.
     */
    @Test
    fun testSoftKeyboardKeyEventNotDoubledByRawViewEvent() {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) }
        )
        val handler = KeyboardHandler(emulator)
        var ic: android.view.inputmethod.InputConnection? = null
        var view: ImeInputView? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = ImeInputView(context, handler).also { v ->
                // isComposeModeActive = false → TYPE_NULL path (the regression scenario)
                v.setOnKeyListener { _, _, event ->
                    if (v.isDispatchingFromIme) return@setOnKeyListener false
                    if (event.action == KeyEvent.ACTION_DOWN) v.resetImeBuffer()
                    handler.onKeyEvent(androidx.compose.ui.input.key.KeyEvent(event))
                }
                ic = v.onCreateInputConnection(EditorInfo())
            }
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val keyDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
            // Path 1: IME delivers key via InputConnection.sendKeyEvent. Our override sets
            //         isDispatchingFromIme=true, calls dispatchKeyEvent → setOnKeyListener
            //         sees the flag and skips, then clears the flag. This path is suppressed.
            ic!!.sendKeyEvent(keyDown)
            // Path 2: IME also fires the same key as a raw view dispatchKeyEvent (the
            //         behavior that causes doubling with TYPE_NULL on Gboard). Now
            //         isDispatchingFromIme=false so setOnKeyListener processes it normally.
            view!!.dispatchKeyEvent(keyDown)
        }
        drainMainLooper()

        // 'a' should appear exactly once: Path 1 suppressed, Path 2 processed.
        assertEquals("a", effectiveText(outputs))
    }
}
