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
}
