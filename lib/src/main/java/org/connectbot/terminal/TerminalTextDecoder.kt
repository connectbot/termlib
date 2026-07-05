/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

/** Accumulates bytes, not independently decoded fragments, so split UTF-8 survives. */
internal class TerminalTextDecoder {
    private class Message(val limit: Int) {
        var bytes = ByteArray(0)
        var size = 0
        var discarded = false

        fun append(fragment: ByteArray) {
            if (discarded) return
            if (fragment.size > limit - size) {
                discarded = true
                bytes = ByteArray(0)
                size = 0
                return
            }
            val needed = size + fragment.size
            if (needed > bytes.size) bytes = bytes.copyOf(maxOf(needed, maxOf(256, bytes.size * 2)).coerceAtMost(limit))
            fragment.copyInto(bytes, size)
            size = needed
        }
    }

    private val messages = mutableMapOf<Pair<Int, Int>, Message>()

    fun accept(kind: Int, command: Int, data: ByteArray, initial: Boolean, final: Boolean): String? {
        require(kind in OSC..CLIPBOARD)
        require(kind != PROPERTY || command in 4..5)
        // Only the two actual string properties need independent state. OSC commands
        // share one stream, preventing unbounded keys from unterminated messages.
        val key = kind to if (kind == PROPERTY) command else 0
        if (initial) messages[key] = Message(if (kind == CLIPBOARD) CLIPBOARD_LIMIT else TEXT_LIMIT)
        val message = messages[key] ?: return null
        message.append(data)
        if (!final) return null
        messages.remove(key)
        return if (message.discarded) null else String(message.bytes, 0, message.size, Charsets.UTF_8)
    }

    companion object {
        const val OSC = 0
        const val PROPERTY = 1
        const val CLIPBOARD = 2
        const val TEXT_LIMIT = 1024 * 1024
        const val CLIPBOARD_LIMIT = 16 * 1024 * 1024
    }
}
