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

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests NFC normalization of on-screen keyboard input.
 *
 * On-screen keyboards (Samsung, Google, etc.) may send accented characters in
 * NFD (decomposed) form — a base character followed by combining marks — e.g.,
 * U+0061 U+0308 for ä instead of the precomposed U+00E4.
 *
 * KeyboardHandler.onTextInput() applies NFC normalization to convert these
 * sequences to precomposed form before dispatching to the terminal emulator.
 * These tests verify the normalization for characters reported in issue #2027.
 */
class KeyboardHandlerNfcTest {

    /**
     * Simulates the NFC normalization applied in KeyboardHandler.onTextInput().
     * This mirrors the exact transformation: UTF-8 bytes -> String -> NFC normalize.
     */
    private fun normalizeAsOnTextInput(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        return Normalizer.normalize(bytes.toString(Charsets.UTF_8), Normalizer.Form.NFC)
    }

    @Test
    fun `NFD a-umlaut is normalized to precomposed form`() {
        // ä as NFD: U+0061 (a) + U+0308 (combining diaeresis)
        val nfd = "a\u0308"
        assertFalse(Normalizer.isNormalized(nfd, Normalizer.Form.NFC))

        val result = normalizeAsOnTextInput(nfd)

        assertEquals("\u00E4", result) // ä as single codepoint
        assertEquals(1, result.length)
    }

    @Test
    fun `NFD o-umlaut is normalized to precomposed form`() {
        // ö as NFD: U+006F (o) + U+0308 (combining diaeresis)
        val nfd = "o\u0308"
        val result = normalizeAsOnTextInput(nfd)

        assertEquals("\u00F6", result) // ö
        assertEquals(1, result.length)
    }

    @Test
    fun `NFD a-ring is normalized to precomposed form`() {
        // å as NFD: U+0061 (a) + U+030A (combining ring above)
        val nfd = "a\u030A"
        val result = normalizeAsOnTextInput(nfd)

        assertEquals("\u00E5", result) // å
        assertEquals(1, result.length)
    }

    @Test
    fun `NFD u-umlaut is normalized to precomposed form`() {
        // ü as NFD: U+0075 (u) + U+0308 (combining diaeresis)
        val nfd = "u\u0308"
        val result = normalizeAsOnTextInput(nfd)

        assertEquals("\u00FC", result) // ü
        assertEquals(1, result.length)
    }

    @Test
    fun `NFD e-acute is normalized to precomposed form`() {
        // é as NFD: U+0065 (e) + U+0301 (combining acute accent)
        val nfd = "e\u0301"
        val result = normalizeAsOnTextInput(nfd)

        assertEquals("\u00E9", result) // é
        assertEquals(1, result.length)
    }

    @Test
    fun `NFD n-tilde is normalized to precomposed form`() {
        // ñ as NFD: U+006E (n) + U+0303 (combining tilde)
        val nfd = "n\u0303"
        val result = normalizeAsOnTextInput(nfd)

        assertEquals("\u00F1", result) // ñ
        assertEquals(1, result.length)
    }

    @Test
    fun `already NFC text passes through unchanged`() {
        val nfc = "\u00E4\u00F6\u00E5" // äöå already in NFC
        assertTrue(Normalizer.isNormalized(nfc, Normalizer.Form.NFC))

        val result = normalizeAsOnTextInput(nfc)

        assertEquals(nfc, result)
        assertEquals(3, result.length)
    }

    @Test
    fun `plain ASCII passes through unchanged`() {
        val result = normalizeAsOnTextInput("hello")
        assertEquals("hello", result)
    }

    @Test
    fun `mixed ASCII and NFD is normalized`() {
        // "hällö" with ä and ö in NFD
        val nfd = "ha\u0308llo\u0308"
        val result = normalizeAsOnTextInput(nfd)

        assertEquals("h\u00E4ll\u00F6", result)
        assertEquals(5, result.length)
    }

    @Test
    fun `multiple NFD characters in sequence are all normalized`() {
        // äöå all in NFD
        val nfd = "a\u0308o\u0308a\u030A"
        val result = normalizeAsOnTextInput(nfd)

        assertEquals("\u00E4\u00F6\u00E5", result)
        assertEquals(3, result.length)
    }

    @Test
    fun `result is always NFC normalized`() {
        val inputs = listOf(
            "a\u0308",      // ä NFD
            "o\u0308",      // ö NFD
            "a\u030A",      // å NFD
            "e\u0301",      // é NFD
            "n\u0303",      // ñ NFD
            "\u00E4",       // ä NFC (already)
            "hello",        // ASCII
            "a\u0308b\u0301", // mixed NFD
        )
        for (input in inputs) {
            val result = normalizeAsOnTextInput(input)
            assertTrue(
                "Expected NFC for input '${input}' but got '${result}'",
                Normalizer.isNormalized(result, Normalizer.Form.NFC)
            )
        }
    }

    @Test
    fun `empty input returns empty string`() {
        val result = normalizeAsOnTextInput("")
        assertEquals("", result)
    }
}
