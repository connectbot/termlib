/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/** Keeps encoded graphics commands away from the decoder until consent is granted. */
internal class InlineImageConsentGate(
    private val protocol: InlineImageProtocol,
    private val output: (ByteArray) -> Unit,
    private val ask: (InlineImageRequest, (Boolean) -> Unit) -> Unit,
    private val limits: InlineImageLimits,
) {
    private data class Sequence(val kitty: Boolean, val bytes: ByteArray, val row: Int, val col: Int)
    private class Group(val request: InlineImageRequest?, val kittyOptions: Map<String, String>) {
        val sequences = mutableListOf<Sequence>()
        var complete = request == null
        var decision: Boolean? = if (request == null) true else null
        var bytes = 0
    }

    private val groups = ArrayDeque<Group>()
    private var current = ByteArrayOutputStream()
    private var currentRow = 0
    private var currentCol = 0
    private var currentOverflow = false
    private var currentHeader = StringBuilder()
    private var currentGroup: Group? = null
    private var headerComplete = false
    private var continuingKitty: Group? = null
    private var continuingIterm: Group? = null
    private var pendingBytes = 0
    private var promptActive = false
    private val prompts = ArrayDeque<Group>()
    private val pendingLimit = ((limits.uploadBytes.toLong() + 2) / 3 * 4 + 8192).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun accept(kitty: Boolean, data: ByteArray, initial: Boolean, final: Boolean, row: Int, col: Int): Long {
        if (initial) {
            current = ByteArrayOutputStream(data.size.coerceAtLeast(64))
            currentRow = row
            currentCol = col
            currentOverflow = false
            currentHeader = StringBuilder()
            currentGroup = if (kitty) continuingKitty else continuingIterm
            headerComplete = false
        }
        if (!headerComplete) scanHeader(kitty, data)
        if (!currentOverflow) {
            if (pendingBytes.toLong() + current.size() + data.size <= pendingLimit) {
                current.write(data)
            } else {
                currentOverflow = true
                current.reset()
            }
        }
        if (!final) return 0

        val bytes = current.toByteArray()
        val header = header(bytes, kitty)
        val options = options(header, kitty)
        val hasPayload = if (kitty) {
            bytes.indexOf(';'.code.toByte()) >= 0
        } else {
            (header.startsWith("File=") && bytes.indexOf(':'.code.toByte()) >= 0) || header.startsWith("FilePart=")
        }
        val sequence = Sequence(kitty, bytes, currentRow, currentCol)

        val group = currentGroup ?: when {
            kitty && continuingKitty != null -> continuingKitty!!

            !kitty && continuingIterm != null && (header.startsWith("FilePart=") || header == "FileEnd") -> continuingIterm!!

            hasPayload || (!kitty && header.startsWith("MultipartFile=")) -> {
                val created = Group(request(header, options, kitty), options)
                groups.add(created)
                prompts.add(created)
                startNextPrompt()
                created
            }

            else -> Group(null, emptyMap()).also(groups::add)
        }
        if (currentOverflow) {
            group.decision = false
        } else {
            group.sequences.add(sequence)
            group.bytes += bytes.size
            pendingBytes += bytes.size
        }

        if (kitty && hasPayload) {
            if (options["m"] == "1") {
                continuingKitty = group
            } else {
                continuingKitty = null
                group.complete = true
            }
        } else if (!kitty && header.startsWith("MultipartFile=")) {
            continuingIterm = group
        } else if (!kitty && header == "FileEnd") {
            continuingIterm = null
            group.complete = true
        } else if (group.request != null && continuingKitty !== group && continuingIterm !== group) {
            group.complete = true
        }
        drain()
        return 0
    }

    private fun scanHeader(kitty: Boolean, data: ByteArray) {
        val delimiter = if (kitty) ';'.code else ':'.code
        for (byte in data) {
            val value = byte.toInt() and 255
            if (value == delimiter) {
                headerComplete = true
                val header = currentHeader.toString()
                if (currentGroup == null && ((kitty && header.startsWith("G")) || (!kitty && header.startsWith("File=")))) {
                    val parsed = options(header, kitty)
                    currentGroup = Group(request(header, parsed, kitty), parsed).also { group ->
                        groups.add(group)
                        prompts.add(group)
                        startNextPrompt()
                    }
                }
                return
            }
            if (currentHeader.length < 4096) currentHeader.append(value.toChar())
        }
    }

    fun reset() {
        groups.clear()
        prompts.clear()
        continuingKitty = null
        continuingIterm = null
        pendingBytes = 0
        promptActive = false
        protocol.reset()
    }

    private fun startNextPrompt() {
        if (promptActive) return
        val group = if (prompts.isEmpty()) return else prompts.removeFirst()
        promptActive = true
        ask(requireNotNull(group.request)) { allowed ->
            group.decision = allowed
            promptActive = false
            drain()
            startNextPrompt()
        }
    }

    private fun drain() {
        while (groups.isNotEmpty()) {
            val group = groups.first()
            if (!group.complete || group.decision == null) return
            groups.removeFirst()
            pendingBytes -= group.bytes
            if (group.decision == true) {
                for (sequence in group.sequences) {
                    protocol.accept(sequence.kitty, sequence.bytes, true, true, sequence.row, sequence.col)
                }
            } else if (group.request?.protocol == InlineImageProtocolType.KITTY) {
                denyKitty(group.kittyOptions)
            }
        }
    }

    private fun denyKitty(options: Map<String, String>) {
        val quiet = options["q"]?.toIntOrNull() ?: 0
        if (quiet == 2) return
        val ids = listOf("i", "I", "p").mapNotNull { key -> options[key]?.let { "$key=$it" } }.joinToString(",")
        output("\u001b_G$ids;EPERM:user denied inline image\u001b\\".toByteArray(Charsets.US_ASCII))
    }

    private fun header(bytes: ByteArray, kitty: Boolean): String {
        val delimiter = if (kitty) ';'.code else ':'.code
        val end = bytes.indexOf(delimiter.toByte()).let { if (it < 0) bytes.size else it }
        return bytes.copyOfRange(0, end.coerceAtMost(4096)).toString(Charsets.US_ASCII)
    }

    private fun options(header: String, kitty: Boolean): Map<String, String> {
        val body = when {
            kitty -> header.removePrefix("G")
            '=' in header -> header.substringAfter('=')
            else -> ""
        }
        val separator = if (kitty) ',' else ';'
        return body.split(separator).mapNotNull { field ->
            val index = field.indexOf('=')
            if (index <= 0) null else field.substring(0, index) to field.substring(index + 1)
        }.toMap()
    }

    private fun request(header: String, options: Map<String, String>, kitty: Boolean): InlineImageRequest {
        fun id(key: String) = options[key]?.toLongOrNull()?.takeIf { it in 1..0xffff_ffffL }
        val name = if (kitty) {
            null
        } else {
            options["name"]?.let {
                runCatching { Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8) }.getOrNull()
            }
        }
        return InlineImageRequest(
            protocol = if (kitty) InlineImageProtocolType.KITTY else InlineImageProtocolType.ITERM2,
            action = if (kitty) options["a"] ?: "t" else header.substringBefore('='),
            imageId = id("i"),
            imageNumber = id("I"),
            name = name,
            declaredSizeBytes = options["size"]?.toLongOrNull(),
            pixelWidth = options["s"]?.toIntOrNull(),
            pixelHeight = options["v"]?.toIntOrNull(),
        )
    }
}
