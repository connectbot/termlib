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
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
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

        // Configure keyboard type:
        // - TYPE_CLASS_TEXT: Standard text input class
        // Note: TYPE_TEXT_FLAG_NO_SUGGESTIONS is intentionally omitted because Gboard
        // hides its entire suggestion strip when that flag is set, and the voice input
        // microphone button lives on the suggestion strip. Since our InputConnection
        // doesn't maintain real text state, suggestions will be minimal.
        // Password variations are also omitted to allow Gboard voice input.
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT

        // Report valid selection state so IMEs know this is a capable text field.
        // Without this, defaults are -1 which signals no selection support.
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0

        // fullEditor=true makes BaseInputConnection manage an internal Editable, so
        // getExtractedText() returns a valid object instead of null. Gboard checks this
        // and disables voice input when it gets null.
        return TerminalInputConnection(this, true).also { activeConnection = it }
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
        activeConnection?.editable?.clear()
        inputMethodManager.updateSelection(this, 0, 0, -1, -1)
    }

    /**
     * Custom InputConnection that handles backspace and other special keys for terminal input.
     */
    private inner class TerminalInputConnection(
        targetView: View,
        fullEditor: Boolean
    ) : BaseInputConnection(targetView, fullEditor) {

        private var composingText: String = ""

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val newText = text?.toString() ?: ""
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

            // Delete multiple characters (leftLength backspaces)
            for (i in 0 until leftLength) {
                sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            }

            // TODO: Implement forward delete if rightLength > 0
            if (leftLength > 0 && composingText.isNotEmpty()) {
                val newLength = (composingText.length - leftLength).coerceAtLeast(0)
                composingText = composingText.substring(0, newLength)
            }

            super.deleteSurroundingText(leftLength, rightLength)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val result = this@ImeInputView.dispatchKeyEvent(event)
            // After any key event, clear the IME's text buffer and reset the selection to (0,0).
            // This prevents Gboard from accumulating terminal input into its suggestion context
            // (e.g. treating "git status<enter>ls -l" as a single suggestion candidate).
            if (event.action == KeyEvent.ACTION_DOWN) {
                editable?.clear()
                inputMethodManager.updateSelection(this@ImeInputView, 0, 0, -1, -1)
            }
            return result
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val committedText = text?.toString() ?: ""
            super.commitText(text, newCursorPosition)

            if (committedText.isNotEmpty()) {
                if (composingText.isNotEmpty()) {
                    sendBackspaces(composingText.length)
                }
                sendTextInput(committedText)
            }
            composingText = ""
            // Clear the internal Editable to prevent unbounded accumulation
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
    }
}
