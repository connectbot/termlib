/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

/**
 * Controls whether inline-image protocol payloads are accepted.
 * File dimensions must be available within the first 32 KiB; malformed or
 * unsupported images are ignored. This header limit does not cap payload size.
 */
sealed class InlineImages {
    data object Off : InlineImages()

    data class On(
        val limits: InlineImageLimits = InlineImageLimits(),
    ) : InlineImages()

    /**
     * Inspect bounded image metadata and reserve layout as data arrives.
     * Pixel decoding and display require approval; denial leaves the reserved space.
     */
    data class Ask(
        val limits: InlineImageLimits = InlineImageLimits(),
        val confirm: suspend (InlineImageRequest) -> Boolean,
    ) : InlineImages()
}

enum class InlineImageProtocolType {
    KITTY,
    ITERM2,
}

/** Header metadata available before any image payload is decoded. */
data class InlineImageRequest(
    val protocol: InlineImageProtocolType,
    val action: String,
    val imageId: Long? = null,
    val imageNumber: Long? = null,
    val name: String? = null,
    val declaredSizeBytes: Long? = null,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
)
