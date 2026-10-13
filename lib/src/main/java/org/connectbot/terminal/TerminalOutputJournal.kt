/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

/** Collected under the emulator damage lock, before StateFlow conflates frames. */
internal class TerminalOutputJournal(initial: TerminalSnapshot) {
    private var baseline = initial.lines.map { it.text }.toMutableList()
    private var cursor = initial.cursorRow to initial.cursorCol
    private var windowCursor = cursor
    private var alternate = initial.alternateScreen
    private var pushed = 0
    private var nextLineId = 0L
    private var lineIds = initial.lines.map { nextLineId++ }.toMutableList()
    private val windowBaselines = mutableMapOf<Long, String>()
    private val pending = linkedMapOf<Long, String>()
    private var characters = 0
    private var overflow = false
    private var changed = false
    private var cursorText: String? = null
    private val finished = (initial.scrollback + initial.lines).flatMap { it.semanticSegments }
        .filter { it.semanticType == SemanticType.COMMAND_FINISHED }.map { it.promptId }.toMutableSet()
    private val completions = ArrayDeque<String?>()

    fun push(line: TerminalLine) {
        appendChange(lineIds.getOrNull(pushed) ?: nextLineId++, baseline.getOrNull(pushed).orEmpty(), line.text)
        pushed++
    }

    fun move(dest: TermRect, src: TermRect, columns: Int) {
        pushed = 0
        if (dest.startCol != 0 || src.startCol != 0 || dest.endCol != columns || src.endCol != columns) return
        val previous = baseline.toList()
        val previousIds = lineIds.toList()
        for (row in minOf(src.startRow, dest.startRow) until maxOf(src.endRow, dest.endRow)) {
            if (row !in dest.startRow until dest.endRow && row in baseline.indices) {
                baseline[row] = ""
                lineIds[row] = nextLineId++
            }
        }
        for (offset in 0 until minOf(src.endRow - src.startRow, dest.endRow - dest.startRow)) {
            val row = dest.startRow + offset
            if (row in baseline.indices) {
                baseline[row] = previous.getOrNull(src.startRow + offset).orEmpty()
                lineIds[row] = previousIds.getOrNull(src.startRow + offset) ?: nextLineId++
            }
        }
    }

    fun update(snapshot: TerminalSnapshot) {
        // libvterm omits moverect when a merged scroll replaces the entire region.
        // Retire pushed identities before assigning the remaining screen changes.
        if (pushed > 0) {
            baseline = baseline.drop(pushed).toMutableList()
            lineIds = lineIds.drop(pushed).toMutableList()
        }
        // A different terminal buffer has no meaningful text diff against the old one.
        if (alternate != snapshot.alternateScreen) {
            baseline.clear()
            lineIds.clear()
            alternate = snapshot.alternateScreen
        }
        while (lineIds.size < snapshot.lines.size) lineIds.add(nextLineId++)
        var textChanged = changed
        for ((row, line) in snapshot.lines.withIndex()) {
            val previous = baseline.getOrNull(row).orEmpty()
            if (previous != line.text) {
                appendChange(lineIds[row], previous, line.text)
                textChanged = true
            }
            for (segment in line.semanticSegments) {
                if (segment.semanticType == SemanticType.COMMAND_FINISHED) complete(segment.promptId, segment.metadata)
            }
        }
        if (finished.size > 2048) finished.retainAll(finished.sorted().takeLast(1024).toSet())
        val nextCursor = snapshot.cursorRow to snapshot.cursorCol
        if (textChanged || !snapshot.cursorVisible || windowCursor == nextCursor) {
            cursorText = null
        } else if (cursor != nextCursor) {
            val line = snapshot.lines.getOrNull(snapshot.cursorRow)
            cursorText = if (windowCursor.first != snapshot.cursorRow) {
                line?.text?.trimEnd()
            } else {
                line?.takeIf { it.cells.size > 0 }?.let {
                    val col = cursorLeadColumn(it, snapshot.cursorCol.coerceIn(0, (it.cells.size - 1).coerceAtLeast(0)))
                    it.cells.text(col, (col + it.cells.width(col).coerceAtLeast(1)).coerceAtMost(it.cells.size))
                }
            }
        }
        baseline = snapshot.lines.map { it.text }.toMutableList()
        cursor = nextCursor
        changed = false
        pushed = 0
    }

    private fun appendChange(id: Long, before: String, after: String) {
        if (id !in windowBaselines && before.trimEnd() == after.trimEnd()) return
        val old = windowBaselines.getOrPut(id) { before.trimEnd() }
        val new = after.trimEnd()
        if (new == old) {
            pending.remove(id)?.let { characters -= it.length }
            windowBaselines.remove(id)
            return
        }
        changed = true
        val text = if (new.startsWith(old)) new.substring(old.length) else new
        if (text.isBlank()) {
            pending.remove(id)?.let { characters -= it.length }
            windowBaselines.remove(id)
            return
        }
        // Bound UTF-16 storage to 16 KiB, including output evicted before a UI frame.
        var start = (text.length - MAX_CHARACTERS).coerceAtLeast(0)
        if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start++
        val bounded = text.substring(start)
        if (bounded.length != text.length) overflow = true
        characters -= pending.put(id, bounded)?.length ?: 0
        characters += bounded.length
        while (characters > MAX_CHARACTERS) {
            val first = pending.keys.first()
            characters -= pending.remove(first)!!.length
            windowBaselines.remove(first)
            overflow = true
        }
    }

    fun complete(promptId: Int, code: String?) {
        if (promptId >= 0 && finished.add(promptId)) {
            completions.addLast(code)
            if (completions.size > 32) completions.removeFirst()
        }
    }

    fun drain(): TerminalOutputBatch {
        val result = TerminalOutputBatch(pending.values.toList(), cursorText, overflow, completions.toList())
        pending.clear()
        windowBaselines.clear()
        completions.clear()
        cursorText = null
        windowCursor = cursor
        characters = 0
        overflow = false
        return result
    }

    companion object {
        private const val MAX_CHARACTERS = 8192
    }
}

internal data class TerminalOutputBatch(
    val output: List<String>,
    val cursor: String?,
    val overflow: Boolean,
    val completions: List<String?>,
)
