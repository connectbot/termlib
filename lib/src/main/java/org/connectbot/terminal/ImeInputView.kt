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
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * A minimal invisible View that provides proper IME input handling for terminal emulation.
 *
 * This view creates a custom InputConnection that:
 * - Handles backspace via deleteSurroundingText by sending KEYCODE_DEL
 * - Handles enter/return keys properly via sendKeyEvent
 * - Configures the keyboard to disable suggestions while allowing voice input
 * - Handles composing text from IME (for voice input partial results)
 * - Manages IME visibility using InputMethodManager for reliable show/hide
 *
 * Based on the ConnectBot v1.9.13 TerminalView implementation.
 */
internal class ImeInputView(
    context: Context,
    private val keyboardHandler: KeyboardHandler,
    internal val inputMethodManager: InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager,
    internal val onUpdateSelection: (view: View, selStart: Int, selEnd: Int, candidatesStart: Int, candidatesEnd: Int) -> Unit =
        { view, selStart, selEnd, candidatesStart, candidatesEnd ->
            inputMethodManager.updateSelection(view, selStart, selEnd, candidatesStart, candidatesEnd)
        },
    internal val onRestartInput: (view: View) -> Unit =
        { view -> inputMethodManager.restartInput(view) },
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    var isComposeModeActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (windowToken != null) {
                onRestartInput(this)
            }
        }

    /**
     * Show the IME forcefully. This is more reliable than SoftwareKeyboardController.
     */
    @Suppress("DEPRECATION")
    fun showIme() {
        if (requestFocus()) {
            inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_FORCED)
        }
    }

    /**
     * Hide the IME.
     */
    fun hideIme() {
        inputMethodManager.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Always hide IME when view is detached to prevent SHOW_FORCED from keeping keyboard
        // open after the app/activity is destroyed
        hideIme()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Configure IME options
        outAttrs.imeOptions = outAttrs.imeOptions or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            EditorInfo.IME_ACTION_NONE

        if (isComposeModeActive) {
            // Compose mode: allow voice input and IME suggestions.
            // TYPE_CLASS_TEXT without NO_SUGGESTIONS keeps the suggestion strip (and its
            // microphone button) visible. fullEditor=true makes BaseInputConnection provide
            // a real Editable so getExtractedText() returns non-null (required by Gboard
            // for voice input).
            outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
            outAttrs.initialSelStart = 0
            outAttrs.initialSelEnd = 0
        } else {
            // Normal terminal mode:
            // - TYPE_TEXT_VARIATION_PASSWORD: Shows password-style keyboard with number rows
            // - TYPE_TEXT_VARIATION_VISIBLE_PASSWORD: Keeps text visible (we handle display ourselves)
            // - TYPE_TEXT_FLAG_NO_SUGGESTIONS: Disables autocomplete/suggestions
            // - TYPE_NULL: No special input processing
            outAttrs.inputType = EditorInfo.TYPE_NULL or
                EditorInfo.TYPE_TEXT_VARIATION_PASSWORD or
                EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        return TerminalInputConnection(this, isComposeModeActive).also { activeConnection = it }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    private var activeConnection: TerminalInputConnection? = null

    /**
     * Clears the IME's internal text buffer and resets its selection state to (0, 0).
     *
     * Call this after key events that are dispatched outside the InputConnection (e.g. physical
     * keyboard events handled via onPreviewKeyEvent or setOnKeyListener), so that the IME's
     * suggestion context stays in sync with the terminal's stateless text model.
     */
    fun resetImeBuffer() {
        // Clear both the Editable AND the composing-text tracking so an
        // in-flight IME composition doesn't resume against stale state.
        // Without the composition reset, a mid-composition interruption
        // from an external key path (hardware keyboard, macro key, IME
        // dismissal) leaves the tracked composingText non-empty, and the
        // next setComposingText() computes its backspace-count against
        // that stale length — producing ghost backspaces or duplicated
        // input on the next IME message.
        activeConnection?.editable?.clear()
        activeConnection?.resetComposition()
        onUpdateSelection(this, 0, 0, -1, -1)
    }

    private fun restartInputSoon() {
        onRestartInput(this)
    }

    /**
     * Custom InputConnection that handles backspace and other special keys for terminal input.
     */
    private inner class TerminalInputConnection(
        targetView: View,
        private val fullEditor: Boolean,
    ) : BaseInputConnection(targetView, fullEditor) {

        private var composingText: String = ""
        private var awaitingPostEnterCommitReplay: Boolean = false
        private var postEnterSubmittedText: String? = null

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!fullEditor) return super.setComposingText(text, newCursorPosition)

            val newText = text?.toString() ?: ""
            if (awaitingPostEnterCommitReplay &&
                newText.isNotEmpty() &&
                postEnterSubmittedText != null &&
                newText == postEnterSubmittedText
            ) {
                super.setComposingText(text, newCursorPosition)
                awaitingPostEnterCommitReplay = false
                postEnterSubmittedText = null
                composingText = ""
                editable?.clear()
                onUpdateSelection(this@ImeInputView, 0, 0, -1, -1)
                // Some IMEs replay the just-submitted composition through setComposingText()
                // after Enter. Ignore that replay so the compose overlay stays cleared.
                restartInputSoon()
                return true
            }

            if (awaitingPostEnterCommitReplay && newText.isNotEmpty()) {
                awaitingPostEnterCommitReplay = false
                postEnterSubmittedText = null
            }
            super.setComposingText(text, newCursorPosition)

            if (newText == composingText) {
                return true
            }

            if (newText.isEmpty()) {
                if (composingText.isNotEmpty()) {
                    // Composition cleared by IME; remove the projected text from the terminal.
                    sendBackspaces(composingText.length)
                }
                composingText = ""
                return true
            }

            when {
                newText.startsWith(composingText) -> {
                    // Typical case: IME appends new chars to the composition
                    val delta = newText.substring(composingText.length)
                    sendTextInput(delta)
                }

                composingText.startsWith(newText) -> {
                    // IME removed characters from the end of the composition
                    val deleteCount = composingText.length - newText.length
                    sendBackspaces(deleteCount)
                }

                else -> {
                    // IME replaced the composition; rewrite it in the terminal
                    sendBackspaces(composingText.length)
                    sendTextInput(newText)
                }
            }

            composingText = newText
            return true
        }

        override fun finishComposingText(): Boolean {
            if (!fullEditor) return super.finishComposingText()

            super.finishComposingText()
            composingText = ""
            // Clear the internal Editable to prevent unbounded accumulation
            editable?.clear()
            return true
        }

        override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
            // Handle backspace by sending DEL key events
            // When IME sends delete, it often sends (0, 0) or (1, 0) for backspace
            if (rightLength == 0 && leftLength == 0) {
                return sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            }

            // Cap the loop: real IMEs send single-digit values here. A misbehaving
            // or hostile IME asking for ~2^31 deletions would freeze the UI thread
            // in a DEL-key loop. MAX_DELETE_SURROUNDING is well above anything
            // legitimate.
            val bounded = leftLength.coerceIn(0, MAX_DELETE_SURROUNDING)
            for (i in 0 until bounded) {
                sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            }

            // TODO: Implement forward delete if rightLength > 0
            if (bounded > 0 && composingText.isNotEmpty()) {
                val newLength = (composingText.length - bounded).coerceAtLeast(0)
                composingText = composingText.substring(0, newLength)
            }

            super.deleteSurroundingText(leftLength, rightLength)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (fullEditor) {
                val isKeyDown = event.action == KeyEvent.ACTION_DOWN
                val postEnterSubmittedBeforeDispatch = if (isKeyDown && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    composingText.takeIf { it.isNotEmpty() }
                } else {
                    null
                }

                // Compose mode (TYPE_CLASS_TEXT): route through dispatchKeyEvent so the
                // setOnKeyListener chain handles it. Clear the editable buffer afterward so
                // Gboard does not accumulate terminal input as suggestion candidates.
                val result = this@ImeInputView.dispatchKeyEvent(event)
                if (isKeyDown) {
                    awaitingPostEnterCommitReplay = event.keyCode == KeyEvent.KEYCODE_ENTER
                    postEnterSubmittedText = postEnterSubmittedBeforeDispatch
                    editable?.clear()
                    onUpdateSelection(this@ImeInputView, 0, 0, -1, -1)
                }
                return result
            } else {
                // TYPE_NULL mode: forward the key directly to keyboardHandler.
                //
                // Some IMEs (e.g. Gboard) also fire a concurrent raw View.dispatchKeyEvent
                // for the same key, but since we call keyboardHandler directly here (not via
                // dispatchKeyEvent), setOnKeyListener is never triggered — no duplication.
                //
                // Other IMEs (e.g. Hacker's Keyboard) only use sendKeyEvent and fire no raw
                // view event, so forwarding here is the only way their keys reach the terminal.
                if (event.action == KeyEvent.ACTION_DOWN) {
                    keyboardHandler.onKeyEvent(ComposeKeyEvent(event))
                }
                return true
            }
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val committedText = text?.toString() ?: ""
            if (!fullEditor) {
                if (committedText.isNotEmpty()) {
                    // When in TYPE_NULL mode, Gboard sends regular characters (a-z, etc.) via BOTH
                    // sendKeyEvent AND a raw View.dispatchKeyEvent.
                    //
                    // We've modified sendKeyEvent to be a no-op in this mode to avoid doubling
                    // with the raw View event. However, accented characters (ü, etc.) often
                    // ONLY arrive via commitText because they have no direct KEYCODE.
                    //
                    // Deliver the text directly; this covers accented chars and any regular
                    // chars sent via commitText rather than the sendKeyEvent/raw-view paths.
                    sendTextInput(committedText)
                }
                return true
            }

            // Save composingText before super.commitText() which internally calls
            // finishComposingText(), clearing composingText before we can use it.
            val previousComposingText = composingText
            super.commitText(text, newCursorPosition)

            if (awaitingPostEnterCommitReplay &&
                postEnterSubmittedText != null &&
                committedText == postEnterSubmittedText
            ) {
                awaitingPostEnterCommitReplay = false
                postEnterSubmittedText = null
                composingText = ""
                editable?.clear()
                onUpdateSelection(this@ImeInputView, 0, 0, -1, -1)
                // Some IMEs replay the just-submitted composition after Enter. Ignore that
                // replay so the shell text stays committed, then force the IME to drop the
                // stale composing span that would otherwise keep the green overlay alive.
                restartInputSoon()
                return true
            }

            awaitingPostEnterCommitReplay = false
            postEnterSubmittedText = null
            if (committedText.isNotEmpty()) {
                if (previousComposingText.isNotEmpty()) {
                    sendBackspaces(previousComposingText.length)
                }
                keyboardHandler.onCommittedText(committedText)
            }
            composingText = ""
            editable?.clear()
            return true
        }

        private fun sendBackspaces(count: Int) {
            repeat(count.coerceAtLeast(0)) {
                sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            }
        }

        private fun sendTextInput(text: String) {
            if (text.isNotEmpty()) {
                keyboardHandler.onTextInput(text.toByteArray(Charsets.UTF_8))
            }
        }

        /**
         * Drop in-flight composition tracking without touching the terminal
         * output. Called from [ImeInputView.resetImeBuffer] so an interrupted
         * composition (e.g. external hardware-key events, IME dismissal mid-
         * conversion) doesn't leave stale length state that a subsequent
         * [setComposingText] would compute its backspace count against.
         */
        fun resetComposition() {
            composingText = ""
        }
    }

    companion object {
        /** Upper bound on [InputConnection.deleteSurroundingText]'s `leftLength`. */
        private const val MAX_DELETE_SURROUNDING = 4096
    }
}
