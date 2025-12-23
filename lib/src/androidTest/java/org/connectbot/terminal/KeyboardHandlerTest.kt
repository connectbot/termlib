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

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardHandlerTest {
    private lateinit var terminalEmulator: TerminalEmulator
    private lateinit var keyboardHandler: KeyboardHandler
    private var inputProcessedCallCount = 0

    @Before
    fun setup() {
        terminalEmulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80
        )
        keyboardHandler = KeyboardHandler(terminalEmulator)
        inputProcessedCallCount = 0
    }

    @Test
    fun testOnInputProcessedCalledOnKeyEvent() {
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        val keyEvent = createKeyEvent(Key.A, KeyEventType.KeyDown)
        val handled = keyboardHandler.onKeyEvent(keyEvent)

        assertTrue(handled)
        assertEquals(1, inputProcessedCallCount)
    }

    @Test
    fun testOnInputProcessedCalledOnSpecialKey() {
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        val keyEvent = createKeyEvent(Key.Enter, KeyEventType.KeyDown)
        val handled = keyboardHandler.onKeyEvent(keyEvent)

        assertTrue(handled)
        assertEquals(1, inputProcessedCallCount)
    }

    @Test
    fun testOnInputProcessedNotCalledOnKeyUp() {
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        val keyEvent = createKeyEvent(Key.A, KeyEventType.KeyUp)
        val handled = keyboardHandler.onKeyEvent(keyEvent)

        assertFalse(handled)
        assertEquals(0, inputProcessedCallCount)
    }

    @Test
    fun testOnInputProcessedCalledOnCharacterInput() {
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        val handled = keyboardHandler.onCharacterInput('a')

        assertTrue(handled)
        assertEquals(1, inputProcessedCallCount)
    }

    @Test
    fun testOnInputProcessedCalledOnTextInput() {
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        val text = "Hello".toByteArray(Charsets.UTF_8)
        keyboardHandler.onTextInput(text)

        assertEquals(1, inputProcessedCallCount)
    }

    @Test
    fun testOnInputProcessedNotCalledWhenNull() {
        // No callback set, should not throw
        val keyEvent = createKeyEvent(Key.A, KeyEventType.KeyDown)
        keyboardHandler.onKeyEvent(keyEvent)

        keyboardHandler.onCharacterInput('a')
        keyboardHandler.onTextInput("test".toByteArray(Charsets.UTF_8))

        // If we get here without exception, test passes
    }

    @Test
    fun testOnInputProcessedCalledMultipleTimes() {
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        // Simulate multiple key presses
        keyboardHandler.onKeyEvent(createKeyEvent(Key.A, KeyEventType.KeyDown))
        keyboardHandler.onKeyEvent(createKeyEvent(Key.B, KeyEventType.KeyDown))
        keyboardHandler.onKeyEvent(createKeyEvent(Key.C, KeyEventType.KeyDown))

        assertEquals(3, inputProcessedCallCount)
    }

    @Test
    fun testOnInputProcessedCalledOnceForTextInput() {
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        // Even though we're sending multiple characters, callback should be called once
        val text = "Hello World".toByteArray(Charsets.UTF_8)
        keyboardHandler.onTextInput(text)

        assertEquals(1, inputProcessedCallCount)
    }

    @Test
    fun testOnInputProcessedWithEmptyTextInput() {
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        val text = "".toByteArray(Charsets.UTF_8)
        keyboardHandler.onTextInput(text)

        // Should not be called for empty input
        assertEquals(0, inputProcessedCallCount)
    }

    @Test
    fun testOnInputProcessedCanBeReassigned() {
        var firstCallbackCalled = false
        var secondCallbackCalled = false

        keyboardHandler.onInputProcessed = { firstCallbackCalled = true }
        keyboardHandler.onKeyEvent(createKeyEvent(Key.A, KeyEventType.KeyDown))
        assertTrue(firstCallbackCalled)
        assertFalse(secondCallbackCalled)

        // Reassign callback
        keyboardHandler.onInputProcessed = { secondCallbackCalled = true }
        keyboardHandler.onKeyEvent(createKeyEvent(Key.B, KeyEventType.KeyDown))
        assertTrue(secondCallbackCalled)
    }

    @Test
    fun testOnInputProcessedWithAllInputMethods() {
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        // Test all three input methods
        keyboardHandler.onKeyEvent(createKeyEvent(Key.A, KeyEventType.KeyDown))
        keyboardHandler.onCharacterInput('b')
        keyboardHandler.onTextInput("c".toByteArray(Charsets.UTF_8))

        assertEquals(3, inputProcessedCallCount)
    }

    private fun createKeyEvent(key: Key, type: KeyEventType): KeyEvent {
        return KeyEvent(
            androidx.compose.ui.input.key.NativeKeyEvent(
                android.view.KeyEvent(
                    if (type == KeyEventType.KeyDown)
                        android.view.KeyEvent.ACTION_DOWN
                    else
                        android.view.KeyEvent.ACTION_UP,
                    keyToAndroidKeyCode(key)
                )
            )
        )
    }

    private fun keyToAndroidKeyCode(key: Key): Int {
        return when (key) {
            Key.A -> android.view.KeyEvent.KEYCODE_A
            Key.B -> android.view.KeyEvent.KEYCODE_B
            Key.C -> android.view.KeyEvent.KEYCODE_C
            Key.Enter -> android.view.KeyEvent.KEYCODE_ENTER
            Key.Spacebar -> android.view.KeyEvent.KEYCODE_SPACE
            Key.Backspace -> android.view.KeyEvent.KEYCODE_DEL
            Key.Tab -> android.view.KeyEvent.KEYCODE_TAB
            else -> android.view.KeyEvent.KEYCODE_UNKNOWN
        }
    }
}
