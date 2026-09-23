/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** OSC/APC parser. Headers are small; binary payloads never pass through a String. */
internal class InlineImageProtocol(
    private val store: InlineImageStore,
    private val output: (ByteArray) -> Unit,
    private val otherOsc: (String, Int, Int) -> Unit,
) {
    @Volatile var enabled = true
    private var header = StringBuilder()
    private var payload = false
    private var discarded = false
    private var kitty = false
    private var row = 0
    private var col = 0
    private var command = ""
    private var keys = emptyMap<String, String>()
    private var upload: Upload? = null
    private var reserved = 0
    private var reserveIterm: ((Long, Int, Int) -> Int)? = null

    private inner class Upload(val iterm: Boolean, val options: Map<String, String>) {
        val builder = ImageBytes.Builder(store.limits.uploadBytes) { bytes ->
            store.reserveUpload(bytes)
            reserved += bytes
        }
        private val raw = !iterm && options["f"] in listOf(null, "24", "32") && options["o"] == null
        private val deflater = if (raw) Deflater() else null
        val sink: OutputStream = if (deflater != null) BufferedOutputStream(DeflaterOutputStream(builder, deflater, ImageBytes.CHUNK), ImageBytes.CHUNK) else builder
        var decoder = ImageBase64(sink)
        var received = 0L
        val counter = object : OutputStream() {
            override fun write(value: Int) {
                received++
                require(received <= if (raw) store.limits.maxPixels.toLong() * 4 else store.limits.uploadBytes.toLong()) {
                    "E2BIG:pixel stream exceeds limit"
                }
                sink.write(value)
            }
        }

        init {
            decoder = ImageBase64(counter)
        }
        fun nextChunk() {
            decoder.finish()
            decoder = ImageBase64(counter)
        }
        fun finish(): ImageBytes {
            decoder.finish()
            sink.close()
            dispose()
            return builder.build()
        }
        fun dispose() {
            deflater?.end()
        }
    }

    fun reset() {
        abortUpload()
        header.setLength(0)
        discarded = true
    }

    fun accept(isKitty: Boolean, data: ByteArray, initial: Boolean, final: Boolean, cursorRow: Int, cursorCol: Int, reserveIterm: ((Long, Int, Int) -> Int)? = null): Long {
        this.reserveIterm = reserveIterm
        if (initial) {
            kitty = isKitty
            header = StringBuilder()
            payload = false
            discarded = false
            command = ""
            keys = emptyMap()
            row = cursorRow
            col = cursorCol
        }
        if (discarded) return 0
        try {
            for (byte in data) {
                val value = byte.toInt() and 255
                if (payload) {
                    if (enabled) upload?.decoder?.accept(value)
                    continue
                }
                if ((kitty && value == ';'.code) || (!kitty && value == ':'.code && header.startsWith("File="))) {
                    startPayload()
                    payload = true
                } else {
                    require(header.length < if (command == "other") TerminalTextDecoder.TEXT_LIMIT else 4096) { "E2BIG:image header exceeds limit" }
                    header.append(value.toChar())
                    if (!kitty && header.endsWith("FilePart=")) {
                        command = "part"
                        payload = true
                    } else if (!kitty && command.isEmpty() && header.contains('=')) {
                        val prefix = header.toString().substringBefore('=')
                        if (prefix !in listOf("File", "MultipartFile", "FilePart")) command = "other"
                    }
                }
            }
            if (!final) return 0
            if (!kitty && command == "other") {
                otherOsc(ByteArray(header.length) { header[it].code.toByte() }.toString(Charsets.UTF_8), row, col)
                return 0
            }
            if (!kitty && header.toString() == "Capabilities") {
                output("\u001b]1337;Capabilities=${if (enabled) "F" else ""}\u001b\\".toByteArray(Charsets.US_ASCII))
                return 0
            }
            if (!enabled) return 0
            if (kitty) {
                if (!payload) {
                    require(header.startsWith("G")) { "ENOTSUP:unknown APC" }
                    keys = parse(header.substring(1), ',')
                }
                if (keys["m"] == "1") {
                    upload?.nextChunk()
                    return 0
                }
                return kittyCommand()
            }
            when {
                header.startsWith("MultipartFile=") -> {
                    abortUpload()
                    val options = parse(header.toString().substringAfter('='), ';')
                    if (options["inline"] == "1") upload = Upload(true, options)
                }

                header.toString() == "FileEnd" -> return finishIterm()

                command == "part" -> Unit

                payload -> return finishIterm()

                else -> otherOsc(header.toString(), row, col)
            }
        } catch (e: Exception) {
            val reason = e.message?.takeIf { it.contains(':') } ?: "EINVAL:invalid image"
            Log.w(
                TAG,
                "Rejected ${if (kitty) "Kitty" else "iTerm2"} inline image " +
                    "(action=${keys["a"]}, format=${keys["f"]}, transport=${keys["t"]}): $reason",
            )
            if (kitty && header.startsWith("G")) reply(keys, reason)
            abortUpload()
            discarded = true
        }
        return 0
    }

    private fun startPayload() {
        if (!enabled) return
        if (kitty) {
            require(header.startsWith("G")) { "ENOTSUP:unknown APC" }
            keys = parse(header.substring(1), ',')
            if (upload != null && !upload!!.iterm && keys.keys.all { it in listOf("m", "q", "a", "i", "I") }) {
                // m applies to this chunk only. In particular, omitting it on
                // the last chunk means the protocol default m=0; inheriting
                // m=1 would leave older Kitty streams open forever.
                keys = (upload!!.options - "m") + keys
            } else {
                abortUpload()
                require(keys["t"] in listOf(null, "d")) { "ENOTSUP:only stream transport is supported" }
                require(keys["f"] in listOf(null, "24", "32", "100")) { "ENOTSUP:unsupported pixel format" }
                require(keys["o"] in listOf(null, "z")) { "ENOTSUP:unsupported compression" }
                upload = Upload(false, keys)
            }
        } else {
            abortUpload()
            keys = parse(header.toString().substringAfter('='), ';')
            if (keys["inline"] == "1") upload = Upload(true, keys)
        }
    }

    private fun abortUpload() {
        upload?.dispose()
        upload = null
        store.releaseUpload(reserved)
        reserved = 0
    }

    private fun source(transfer: Upload): ImageSource {
        var bytes = transfer.finish()
        val format = if (transfer.iterm) 100 else transfer.options["f"]?.toInt() ?: 32
        if (format == 100 && transfer.options["o"] == "z") {
            val unwrapped = ImageBytes.Builder(store.limits.uploadBytes) { count ->
                store.reserveUpload(count)
                reserved += count
            }
            InflaterInputStream(bytes.input()).use { it.copyTo(unwrapped, ImageBytes.CHUNK) }
            bytes = unwrapped.build()
        }
        var mime: String? = null
        val dimensions = if (format == 100) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            bytes.input().use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outMimeType in listOf("image/png", "image/jpeg", "image/gif", "image/webp")) { "ENOTSUP:unsupported image file" }
            mime = bounds.outMimeType
            if (!transfer.iterm) require(bounds.outMimeType == "image/png") { "EINVAL:Kitty f=100 requires PNG" }
            bounds.outWidth to bounds.outHeight
        } else {
            integer(transfer.options, "s", 0) to integer(transfer.options, "v", 0)
        }
        val (width, height) = dimensions
        require(width in 1..store.limits.maxDimension && height in 1..store.limits.maxDimension && width.toLong() * height <= store.limits.maxPixels) {
            "E2BIG:invalid image dimensions"
        }
        if (format != 100) {
            val expected = width.toLong() * height * (format / 8)
            var count = 0L
            val scratch = ByteArray(ImageBytes.CHUNK)
            InflaterInputStream(bytes.input()).use { input ->
                while (true) {
                    val read = input.read(scratch)
                    if (read < 0) break
                    count += read
                    require(count <= expected) { "EINVAL:excess pixel data" }
                }
            }
            require(count == expected) { "EINVAL:short pixel data" }
        }
        if (mime == "image/gif" && android.os.Build.VERSION.SDK_INT < 28) {
            require(width.toLong() * height * 12 + bytes.size.toLong() * 2 <= store.limits.decodedBytes) { "ENOSPC:animation decode exceeds memory limit" }
        }
        val frames = ImageAnimationBounds.count(bytes, mime, store.limits.maxFrames - store.frameCount())
        require(store.frameCount() + frames <= store.limits.maxFrames) { "ENOSPC:too many frames" }
        return ImageSource(bytes, width, height, format, mime, frames)
    }

    private fun finishIterm(): Long {
        val transfer = upload ?: return 0
        require(transfer.iterm) { "EINVAL:unexpected FileEnd" }
        val source = source(transfer)
        val options = transfer.options
        var width = dimension(options["width"], store.cellWidth, store.cols)
        var height = dimension(options["height"], store.cellHeight, store.rows)
        if (width == null && height == null) {
            width = source.width.toDouble()
            height = source.height.toDouble()
        }
        if (width == null) width = height!! * source.width / source.height
        if (height == null) height = width * source.height / source.width
        if (options["preserveAspectRatio"] != "0") {
            val scale = min(width / source.width, height / source.height)
            width = source.width * scale
            height = source.height * scale
        }
        val available = (store.cols - col).coerceAtLeast(1) * store.cellWidth
        if (width > available) {
            height *= available / width
            width = available.toDouble()
        }
        val columns = ceil(width / store.cellWidth).toInt().coerceAtLeast(1)
        val rows = ceil(height / store.cellHeight).toInt().coerceIn(1, store.limits.maxDimension)
        require(store.placements.size < store.limits.maxPlacements) { "ENOSPC:too many placements" }
        val movement = (rows.toLong() shl 32) or (columns.toLong() shl 1) or 1
        // Delayed consent must make space before the placement is registered,
        // so scrolling moves existing images without moving the new image twice.
        reserveIterm?.let { row = it(movement, row, col) }
        store.edit(TermRect(row, row + rows, col, col + columns))
        val frame = ImageFrame(source, width = source.width, height = source.height)
        val asset = store.add(store.allocateId(), null, frame)
        store.placements.add(
            ImagePlacement(
                asset, 0, row, col, columns, rows,
                Rect(0, 0, source.width, source.height), -1, true, store.alternate,
                renderWidth = (width / store.cellWidth).toFloat(), renderHeight = (height / store.cellHeight).toFloat(),
            ),
        )
        abortUpload()
        return if (reserveIterm == null) movement else 0
    }

    private fun kittyCommand(): Long {
        var options = keys
        var movement = 0L
        try {
            require(!(options.containsKey("i") && options.containsKey("I"))) { "EINVAL:i and I are mutually exclusive" }
            when (options["a"] ?: "t") {
                "t", "T", "q" -> {
                    val transfer = requireNotNull(upload) { "EINVAL:missing image payload" }
                    val source = source(transfer)
                    if (options["a"] == "q") {
                        reply(options, "OK")
                        return 0
                    }
                    val id = identifier(options, "i") ?: generateKittyId()
                    val asset = store.add(id, identifier(options, "I"), ImageFrame(source, width = source.width, height = source.height))
                    options = options + ("i" to id.toString())
                    if (options["a"] == "T") movement = place(asset, options)
                }

                "p" -> movement = place(find(options), options)

                "d" -> delete(options)

                "f" -> frame(options)

                "a" -> animate(options)

                "c" -> compose(options)

                else -> throw IllegalArgumentException("ENOTSUP:unsupported graphics action")
            }
            reply(options, "OK")
        } finally {
            if ((options["a"] ?: "t") in listOf("t", "T", "q", "f", "d")) abortUpload()
        }
        return movement
    }

    private fun generateKittyId(): Long = (1L..0xffff_ffffL).first { !store.assets.containsKey(it) }

    private fun find(options: Map<String, String>): ImageAsset {
        val id = identifier(options, "i")
        val number = identifier(options, "I")
        return requireNotNull(if (id != null) store.assets[id] else store.assets.values.lastOrNull { number != null && it.number == number }) {
            "ENOENT:unknown image"
        }
    }

    private fun place(asset: ImageAsset, options: Map<String, String>): Long {
        val base = asset.frames.first()
        val x = integer(options, "x", 0)
        val y = integer(options, "y", 0)
        val w = integer(options, "w", base.width - x)
        val h = integer(options, "h", base.height - y)
        require(x >= 0 && y >= 0 && w > 0 && h > 0 && x < base.width && y < base.height) { "EINVAL:invalid crop" }
        val crop = Rect(x, y, minOf(base.width.toLong(), x.toLong() + w).toInt(), minOf(base.height.toLong(), y.toLong() + h).toInt())
        var columns = integer(options, "c", 0)
        var rows = integer(options, "r", 0)
        val naturalSize = columns == 0 && rows == 0
        require(columns >= 0 && rows >= 0) { "EINVAL:negative placement size" }
        val offsetX = integer(options, "X", 0)
        val offsetY = integer(options, "Y", 0)
        require(offsetX in 0 until store.cellWidth && offsetY in 0 until store.cellHeight) { "EINVAL:invalid cell offset" }
        var pixelWidth = columns.toDouble() * store.cellWidth - offsetX
        var pixelHeight = rows.toDouble() * store.cellHeight - offsetY
        if (columns == 0 && rows == 0) {
            pixelWidth = crop.width().toDouble()
            pixelHeight = crop.height().toDouble()
            columns = ceil((pixelWidth + offsetX) / store.cellWidth).toInt()
            rows = ceil((pixelHeight + offsetY) / store.cellHeight).toInt()
        } else if (columns == 0) {
            pixelWidth = pixelHeight * crop.width() / crop.height()
            columns = ceil((pixelWidth + offsetX) / store.cellWidth).toInt()
        } else if (rows == 0) {
            pixelHeight = pixelWidth * crop.height() / crop.width()
            rows = ceil((pixelHeight + offsetY) / store.cellHeight).toInt()
        }
        require(columns in 1..store.limits.maxDimension && rows in 1..store.limits.maxDimension) { "E2BIG:invalid placement size" }
        val id = identifier(options, "p") ?: 0
        val parent = identifier(options, "P")?.let { it to (identifier(options, "Q") ?: 0) }
        if (parent != null) {
            require(parent != asset.id to id) { "EINVAL:cyclic relative placement" }
            require(store.placements.any { it.asset.id == parent.first && it.id == parent.second }) { "ENOENT:missing parent placement" }
            var current: Pair<Long, Long>? = parent
            val visited = mutableSetOf(asset.id to id)
            while (current != null) {
                require(visited.add(current) && visited.size <= 32) { "EINVAL:cyclic or excessive relative placement depth" }
                val (currentAssetId, currentPlacementId) = current
                val ancestor = store.placements.firstOrNull {
                    it.asset.id == currentAssetId && it.id == currentPlacementId
                }
                current = ancestor?.parent
            }
        }
        val virtual = options["U"] == "1"
        if (virtual) {
            val scale = minOf(pixelWidth / crop.width(), pixelHeight / crop.height())
            pixelWidth = crop.width() * scale
            pixelHeight = crop.height() * scale
        }
        require(store.placements.size < store.limits.maxPlacements) { "ENOSPC:too many placements" }
        if (id != 0L) store.placements.removeAll { it.asset === asset && it.id == id && it.alternate == store.alternate }
        store.placements.add(
            ImagePlacement(
                asset, id,
                if (parent == null) row else integer(options, "V", 0),
                if (parent == null) col else integer(options, "H", 0), columns, rows, crop,
                integer(options, "z", 0), false, store.alternate, virtual, parent, offsetX, offsetY,
                (pixelWidth / store.cellWidth).toFloat(), (pixelHeight / store.cellHeight).toFloat(),
                naturalSize = naturalSize,
            ),
        )
        return if (virtual || parent != null || options["C"] == "1") 0 else (rows.toLong() shl 32) or (columns.toLong() shl 1)
    }

    private fun delete(options: Map<String, String>) {
        val selector = options["d"] ?: "a"
        require(selector.length == 1 && selector.lowercase() in listOf("a", "i", "n", "c", "f", "p", "q", "r", "x", "y", "z")) { "EINVAL:unknown deletion selector" }
        val x = integer(options, "x", 1) - 1
        val y = integer(options, "y", 1) - 1
        val z = integer(options, "z", 0)
        val selected = store.placements.filter { p ->
            if (p.alternate != store.alternate || p.iterm) {
                false
            } else if (p.virtual && selector.lowercase() !in listOf("i", "n", "r")) {
                false
            } else {
                when (selector.lowercase()) {
                    "i" -> p.asset.id == identifier(options, "i") && (options["p"] == null || p.id == identifier(options, "p"))
                    "n" -> p.asset === find(options)
                    "r" -> p.asset.id in (options["x"]?.toLongOrNull() ?: 0)..(options["y"]?.toLongOrNull() ?: 0)
                    "c" -> col in p.left until p.left + p.width && row in p.top until p.top + p.height
                    "p", "q" -> x in p.left until p.left + p.width && y in p.top until p.top + p.height && (selector.lowercase() != "q" || p.z == z)
                    "x" -> x in p.left until p.left + p.width
                    "y" -> y in p.top until p.top + p.height
                    "z" -> p.z == z
                    "f" -> false
                    else -> p.top < store.rows && p.top + p.height > 0
                }
            }
        }
        store.placements.removeAll(selected.toSet())
        if (selector.equals("f", true)) {
            val asset = find(options)
            val index = integer(options, "r", 1) - 1
            require(index in asset.frames.indices && asset.frames.size > 1) { "EINVAL:invalid frame deletion" }
            asset.frames.removeAt(index)
            asset.gaps.removeAt(index)
            asset.frameIndex = asset.frameIndex.coerceAtMost(asset.frames.lastIndex)
            asset.changed()
        } else if (selector[0].isUpperCase()) {
            val ids = selected.map { it.asset.id }.toMutableSet()
            if (selector == "I") identifier(options, "i")?.let(ids::add)
            if (selector == "N") ids.add(find(options).id)
            ids.filter { id -> store.placements.none { it.asset.id == id } }.forEach(store::remove)
        }
    }

    private fun frame(options: Map<String, String>) {
        val asset = find(options)
        require(store.frameCount() < store.limits.maxFrames) { "ENOSPC:too many frames" }
        val source = source(requireNotNull(upload) { "EINVAL:missing frame data" })
        val target = integer(options, "r", 0) - 1
        val baseIndex = integer(options, "c", 0) - 1
        require(target == -1 || target in asset.frames.indices) { "EINVAL:invalid frame number" }
        require(baseIndex == -1 || baseIndex in asset.frames.indices) { "EINVAL:invalid background frame" }
        val background = if (target >= 0) asset.frames[target] else asset.frames.getOrNull(baseIndex)
        val foreground = ImageFrame(source, width = source.width, height = source.height)
        val base = asset.frames.first()
        val result = ImageFrame(
            background = background,
            foreground = foreground,
            x = integer(options, "x", 0),
            y = integer(options, "y", 0),
            width = base.width,
            height = base.height,
            color = rgba(options["Y"]?.toLongOrNull() ?: 0),
            replace = options["X"] == "1",
        )
        require(result.depth <= 32 && result.operations <= store.limits.maxFrames) { "E2BIG:frame dependency limit" }
        val gap = integer(options, "z", 0)
        if (target < 0) {
            asset.frames.add(result)
            asset.gaps.add(if (gap == 0) 40 else max(0, gap))
        } else {
            asset.frames[target] = result
            if (gap != 0) asset.gaps[target] = max(0, gap)
        }
        asset.changed()
    }

    private fun animate(options: Map<String, String>) {
        val asset = find(options)
        options["s"]?.let {
            require(it.toInt() in 1..3)
            asset.animationState = it.toInt()
            if (asset.animationState == 1) asset.completedLoops = 0
        }
        options["c"]?.let {
            require(it.toInt() - 1 in asset.frames.indices)
            asset.frameIndex = it.toInt() - 1
        }
        options["v"]?.let {
            val count = it.toInt().also { n -> require(n >= 0) }
            if (count > 0) {
                asset.loops = count - 1
                asset.completedLoops = 0
            }
        }
        options["z"]?.let {
            val index = integer(options, "r", asset.frameIndex + 1) - 1
            require(index in asset.frames.indices)
            if (it.toInt() != 0) asset.gaps[index] = max(0, it.toInt())
        }
        asset.deadline = 0
        asset.changed()
    }

    private fun compose(options: Map<String, String>) {
        val asset = find(options)
        val sourceIndex = integer(options, "r", 0) - 1
        val targetIndex = integer(options, "c", 0) - 1
        require(sourceIndex in asset.frames.indices && targetIndex in asset.frames.indices) { "ENOENT:invalid composition frame" }
        val source = asset.frames[sourceIndex]
        val target = asset.frames[targetIndex]
        val x = integer(options, "x", 0)
        val y = integer(options, "y", 0)
        val w = integer(options, "w", source.width)
        val h = integer(options, "h", source.height)
        require(x >= 0 && y >= 0 && w > 0 && h > 0 && x.toLong() + w <= source.width && y.toLong() + h <= source.height) { "EINVAL:invalid composition rectangle" }
        val destX = integer(options, "X", 0)
        val destY = integer(options, "Y", 0)
        require(destX >= 0 && destY >= 0 && destX.toLong() + w <= target.width && destY.toLong() + h <= target.height) { "EINVAL:invalid destination rectangle" }
        require(sourceIndex != targetIndex || !Rect.intersects(Rect(x, y, x + w, y + h), Rect(destX, destY, destX + w, destY + h))) { "EINVAL:overlapping composition rectangles" }
        val result = ImageFrame(
            background = target,
            foreground = source,
            x = destX,
            y = destY,
            width = target.width,
            height = target.height,
            replace = options["C"] == "1",
            sourceRect = Rect(x, y, x + w, y + h),
        )
        require(result.depth <= 32 && result.operations <= store.limits.maxFrames) { "E2BIG:frame dependency limit" }
        asset.frames[targetIndex] = result
        asset.changed()
    }

    private fun reply(options: Map<String, String>, status: String) {
        val quiet = options["q"]?.toIntOrNull() ?: 0
        if (quiet == 2 || (quiet == 1 && status == "OK")) return
        val identifiers = listOf("i", "I", "p").mapNotNull { key -> options[key]?.let { "$key=$it" } }.joinToString(",")
        output("\u001b_G$identifiers;$status\u001b\\".toByteArray(Charsets.US_ASCII))
    }

    private fun parse(value: String, separator: Char): Map<String, String> = if (value.isEmpty()) {
        emptyMap()
    } else {
        value.split(separator).associate { field ->
            require('=' in field) { "EINVAL:invalid image header" }
            field.substringBefore('=') to field.substringAfter('=')
        }
    }

    private fun integer(options: Map<String, String>, key: String, default: Int): Int = options[key]?.let {
        requireNotNull(it.toIntOrNull()) { "EINVAL:invalid $key" }
    } ?: default

    private fun identifier(options: Map<String, String>, key: String): Long? = options[key]?.let {
        val result = it.toLongOrNull()
        require(result != null && result in 0..0xffff_ffffL) { "EINVAL:invalid $key" }
        result.takeIf { number -> number != 0L }
    }

    private fun dimension(value: String?, cell: Int, extent: Int): Double? {
        if (value == null || value == "auto") return null
        val result = when {
            value.endsWith("px") -> value.dropLast(2).toDouble()
            value.endsWith('%') -> value.dropLast(1).toDouble() * extent * cell / 100
            else -> value.toDouble() * cell
        }
        require(result.isFinite() && result > 0 && result <= Int.MAX_VALUE) { "EINVAL:invalid image size" }
        return result
    }

    private fun rgba(value: Long): Int = ((value and 255) shl 24 or (value ushr 8)).toInt()

    private companion object {
        const val TAG = "InlineImageProtocol"
    }
}
