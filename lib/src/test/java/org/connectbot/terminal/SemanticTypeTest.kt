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

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class SemanticTypeTest {
    @Test
    fun testSemanticTypeExists() {
        val types = SemanticType.values()
        assertEquals(7, types.size)
        assertTrue(types.contains(SemanticType.PROMPT))
        assertTrue(types.contains(SemanticType.COMMAND_INPUT))
        assertTrue(types.contains(SemanticType.COMMAND_OUTPUT))
        assertTrue(types.contains(SemanticType.COMMAND_FINISHED))
        assertTrue(types.contains(SemanticType.ANNOTATION))
        assertTrue(types.contains(SemanticType.HYPERLINK))
        assertTrue(types.contains(SemanticType.DEFAULT))
    }

    @Test
    fun testSemanticSegment() {
        val segment = SemanticSegment(
            startCol = 0,
            endCol = 11,
            semanticType = SemanticType.PROMPT,
            metadata = null,
            promptId = 1
        )

        assertTrue(segment.contains(0))
        assertTrue(segment.contains(10))
        assertFalse(segment.contains(11))
        assertFalse(segment.contains(-1))

        assertEquals(11, segment.length)
    }

    @Test
    fun testTerminalLineWithMultipleSegments() {
        // Simulate line: "user@host$ ls -l"
        // Columns:       0123456789012345
        val cells = buildTestCells("user@host\$ ls -l")
        val line = TerminalLine(
            row = 0,
            cells = cells,
            semanticSegments = listOf(
                SemanticSegment(0, 11, SemanticType.PROMPT, null, 1),      // "user@host$ "
                SemanticSegment(11, 16, SemanticType.COMMAND_INPUT, null, 1) // "ls -l"
            )
        )

        assertEquals(SemanticType.PROMPT, line.getSemanticTypeAt(0))
        assertEquals(SemanticType.PROMPT, line.getSemanticTypeAt(10))
        assertEquals(SemanticType.COMMAND_INPUT, line.getSemanticTypeAt(11))
        assertEquals(SemanticType.COMMAND_INPUT, line.getSemanticTypeAt(15))
        assertEquals(SemanticType.DEFAULT, line.getSemanticTypeAt(16))

        assertTrue(line.hasPrompt())

        assertEquals(1, line.promptId)

        assertEquals(1, line.getSegmentsOfType(SemanticType.PROMPT).size)
        assertEquals(1, line.getSegmentsOfType(SemanticType.COMMAND_INPUT).size)
        assertEquals(0, line.getSegmentsOfType(SemanticType.COMMAND_OUTPUT).size)
    }

    @Test
    fun testTerminalLineWithNoSegments() {
        val cells = buildTestCells("plain text")
        val line = TerminalLine(
            row = 0,
            cells = cells
        )

        assertEquals(SemanticType.DEFAULT, line.getSemanticTypeAt(0))
        assertFalse(line.hasPrompt())
        assertEquals(-1, line.promptId)
    }

    @Test
    fun testTerminalLineTextContent() {
        val cells = buildTestCells("Hello World")
        val line = TerminalLine(
            row = 0,
            cells = cells
        )

        assertEquals("Hello World", line.text)
    }

    @Test
    fun testSemanticSegmentWithMetadata() {
        val segment = SemanticSegment(
            startCol = 0,
            endCol = 1,
            semanticType = SemanticType.COMMAND_FINISHED,
            metadata = "42",
            promptId = 5
        )

        assertEquals("42", segment.metadata)
        assertEquals(5, segment.promptId)
        assertEquals(SemanticType.COMMAND_FINISHED, segment.semanticType)
    }

    @Test
    fun testMultiplePromptsInLine() {
        val cells = buildTestCells("$ cmd1 && $ cmd2")
        val line = TerminalLine(
            row = 0,
            cells = cells,
            semanticSegments = listOf(
                SemanticSegment(0, 2, SemanticType.PROMPT, null, 1),
                SemanticSegment(2, 7, SemanticType.COMMAND_INPUT, null, 1),
                SemanticSegment(10, 12, SemanticType.PROMPT, null, 2),
                SemanticSegment(12, 17, SemanticType.COMMAND_INPUT, null, 2)
            )
        )

        assertEquals(2, line.getSegmentsOfType(SemanticType.PROMPT).size)
        assertEquals(2, line.getSegmentsOfType(SemanticType.COMMAND_INPUT).size)
        assertEquals(1, line.promptId) // Returns first promptId
    }

    @Test
    fun testHyperlinkSegment() {
        val cells = buildTestCells("Click here for docs")
        val line = TerminalLine(
            row = 0,
            cells = cells,
            semanticSegments = listOf(
                SemanticSegment(
                    startCol = 0,
                    endCol = 10,
                    semanticType = SemanticType.HYPERLINK,
                    metadata = "https://example.com",
                    promptId = -1
                )
            )
        )

        assertEquals(SemanticType.HYPERLINK, line.getSemanticTypeAt(0))
        assertEquals(SemanticType.HYPERLINK, line.getSemanticTypeAt(9))
        assertEquals(SemanticType.DEFAULT, line.getSemanticTypeAt(10))

        val hyperlinkSegments = line.getSegmentsOfType(SemanticType.HYPERLINK)
        assertEquals(1, hyperlinkSegments.size)
        assertEquals("https://example.com", hyperlinkSegments[0].metadata)
    }

    // --- URL auto-detection tests ---

    @Test
    fun testAutoDetectedUrlsHttps() {
        val line = TerminalLine(row = 0, cells = buildTestCells("Visit https://example.com for docs"))
        val urls = line.autoDetectedUrls
        assertEquals(1, urls.size)
        assertEquals("https://example.com", urls[0].third)
        assertEquals(6, urls[0].first)   // starts at 'h'
        assertEquals(25, urls[0].second) // exclusive end
    }

    @Test
    fun testAutoDetectedUrlsBareDomain() {
        val line = TerminalLine(row = 0, cells = buildTestCells("See example.com/path for info"))
        val urls = line.autoDetectedUrls
        assertEquals(1, urls.size)
        assertEquals("example.com/path", urls[0].third)
    }

    @Test
    fun testAutoDetectedUrlsMultiple() {
        val line = TerminalLine(row = 0, cells = buildTestCells("https://a.com and https://b.org/foo"))
        val urls = line.autoDetectedUrls
        assertEquals(2, urls.size)
        assertEquals("https://a.com", urls[0].third)
        assertEquals("https://b.org/foo", urls[1].third)
    }

    @Test
    fun testAutoDetectedUrlsNone() {
        val line = TerminalLine(row = 0, cells = buildTestCells("no urls here"))
        assertTrue(line.autoDetectedUrls.isEmpty())
    }

    @Test
    fun testAutoDetectedUrlsIpWithPort() {
        val line = TerminalLine(row = 0, cells = buildTestCells("connect to 192.168.1.1:8080 now"))
        val urls = line.autoDetectedUrls
        assertEquals(1, urls.size)
        assertEquals("192.168.1.1:8080", urls[0].third)
    }

    @Test
    fun testAutoDetectedUrlsIpWithPortAndPath() {
        val line = TerminalLine(row = 0, cells = buildTestCells("http://10.0.0.1:3000/api/health"))
        val urls = line.autoDetectedUrls
        assertEquals(1, urls.size)
        assertEquals("http://10.0.0.1:3000/api/health", urls[0].third)
    }

    @Test
    fun testAutoDetectedUrlsDomainWithPort() {
        val line = TerminalLine(row = 0, cells = buildTestCells("localhost.dev:9090/dashboard"))
        val urls = line.autoDetectedUrls
        assertEquals(1, urls.size)
        assertEquals("localhost.dev:9090/dashboard", urls[0].third)
    }

    @Test
    fun testAutoDetectedUrlsBareIpNoPort() {
        // Bare IP without port should NOT match (avoids false positives like version numbers)
        val line = TerminalLine(row = 0, cells = buildTestCells("version 1.2.3.4 released"))
        assertTrue(line.autoDetectedUrls.isEmpty())
    }

    @Test
    fun testGetHyperlinkUrlAtFallsBackToAutoDetect() {
        // No OSC 8 segments — should fall back to auto-detected URL when enabled
        val line = TerminalLine(row = 0, cells = buildTestCells("go to https://example.com ok"))
        // "https://example.com" starts at col 6
        assertNull(line.getHyperlinkUrlAt(0, autoDetectUrls = true))
        assertEquals("https://example.com", line.getHyperlinkUrlAt(6, autoDetectUrls = true))
        assertEquals("https://example.com", line.getHyperlinkUrlAt(24, autoDetectUrls = true)) // last char 'm'
        assertNull(line.getHyperlinkUrlAt(25, autoDetectUrls = true))
    }

    @Test
    fun testGetHyperlinkUrlAtDoesNotAutoDetectWhenDisabled() {
        val line = TerminalLine(row = 0, cells = buildTestCells("go to https://example.com ok"))
        assertNull(line.getHyperlinkUrlAt(6, autoDetectUrls = false))
        assertNull(line.getHyperlinkUrlAt(6))
    }

    @Test
    fun testOsc8TakesPriorityOverAutoDetect() {
        // Line text contains a URL, AND an OSC 8 segment covers the same range with a different URL
        val text = "Click https://example.com here"
        val line = TerminalLine(
            row = 0,
            cells = buildTestCells(text),
            semanticSegments = listOf(
                SemanticSegment(
                    startCol = 6,
                    endCol = 25,
                    semanticType = SemanticType.HYPERLINK,
                    metadata = "https://osc8-override.com"
                )
            )
        )
        // OSC 8 wins over auto-detect
        assertEquals("https://osc8-override.com", line.getHyperlinkUrlAt(6))
        assertEquals("https://osc8-override.com", line.getHyperlinkUrlAt(10))
    }

    private fun buildTestCells(text: String): List<TerminalLine.Cell> {
        return text.map { char ->
            TerminalLine.Cell(
                char = char,
                fgColor = Color.White,
                bgColor = Color.Black
            )
        }
    }
}
