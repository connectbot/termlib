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

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        keyboardHandler = KeyboardHandler(terminalEmulator)
    }

    private val noOpImm get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun makeView(
        selectionUpdates: MutableList<SelectionUpdate>? = null
    ): ImeInputView {
        val onUpdateSelection: (View, Int, Int, Int, Int) -> Unit =
            if (selectionUpdates != null) {
                { view, selStart, selEnd, cStart, cEnd ->
                    selectionUpdates.add(SelectionUpdate(view, selStart, selEnd, cStart, cEnd))
                }
            } else {
                { _, _, _, _, _ -> }
            }
        return ImeInputView(context, keyboardHandler, noOpImm, onUpdateSelection)
    }

    data class SelectionUpdate(
        val view: View,
        val selStart: Int,
        val selEnd: Int,
        val candidatesStart: Int,
        val candidatesEnd: Int
    )

    private fun ImeInputView.ic(composeMode: Boolean = false): BaseInputConnection {
        isComposeModeActive = composeMode
        return onCreateInputConnection(EditorInfo()) as BaseInputConnection
    }

    // === IME editable buffer reset on key events (compose mode — has a real Editable) ===

    @Test
    fun testSendEnterKeyDownClearsEditable() {
        val ic = makeView().ic(composeMode = true)
        ic.commitText("git status", 1)

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testSendKeyUpDoesNotClearEditable() {
        val ic = makeView().ic(composeMode = true)
        ic.getEditable()?.append("hello")

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

        assertEquals("hello", ic.getEditable()?.toString())
    }

    @Test
    fun testSendBackspaceKeyDownClearsEditable() {
        val ic = makeView().ic(composeMode = true)
        ic.commitText("abc", 1)

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testSecondCommandDoesNotAccumulateAfterEnter() {
        // Regression: "git status<enter>ls -l" should not appear as one suggestion candidate.
        val ic = makeView().ic(composeMode = true)

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
        val ic = makeView().ic(composeMode = true)
        ic.commitText("some text", 1)

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testCommitTextWithActiveCompositionClearsEditable() {
        val ic = makeView().ic(composeMode = true)
        ic.setComposingText("wor", 1)
        ic.commitText("word", 1)

        assertEquals("", ic.getEditable()?.toString())
    }

    // === finishComposingText clears editable (regression guard) ===

    @Test
    fun testFinishComposingTextClearsEditable() {
        val ic = makeView().ic(composeMode = true)
        ic.setComposingText("partial", 1)
        ic.finishComposingText()

        assertEquals("", ic.getEditable()?.toString())
    }

    // === updateSelection is called after ACTION_DOWN key events (compose mode) ===

    @Test
    fun testUpdateSelectionCalledAfterEnterKeyDown() {
        val updates = mutableListOf<SelectionUpdate>()
        val view = makeView(updates)
        val ic = view.ic(composeMode = true)

        ic.commitText("git status", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        assertTrue(updates.any { it.view === view && it.selStart == 0 && it.selEnd == 0 && it.candidatesStart == -1 && it.candidatesEnd == -1 })
    }

    @Test
    fun testUpdateSelectionCalledAfterBackspaceKeyDown() {
        val updates = mutableListOf<SelectionUpdate>()
        val view = makeView(updates)
        val ic = view.ic(composeMode = true)

        ic.commitText("abc", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))

        assertTrue(updates.any { it.view === view && it.selStart == 0 && it.selEnd == 0 && it.candidatesStart == -1 && it.candidatesEnd == -1 })
    }

    @Test
    fun testUpdateSelectionNotCalledOnKeyUp() {
        val updates = mutableListOf<SelectionUpdate>()
        val view = makeView(updates)
        val ic = view.ic(composeMode = true)

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

        assertTrue(updates.isEmpty())
    }

    // === resetImeBuffer() — used by physical keyboard paths that bypass InputConnection ===

    @Test
    fun testResetImeBufferClearsEditable() {
        val view = makeView()
        val ic = view.ic(composeMode = true)

        ic.commitText("git status", 1)
        view.resetImeBuffer()

        assertEquals("", ic.getEditable()?.toString())
    }

    @Test
    fun testResetImeBufferCallsUpdateSelection() {
        val updates = mutableListOf<SelectionUpdate>()
        val view = makeView(updates)
        view.ic()

        view.resetImeBuffer()

        assertTrue(updates.any { it.view === view && it.selStart == 0 && it.selEnd == 0 && it.candidatesStart == -1 && it.candidatesEnd == -1 })
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
        val updates = mutableListOf<SelectionUpdate>()
        val view = makeView(updates)
        val ic = view.ic(composeMode = true)

        ic.setComposingText("hel", 1)
        view.resetImeBuffer()

        assertEquals("", ic.getEditable()?.toString())
        assertTrue(updates.any { it.view === view && it.selStart == 0 && it.selEnd == 0 && it.candidatesStart == -1 && it.candidatesEnd == -1 })
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

    // === Soft-keyboard TYPE_NULL key event routing ===

    private fun createNonComposeModeCapture(): Triple<InputConnection, ImeInputView, MutableList<ByteArray>> {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) }
        )
        val handler = KeyboardHandler(emulator)
        var ic: InputConnection? = null
        var view: ImeInputView? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = ImeInputView(context, handler).also { v ->
                // isComposeModeActive = false → TYPE_NULL path
                v.setOnKeyListener { _, _, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) v.resetImeBuffer()
                    handler.onKeyEvent(androidx.compose.ui.input.key.KeyEvent(event))
                }
                ic = v.onCreateInputConnection(EditorInfo())
            }
        }
        return Triple(ic!!, view!!, outputs)
    }

    /**
     * With TYPE_NULL, InputConnection.sendKeyEvent must deliver the key directly to the
     * terminal without going through dispatchKeyEvent. This prevents Gboard's concurrent
     * raw view key event from causing a double delivery.
     */
    @Test
    fun testTypeNullSendKeyEventDeliversCharacter() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        drainMainLooper()

        assertEquals("a", effectiveText(outputs))
    }

    /**
     * With TYPE_NULL, ENTER via InputConnection.sendKeyEvent must reach the terminal.
     * This was broken by the previous isDispatchingFromIme approach which suppressed
     * sendKeyEvent delivery and relied on a raw view event that Gboard does not always fire.
     */
    @Test
    fun testTypeNullSendKeyEventEnterReachesTerminal() {
        val outputs = mutableListOf<ByteArray>()
        var enterDispatched = false
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data ->
                outputs.add(data.copyOf())
                if (data.contains(0x0D.toByte())) enterDispatched = true
            }
        )
        val handler = KeyboardHandler(emulator)
        var ic: InputConnection? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = ImeInputView(context, handler).also { v ->
                v.setOnKeyListener { _, _, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) v.resetImeBuffer()
                    handler.onKeyEvent(androidx.compose.ui.input.key.KeyEvent(event))
                }
                ic = v.onCreateInputConnection(EditorInfo())
            }
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic!!.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
        drainMainLooper()

        assertTrue("ENTER via sendKeyEvent did not reach the terminal", enterDispatched)
    }

    /**
     * With TYPE_NULL, a raw view key event (Gboard's concurrent dispatch path) still reaches
     * the terminal via setOnKeyListener, and does not double with the sendKeyEvent path.
     */
    @Test
    fun testTypeNullRawViewEventAndSendKeyEventAreIndependent() {
        val (ic, view, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Simulate Gboard: sends via InputConnection AND fires a raw view event.
            // Each path delivers the character once — two independent 'a's total.
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        drainMainLooper()

        assertEquals("aa", effectiveText(outputs))
    }
}
