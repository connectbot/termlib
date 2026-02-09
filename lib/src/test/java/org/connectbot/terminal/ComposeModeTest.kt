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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComposeModeTest {
    private lateinit var composeMode: ComposeMode

    @Before
    fun setup() {
        composeMode = ComposeMode()
    }

    @Test
    fun testInitialState() {
        assertFalse(composeMode.isActive)
        assertEquals("", composeMode.buffer)
    }

    @Test
    fun testActivate() {
        composeMode.activate()
        assertTrue(composeMode.isActive)
        assertEquals("", composeMode.buffer)
    }

    @Test
    fun testActivateClearsPreviousBuffer() {
        composeMode.activate()
        composeMode.appendChar('a')
        composeMode.appendChar('b')
        assertEquals("ab", composeMode.buffer)

        composeMode.activate()
        assertTrue(composeMode.isActive)
        assertEquals("", composeMode.buffer)
    }

    @Test
    fun testDeactivate() {
        composeMode.activate()
        composeMode.appendChar('x')
        composeMode.deactivate()

        assertFalse(composeMode.isActive)
        assertEquals("", composeMode.buffer)
    }

    @Test
    fun testAppendChar() {
        composeMode.activate()
        composeMode.appendChar('h')
        composeMode.appendChar('i')
        assertEquals("hi", composeMode.buffer)
    }

    @Test
    fun testAppendCharWhenInactive() {
        composeMode.appendChar('x')
        assertEquals("", composeMode.buffer)
    }

    @Test
    fun testAppendText() {
        composeMode.activate()
        composeMode.appendText("hello")
        assertEquals("hello", composeMode.buffer)
    }

    @Test
    fun testAppendTextWhenInactive() {
        composeMode.appendText("hello")
        assertEquals("", composeMode.buffer)
    }

    @Test
    fun testAppendTextMultiple() {
        composeMode.activate()
        composeMode.appendText("hel")
        composeMode.appendText("lo")
        assertEquals("hello", composeMode.buffer)
    }

    @Test
    fun testDeleteLastChar() {
        composeMode.activate()
        composeMode.appendText("abc")
        composeMode.deleteLastChar()
        assertEquals("ab", composeMode.buffer)
    }

    @Test
    fun testDeleteLastCharEmptyBuffer() {
        composeMode.activate()
        composeMode.deleteLastChar()
        assertEquals("", composeMode.buffer)
        assertTrue(composeMode.isActive)
    }

    @Test
    fun testDeleteLastCharWhenInactive() {
        composeMode.deleteLastChar()
        assertEquals("", composeMode.buffer)
    }

    @Test
    fun testDeleteAllChars() {
        composeMode.activate()
        composeMode.appendText("ab")
        composeMode.deleteLastChar()
        composeMode.deleteLastChar()
        assertEquals("", composeMode.buffer)
        assertTrue(composeMode.isActive)
    }

    @Test
    fun testCommit() {
        composeMode.activate()
        composeMode.appendText("hello")
        val result = composeMode.commit()

        assertEquals("hello", result)
        assertFalse(composeMode.isActive)
        assertEquals("", composeMode.buffer)
    }

    @Test
    fun testCommitEmptyBuffer() {
        composeMode.activate()
        val result = composeMode.commit()

        assertNull(result)
        assertFalse(composeMode.isActive)
    }

    @Test
    fun testCommitWhenInactive() {
        val result = composeMode.commit()

        assertNull(result)
        assertFalse(composeMode.isActive)
    }

    @Test
    fun testCancel() {
        composeMode.activate()
        composeMode.appendText("hello")
        composeMode.cancel()

        assertFalse(composeMode.isActive)
        assertEquals("", composeMode.buffer)
    }

    @Test
    fun testCancelWhenInactive() {
        composeMode.cancel()
        assertFalse(composeMode.isActive)
        assertEquals("", composeMode.buffer)
    }

    @Test
    fun testMixedAppendAndDelete() {
        composeMode.activate()
        composeMode.appendChar('a')
        composeMode.appendChar('b')
        composeMode.appendChar('c')
        composeMode.deleteLastChar()
        composeMode.appendChar('d')
        assertEquals("abd", composeMode.buffer)
    }

    @Test
    fun testCommitThenReactivate() {
        composeMode.activate()
        composeMode.appendText("first")
        composeMode.commit()

        composeMode.activate()
        composeMode.appendText("second")
        val result = composeMode.commit()

        assertEquals("second", result)
    }
}
