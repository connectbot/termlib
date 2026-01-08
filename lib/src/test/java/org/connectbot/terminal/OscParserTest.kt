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

import org.junit.Assert.*
import org.junit.Test

class OscParserTest {
    @Test
    fun testOsc133PromptFlow() {
        val parser = OscParser()
        val row = 5
        val cols = 80

        // 1. Prompt start (A)
        var actions = parser.parse(133, "A", row, 0, cols)
        assertTrue(actions.isEmpty()) // No action yet, just state update

        // 2. Prompt end / Input start (B) at col 10
        // "user@host$" is 10 chars
        actions = parser.parse(133, "B", row, 10, cols)
        assertEquals(1, actions.size)
        val promptAction = actions[0] as OscParser.Action.AddSegment
        assertEquals(SemanticType.PROMPT, promptAction.type)
        assertEquals(0, promptAction.startCol)
        assertEquals(10, promptAction.endCol)
        assertTrue(promptAction.promptId > 0)

        // 3. Input end / Output start (C) at col 15
        // "ls -l" is 5 chars
        actions = parser.parse(133, "C", row, 15, cols)
        assertEquals(1, actions.size)
        val inputAction = actions[0] as OscParser.Action.AddSegment
        assertEquals(SemanticType.COMMAND_INPUT, inputAction.type)
        assertEquals(10, inputAction.startCol)
        assertEquals(15, inputAction.endCol)
        assertEquals(promptAction.promptId, inputAction.promptId)

        // 4. Command finished (D)
        actions = parser.parse(133, "D;0", row, 0, cols)
        assertEquals(1, actions.size)
        val finishedAction = actions[0] as OscParser.Action.AddSegment
        assertEquals(SemanticType.COMMAND_FINISHED, finishedAction.type)
        assertEquals("0", finishedAction.metadata)
        assertEquals(promptAction.promptId, finishedAction.promptId)
    }

    @Test
    fun testOsc1337Annotation() {
        val parser = OscParser()
        val row = 10
        val cols = 80

        val actions = parser.parse(1337, "AddAnnotation=Hello World", row, 0, cols)
        assertEquals(1, actions.size)
        val action = actions[0] as OscParser.Action.AddSegment
        assertEquals(SemanticType.ANNOTATION, action.type)
        assertEquals(0, action.startCol)
        assertEquals(cols, action.endCol)
        assertEquals("Hello World", action.metadata)
    }

    @Test
    fun testOsc1337CursorShape() {
        val parser = OscParser()
        val row = 0
        val cols = 80

        // Block
        var actions = parser.parse(1337, "SetCursorShape=0", row, 0, cols)
        assertEquals(OscParser.Action.SetCursorShape(CursorShape.BLOCK), actions[0])

        // Bar
        actions = parser.parse(1337, "SetCursorShape=1", row, 0, cols)
        assertEquals(OscParser.Action.SetCursorShape(CursorShape.BAR_LEFT), actions[0])

        // Underline
        actions = parser.parse(1337, "SetCursorShape=2", row, 0, cols)
        assertEquals(OscParser.Action.SetCursorShape(CursorShape.UNDERLINE), actions[0])
    }

    @Test
    fun testOsc52ClipboardCopy() {
        val parser = OscParser()
        val row = 0
        val cols = 80

        // "Hello World" in base64 is "SGVsbG8gV29ybGQ="
        val actions = parser.parse(52, "c;SGVsbG8gV29ybGQ=", row, 0, cols)
        assertEquals(1, actions.size)
        val action = actions[0] as OscParser.Action.ClipboardCopy
        assertEquals("c", action.selection)
        assertEquals("Hello World", action.data)
    }

    @Test
    fun testOsc52ClipboardCopyWithPrimarySelection() {
        val parser = OscParser()
        val row = 0
        val cols = 80

        // Test with 'p' (primary) selection
        // "Test" in base64 is "VGVzdA=="
        val actions = parser.parse(52, "p;VGVzdA==", row, 0, cols)
        assertEquals(1, actions.size)
        val action = actions[0] as OscParser.Action.ClipboardCopy
        assertEquals("p", action.selection)
        assertEquals("Test", action.data)
    }

    @Test
    fun testOsc52ClipboardCopyEmptySelection() {
        val parser = OscParser()
        val row = 0
        val cols = 80

        // Empty selection (just ';') is valid - means "c" (clipboard)
        // "data" in base64 is "ZGF0YQ=="
        val actions = parser.parse(52, ";ZGF0YQ==", row, 0, cols)
        assertEquals(1, actions.size)
        val action = actions[0] as OscParser.Action.ClipboardCopy
        assertEquals("", action.selection)
        assertEquals("data", action.data)
    }

    @Test
    fun testOsc52ClipboardReadRequestIgnored() {
        val parser = OscParser()
        val row = 0
        val cols = 80

        // Read request (? as data) should be ignored for security
        val actions = parser.parse(52, "c;?", row, 0, cols)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun testOsc52InvalidBase64TreatedAsRawData() {
        val parser = OscParser()
        val row = 0
        val cols = 80

        // Invalid base64 data is treated as raw/pre-decoded data
        // (libvterm's selection callback provides pre-decoded data)
        val actions = parser.parse(52, "c;!!invalid!!", row, 0, cols)
        assertEquals(1, actions.size)
        val action = actions[0] as OscParser.Action.ClipboardCopy
        assertEquals("c", action.selection)
        assertEquals("!!invalid!!", action.data)
    }

    @Test
    fun testOsc52MissingSeparator() {
        val parser = OscParser()
        val row = 0
        val cols = 80

        // Missing semicolon separator
        val actions = parser.parse(52, "cSGVsbG8=", row, 0, cols)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun testOsc52UnicodeContent() {
        val parser = OscParser()
        val row = 0
        val cols = 80

        // Unicode text "日本語" in base64 is "5pel5pys6Kqe"
        val actions = parser.parse(52, "c;5pel5pys6Kqe", row, 0, cols)
        assertEquals(1, actions.size)
        val action = actions[0] as OscParser.Action.ClipboardCopy
        assertEquals("c", action.selection)
        assertEquals("日本語", action.data)
    }

    @Test
    fun testOsc52EmptyData() {
        val parser = OscParser()
        val row = 0
        val cols = 80

        // Empty base64 data decodes to empty string
        val actions = parser.parse(52, "c;", row, 0, cols)
        assertEquals(1, actions.size)
        val action = actions[0] as OscParser.Action.ClipboardCopy
        assertEquals("c", action.selection)
        assertEquals("", action.data)
    }

    @Test
    fun testOsc8HyperlinkBasic() {
        val parser = OscParser()
        val row = 5
        val cols = 80

        // Start hyperlink at col 0
        var actions = parser.parse(8, ";https://example.com", row, 0, cols)
        assertTrue(actions.isEmpty()) // No action yet, just state update

        // End hyperlink at col 12 (after "Example Link")
        actions = parser.parse(8, ";", row, 12, cols)
        assertEquals(1, actions.size)
        val action = actions[0] as OscParser.Action.AddSegment
        assertEquals(SemanticType.HYPERLINK, action.type)
        assertEquals(0, action.startCol)
        assertEquals(12, action.endCol)
        assertEquals("https://example.com", action.metadata)
    }

    @Test
    fun testOsc8HyperlinkWithId() {
        val parser = OscParser()
        val row = 5
        val cols = 80

        // Start hyperlink with id parameter
        var actions = parser.parse(8, "id=link1;https://example.com", row, 0, cols)
        assertTrue(actions.isEmpty())

        // End hyperlink
        actions = parser.parse(8, ";", row, 10, cols)
        assertEquals(1, actions.size)
        val action = actions[0] as OscParser.Action.AddSegment
        assertEquals(SemanticType.HYPERLINK, action.type)
        assertEquals("https://example.com", action.metadata)
    }

    @Test
    fun testOsc8HyperlinkWithMultipleParams() {
        val parser = OscParser()
        val row = 5
        val cols = 80

        // Start hyperlink with multiple params (colon-separated)
        var actions = parser.parse(8, "id=link1:foo=bar;https://example.com", row, 0, cols)
        assertTrue(actions.isEmpty())

        // End hyperlink
        actions = parser.parse(8, ";", row, 8, cols)
        assertEquals(1, actions.size)
        val action = actions[0] as OscParser.Action.AddSegment
        assertEquals(SemanticType.HYPERLINK, action.type)
        assertEquals("https://example.com", action.metadata)
    }

    @Test
    fun testOsc8HyperlinkNoEndMarker() {
        val parser = OscParser()
        val row = 5
        val cols = 80

        // Start hyperlink but never end it - no segment should be created
        var actions = parser.parse(8, ";https://example.com", row, 0, cols)
        assertTrue(actions.isEmpty())

        // Start a new hyperlink on a different row (old one implicitly abandoned)
        actions = parser.parse(8, ";https://other.com", row + 1, 5, cols)
        assertTrue(actions.isEmpty())

        // End the new hyperlink
        actions = parser.parse(8, ";", row + 1, 15, cols)
        assertEquals(1, actions.size)
        val action = actions[0] as OscParser.Action.AddSegment
        assertEquals("https://other.com", action.metadata)
        assertEquals(5, action.startCol)
        assertEquals(15, action.endCol)
    }

    @Test
    fun testOsc8HyperlinkSameRowReplacement() {
        val parser = OscParser()
        val row = 5
        val cols = 80

        // Start hyperlink at col 0
        var actions = parser.parse(8, ";https://first.com", row, 0, cols)
        assertTrue(actions.isEmpty())

        // Start another hyperlink at col 10 (should close first one)
        actions = parser.parse(8, ";https://second.com", row, 10, cols)
        assertEquals(1, actions.size)
        val action = actions[0] as OscParser.Action.AddSegment
        assertEquals("https://first.com", action.metadata)
        assertEquals(0, action.startCol)
        assertEquals(10, action.endCol)

        // End the second hyperlink
        actions = parser.parse(8, ";", row, 20, cols)
        assertEquals(1, actions.size)
        val action2 = actions[0] as OscParser.Action.AddSegment
        assertEquals("https://second.com", action2.metadata)
        assertEquals(10, action2.startCol)
        assertEquals(20, action2.endCol)
    }

    @Test
    fun testOsc8HyperlinkMissingSeparator() {
        val parser = OscParser()
        val row = 0
        val cols = 80

        // Missing semicolon separator should be ignored
        val actions = parser.parse(8, "https://example.com", row, 0, cols)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun testOsc8HyperlinkEmptyEndAtSameColumn() {
        val parser = OscParser()
        val row = 5
        val cols = 80

        // Start hyperlink at col 5
        var actions = parser.parse(8, ";https://example.com", row, 5, cols)
        assertTrue(actions.isEmpty())

        // End hyperlink at same column (zero-width) - should not create segment
        actions = parser.parse(8, ";", row, 5, cols)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun testOsc8HyperlinkUrlWithSpecialChars() {
        val parser = OscParser()
        val row = 5
        val cols = 80

        // URL with query params and fragments
        val complexUrl = "https://example.com/path?query=value&foo=bar#section"
        var actions = parser.parse(8, ";$complexUrl", row, 0, cols)
        assertTrue(actions.isEmpty())

        actions = parser.parse(8, ";", row, 20, cols)
        assertEquals(1, actions.size)
        val action = actions[0] as OscParser.Action.AddSegment
        assertEquals(complexUrl, action.metadata)
    }

    @Test
    fun testOsc8HyperlinkFileUrl() {
        val parser = OscParser()
        val row = 5
        val cols = 80

        // File URL (common for local links in terminal)
        val fileUrl = "file:///home/user/document.txt"
        var actions = parser.parse(8, ";$fileUrl", row, 0, cols)
        assertTrue(actions.isEmpty())

        actions = parser.parse(8, ";", row, 12, cols)
        assertEquals(1, actions.size)
        val action = actions[0] as OscParser.Action.AddSegment
        assertEquals(fileUrl, action.metadata)
    }
}
