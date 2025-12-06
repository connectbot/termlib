package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellIntegrationTest {
    @Test
    fun testOsc133PromptMarker() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 10,
            initialCols = 40
        )

        // Send OSC 133;A (prompt start)
        // "user@host"
        // OSC 133;B (command input start)
        // Effectively marks "user@host" as the prompt

        val promptText = "user@host"
        val input = "\u001B]133;A\u001B\\$promptText\u001B]133;B\u001B\\"

        emulator.writeInput(input.toByteArray())

        // Verify the line is marked as PROMPT

        val snapshot = (emulator as TerminalEmulatorImpl).let {
            it.processPendingUpdates()
            it.snapshot.value
        }
        val promptLine = snapshot.lines.firstOrNull { it.hasPrompt() }

        assertNotNull("Expected a line marked as PROMPT", promptLine)

        // Verify specific segment
        val segments = promptLine!!.semanticSegments
        assertEquals(1, segments.size)
        assertEquals(SemanticType.PROMPT, segments[0].semanticType)
        assertEquals(0, segments[0].startCol)
        assertEquals(promptText.length, segments[0].endCol)
    }

    @Test
    fun testOsc133CommandFinished() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 10,
            initialCols = 40
        )

        // Send OSC 133;D;42 (command finished with exit code 42)
        emulator.writeInput("\u001B]133;D;42\u001B\\".toByteArray())

        // Verify metadata contains exit code
        val snapshot = (emulator as TerminalEmulatorImpl).let {
            it.processPendingUpdates()
            it.snapshot.value
        }
        val finishedLine = snapshot.lines.firstOrNull {
            it.getSegmentsOfType(SemanticType.COMMAND_FINISHED).isNotEmpty()
        }

        assertNotNull("Expected a line with COMMAND_FINISHED", finishedLine)

        val segment = finishedLine!!.getSegmentsOfType(SemanticType.COMMAND_FINISHED).first()
        assertEquals("42", segment.metadata)
    }

    @Test
    fun testOsc1337Annotation() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 10,
            initialCols = 40
        )

        // Send OSC 1337;AddAnnotation=Hello World
        val annotationMsg = "Hello World"
        emulator.writeInput("\u001B]1337;AddAnnotation=$annotationMsg\u001B\\".toByteArray())
        // Verify annotation
        val snapshot = (emulator as TerminalEmulatorImpl).let {
            it.processPendingUpdates()
            it.snapshot.value
        }
        val annotatedLine = snapshot.lines.firstOrNull {
            it.getSegmentsOfType(SemanticType.ANNOTATION).isNotEmpty()
        }

        assertNotNull("Expected a line with ANNOTATION", annotatedLine)

        val segment = annotatedLine!!.getSegmentsOfType(SemanticType.ANNOTATION).first()
        assertEquals(annotationMsg, segment.metadata)
    }
}
