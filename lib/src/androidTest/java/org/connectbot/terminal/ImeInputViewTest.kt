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
import java.text.Normalizer

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

    // === Unicode precomposition (NFC normalization) ===

    /**
     * Some IMEs send decomposed Unicode (NFD): a base character followed by a combining
     * diacritic as separate code points. The terminal must send the precomposed NFC form
     * so the remote host receives a single character (e.g. ä U+00E4) rather than two
     * separate code points (a U+0061 + combining umlaut U+0308).
     */
    @Test
    fun testDecomposedUmlautIsPrecomposed() {
        val (ic, outputs) = createKeyboardOutputCapture()
        // NFD: 'a' (U+0061) + combining diaeresis (U+0308) → should arrive as NFC ä (U+00E4)
        val nfdUmlaut = "a\u0308"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText(nfdUmlaut, 1)
        }
        drainMainLooper()
        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        val expected = Normalizer.normalize(nfdUmlaut, Normalizer.Form.NFC)
        assertEquals(expected, received)
    }

    @Test
    fun testDecomposedCircumflexIsPrecomposed() {
        val (ic, outputs) = createKeyboardOutputCapture()
        // NFD: 'e' (U+0065) + combining circumflex (U+0302) → should arrive as NFC ê (U+00EA)
        val nfdCircumflex = "e\u0302"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText(nfdCircumflex, 1)
        }
        drainMainLooper()
        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        val expected = Normalizer.normalize(nfdCircumflex, Normalizer.Form.NFC)
        assertEquals(expected, received)
    }

    @Test
    fun testAlreadyNfcTextIsUnchanged() {
        val (ic, outputs) = createKeyboardOutputCapture()
        // NFC ä (U+00E4) should pass through unchanged
        val nfcUmlaut = "\u00E4"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText(nfcUmlaut, 1)
        }
        drainMainLooper()
        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals(nfcUmlaut, received)
    }

    @Test
    fun testSurrogatePairSentAsOneCodepoint() {
        val (ic, outputs) = createKeyboardOutputCapture()
        // U+1F600 GRINNING FACE — encoded as a surrogate pair in Java/Kotlin strings
        val emoji = "\uD83D\uDE00"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText(emoji, 1)
        }
        drainMainLooper()
        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals(emoji, received)
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
     * With TYPE_NULL, InputConnection.sendKeyEvent is ignored to prevent doubling with
     * the raw view event that Gboard also fires.
     */
    @Test
    fun testTypeNullSendKeyEventIsIgnored() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        drainMainLooper()

        assertEquals("", effectiveText(outputs))
    }

    /**
     * With TYPE_NULL, events delivered via View.dispatchKeyEvent (Gboard's raw path)
     * must reach the terminal.
     */
    @Test
    fun testTypeNullRawViewEventDeliversCharacter() {
        val (_, view, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        drainMainLooper()

        assertEquals("a", effectiveText(outputs))
    }

    /**
     * With TYPE_NULL, ENTER via View.dispatchKeyEvent must reach the terminal.
     */
    @Test
    fun testTypeNullRawViewEventEnterReachesTerminal() {
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
        var view: ImeInputView? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = ImeInputView(context, handler).also { v ->
                v.setOnKeyListener { _, _, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) v.resetImeBuffer()
                    handler.onKeyEvent(androidx.compose.ui.input.key.KeyEvent(event))
                }
                v.onCreateInputConnection(EditorInfo())
            }
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view!!.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
        drainMainLooper()

        assertTrue("ENTER via dispatchKeyEvent did not reach the terminal", enterDispatched)
    }

    /**
     * With TYPE_NULL, a raw view key event (Gboard's concurrent dispatch path) still reaches
     * the terminal via setOnKeyListener, and does not double with the sendKeyEvent path.
     */
    @Test
    fun testTypeNullRawViewEventAndCommitTextDeliverIndependently() {
        val (ic, view, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Simulate Gboard: sends via commitText AND fires a raw view event.
            // Each path delivers a character — two independent 'a's total.
            ic.commitText("a", 1)
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        drainMainLooper()

        assertEquals("aa", effectiveText(outputs))
    }

    @Test
    fun testTypeNullCommitTextDeliversAccentedCharacterWithoutDuplication() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.commitText("ü", 1)
            ic.commitText("a", 1)
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals("üa", received)
    }

    /**
     * With TYPE_NULL, KEYCODE_DEL delivered via View.dispatchKeyEvent (physical keyboard or
     * Gboard's raw key path) must reach the terminal.
     */
    @Test
    fun testTypeNullRawViewDelKeyReachesTerminal() {
        val (_, view, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("DEL via dispatchKeyEvent did not reach the terminal", received.contains(0x7F.toByte()))
    }

    /**
     * With TYPE_NULL, soft-keyboard backspace arrives via deleteSurroundingText →
     * sendKeyEvent(KEYCODE_DEL). Verify it reaches the terminal.
     */
    @Test
    fun testTypeNullDeleteSurroundingTextDeliversBackspace() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.deleteSurroundingText(1, 0)
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("DEL via deleteSurroundingText did not reach the terminal", received.contains(0x7F.toByte()))
    }

    /**
     * With TYPE_NULL, soft-keyboard ENTER arrives via sendKeyEvent(KEYCODE_ENTER) — it is a
     * non-printable key so there is no competing raw view event. Verify it reaches the terminal.
     */
    @Test
    fun testTypeNullSendKeyEventEnterReachesTerminal() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("ENTER via sendKeyEvent did not reach the terminal", received.contains(0x0D.toByte()))
    }

    // === Ctrl/Alt modifier key routing from soft keyboards (issue-2050) ===
    // Keyboards like "Unexpected keyboard" and SwiftKey send Ctrl/Alt combos via
    // sendKeyEvent with metaState set. Unlike plain printable keys (which are dropped
    // here to avoid doubling with Gboard's concurrent raw view event), modifier-carrying
    // events must be forwarded since no competing raw view event is fired for them.

    /**
     * Ctrl+A via sendKeyEvent (metaState=META_CTRL_ON) must reach the terminal as 0x01.
     * This is the path used by keyboards like "Unexpected keyboard" and SwiftKey.
     */
    @Test
    fun testTypeNullSendKeyEventCtrlAProducesControlChar() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON)
            )
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Ctrl+A via sendKeyEvent did not produce 0x01", received.contains(0x01.toByte()))
    }

    /**
     * Ctrl+C via sendKeyEvent must reach the terminal as 0x03.
     */
    @Test
    fun testTypeNullSendKeyEventCtrlCProducesControlChar() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON)
            )
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Ctrl+C via sendKeyEvent did not produce 0x03", received.contains(0x03.toByte()))
    }

    /**
     * Alt+A via sendKeyEvent (metaState=META_ALT_ON) must reach the terminal as ESC + 'a'.
     */
    @Test
    fun testTypeNullSendKeyEventAltAProducesEscapePrefix() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_ALT_ON)
            )
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Alt+A via sendKeyEvent did not produce ESC prefix (0x1B)", received.contains(0x1B.toByte()))
        assertTrue("Alt+A via sendKeyEvent did not produce 'a'", received.contains('a'.code.toByte()))
    }

    /**
     * A plain printable key via sendKeyEvent (no modifier) must still be dropped to prevent
     * doubling with Gboard's concurrent raw view event. This is a regression guard.
     */
    @Test
    fun testTypeNullSendKeyEventPlainPrintableIsStillIgnored() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B))
        }
        drainMainLooper()

        assertEquals("Plain printable key via sendKeyEvent should be dropped", "", effectiveText(outputs))
    }

    /**
     * Space via sendKeyEvent (no modifier) must be dropped even though isPrintingKey() returns
     * false for KEYCODE_SPACE (KeyCharacterMap classifies ' ' as SPACE_SEPARATOR, not printable).
     * Gboard fires a concurrent raw View.dispatchKeyEvent for space; forwarding sendKeyEvent
     * as well would cause a duplicate space.
     */
    @Test
    fun testTypeNullSendKeyEventPlainSpaceIsIgnored() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE))
        }
        drainMainLooper()

        assertEquals("Plain space via sendKeyEvent should be dropped", "", effectiveText(outputs))
    }

    /**
     * Ctrl modifier delivered via sendKeyEvent must not double when the same key is also
     * delivered via raw View.dispatchKeyEvent without a modifier. The two events are
     * independent: one carries the Ctrl combo, the other is a plain key press.
     */
    @Test
    fun testTypeNullCtrlSendKeyEventAndPlainRawViewAreIndependent() {
        val (ic, view, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Ctrl+A via sendKeyEvent → 0x01
            ic.sendKeyEvent(
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON)
            )
            // Plain 'a' via raw view dispatch → 'a'
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Ctrl+A control char (0x01) missing", received.contains(0x01.toByte()))
        assertTrue("Plain 'a' missing", received.contains('a'.code.toByte()))
    }
}
