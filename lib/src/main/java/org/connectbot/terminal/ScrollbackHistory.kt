/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

/** Bounded history with immutable snapshots. The emulator serializes mutations. */
internal class ScrollbackHistory(private val limit: Int) {
    private class Page(val lines: Array<TerminalLine?> = arrayOfNulls(PAGE_SIZE)) {
        // Older snapshots only access the published prefix of this page.
        var publishedLength = 0
    }

    private val pages = ArrayDeque<Page>()
    private var head = 0
    private var size = 0
    private var published: List<TerminalLine>? = emptyList()

    fun append(line: TerminalLine) {
        if (limit == 0) return
        val slot = (head + size) % PAGE_SIZE
        if (pages.isEmpty() || slot == 0) pages.addLast(Page())
        val tail = pages.last()
        val writable = if (slot < tail.publishedLength) {
            Page(tail.lines.copyOf()).also { pages[pages.lastIndex] = it }
        } else {
            tail
        }
        writable.lines[slot] = line
        if (size < limit) {
            size++
        } else {
            val page = pages.first()
            if (head >= page.publishedLength) page.lines[head] = null
            head++
            if (head == PAGE_SIZE) {
                pages.removeFirst()
                head = 0
            }
        }
        published = null
    }

    fun lastOrNull(): TerminalLine? = if (size == 0) null else pages.last().lines[(head + size - 1) % PAGE_SIZE]

    fun removeLast() {
        check(size > 0)
        if (--size == 0) {
            clear()
        } else {
            val slot = (head + size) % PAGE_SIZE
            if (slot == 0) {
                pages.removeLast()
            } else {
                val tail = pages.last()
                if (slot >= tail.publishedLength) tail.lines[slot] = null
            }
            published = null
        }
    }

    fun clear() {
        pages.clear()
        head = 0
        size = 0
        published = emptyList()
    }

    fun snapshot(): List<TerminalLine> {
        published?.let { return it }
        val directory = pages.toTypedArray()
        val last = directory.lastIndex
        for (index in 0 until last) directory[index].publishedLength = PAGE_SIZE
        val tail = directory[last]
        val used = (head + size - 1) % PAGE_SIZE + 1
        tail.publishedLength = maxOf(tail.publishedLength, used)
        return View(directory, head, size).also { published = it }
    }

    private class View(
        private val pages: Array<Page>,
        private val head: Int,
        override val size: Int,
    ) : AbstractList<TerminalLine>(),
        RandomAccess {
        override fun get(index: Int): TerminalLine {
            if (index !in 0 until size) throw IndexOutOfBoundsException("Scrollback index $index")
            val slot = head + index
            return checkNotNull(pages[slot / PAGE_SIZE].lines[slot % PAGE_SIZE])
        }
    }

    companion object {
        private const val PAGE_SIZE = 32
    }
}
