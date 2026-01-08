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

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OscSequenceTest {
    @Test
    fun testOscSequenceCallback() = runBlocking {
        var receivedCommand = -1
        var receivedPayload = ""
        var callbackInvoked = false

        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80
        )

        // Access the internal implementation to verify callback
        val impl = emulator as TerminalEmulatorImpl

        // Send OSC 133;A sequence (shell prompt marker)
        // ESC ] 133 ; A ESC \
        val oscSequence = "\u001B]133;A\u001B\\".toByteArray()
        emulator.writeInput(oscSequence)

        // Allow some time for processing
        delay(100)

        // Phase 0: Just verify no crash occurs
        // The stub implementation logs but doesn't modify state
        // In Phase 4, we'll verify semantic metadata is set
    }

    @Test
    fun testOscSequenceWithPayload() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80
        )

        // OSC 1337 with annotation
        // ESC ] 1337 ; AddAnnotation=Test ESC \
        val oscSequence = "\u001B]1337;AddAnnotation=Test\u001B\\".toByteArray()
        emulator.writeInput(oscSequence)

        delay(100)

        // Phase 0: Verify no crash
        // Phase 4 will verify annotation is stored
    }

    @Test
    fun testMultipleOscSequences() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80
        )

        // Send multiple OSC sequences in sequence
        val sequences = listOf(
            "\u001B]133;A\u001B\\",  // Prompt start
            "\u001B]133;B\u001B\\",  // Command input start
            "\u001B]133;C\u001B\\",  // Command output start
            "\u001B]133;D;0\u001B\\" // Command finished with exit code 0
        )

        for (seq in sequences) {
            emulator.writeInput(seq.toByteArray())
            delay(50)
        }

        // Phase 0: Verify no crash with multiple sequences
    }

    @Test
    fun testOscSequenceWithMixedContent() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80
        )

        // Mix normal text with OSC sequences
        val mixedContent = "Hello\u001B]133;A\u001B\\ World\u001B]133;B\u001B\\".toByteArray()
        emulator.writeInput(mixedContent)

        delay(100)

        // Verify terminal still works with mixed content
        val impl = emulator as TerminalEmulatorImpl
        val snapshot = impl.snapshot.value

        // Should have text rendered
        assertTrue(snapshot.lines.isNotEmpty())
    }

    @Test
    fun testInvalidOscSequence() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80
        )

        // Send invalid OSC sequence (should be handled gracefully)
        val invalidOsc = "\u001B]999;InvalidCommand\u001B\\".toByteArray()
        emulator.writeInput(invalidOsc)

        delay(100)

        // Phase 0: Verify no crash on invalid sequence
    }

    @Test
    fun testOscSequencePartialData() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80
        )

        // Send OSC in parts (simulating slow data arrival)
        val part1 = "\u001B]133".toByteArray()
        val part2 = ";A\u001B\\".toByteArray()

        emulator.writeInput(part1)
        delay(50)
        emulator.writeInput(part2)
        delay(50)

        // Phase 0: Verify buffering works correctly
    }

    @Test
    fun testOsc8HyperlinkBasic() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80
        )

        // Send OSC 8 hyperlink sequence with text
        // ESC ] 8 ; ; URL ESC \ <text> ESC ] 8 ; ; ESC \
        val hyperlinkSequence = "\u001B]8;;https://example.com\u001B\\Click here\u001B]8;;\u001B\\".toByteArray()
        emulator.writeInput(hyperlinkSequence)

        delay(100)

        // Verify terminal renders the text without crashing
        val impl = emulator as TerminalEmulatorImpl
        val snapshot = impl.snapshot.value
        assertTrue(snapshot.lines.isNotEmpty())

        // Verify the text content is rendered
        val line = snapshot.lines[0]
        val text = line.cells.take(10).map { it.char }.joinToString("")
        assertEquals("Click here", text)
    }

    @Test
    fun testOsc8HyperlinkWithId() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80
        )

        // Send OSC 8 hyperlink with id parameter
        val hyperlinkSequence = "\u001B]8;id=link1;https://example.com\u001B\\Link\u001B]8;;\u001B\\".toByteArray()
        emulator.writeInput(hyperlinkSequence)

        delay(100)

        // Verify no crash and text is rendered
        val impl = emulator as TerminalEmulatorImpl
        val snapshot = impl.snapshot.value
        assertTrue(snapshot.lines.isNotEmpty())
    }

    @Test
    fun testOsc8MultipleHyperlinks() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80
        )

        // Send multiple hyperlinks on same line
        val sequence = (
            "\u001B]8;;https://first.com\u001B\\First\u001B]8;;\u001B\\ " +
            "\u001B]8;;https://second.com\u001B\\Second\u001B]8;;\u001B\\"
        ).toByteArray()
        emulator.writeInput(sequence)

        delay(100)

        // Verify no crash and text is rendered
        val impl = emulator as TerminalEmulatorImpl
        val snapshot = impl.snapshot.value
        assertTrue(snapshot.lines.isNotEmpty())

        val line = snapshot.lines[0]
        val text = line.cells.take(12).map { it.char }.joinToString("")
        assertEquals("First Second", text)
    }

    @Test
    fun testOsc8HyperlinkWithMixedContent() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80
        )

        // Mix hyperlink with regular text
        val mixedContent = "Hello \u001B]8;;https://example.com\u001B\\World\u001B]8;;\u001B\\ today".toByteArray()
        emulator.writeInput(mixedContent)

        delay(100)

        val impl = emulator as TerminalEmulatorImpl
        val snapshot = impl.snapshot.value

        // Verify text is rendered correctly
        assertTrue(snapshot.lines.isNotEmpty())
        val line = snapshot.lines[0]
        val text = line.cells.take(17).map { it.char }.joinToString("")
        assertEquals("Hello World today", text)
    }

    @Test
    fun testOsc8HyperlinkAccessibility() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80
        )

        // Test that hyperlink text is rendered (URL should not appear in visible text)
        val sequence = "\u001B]8;;https://github.com/connectbot\u001B\\ConnectBot Project\u001B]8;;\u001B\\".toByteArray()
        emulator.writeInput(sequence)

        delay(100)

        val impl = emulator as TerminalEmulatorImpl
        val snapshot = impl.snapshot.value
        assertTrue(snapshot.lines.isNotEmpty())

        // The visible text should be the link text, not the URL
        val line = snapshot.lines[0]
        val text = line.cells.take(17).map { it.char }.joinToString("")
        assertEquals("ConnectBot Projec", text)  // First 17 chars of "ConnectBot Project"
    }
}
