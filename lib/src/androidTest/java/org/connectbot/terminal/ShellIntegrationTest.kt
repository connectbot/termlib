package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellIntegrationTest {

    // OSC 8 escape sequence helpers
    private fun osc8Start(url: String, id: String? = null): String {
        val params = if (id != null) "id=$id" else ""
        return "\u001B]8;$params;$url\u001B\\"
    }

    private fun osc8End(): String = "\u001B]8;;\u001B\\"
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

    @Test
    fun testOsc8BasicHyperlink() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 10,
            initialCols = 40
        )

        // Print: "Click [here](https://example.com) for info"
        val url = "https://example.com"
        val linkText = "here"
        val input = "Click ${osc8Start(url)}$linkText${osc8End()} for info"

        emulator.writeInput(input.toByteArray())

        val snapshot = (emulator as TerminalEmulatorImpl).let {
            it.processPendingUpdates()
            it.snapshot.value
        }

        // Hyperlink should be on row 0
        val hyperlinkSegments = snapshot.lines[0].getSegmentsOfType(SemanticType.HYPERLINK)
        assertEquals("Expected 1 hyperlink segment on row 0", 1, hyperlinkSegments.size)

        val segment = hyperlinkSegments[0]
        assertEquals(url, segment.metadata)
        assertEquals(6, segment.startCol)  // "Click " is 6 chars
        assertEquals(6 + linkText.length, segment.endCol)

        // Other rows should have no hyperlinks
        for (row in 1 until snapshot.lines.size) {
            val segments = snapshot.lines[row].getSegmentsOfType(SemanticType.HYPERLINK)
            assertTrue("Row $row should have no hyperlinks", segments.isEmpty())
        }
    }

    @Test
    fun testOsc8MultipleHyperlinksOnDifferentLines() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 10,
            initialCols = 40
        )

        // Simulate output like test_osc8.sh:
        // Line 0: "=== OSC8 Hyperlink Test ==="
        // Line 1: ""
        // Line 2: "1. Google: [Google](https://google.com)"
        // Line 3: "2. GitHub: [GitHub](https://github.com)"
        // Line 4: ""
        // Line 5: "End of test"

        val impl = emulator as TerminalEmulatorImpl

        // Use \r\n to ensure cursor column resets to 0 after each line
        impl.writeInput("=== OSC8 Hyperlink Test ===\r\n\r\n".toByteArray())
        impl.processPendingUpdates()

        // Write Google line with hyperlink
        impl.writeInput("1. Google: ${osc8Start("https://google.com")}Google${osc8End()}\r\n".toByteArray())
        impl.processPendingUpdates()

        // Write GitHub line with hyperlink
        impl.writeInput("2. GitHub: ${osc8Start("https://github.com")}GitHub${osc8End()}\r\n".toByteArray())
        impl.processPendingUpdates()

        impl.writeInput("\r\nEnd of test\r\n".toByteArray())
        impl.processPendingUpdates()

        val snapshot = (emulator as TerminalEmulatorImpl).let {
            it.processPendingUpdates()
            it.snapshot.value
        }

        // Row 0: No hyperlinks (just header text)
        assertTrue(
            "Row 0 should have no hyperlinks",
            snapshot.lines[0].getSegmentsOfType(SemanticType.HYPERLINK).isEmpty()
        )

        // Row 1: Empty line, no hyperlinks
        assertTrue(
            "Row 1 should have no hyperlinks",
            snapshot.lines[1].getSegmentsOfType(SemanticType.HYPERLINK).isEmpty()
        )

        // Row 2: Google hyperlink
        val row2Segments = snapshot.lines[2].getSegmentsOfType(SemanticType.HYPERLINK)
        assertEquals("Row 2 should have 1 hyperlink", 1, row2Segments.size)
        assertEquals("https://google.com", row2Segments[0].metadata)

        // Row 3: GitHub hyperlink
        val row3Segments = snapshot.lines[3].getSegmentsOfType(SemanticType.HYPERLINK)
        assertEquals("Row 3 should have 1 hyperlink", 1, row3Segments.size)
        assertEquals("https://github.com", row3Segments[0].metadata)

        // Row 4: Empty line, no hyperlinks
        assertTrue(
            "Row 4 should have no hyperlinks",
            snapshot.lines[4].getSegmentsOfType(SemanticType.HYPERLINK).isEmpty()
        )

        // Row 5: "End of test", no hyperlinks
        assertTrue(
            "Row 5 should have no hyperlinks",
            snapshot.lines[5].getSegmentsOfType(SemanticType.HYPERLINK).isEmpty()
        )

        // Remaining rows should also have no hyperlinks
        for (row in 6 until snapshot.lines.size) {
            assertTrue(
                "Row $row should have no hyperlinks",
                snapshot.lines[row].getSegmentsOfType(SemanticType.HYPERLINK).isEmpty()
            )
        }
    }

    @Test
    fun testOsc8HyperlinksWithScrolling() = runBlocking {
        // Use a small terminal (5 rows) to force scrolling
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 5,
            initialCols = 40
        )

        // Output 7 lines to force scrolling (2 lines will scroll off)
        // Line 0: "Line 1" (will scroll to scrollback)
        // Line 1: "Line 2" (will scroll to scrollback)
        // Line 2: "[Link1](url1)"
        // Line 3: "Plain text"
        // Line 4: "[Link2](url2)"
        // Line 5: "More text"
        // Line 6: "[Link3](url3)"

        // Write each line separately with \r\n to ensure proper cursor positioning
        val impl = emulator as TerminalEmulatorImpl

        impl.writeInput("Line 1\r\n".toByteArray())
        impl.processPendingUpdates()

        impl.writeInput("Line 2\r\n".toByteArray())
        impl.processPendingUpdates()

        impl.writeInput("${osc8Start("https://link1.com")}Link1${osc8End()}\r\n".toByteArray())
        impl.processPendingUpdates()

        impl.writeInput("Plain text\r\n".toByteArray())
        impl.processPendingUpdates()

        impl.writeInput("${osc8Start("https://link2.com")}Link2${osc8End()}\r\n".toByteArray())
        impl.processPendingUpdates()

        impl.writeInput("More text\r\n".toByteArray())
        impl.processPendingUpdates()

        impl.writeInput("${osc8Start("https://link3.com")}Link3${osc8End()}\r\n".toByteArray())
        impl.processPendingUpdates()

        val snapshot = (emulator as TerminalEmulatorImpl).let {
            it.processPendingUpdates()
            it.snapshot.value
        }

        // After scrolling (7 lines, 5 rows = 3 scrolls), visible lines should be:
        // Row 0: "Plain text" (was line 3) - no hyperlink
        // Row 1: "[Link2](url2)" (was line 4) - has hyperlink
        // Row 2: "More text" (was line 5) - no hyperlink
        // Row 3: "[Link3](url3)" (was line 6) - has hyperlink
        // Row 4: empty (cursor after last newline)
        // Scrollback contains: Line1, Line2, Link1

        // Row 0 should have no hyperlinks (plain text)
        assertTrue(
            "Row 0 should have no hyperlinks (plain text)",
            snapshot.lines[0].getSegmentsOfType(SemanticType.HYPERLINK).isEmpty()
        )

        // Row 1 should have Link2
        val row1Segments = snapshot.lines[1].getSegmentsOfType(SemanticType.HYPERLINK)
        assertEquals("Row 1 should have 1 hyperlink (Link2)", 1, row1Segments.size)
        assertEquals("https://link2.com", row1Segments[0].metadata)

        // Row 2 should have no hyperlinks (more text)
        assertTrue(
            "Row 2 should have no hyperlinks",
            snapshot.lines[2].getSegmentsOfType(SemanticType.HYPERLINK).isEmpty()
        )

        // Row 3 should have Link3
        val row3Segments = snapshot.lines[3].getSegmentsOfType(SemanticType.HYPERLINK)
        assertEquals("Row 3 should have 1 hyperlink (Link3)", 1, row3Segments.size)
        assertEquals("https://link3.com", row3Segments[0].metadata)

        // Row 4 should have no hyperlinks (empty after cursor)
        assertTrue(
            "Row 4 should have no hyperlinks (empty)",
            snapshot.lines[4].getSegmentsOfType(SemanticType.HYPERLINK).isEmpty()
        )
    }

    @Test
    fun testOsc8HyperlinkNotOnLastLineWhenPrintedElsewhere() = runBlocking {
        // This test specifically checks the bug where hyperlinks appeared on the last line
        // even though they were printed on a different line
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 10,
            initialCols = 40
        )

        // Print hyperlink on row 0, then move to row 5 and print plain text
        // Using \r\n for proper cursor column reset after newlines
        val input = buildString {
            append("${osc8Start("https://example.com")}Click me${osc8End()}\r\n")
            append("\r\n")
            append("\r\n")
            append("\r\n")
            append("\r\n")
            append("This is plain text on row 5")
        }

        emulator.writeInput(input.toByteArray())

        val snapshot = (emulator as TerminalEmulatorImpl).let {
            it.processPendingUpdates()
            it.snapshot.value
        }

        // Only row 0 should have the hyperlink
        val row0Segments = snapshot.lines[0].getSegmentsOfType(SemanticType.HYPERLINK)
        assertEquals("Row 0 should have 1 hyperlink", 1, row0Segments.size)
        assertEquals("https://example.com", row0Segments[0].metadata)

        // All other rows (including the last used row 5) should have no hyperlinks
        for (row in 1 until snapshot.lines.size) {
            val segments = snapshot.lines[row].getSegmentsOfType(SemanticType.HYPERLINK)
            assertTrue(
                "Row $row should have no hyperlinks but has ${segments.size}",
                segments.isEmpty()
            )
        }
    }
}
