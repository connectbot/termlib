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
}
