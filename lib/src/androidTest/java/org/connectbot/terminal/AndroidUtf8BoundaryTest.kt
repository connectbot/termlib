/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the untrusted byte-to-Kotlin boundary under ART and CheckJNI. */
@RunWith(AndroidJUnit4::class)
class AndroidUtf8BoundaryTest {
    @Test
    fun malformedAndSplitOscPayloadsDoNotAbortArt() {
        val terminal = TerminalEmulatorFactory.create()
        val malformed = listOf(
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xC3.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf(0xF0.toByte(), 0x9F.toByte()),
        )
        for (payload in malformed) {
            terminal.writeInput("\u001B]0;".toByteArray() + payload + byteArrayOf(7))
        }
        val valid = "\u001B]2;é日🎉\u0007".toByteArray()
        for (split in valid.indices) {
            terminal.writeInput(valid, 0, split)
            terminal.writeInput(valid, split, valid.size - split)
        }
        terminal.writeInput("still alive".toByteArray())
    }
}
