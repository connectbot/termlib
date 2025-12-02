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

/**
 * Efficient color cache to prevent allocating Color objects on every frame.
 * Most terminal colors come from the 256-color palette, with few custom RGB colors.
 *
 * This cache dramatically reduces allocation pressure during rendering:
 * - Without cache: ~3,840 Color allocations per frame (60fps = ~230K/sec)
 * - With cache: ~0 allocations per frame after warmup
 */
object ColorCache {
    // Pre-computed 256-color xterm palette
    private val paletteCache = Array(256) { i ->
        when {
            i < 16 -> standardAnsiColor(i)
            i < 232 -> rgb6Color(i - 16)
            else -> grayscaleColor(i - 232)
        }
    }

    // Pre-computed RGB values for fast palette lookup (avoid floating point math)
    private val paletteRgb = IntArray(256) { i ->
        val c = paletteCache[i]
        ((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
    }

    // Bounded concurrent hash map for custom colors (max 1024 entries)
    private val customColorCache = java.util.concurrent.ConcurrentHashMap<Int, Color>(256)
    private val maxCustomColors = 1024

    /**
     * Get a cached Color object for the given RGB values.
     * First checks the 256-color palette, then the custom cache.
     */
    fun get(r: Int, g: Int, b: Int): Color {
        val key = (r shl 16) or (g shl 8) or b

        // Try exact palette match first (common case) - O(1) hash lookup
        val paletteIndex = findPaletteIndex(key)
        if (paletteIndex >= 0) {
            return paletteCache[paletteIndex]
        }

        // Check custom cache (lock-free)
        val existing = customColorCache[key]
        if (existing != null) {
            return existing
        }

        // Create new color, but enforce size limit
        val newColor = Color(r, g, b)
        if (customColorCache.size < maxCustomColors) {
            customColorCache[key] = newColor
        }
        // If cache is full, just return the color without caching (rare case)
        return newColor
    }

    private fun findPaletteIndex(rgb: Int): Int {
        // Extract components once
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF

        // 216 RGB colors (6×6×6 cube) - most common case
        if (r % 51 == 0 && g % 51 == 0 && b % 51 == 0) {
            val rIdx = r / 51
            val gIdx = g / 51
            val bIdx = b / 51
            if (rIdx in 0..5 && gIdx in 0..5 && bIdx in 0..5) {
                return 16 + (rIdx * 36 + gIdx * 6 + bIdx)
            }
        }

        // 24 grayscale colors
        if (r == g && g == b && r >= 8 && r <= 238) {
            val grayIdx = (r - 8) / 10
            if (grayIdx in 0..23) {
                val expectedGray = 8 + grayIdx * 10
                if (r == expectedGray) {
                    return 232 + grayIdx
                }
            }
        }

        // Standard 16 ANSI colors - linear search but only 16 items
        for (i in 0 until 16) {
            if (paletteRgb[i] == rgb) return i
        }

        return -1 // Not in palette
    }

    private fun standardAnsiColor(i: Int): Color {
        return when (i) {
            0 -> Color(0, 0, 0)         // Black
            1 -> Color(205, 0, 0)       // Red
            2 -> Color(0, 205, 0)       // Green
            3 -> Color(205, 205, 0)     // Yellow
            4 -> Color(0, 0, 238)       // Blue
            5 -> Color(205, 0, 205)     // Magenta
            6 -> Color(0, 205, 205)     // Cyan
            7 -> Color(229, 229, 229)   // White
            8 -> Color(127, 127, 127)   // Bright Black
            9 -> Color(255, 0, 0)       // Bright Red
            10 -> Color(0, 255, 0)      // Bright Green
            11 -> Color(255, 255, 0)    // Bright Yellow
            12 -> Color(92, 92, 255)    // Bright Blue
            13 -> Color(255, 0, 255)    // Bright Magenta
            14 -> Color(0, 255, 255)    // Bright Cyan
            15 -> Color(255, 255, 255)  // Bright White
            else -> Color.White
        }
    }

    private fun rgb6Color(offset: Int): Color {
        val r = (offset / 36) * 51
        val g = ((offset / 6) % 6) * 51
        val b = (offset % 6) * 51
        return Color(r, g, b)
    }

    private fun grayscaleColor(offset: Int): Color {
        val gray = 8 + offset * 10
        return Color(gray, gray, gray)
    }
}
