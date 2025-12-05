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

/**
 * A minimal invisible View that provides proper IME input handling for terminal emulation.
 *
 * This view creates a custom InputConnection that:
 * - Handles backspace via deleteSurroundingText by sending KEYCODE_DEL
 * - Handles enter/return keys properly via sendKeyEvent
 * - Configures the keyboard as password-type to show number rows
 * - Disables text suggestions and autocorrect
 *
 * Based on the ConnectBot v1.9.13 TerminalView implementation.
 */
internal class ImeInputView(
    context: Context,
    private val keyboardHandler: KeyboardHandler
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Configure IME options
        outAttrs.imeOptions = outAttrs.imeOptions or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_ENTER_ACTION or
                EditorInfo.IME_ACTION_NONE

        // Configure keyboard type:
        // - TYPE_TEXT_VARIATION_PASSWORD: Shows password-style keyboard with number rows
        // - TYPE_TEXT_VARIATION_VISIBLE_PASSWORD: Keeps text visible (we handle display ourselves)
        // - TYPE_TEXT_FLAG_NO_SUGGESTIONS: Disables autocomplete/suggestions
        // - TYPE_NULL: No special input processing
        outAttrs.inputType = EditorInfo.TYPE_NULL or
                EditorInfo.TYPE_TEXT_VARIATION_PASSWORD or
                EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        return TerminalInputConnection(this, false)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    /**
     * Custom InputConnection that handles backspace and other special keys for terminal input.
     */
    private inner class TerminalInputConnection(
        targetView: View,
        fullEditor: Boolean
    ) : BaseInputConnection(targetView, fullEditor) {

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
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            // Let the view's key listener handle the event
            return this@ImeInputView.dispatchKeyEvent(event)
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text.isNullOrEmpty()) {
                return true
            }

            // Send text input to the terminal
            val bytes = text.toString().toByteArray(Charsets.UTF_8)
            keyboardHandler.onTextInput(bytes)
            return true
        }
    }
}
