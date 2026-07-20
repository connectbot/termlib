/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

/** Per-terminal limits. Budgets include retained data and reservations for pending work. */
data class InlineImageLimits(
    val encodedBytes: Int = 16 * 1024 * 1024,
    val decodedBytes: Int = 32 * 1024 * 1024,
    /** Upper bound for transfer scratch; the streaming implementation needs only 16 KiB. */
    val transferBytes: Int = 4 * 1024 * 1024,
    val uploadBytes: Int = 16 * 1024 * 1024,
    val maxDimension: Int = 16_384,
    val maxPixels: Int = 16 * 1024 * 1024,
    val maxImages: Int = 1024,
    val maxPlacements: Int = 4096,
    val maxFrames: Int = 4096,
) {
    init {
        require(encodedBytes > 0 && decodedBytes > 0 && transferBytes >= 16 * 1024 && uploadBytes > 0)
        require(maxDimension in 1..16_384 && maxPixels > 0)
        require(maxImages > 0 && maxPlacements > 0 && maxFrames > 0)
    }
}
