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
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        selectionUpdates: MutableList<SelectionUpdate>? = null,
        handler: KeyboardHandler = keyboardHandler,
    ): ImeInputView {
        val onUpdateSelection: (View, Int, Int, Int, Int) -> Unit =
            if (selectionUpdates != null) {
                { view, selStart, selEnd, cStart, cEnd ->
                    selectionUpdates.add(SelectionUpdate(view, selStart, selEnd, cStart, cEnd))
                }
            } else {
                { _, _, _, _, _ -> }
            }
        return ImeInputView(context, handler, noOpImm, onUpdateSelection)
    }

    data class SelectionUpdate(
        val view: View,
        val selStart: Int,
        val selEnd: Int,
        val candidatesStart: Int,
        val candidatesEnd: Int,
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
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator)
        var ic: InputConnection? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = ImeInputView(context, handler)
            view.isComposeModeActive = true
            view.setOnKeyListener { _, _, event ->
                handler.onKeyEvent(
                    androidx.compose.ui.input.key.KeyEvent(event),
                )
            }
            ic = view.onCreateInputConnection(EditorInfo())
        }
        return ic!! to outputs
    }

    private data class ComposeReplayCapture(
        val ic: InputConnection,
        val outputs: MutableList<ByteArray>,
        val composeMode: ComposeMode,
        val restartRequests: MutableList<View>,
    )

    private fun createComposeReplayCapture(): ComposeReplayCapture {
        val outputs = mutableListOf<ByteArray>()
        val restartRequests = mutableListOf<View>()
        val composeMode = ComposeMode().also { it.activate() }
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator).also { it.composeMode = composeMode }
        var ic: InputConnection? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = ImeInputView(
                context = context,
                keyboardHandler = handler,
                inputMethodManager = noOpImm,
                onRestartInput = { restartRequests.add(it) },
            ).also { v ->
                v.isComposeModeActive = true
                v.setOnKeyListener { _, _, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) v.resetImeBuffer()
                    handler.onKeyEvent(androidx.compose.ui.input.key.KeyEvent(event))
                }
            }
            ic = view.onCreateInputConnection(EditorInfo())
        }
        return ComposeReplayCapture(ic!!, outputs, composeMode, restartRequests)
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

    @Test
    fun testCommittedJapaneseTextLeavesComposeBufferImmediately() {
        val capture = createComposeReplayCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            capture.ic.setComposingText("a", 1)
            capture.ic.setComposingText("あ", 1)
            capture.ic.commitText("あ", 1)
        }
        drainMainLooper()

        val received = capture.outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals("あ", received)
        assertEquals("", capture.composeMode.buffer)
        assertEquals(0, capture.restartRequests.size)
    }

    @Test
    fun testSpaceAfterCommittedJapaneseTextStartsFreshComposition() {
        val capture = createComposeReplayCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            capture.ic.setComposingText("a", 1)
            capture.ic.setComposingText("あ", 1)
            capture.ic.commitText("あ", 1)
            capture.ic.setComposingText(" ", 1)
        }
        drainMainLooper()

        val received = capture.outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
        assertEquals("あ", received)
        assertEquals(" ", capture.composeMode.buffer)
        assertEquals(0, capture.restartRequests.size)
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

    @Test
    fun testTrailingCommitAfterEnterDoesNotDeleteSubmittedJapaneseText() {
        val capture = createComposeReplayCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            capture.ic.setComposingText("a", 1)
            capture.ic.setComposingText("あ", 1)
            capture.ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            capture.ic.commitText("あ", 1)
        }
        drainMainLooper()

        val received = capture.outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Trailing replay emitted DEL and removed submitted text", !received.contains(0x7F.toByte()))
        assertEquals("あ\r", received.toString(Charsets.UTF_8))
        assertEquals("", capture.composeMode.buffer)
        assertEquals(1, capture.restartRequests.size)
    }

    @Test
    fun testSpaceAfterTrailingCommitReplayStartsFreshComposition() {
        val capture = createComposeReplayCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            capture.ic.setComposingText("a", 1)
            capture.ic.setComposingText("あ", 1)
            capture.ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            capture.ic.commitText("あ", 1)
            capture.ic.setComposingText(" ", 1)
        }
        drainMainLooper()

        assertEquals(" ", capture.composeMode.buffer)
        assertEquals(1, capture.restartRequests.size)
    }

    @Test
    fun testTrailingSetComposingReplayAfterEnterDoesNotRefillComposeBuffer() {
        val capture = createComposeReplayCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            capture.ic.setComposingText("a", 1)
            capture.ic.setComposingText("あ", 1)
            capture.ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            capture.ic.setComposingText("あ", 1)
        }
        drainMainLooper()

        val received = capture.outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Trailing setComposing replay emitted DEL and removed submitted text", !received.contains(0x7F.toByte()))
        assertEquals("あ\r", received.toString(Charsets.UTF_8))
        assertEquals("", capture.composeMode.buffer)
        assertEquals(1, capture.restartRequests.size)
    }

    @Test
    fun testSpaceAfterTrailingSetComposingReplayStartsFreshComposition() {
        val capture = createComposeReplayCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            capture.ic.setComposingText("a", 1)
            capture.ic.setComposingText("あ", 1)
            capture.ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            capture.ic.setComposingText("あ", 1)
            capture.ic.setComposingText(" ", 1)
        }
        drainMainLooper()

        assertEquals(" ", capture.composeMode.buffer)
        assertEquals(1, capture.restartRequests.size)
    }

    // === Soft-keyboard TYPE_NULL key event routing ===

    private fun createNonComposeModeCapture(): Triple<InputConnection, ImeInputView, MutableList<ByteArray>> {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator)
        var ic: InputConnection? = null
        var view: ImeInputView? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = ImeInputView(context, handler).also { v ->
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
     * With TYPE_NULL, InputConnection.sendKeyEvent delivers the key directly to the terminal.
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
     * With TYPE_NULL, a raw view event (e.g. physical keyboard) that arrives independently
     * of sendKeyEvent still reaches the terminal via setOnKeyListener.
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
            },
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
     * Gboard sends via commitText AND fires a concurrent raw view event. The commitText
     * path delivers the character; the raw view event is independent. Two 'a's total.
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

    // === DelKeyMode IME tests ===

    private fun createNonComposeModeWithMode(delKeyMode: DelKeyMode): Pair<InputConnection, MutableList<ByteArray>> {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator)
        handler.delKeyMode = delKeyMode
        var ic: InputConnection? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ImeInputView(context, handler).also { v ->
                v.setOnKeyListener { _, _, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) v.resetImeBuffer()
                    handler.onKeyEvent(androidx.compose.ui.input.key.KeyEvent(event))
                }
                ic = v.onCreateInputConnection(EditorInfo())
            }
        }
        return ic!! to outputs
    }

    @Test
    fun testImeSoftBackspaceDeleteModeDefaultDeliversDel() {
        val (ic, outputs) = createNonComposeModeWithMode(DelKeyMode.Delete)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.deleteSurroundingText(1, 0)
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Expected DEL (0x7f) in default Delete mode", received.contains(0x7F.toByte()))
    }

    @Test
    fun testImeSoftBackspaceBackspaceModeDeliversCtrlH() {
        val (ic, outputs) = createNonComposeModeWithMode(DelKeyMode.Backspace)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.deleteSurroundingText(1, 0)
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Expected ^H (0x08) in Backspace mode", received.contains(0x08.toByte()))
        assertFalse("Should NOT send DEL (0x7f) in Backspace mode", received.contains(0x7F.toByte()))
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

    /**
     * Some IMEs deliver enter-like input as ACTION_MULTIPLE + KEYCODE_UNKNOWN carrying a raw
     * newline string instead of KEYCODE_ENTER. Old ConnectBot accepted this path; ensure the
     * terminal still receives it.
     */
    @Test
    fun testTypeNullActionMultipleUnknownNewlineReachesTerminal() {
        val (_, view, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.dispatchKeyEvent(
                KeyEvent(
                    SystemClock.uptimeMillis(),
                    "\n",
                    KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0,
                ),
            )
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue(
            "ACTION_MULTIPLE newline should send Enter (CR) to the terminal",
            received.contains('\r'.code.toByte()),
        )
    }

    // === Ctrl/Alt modifier key routing from soft keyboards (issue-2050) ===
    // Keyboards like "Unexpected keyboard", SwiftKey, and Hacker's Keyboard send Ctrl/Alt
    // combos via sendKeyEvent (with or without metaState). All sendKeyEvent calls are
    // forwarded directly to keyboardHandler.

    /**
     * Ctrl+A via sendKeyEvent (metaState=META_CTRL_ON) must reach the terminal as 0x01.
     * This is the path used by keyboards like "Unexpected keyboard" and SwiftKey.
     */
    @Test
    fun testTypeNullSendKeyEventCtrlAProducesControlChar() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON),
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
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON),
            )
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Ctrl+C via sendKeyEvent did not produce 0x03", received.contains(0x03.toByte()))
    }

    /**
     * Alt+A via sendKeyEvent must reach the terminal as ESC + 'a'.
     */
    @Test
    fun testTypeNullSendKeyEventAltAProducesEscapePrefix() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_ALT_ON),
            )
        }
        drainMainLooper()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Alt+A via sendKeyEvent did not produce ESC prefix (0x1B)", received.contains(0x1B.toByte()))
        assertTrue("Alt+A via sendKeyEvent did not produce 'a'", received.contains('a'.code.toByte()))
    }

    /**
     * Plain printable key via sendKeyEvent (no modifier) delivers the character.
     * Hacker's Keyboard uses this path for number keys.
     */
    @Test
    fun testTypeNullSendKeyEventPlainPrintableDeliversCharacter() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B))
        }
        drainMainLooper()

        assertEquals("b", effectiveText(outputs))
    }

    /**
     * Space via sendKeyEvent delivers a space. KEYCODE_SPACE has isPrintingKey()=false
     * (KeyCharacterMap classifies ' ' as SPACE_SEPARATOR), but it is still forwarded like
     * all other sendKeyEvent keys.
     */
    @Test
    fun testTypeNullSendKeyEventSpaceDeliversSpace() {
        val (ic, _, outputs) = createNonComposeModeCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE))
        }
        drainMainLooper()

        assertEquals(" ", effectiveText(outputs))
    }

    // === Robustness: deleteSurroundingText upper bound ===

    /**
     * A misbehaving or hostile IME can call [InputConnection.deleteSurroundingText]
     * with an unbounded (Int.MAX_VALUE-ish) left length. Without a cap that would
     * freeze the UI thread in a DEL-key dispatch loop. Cap is well above anything
     * any real IME sends (real values are 0–few).
     */
    @Test
    fun testDeleteSurroundingTextCapsAbsurdLeftLength() {
        val (ic, outputs) = createKeyboardOutputCapture()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ic.deleteSurroundingText(Int.MAX_VALUE, 0)
        }
        drainMainLooper()

        // Cap is 4096 — assert we got no more than that many DEL bytes.
        val delCount = countDelBytes(outputs)
        assertTrue("delete count should be capped, got $delCount", delCount <= 4096)
    }

    // === Robustness: resetImeBuffer drops stale composition state ===

    /**
     * [ImeInputView.resetImeBuffer] used to clear only the Editable and the IME's
     * selection, leaving the internal `composingText` tracking string non-empty.
     * A composition interrupted by an external key path (hardware keyboard, macro
     * key, IME dismissal mid-conversion) would resume against stale state on the
     * next [setComposingText], producing ghost backspaces sized to the old
     * composition. Reset both.
     */
    @Test
    fun testResetImeBufferDropsCompositionSoNextSetComposingStartsClean() {
        // Uses an output-capturing KeyboardHandler so we can observe the
        // backspace bytes dispatched by setComposingText, then runs through
        // makeView()/view.ic(composeMode = true) so the setup matches the
        // other resetImeBuffer tests.
        val outputs = mutableListOf<ByteArray>()
        val captureEmulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val captureHandler = KeyboardHandler(captureEmulator)
        val view = makeView(handler = captureHandler)
        val ic = view.ic(composeMode = true)

        // Build up a 5-char composition in the background
        ic.setComposingText("hello", 1)
        // External key path interrupts — buffer reset
        view.resetImeBuffer()
        // New composition starts from scratch. Without the reset, the internal
        // composingText would still read "hello" and the next setComposingText
        // would dispatch 5 backspaces before projecting "a".
        ic.setComposingText("a", 1)

        // With the reset, no DEL (0x7F) bytes should be dispatched for the
        // second setComposingText. Before the fix the count was 5 — one DEL
        // per char of the stale "hello" composition.
        assertEquals(
            "no backspaces should be dispatched after a fresh reset",
            0,
            countDelBytes(outputs),
        )
    }

    /**
     * KeyboardHandler's default DEL-key mapping emits 0x7F for
     * `KEYCODE_DEL`. Both robustness tests above dispatch DEL through the
     * identical sendKeyEvent path, so they share this helper instead of
     * inlining the byte-counter twice.
     */
    private fun countDelBytes(outputs: List<ByteArray>): Int = outputs.sumOf { bytes -> bytes.count { (it.toInt() and 0xFF) == 0x7F } }
}
