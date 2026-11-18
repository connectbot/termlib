/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.Rect

/** Native drawing commands preserve glyph overhang and destination edge blending. */
internal class TerminalForegroundRows {
    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private val clip = Rect()
    var recording = false
        private set
    var retainedBytes = 0
        private set

    fun clear() {
        entries.clear()
        retainedBytes = 0
    }

    fun draw(canvas: Canvas, cells: PackedCells, shaped: ShapedLine, above: PackedCells?, below: PackedCells?, y: Float, width: Float, height: Float, baseline: Float, record: (Canvas) -> Unit) {
        canvas.getClipBounds(clip)
        if (clip.isEmpty) return
        val key = Key(cells, shaped, above?.verticalDashSignature().orEmpty(), below?.verticalDashSignature().orEmpty(), y, width, height, baseline, clip.left, clip.top, clip.right, clip.bottom)
        var entry = entries[key]
        if (entry == null) {
            val picture = Picture()
            val source = picture.beginRecording(clip.width(), clip.height())
            source.translate(-clip.left.toFloat(), -clip.top.toFloat())
            recording = true
            try {
                record(source)
            } finally {
                recording = false
                picture.endRecording()
            }
            // Conservative command/data accounting, separate from platform font
            // caches. Entry count also bounds native recording metadata.
            val bytes = 4096L + cells.shapingRetentionBytes * 4L + cells.size * 128L + shaped.bytes
            entry = Entry(picture, bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (bytes <= MAX_BYTES) {
                val old = entries.entries.iterator()
                while ((retainedBytes + bytes > MAX_BYTES || entries.size >= 512) && old.hasNext()) {
                    retainedBytes -= old.next().value.bytes
                    old.remove()
                }
                entries[key] = entry
                retainedBytes += entry.bytes
            }
        }
        val saved = canvas.save()
        try {
            canvas.translate(key.left.toFloat(), key.top.toFloat())
            entry.picture.draw(canvas)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private class Entry(val picture: Picture, val bytes: Int)

    private class Key(
        val cells: PackedCells,
        val shaped: ShapedLine,
        val above: String,
        val below: String,
        val y: Float,
        val width: Float,
        val height: Float,
        val baseline: Float,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        override fun hashCode(): Int {
            var hash = cells.renderingHash() * 31 + System.identityHashCode(shaped)
            for (value in intArrayOf(above.hashCode(), below.hashCode(), y.toBits(), width.toBits(), height.toBits(), baseline.toBits(), left, top, right, bottom)) hash = hash * 31 + value
            return hash
        }
        override fun equals(other: Any?): Boolean = other is Key && shaped === other.shaped && cells.sameRendering(other.cells) &&
            above == other.above && below == other.below && y.toBits() == other.y.toBits() && width.toBits() == other.width.toBits() && height.toBits() == other.height.toBits() && baseline.toBits() == other.baseline.toBits() &&
            left == other.left && top == other.top && right == other.right && bottom == other.bottom
    }

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
