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

import android.view.KeyCharacterMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.view.KeyEvent as AndroidKeyEvent

@RunWith(AndroidJUnit4::class)
class KeyboardHandlerTest {
    private lateinit var terminalEmulator: TerminalEmulator
    private lateinit var keyboardHandler: KeyboardHandler
    private var inputProcessedCallCount = 0

    @Before
    fun setup() {
        terminalEmulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
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

    // === DelKeyMode tests ===

    @Test
    fun testDeleteModeDefaultBackspaceSendsDel() {
        // Default mode (DelKeyMode.Delete): Key.Backspace → DEL (0x7f)
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator)

        handler.onKeyEvent(createKeyEvent(Key.Backspace, KeyEventType.KeyDown))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Expected DEL (0x7f) in default Delete mode", received.contains(0x7F.toByte()))
    }

    @Test
    fun testBackspaceModeBackspaceSendsCtrlH() {
        // DelKeyMode.Backspace: Key.Backspace → ^H (0x08)
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator)
        handler.delKeyMode = DelKeyMode.Backspace

        handler.onKeyEvent(createKeyEvent(Key.Backspace, KeyEventType.KeyDown))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Expected ^H (0x08) in Backspace mode", received.contains(0x08.toByte()))
        assertFalse("Should NOT send DEL (0x7f) in Backspace mode", received.contains(0x7F.toByte()))
    }

    @Test
    fun testBackspaceModeDeleteKeySendsDel() {
        // DelKeyMode.Backspace: Key.Delete → DEL (0x7f)
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator)
        handler.delKeyMode = DelKeyMode.Backspace

        val deleteKeyEvent = KeyEvent(
            NativeKeyEvent(
                AndroidKeyEvent(
                    AndroidKeyEvent.ACTION_DOWN,
                    AndroidKeyEvent.KEYCODE_FORWARD_DEL,
                ),
            ),
        )
        handler.onKeyEvent(deleteKeyEvent)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Expected DEL (0x7f) for Delete key in Backspace mode", received.contains(0x7F.toByte()))
    }

    private fun createKeyEvent(key: Key, type: KeyEventType): KeyEvent = KeyEvent(
        NativeKeyEvent(
            AndroidKeyEvent(
                if (type == KeyEventType.KeyDown) {
                    AndroidKeyEvent.ACTION_DOWN
                } else {
                    AndroidKeyEvent.ACTION_UP
                },
                keyToAndroidKeyCode(key),
            ),
        ),
    )

    // === Compose Mode Tests ===

    @Test
    fun testComposeModeInterceptsKeyEvent() {
        val composeMode = ComposeMode()
        composeMode.activate()
        keyboardHandler.composeMode = composeMode

        val handled = keyboardHandler.onKeyEvent(createKeyEvent(Key.A, KeyEventType.KeyDown))

        assertTrue(handled)
        assertEquals("a", composeMode.buffer)
    }

    @Test
    fun testComposeModeInterceptsMultipleKeys() {
        val composeMode = ComposeMode()
        composeMode.activate()
        keyboardHandler.composeMode = composeMode

        keyboardHandler.onKeyEvent(createKeyEvent(Key.A, KeyEventType.KeyDown))
        keyboardHandler.onKeyEvent(createKeyEvent(Key.B, KeyEventType.KeyDown))
        keyboardHandler.onKeyEvent(createKeyEvent(Key.C, KeyEventType.KeyDown))

        assertEquals("abc", composeMode.buffer)
    }

    @Test
    fun testComposeModeBackspace() {
        val composeMode = ComposeMode()
        composeMode.activate()
        keyboardHandler.composeMode = composeMode
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        keyboardHandler.onKeyEvent(createKeyEvent(Key.A, KeyEventType.KeyDown))
        keyboardHandler.onKeyEvent(createKeyEvent(Key.B, KeyEventType.KeyDown))
        keyboardHandler.onKeyEvent(createKeyEvent(Key.Backspace, KeyEventType.KeyDown))

        assertEquals("a", composeMode.buffer)
        // Backspace with non-empty buffer just edits the buffer; no terminal dispatch.
        assertEquals(0, inputProcessedCallCount)
    }

    @Test
    fun testComposeModeBackspacePassesThroughWhenBufferEmpty() {
        val composeMode = ComposeMode()
        composeMode.activate()
        keyboardHandler.composeMode = composeMode
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        keyboardHandler.onKeyEvent(createKeyEvent(Key.Backspace, KeyEventType.KeyDown))

        assertTrue(composeMode.isActive)
        assertEquals("", composeMode.buffer)
        // Backspace with empty buffer is dispatched to the terminal as a real Backspace.
        assertEquals(1, inputProcessedCallCount)
    }

    @Test
    fun testComposeModeBackspacePassthroughRespectsDelKeyModeBackspace() {
        // When compose buffer is empty and DelKeyMode.Backspace is active, the pass-through
        // Backspace must send ^H (0x08), not DEL (0x7f).
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator)
        handler.delKeyMode = DelKeyMode.Backspace
        val composeMode = ComposeMode()
        composeMode.activate()
        handler.composeMode = composeMode

        handler.onKeyEvent(createKeyEvent(Key.Backspace, KeyEventType.KeyDown))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertTrue("Expected ^H (0x08) in Backspace mode", received.contains(0x08.toByte()))
        assertFalse("Should NOT send DEL (0x7f) in Backspace mode", received.contains(0x7F.toByte()))
    }

    @Test
    fun testComposeModeEscapeCancelsCompositionInProgress() {
        val composeMode = ComposeMode()
        composeMode.activate()
        keyboardHandler.composeMode = composeMode
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        keyboardHandler.onKeyEvent(createKeyEvent(Key.A, KeyEventType.KeyDown))
        keyboardHandler.onKeyEvent(createKeyEvent(Key.Escape, KeyEventType.KeyDown))

        assertTrue(composeMode.isActive)
        assertEquals("", composeMode.buffer)
        // Esc with non-empty buffer just cancels the composition; no terminal dispatch.
        assertEquals(0, inputProcessedCallCount)
    }

    @Test
    fun testComposeModeEscapePassesThroughWhenBufferEmpty() {
        val composeMode = ComposeMode()
        composeMode.activate()
        keyboardHandler.composeMode = composeMode
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        keyboardHandler.onKeyEvent(createKeyEvent(Key.Escape, KeyEventType.KeyDown))

        assertTrue(composeMode.isActive)
        assertEquals("", composeMode.buffer)
        // Esc with empty buffer is dispatched to the terminal as a real Escape key.
        assertEquals(1, inputProcessedCallCount)
    }

    @Test
    fun testComposeModeEnterCommits() {
        val composeMode = ComposeMode()
        composeMode.activate()
        keyboardHandler.composeMode = composeMode
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        keyboardHandler.onKeyEvent(createKeyEvent(Key.A, KeyEventType.KeyDown))
        keyboardHandler.onKeyEvent(createKeyEvent(Key.B, KeyEventType.KeyDown))
        keyboardHandler.onKeyEvent(createKeyEvent(Key.Enter, KeyEventType.KeyDown))

        assertTrue(composeMode.isActive)
        assertEquals("", composeMode.buffer)
        assertEquals(1, inputProcessedCallCount)
    }

    @Test
    fun testComposeModeDoesNotInterceptWhenInactive() {
        val composeMode = ComposeMode()
        keyboardHandler.composeMode = composeMode
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        val handled = keyboardHandler.onKeyEvent(createKeyEvent(Key.A, KeyEventType.KeyDown))

        assertTrue(handled)
        assertEquals("", composeMode.buffer)
        assertEquals(1, inputProcessedCallCount)
    }

    @Test
    fun testComposeModeInterceptsCharacterInput() {
        val composeMode = ComposeMode()
        composeMode.activate()
        keyboardHandler.composeMode = composeMode

        val handled = keyboardHandler.onCharacterInput('x')

        assertTrue(handled)
        assertEquals("x", composeMode.buffer)
    }

    @Test
    fun testComposeModeInterceptsTextInput() {
        val composeMode = ComposeMode()
        composeMode.activate()
        keyboardHandler.composeMode = composeMode

        keyboardHandler.onTextInput("hello".toByteArray(Charsets.UTF_8))

        assertEquals("hello", composeMode.buffer)
    }

    @Test
    fun testComposeModeTextInputDoesNotDispatchToTerminal() {
        val composeMode = ComposeMode()
        composeMode.activate()
        keyboardHandler.composeMode = composeMode
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        keyboardHandler.onTextInput("hello".toByteArray(Charsets.UTF_8))

        // onInputProcessed should NOT be called when compose mode intercepts
        assertEquals(0, inputProcessedCallCount)
    }

    @Test
    fun testComposeModeCharacterInputDoesNotCallOnInputProcessed() {
        val composeMode = ComposeMode()
        composeMode.activate()
        keyboardHandler.composeMode = composeMode
        keyboardHandler.onInputProcessed = { inputProcessedCallCount++ }

        keyboardHandler.onCharacterInput('a')

        // onInputProcessed should NOT be called when compose mode intercepts
        assertEquals(0, inputProcessedCallCount)
    }

    // === Dead Key Tests ===

    // Fake keycodes used exclusively in dead-key tests.
    private val FAKE_DEAD_GRAVE = 900 // returns COMBINING_ACCENT | '`' (U+0060, grave accent)
    private val FAKE_KEY_A = 901 // returns 'a' — combines with grave → 'à' (U+00E0)
    private val FAKE_KEY_B = 902 // returns 'b' — does NOT combine with grave
    private val FAKE_ACCENT_GRAVE = '`'.code // spacing form of the grave accent

    /**
     * A [KeyboardHandler.unicodeCharLookup] that simulates a keyboard with a dead grave key.
     *
     * Keycodes:
     *  - [FAKE_DEAD_GRAVE] → COMBINING_ACCENT | grave (dead key)
     *  - [FAKE_KEY_A]      → 'a' (combines with grave → 'à')
     *  - [FAKE_KEY_B]      → 'b' (no composition with grave)
     *  - [AndroidKeyEvent.KEYCODE_A] → 'a' (used by non-dead-key tests)
     *  - all others        → 0
     */
    private val fakeDeadKeyLookup: (Int, Int, Int) -> Int = { _, keyCode, _ ->
        when (keyCode) {
            FAKE_DEAD_GRAVE -> KeyCharacterMap.COMBINING_ACCENT or FAKE_ACCENT_GRAVE
            FAKE_KEY_A -> 'a'.code
            FAKE_KEY_B -> 'b'.code
            AndroidKeyEvent.KEYCODE_A -> 'a'.code
            else -> 0
        }
    }

    /** Creates a KeyEvent directly from an Android keycode (no Compose Key mapping needed). */
    private fun createRawKeyEvent(keycode: Int): KeyEvent = KeyEvent(
        NativeKeyEvent(
            AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, keycode),
        ),
    )

    /**
     * Send [events] through a fresh KeyboardHandler (with [lookup] installed) and return
     * the bytes written to the PTY decoded as a UTF-8 string.
     */
    private fun collectOutput(events: List<KeyEvent>, lookup: (Int, Int, Int) -> Int = fakeDeadKeyLookup): String {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator, unicodeCharLookup = lookup)
        for (event in events) handler.onKeyEvent(event)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
    }

    @Test
    fun testDeadKeyFollowedByCombinableBaseProducesComposedChar() {
        // dead grave + 'a' → 'à' (U+00E0)
        val expected = KeyCharacterMap.getDeadChar(FAKE_ACCENT_GRAVE, 'a'.code)

        val output = collectOutput(
            listOf(
                createRawKeyEvent(FAKE_DEAD_GRAVE),
                createRawKeyEvent(FAKE_KEY_A),
            ),
        )

        assertEquals(String(Character.toChars(expected)), output)
    }

    @Test
    fun testDeadKeyFollowedByNonCombinableBaseEmitsBothChars() {
        // dead grave + 'b' → no composition: emit '`' then 'b'
        val combined = KeyCharacterMap.getDeadChar(FAKE_ACCENT_GRAVE, 'b'.code)
        assumeTrue("grave + b unexpectedly combines", combined == 0)

        val output = collectOutput(
            listOf(
                createRawKeyEvent(FAKE_DEAD_GRAVE),
                createRawKeyEvent(FAKE_KEY_B),
            ),
        )

        assertEquals("`b", output)
    }

    @Test
    fun testSameDeadKeyTwiceEmitsSpacingAccent() {
        // dead grave + dead grave → '`'
        val output = collectOutput(
            listOf(
                createRawKeyEvent(FAKE_DEAD_GRAVE),
                createRawKeyEvent(FAKE_DEAD_GRAVE),
            ),
        )

        assertEquals("`", output)
    }

    @Test
    fun testDeadKeyStateClearedByNonPrintableKey() {
        // dead grave then Enter: accent is discarded; 'a' after should be plain 'a'
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator, unicodeCharLookup = fakeDeadKeyLookup)
        handler.onKeyEvent(createRawKeyEvent(FAKE_DEAD_GRAVE))
        handler.onKeyEvent(createRawKeyEvent(AndroidKeyEvent.KEYCODE_ENTER))
        handler.onKeyEvent(createRawKeyEvent(AndroidKeyEvent.KEYCODE_A))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val allOutput = outputs.flatMap { it.toList() }.toByteArray()
        val text = allOutput.filter { it >= 0x20 }.toByteArray().toString(Charsets.UTF_8)
        assertEquals("a", text)
    }

    @Test
    fun testDeadKeyInComposeModeProducesComposedChar() {
        // dead grave + 'a' → 'à' in compose buffer
        val expected = KeyCharacterMap.getDeadChar(FAKE_ACCENT_GRAVE, 'a'.code)

        val composeMode = ComposeMode()
        composeMode.activate()
        keyboardHandler.unicodeCharLookup = fakeDeadKeyLookup
        keyboardHandler.composeMode = composeMode

        keyboardHandler.onKeyEvent(createRawKeyEvent(FAKE_DEAD_GRAVE))
        keyboardHandler.onKeyEvent(createRawKeyEvent(FAKE_KEY_A))

        assertEquals(String(Character.toChars(expected)), composeMode.buffer)
    }

    /** Creates a KeyEvent with an explicit [deviceId] for testing device-specific lookup. */
    private fun createRawKeyEventWithDeviceId(keycode: Int, deviceId: Int): KeyEvent = KeyEvent(
        NativeKeyEvent(
            AndroidKeyEvent(
                /* downTime = */
                0L,
                /* eventTime = */
                0L,
                AndroidKeyEvent.ACTION_DOWN,
                keycode,
                /* repeat = */
                0,
                /* metaState = */
                0,
                deviceId,
                /* scancode = */
                0,
            ),
        ),
    )

    @Test
    fun testUnicodeCharLookupReceivesDeviceIdFromKeyEvent() {
        val expectedDeviceId = 42
        var capturedDeviceId: Int? = null

        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
        )
        val handler = KeyboardHandler(emulator) { deviceId, keyCode, metaState ->
            capturedDeviceId = deviceId
            if (keyCode == AndroidKeyEvent.KEYCODE_A) 'a'.code else 0
        }
        handler.onKeyEvent(createRawKeyEventWithDeviceId(AndroidKeyEvent.KEYCODE_A, expectedDeviceId))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertEquals(expectedDeviceId, capturedDeviceId)
    }

    // === Swiss German APOSTROPHE key tests ===
    //
    // KCM entry for reference:
    //   key APOSTROPHE { base: 'ä', shift: 'à', capslock: 'Ä', capslock+shift: 'À', ralt: '{' }

    private val swissGermanApostropheKeycode = AndroidKeyEvent.KEYCODE_APOSTROPHE

    private val swissGermanKcmLookup: (Int, Int, Int) -> Int = { _, keyCode, metaState ->
        if (keyCode == swissGermanApostropheKeycode) {
            val shift = metaState and AndroidKeyEvent.META_SHIFT_ON != 0
            val caps = metaState and AndroidKeyEvent.META_CAPS_LOCK_ON != 0
            val rightAlt = metaState and AndroidKeyEvent.META_ALT_RIGHT_ON != 0
            when {
                rightAlt -> '{'.code

                caps && shift -> '\u00C0'.code

                // À
                caps -> '\u00C4'.code

                // Ä
                shift -> '\u00E0'.code

                // à
                else -> '\u00E4'.code // ä
            }
        } else {
            0
        }
    }

    private fun createKeyEventWithMeta(keycode: Int, metaState: Int): KeyEvent = KeyEvent(
        NativeKeyEvent(
            AndroidKeyEvent(
                /* downTime = */
                0L,
                /* eventTime = */
                0L,
                AndroidKeyEvent.ACTION_DOWN,
                keycode,
                /* repeat = */
                0,
                metaState,
                /* deviceId = */
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                /* scancode = */
                0,
            ),
        ),
    )

    private fun collectOutputWithMeta(events: List<KeyEvent>, rightAltMode: RightAltMode = RightAltMode.CharacterModifier): String {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator, unicodeCharLookup = swissGermanKcmLookup, rightAltMode = rightAltMode)
        for (event in events) handler.onKeyEvent(event)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return outputs.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8)
    }

    @Test
    fun testSwissGermanApostropheBaseProducesAe() {
        val output = collectOutputWithMeta(
            listOf(
                createKeyEventWithMeta(swissGermanApostropheKeycode, 0),
            ),
        )
        assertEquals("ä", output)
    }

    @Test
    fun testSwissGermanApostropheShiftProducesAGrave() {
        val output = collectOutputWithMeta(
            listOf(
                createKeyEventWithMeta(swissGermanApostropheKeycode, AndroidKeyEvent.META_SHIFT_ON),
            ),
        )
        assertEquals("à", output)
    }

    @Test
    fun testSwissGermanApostropheCapslockProducesUpperAe() {
        val output = collectOutputWithMeta(
            listOf(
                createKeyEventWithMeta(swissGermanApostropheKeycode, AndroidKeyEvent.META_CAPS_LOCK_ON),
            ),
        )
        assertEquals("Ä", output)
    }

    @Test
    fun testSwissGermanApostropheCapslockShiftProducesUpperAGrave() {
        val output = collectOutputWithMeta(
            listOf(
                createKeyEventWithMeta(
                    swissGermanApostropheKeycode,
                    AndroidKeyEvent.META_CAPS_LOCK_ON or AndroidKeyEvent.META_SHIFT_ON,
                ),
            ),
        )
        assertEquals("À", output)
    }

    @Test
    fun testSwissGermanApostropheRaltProducesOpenBrace() {
        val output = collectOutputWithMeta(
            listOf(
                createKeyEventWithMeta(swissGermanApostropheKeycode, AndroidKeyEvent.META_ALT_RIGHT_ON),
            ),
        )
        assertEquals("{", output)
    }

    @Test
    fun testSwissGermanApostropheRaltAsMetaProducesEscapePrefix() {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(
            emulator,
            unicodeCharLookup = swissGermanKcmLookup,
            rightAltMode = RightAltMode.Meta,
        )
        handler.onKeyEvent(
            createKeyEventWithMeta(
                swissGermanApostropheKeycode,
                AndroidKeyEvent.META_ALT_RIGHT_ON or AndroidKeyEvent.META_ALT_ON,
            ),
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val bytes = outputs.flatMap { it.toList() }.toByteArray()
        // ESC prefix (0x1B) followed by UTF-8 encoding of 'ä' (0xC3 0xA4)
        val expected = byteArrayOf(0x1B, 0xC3.toByte(), 0xA4.toByte())
        assertEquals(expected.toList(), bytes.toList())
    }

    @Test
    fun testDeadKeyReturnsTrue() {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
        )
        val handler = KeyboardHandler(emulator, unicodeCharLookup = fakeDeadKeyLookup)
        val handled = handler.onKeyEvent(createRawKeyEvent(FAKE_DEAD_GRAVE))
        assertTrue("Dead key should be handled by KeyboardHandler", handled)
    }

    @Test
    fun testLargeDeadKeyFollowedByNonCombinableBaseEmitsBothChars() {
        val largeAccent = 0x1AB0
        val fakeLargeDeadKeyLookup: (Int, Int, Int) -> Int = { _, keyCode, _ ->
            when (keyCode) {
                FAKE_DEAD_GRAVE -> KeyCharacterMap.COMBINING_ACCENT or largeAccent
                FAKE_KEY_B -> 'b'.code
                else -> 0
            }
        }
        val output = collectOutput(
            listOf(
                createRawKeyEvent(FAKE_DEAD_GRAVE),
                createRawKeyEvent(FAKE_KEY_B),
            ),
            lookup = fakeLargeDeadKeyLookup,
        )
        assertEquals(String(Character.toChars(largeAccent)) + "b", output)
    }

    // === Newline (0x0A) handling tests ===

    private fun collectCharacterOutput(block: (KeyboardHandler) -> Unit): ByteArray {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val handler = KeyboardHandler(emulator)
        block(handler)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return outputs.flatMap { it.toList() }.toByteArray()
    }

    @Test
    fun testCharacterInputNewlineWithoutCtrlSendsEnter() {
        // '\n' from keyboard (no ctrl) → VTermKey.ENTER, same as pressing the Enter key
        val withEnterKey = collectCharacterOutput { it.onKeyEvent(createKeyEvent(Key.Enter, KeyEventType.KeyDown)) }
        val withNewline = collectCharacterOutput { it.onCharacterInput('\n', ctrl = false) }
        assertEquals(withEnterKey.toList(), withNewline.toList())
    }

    @Test
    fun testCharacterInputNewlineWithCtrlSendsCtrlEnter() {
        // '\n' with ctrl → dispatchKey(CTRL, VTermKey.ENTER), not raw LF (0x0A)
        val received = collectCharacterOutput { it.onCharacterInput('\n', ctrl = true) }
        assertFalse("Ctrl+newline should not send bare LF (0x0A)", received.contentEquals(byteArrayOf(0x0A)))
        assertTrue("Ctrl+newline should produce output", received.isNotEmpty())
    }

    @Test
    fun testTextInputNewlineWithoutStickyCtrlSendsEnter() {
        // '\n' in text input (no sticky ctrl) → VTermKey.ENTER, same as pressing the Enter key
        val withEnterKey = collectCharacterOutput { it.onKeyEvent(createKeyEvent(Key.Enter, KeyEventType.KeyDown)) }
        val withNewline = collectCharacterOutput { it.onTextInput("\n".toByteArray(Charsets.UTF_8)) }
        assertEquals(withEnterKey.toList(), withNewline.toList())
    }

    @Test
    fun testTextInputNewlineWithStickyCtrlSendsCtrlEnter() {
        val outputs = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { data -> outputs.add(data.copyOf()) },
        )
        val modifierManager = object : ModifierManager {
            override fun isCtrlActive() = true
            override fun isAltActive() = false
            override fun isShiftActive() = false
            override fun clearTransients() {}
        }
        val handler = KeyboardHandler(emulator, modifierManager = modifierManager)

        handler.onTextInput("\n".toByteArray(Charsets.UTF_8))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // With sticky Ctrl active, '\n' → dispatchKey(CTRL, ENTER), NOT raw 0x0A
        val received = outputs.flatMap { it.toList() }.toByteArray()
        assertFalse("Sticky Ctrl+newline should not send bare LF", received.contentEquals(byteArrayOf(0x0A)))
        assertTrue("Sticky Ctrl+newline should produce output", received.isNotEmpty())
    }

    private fun keyToAndroidKeyCode(key: Key): Int = when (key) {
        Key.A -> AndroidKeyEvent.KEYCODE_A
        Key.B -> AndroidKeyEvent.KEYCODE_B
        Key.C -> AndroidKeyEvent.KEYCODE_C
        Key.Enter -> AndroidKeyEvent.KEYCODE_ENTER
        Key.Spacebar -> AndroidKeyEvent.KEYCODE_SPACE
        Key.Backspace -> AndroidKeyEvent.KEYCODE_DEL
        Key.Tab -> AndroidKeyEvent.KEYCODE_TAB
        Key.Escape -> AndroidKeyEvent.KEYCODE_ESCAPE
        else -> AndroidKeyEvent.KEYCODE_UNKNOWN
    }
}
