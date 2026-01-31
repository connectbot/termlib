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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReadLastOutputTest {
    @Test
    fun testBasicCommandOutput() {
        // Simulates: prompt$ ls\nfile1\nfile2\n[finished]
        val lines = listOf(
            makeLine(0, "prompt\$ ls", listOf(
                SemanticSegment(0, 8, SemanticType.PROMPT, promptId = 1),
                SemanticSegment(8, 10, SemanticType.COMMAND_INPUT, promptId = 1)
            )),
            makeLine(1, "file1"),
            makeLine(2, "file2"),
            makeLine(3, "", listOf(
                SemanticSegment(0, 0, SemanticType.COMMAND_FINISHED, metadata = "0", promptId = 1)
            ))
        )

        val output = getLastCommandOutput(lines)
        assertNotNull(output)
        assertEquals("file1\nfile2", output)
    }

    @Test
    fun testNoCommandFinished() {
        val lines = listOf(
            makeLine(0, "prompt\$ ls", listOf(
                SemanticSegment(0, 8, SemanticType.PROMPT, promptId = 1),
                SemanticSegment(8, 10, SemanticType.COMMAND_INPUT, promptId = 1)
            )),
            makeLine(1, "file1")
        )

        assertNull(getLastCommandOutput(lines))
    }

    @Test
    fun testNoCommandInput() {
        val lines = listOf(
            makeLine(0, "some text"),
            makeLine(1, "", listOf(
                SemanticSegment(0, 0, SemanticType.COMMAND_FINISHED, metadata = "0", promptId = 1)
            ))
        )

        assertNull(getLastCommandOutput(lines))
    }

    @Test
    fun testEmptyOutput() {
        // Command that produces no output (e.g., "cd /tmp")
        val lines = listOf(
            makeLine(0, "prompt\$ cd /tmp", listOf(
                SemanticSegment(0, 8, SemanticType.PROMPT, promptId = 1),
                SemanticSegment(8, 15, SemanticType.COMMAND_INPUT, promptId = 1)
            )),
            makeLine(1, "", listOf(
                SemanticSegment(0, 0, SemanticType.COMMAND_FINISHED, metadata = "0", promptId = 1)
            ))
        )

        assertNull(getLastCommandOutput(lines))
    }

    @Test
    fun testMultipleCommands() {
        // Two commands: first outputs "hello", second outputs "world"
        val lines = listOf(
            makeLine(0, "prompt\$ echo hello", listOf(
                SemanticSegment(0, 8, SemanticType.PROMPT, promptId = 1),
                SemanticSegment(8, 18, SemanticType.COMMAND_INPUT, promptId = 1)
            )),
            makeLine(1, "hello"),
            makeLine(2, "", listOf(
                SemanticSegment(0, 0, SemanticType.COMMAND_FINISHED, metadata = "0", promptId = 1)
            )),
            makeLine(3, "prompt\$ echo world", listOf(
                SemanticSegment(0, 8, SemanticType.PROMPT, promptId = 2),
                SemanticSegment(8, 18, SemanticType.COMMAND_INPUT, promptId = 2)
            )),
            makeLine(4, "world"),
            makeLine(5, "", listOf(
                SemanticSegment(0, 0, SemanticType.COMMAND_FINISHED, metadata = "0", promptId = 2)
            ))
        )

        // Should return only the last command's output
        val output = getLastCommandOutput(lines)
        assertNotNull(output)
        assertEquals("world", output)
    }

    @Test
    fun testMultiLineOutput() {
        val lines = listOf(
            makeLine(0, "prompt\$ find .", listOf(
                SemanticSegment(0, 8, SemanticType.PROMPT, promptId = 1),
                SemanticSegment(8, 14, SemanticType.COMMAND_INPUT, promptId = 1)
            )),
            makeLine(1, "./src"),
            makeLine(2, "./src/main"),
            makeLine(3, "./src/test"),
            makeLine(4, "", listOf(
                SemanticSegment(0, 0, SemanticType.COMMAND_FINISHED, metadata = "0", promptId = 1)
            ))
        )

        val output = getLastCommandOutput(lines)
        assertNotNull(output)
        assertEquals("./src\n./src/main\n./src/test", output)
    }

    @Test
    fun testOutputWithTrailingWhitespace() {
        val lines = listOf(
            makeLine(0, "prompt\$ cmd", listOf(
                SemanticSegment(0, 8, SemanticType.PROMPT, promptId = 1),
                SemanticSegment(8, 11, SemanticType.COMMAND_INPUT, promptId = 1)
            )),
            makeLine(1, "output   "),
            makeLine(2, "   "),
            makeLine(3, "", listOf(
                SemanticSegment(0, 0, SemanticType.COMMAND_FINISHED, metadata = "0", promptId = 1)
            ))
        )

        val output = getLastCommandOutput(lines)
        assertNotNull(output)
        // Each line is trimEnd'd, then the whole string is trimEnd'd
        assertEquals("output", output)
    }

    @Test
    fun testCommandInputAndFinishedAdjacent() {
        // COMMAND_INPUT on line 0, COMMAND_FINISHED on line 1 with no output between
        val lines = listOf(
            makeLine(0, "prompt\$ true", listOf(
                SemanticSegment(0, 8, SemanticType.PROMPT, promptId = 1),
                SemanticSegment(8, 12, SemanticType.COMMAND_INPUT, promptId = 1)
            )),
            makeLine(1, "", listOf(
                SemanticSegment(0, 0, SemanticType.COMMAND_FINISHED, metadata = "0", promptId = 1)
            ))
        )

        // No lines between input and finished
        assertNull(getLastCommandOutput(lines))
    }

    @Test
    fun testOutputFromScrollback() {
        // Simulates output that has scrolled into scrollback
        val lines = listOf(
            // Scrollback lines
            makeLine(-1, "prompt\$ ls -la", listOf(
                SemanticSegment(0, 8, SemanticType.PROMPT, promptId = 1),
                SemanticSegment(8, 14, SemanticType.COMMAND_INPUT, promptId = 1)
            )),
            makeLine(-1, "total 42"),
            makeLine(-1, "drwxr-xr-x  2 user group 4096 Jan 1 file1"),
            makeLine(-1, "drwxr-xr-x  2 user group 4096 Jan 1 file2"),
            // Visible screen
            makeLine(0, "", listOf(
                SemanticSegment(0, 0, SemanticType.COMMAND_FINISHED, metadata = "0", promptId = 1)
            )),
            makeLine(1, "prompt\$ ", listOf(
                SemanticSegment(0, 8, SemanticType.PROMPT, promptId = 2)
            ))
        )

        val output = getLastCommandOutput(lines)
        assertNotNull(output)
        assertEquals(
            "total 42\ndrwxr-xr-x  2 user group 4096 Jan 1 file1\ndrwxr-xr-x  2 user group 4096 Jan 1 file2",
            output
        )
    }

    private fun makeLine(
        row: Int,
        text: String,
        segments: List<SemanticSegment> = emptyList()
    ): TerminalLine {
        val cells = text.map { char ->
            TerminalLine.Cell(
                char = char,
                fgColor = Color.White,
                bgColor = Color.Black
            )
        }
        return TerminalLine(row = row, cells = cells, semanticSegments = segments)
    }
}
