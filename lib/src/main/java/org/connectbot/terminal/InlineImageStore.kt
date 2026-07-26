/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
@file:Suppress("DEPRECATION")

package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.InputStream
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.InflaterInputStream
import kotlin.math.ceil
import kotlin.math.max

internal data class ImageSource(val bytes: ImageBytes, val width: Int, val height: Int, val format: Int = 100, val mime: String? = null, val frameCount: Int = 1) {
    fun stream(): InputStream = if (format == 100) bytes.input() else InflaterInputStream(bytes.input())

    fun decode(targetWidth: Int, targetHeight: Int): Bitmap {
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        if (format == 100) {
            return stream().use {
                requireNotNull(BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })) {
                    "EINVAL:invalid image"
                }
            }
        }
        val w = (width + sample - 1) / sample
        val h = (height + sample - 1) / sample
        val pixels = IntArray(w * h)
        stream().buffered(ImageBytes.CHUNK).use { input ->
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val r = input.read()
                    val g = input.read()
                    val b = input.read()
                    val a = if (format == 32) input.read() else 255
                    require(r >= 0 && g >= 0 && b >= 0 && a >= 0) { "EINVAL:short pixel data" }
                    if (x % sample == 0 && y % sample == 0) {
                        pixels[y / sample * w + x / sample] =
                            (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
            require(input.read() == -1) { "EINVAL:excess pixel data" }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}

/** Immutable frame graph. Edits share encoded sources, never decoded canvases. */
internal data class ImageFrame(
    val source: ImageSource? = null,
    val background: ImageFrame? = null,
    val foreground: ImageFrame? = null,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int,
    val height: Int,
    val color: Int = 0,
    val replace: Boolean = false,
    val sourceRect: Rect? = null,
) {
    val depth: Int = 1 + max(background?.depth ?: 0, foreground?.depth ?: 0)
    val operations: Int = (1L + (background?.operations ?: 0) + (foreground?.operations ?: 0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun sources(result: MutableSet<ImageSource>, visited: MutableSet<ImageFrame> = Collections.newSetFromMap(IdentityHashMap())) {
        if (!visited.add(this)) return
        source?.let(result::add)
        background?.sources(result, visited)
        foreground?.sources(result, visited)
    }

    fun workingBytes(w: Int, h: Int): Long {
        if (source != null) {
            var sample = 1
            while (source.width / (sample * 2) >= w && source.height / (sample * 2) >= h) sample *= 2
            return ((source.width.toLong() + sample - 1) / sample) * ((source.height.toLong() + sample - 1) / sample) * 8
        }
        val own = w.toLong() * h * 4
        val fg = foreground?.let {
            it.workingBytes(max(1, ceil(it.width.toDouble() * w / width).toInt()), max(1, ceil(it.height.toDouble() * h / height).toInt()))
        } ?: 0
        return own + max(background?.workingBytes(w, h) ?: 0, fg)
    }

    fun decode(w: Int, h: Int): Bitmap {
        source?.let { return it.decode(w, h) }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        background?.decode(w, h)?.let {
            canvas.drawBitmap(it, null, Rect(0, 0, w, h), paint)
            it.recycle()
        }
        foreground?.let { frame ->
            val sx = w.toFloat() / width
            val sy = h.toFloat() / height
            val decoded = frame.decode(max(1, ceil(frame.width * sx).toInt()), max(1, ceil(frame.height * sy).toInt()))
            val crop = sourceRect?.let {
                Rect(
                    it.left * decoded.width / frame.width,
                    it.top * decoded.height / frame.height,
                    it.right * decoded.width / frame.width,
                    it.bottom * decoded.height / frame.height,
                )
            }
            val fw = sourceRect?.width() ?: frame.width
            val fh = sourceRect?.height() ?: frame.height
            if (replace) paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
            canvas.drawBitmap(decoded, crop, RectF(x * sx, y * sy, (x + fw) * sx, (y + fh) * sy), paint)
            decoded.recycle()
        }
        return bitmap
    }
}

internal class InlineImageStore(val limits: InlineImageLimits, private val handler: Handler) {
    // Published scheduling hint; UI reads this scalar, never the backend asset map.
    var frameUpdatesNeeded by mutableStateOf(false)
        private set
    private val maintenance = TerminalDispatcher()
    private val latestView = AtomicReference<ImageViewport?>()
    private val viewScheduled = AtomicBoolean()
    private val releasedResources = ReferenceQueue<Any>()
    private class RetainedResource(value: Any, queue: ReferenceQueue<Any>, val bytes: Long) : WeakReference<Any>(value, queue)
    private val retainedResources = mutableSetOf<RetainedResource>()

    fun updateViewport(view: ImageViewport) {
        latestView.set(view)
        scheduleViewport()
    }

    private fun scheduleViewport() {
        if (!viewScheduled.compareAndSet(false, true)) return
        maintenance.execute {
            try {
                latestView.getAndSet(null)?.let { view ->
                    synchronized(this) {
                        viewportTop = view.top
                        displayedIds = view.slices.map { it.asset.id }.toSet()
                        if (!view.attached) {
                            assets.values.forEach { it.clearDecoded() }
                            frameUpdatesNeeded = false
                        } else {
                            view.slices.forEach { slice ->
                                request(slice.asset, max(1, (slice.renderWidth * view.cellWidth * slice.asset.width / slice.crop.width()).toInt()), max(1, (slice.renderHeight * view.cellHeight * slice.asset.height / slice.crop.height()).toInt()))
                            }
                            advanceAnimations(view.now)
                            frameUpdatesNeeded = view.slices.any { slice ->
                                val asset = slice.asset
                                assets[asset.id] === asset && (
                                    asset.pending || (asset.bitmap == null && asset.drawable == null) ||
                                        asset.movie != null || (asset.animationState != 1 && asset.frames.size > 1)
                                    )
                            }
                        }
                    }
                }
            } finally {
                viewScheduled.set(false)
                if (latestView.get() != null) scheduleViewport()
            }
        }
    }

    @Synchronized
    private fun retain(bitmap: Bitmap?) {
        if (bitmap == null) return
        retainResource(bitmap, bitmap.allocationByteCount.toLong())
    }

    private fun retainResource(resource: Any, bytes: Long) {
        if (retainedResources.none { it.get() === resource }) {
            retainedResources.add(RetainedResource(resource, releasedResources, bytes))
        }
    }
    val assets = linkedMapOf<Long, ImageAsset>()
    val placements = mutableListOf<ImagePlacement>()
    var alternate = false
    var rows = 24
    var cols = 80
    var cellWidth = 8
    var cellHeight = 16
    var viewportTop = 0
    var displayedIds: Set<Long> = emptySet()
    private val placeholderAnchors = mutableMapOf<Pair<Long, Long>, MutableSet<Pair<Int, Int>>>()
    private var nextId = 0x1_0000_0000L
    var uploadBytes = 0
        private set
    private var decodedReservation = 0L

    @Synchronized
    fun encodedUsage(): Long {
        val sources = mutableSetOf<ImageSource>()
        assets.values.forEach { asset -> asset.frames.forEach { it.sources(sources) } }
        return sources.sumOf { it.bytes.chunks.sumOf { chunk -> chunk.size.toLong() } }
    }

    @Synchronized
    fun frameCount(): Int = assets.values.sumOf { asset -> asset.frames.sumOf { it.source?.frameCount ?: 1 } }

    @Synchronized
    fun reserveUpload(bytes: Int) {
        while (encodedUsage() + uploadBytes + bytes > limits.encodedBytes) {
            val victim = assets.values.firstOrNull { asset -> placements.none { it.asset === asset } }
                ?: assets.values.firstOrNull { !visibleAsset(it) }
                ?: throw IllegalArgumentException("ENOSPC:image memory limit")
            remove(victim.id)
        }
        uploadBytes += bytes
    }

    @Synchronized
    fun releaseUpload(bytes: Int) {
        uploadBytes = (uploadBytes - bytes).coerceAtLeast(0)
    }

    private fun visible(p: ImagePlacement): Boolean = p.alternate == alternate && origins(p).any {
        it.first < viewportTop + rows && it.first + p.height > viewportTop && it.second < cols && it.second + p.width > 0
    }

    private fun visibleAsset(asset: ImageAsset): Boolean = asset.id in displayedIds || placements.any { it.asset === asset && visible(it) }

    @Synchronized
    fun allocateId(): Long {
        while (assets.containsKey(nextId)) nextId++
        return nextId++
    }

    @Synchronized
    fun add(id: Long, number: Long?, frame: ImageFrame): ImageAsset {
        require(assets.size < limits.maxImages || assets.containsKey(id)) { "ENOSPC:too many images" }
        remove(id)
        return ImageAsset(id, number, this, frame).also { assets[id] = it }
    }

    @Synchronized
    fun remove(id: Long) {
        assets.remove(id)?.release()
        placements.removeAll { it.asset.id == id }
    }

    @Synchronized
    fun clear() {
        assets.values.forEach { it.release() }
        assets.clear()
        placements.clear()
    }

    @Synchronized
    fun clearScreen() {
        placements.removeAll { it.alternate == alternate && !it.virtual && it.top + it.height > 0 }
        collectIterm()
    }

    @Synchronized
    fun switchScreen(value: Boolean) {
        alternate = value
        if (value) placements.removeAll { it.alternate }
        collectIterm()
    }

    @Synchronized
    fun edit(rect: TermRect) {
        placements.filter { it.iterm && it.alternate == alternate }.forEach { p ->
            for (row in maxOf(rect.startRow, p.top) until minOf(rect.endRow, p.top + p.height)) {
                p.erase(row - p.top, rect.startCol, rect.endCol)
            }
        }
        placements.removeAll { it.iterm && it.coverage.all { row -> row.isEmpty() } }
        collectIterm()
    }

    @Synchronized
    fun scroll(rect: TermRect, down: Int, right: Int) {
        val history = !alternate && rect.startRow == 0 && rect.startCol == 0 && rect.endCol == cols && down > 0 && right == 0
        placements.filter { it.alternate == alternate && !it.virtual && it.parent == null }.toList().forEach { p ->
            if (history && p.top < rect.endRow && p.left >= rect.startCol && p.left + p.width <= rect.endCol) {
                p.top -= down
            } else if (p.top >= rect.startRow && p.top + p.height <= rect.endRow && p.left >= rect.startCol && p.left + p.width <= rect.endCol) {
                p.top -= down
                p.left -= right
                p.coverage = p.coverage.map { ranges -> ranges.map { (it.first - right)..(it.last - right) } }.toMutableList()
                p.clipTop = maxOf(p.clipTop, rect.startRow - p.top)
                p.clipBottom = minOf(p.clipBottom, rect.endRow - p.top)
                p.clipLeft = maxOf(p.clipLeft, rect.startCol - p.left)
                p.clipRight = minOf(p.clipRight, rect.endCol - p.left)
            } else if (p.iterm) {
                // Cell-attached images may be split by an editing rectangle.
                val moved = mutableListOf<Pair<Int, IntRange>>()
                p.coverage.forEachIndexed { row, ranges ->
                    if (p.top + row in rect.startRow until rect.endRow) {
                        ranges.forEach { range ->
                            val first = maxOf(range.first, rect.startCol)
                            val last = minOf(range.last, rect.endCol - 1)
                            if (first <= last) moved.add(row to first..last)
                        }
                    }
                }
                moved.forEach { (row, range) -> p.erase(row, range.first, range.last + 1) }
                moved.forEach { (row, range) ->
                    val dest = p.top + row - down
                    val first = maxOf(rect.startCol, range.first - right)
                    val last = minOf(rect.endCol - 1, range.last - right)
                    if (dest in rect.startRow until rect.endRow && first <= last && placements.size < limits.maxPlacements) {
                        val fragment = p.fragment(row, first + right, last + right + 1)
                        fragment.top = dest
                        fragment.left = first
                        fragment.coverage[0] = listOf(first..last)
                        placements.add(fragment)
                    }
                }
            }
        }
        placements.removeAll { it.iterm && it.coverage.all { row -> row.isEmpty() } }
        trimHistory(1000)
    }

    @Synchronized
    fun trimHistory(lines: Int) {
        placements.removeAll { !it.alternate && !it.virtual && it.top + it.height <= -lines }
        collectIterm()
    }

    @Synchronized
    fun resizeImages(screen: Boolean, delta: Int, rows: Int, cols: Int) {
        placements.filter { it.alternate == screen && !it.virtual && it.parent == null }.forEach {
            it.top += delta
            it.clipRight = minOf(it.clipRight, cols - it.left)
            if (screen) it.clipTop = maxOf(it.clipTop, -it.top)
        }
        placements.removeAll { it.alternate == screen && !it.virtual && (it.top >= rows || it.clipRight <= it.clipLeft) }
        collectIterm()
    }

    private fun collectIterm() {
        assets.values.filter { it.id >= 0x1_0000_0000L && placements.none { p -> p.asset === it } }
            .map { it.id }.forEach(::remove)
    }

    @Synchronized
    fun slices(row: Int, screen: Boolean = alternate): List<ImageSlice> = placements.flatMap { p ->
        if (p.alternate != screen || p.virtual) return@flatMap emptyList()
        origins(p).flatMap origins@{ origin ->
            val y = row - origin.first
            if (y !in maxOf(0, p.clipTop) until minOf(p.height, p.clipBottom)) return@origins emptyList()
            val ranges = if (p.iterm) p.coverage.getOrNull(y).orEmpty() else listOf(origin.second until origin.second + p.width)
            ranges.mapNotNull { range ->
                val left = maxOf(0, range.first, origin.second + p.clipLeft)
                val right = minOf(cols, range.last + 1, origin.second + p.clipRight)
                if (left >= right) {
                    null
                } else {
                    ImageSlice(
                        p.asset, left, right, y + p.sourceRow, p.height,
                        left - origin.second + p.sourceCol, p.width, p.crop, p.z, p.offsetX, p.offsetY, p.renderWidth, p.renderHeight,
                    )
                }
            }
        }
    }.sortedWith(compareBy<ImageSlice> { it.z }.thenBy { it.asset.id })

    fun origins(p: ImagePlacement, depth: Int = 0): List<Pair<Int, Int>> {
        if (depth > 32) return emptyList()
        if (p.virtual) return placeholderAnchors[p.asset.id to p.id]?.toList().orEmpty()
        val parent = p.parent ?: return listOf(p.top to p.left)
        val base = placements.firstOrNull { it.asset.id == parent.first && it.id == parent.second && it.alternate == p.alternate } ?: return emptyList()
        return origins(base, depth + 1).map { it.first + p.top to it.second + p.left }
    }

    @Synchronized
    fun preparePlaceholders(lines: List<TerminalLine>, history: List<TerminalLine>) {
        placeholderAnchors.clear()
        if (placements.none { it.virtual }) return
        lines.forEachIndexed { row, line -> placeholders(line.cells, row) }
        if (!alternate) history.forEachIndexed { row, line -> placeholders(line.cells, row - history.size) }
    }

    @Synchronized
    fun placeholders(cells: PackedCells, screenRow: Int? = null): List<ImageSlice> {
        data class Previous(val image: Long, val placement: Long, val row: Int, val col: Int, val high: Int)
        var previous: Previous? = null
        return buildList {
            for (col in 0 until cells.size) {
                if (!cells.placeholder(col)) {
                    previous = null
                    continue
                }
                val image = cells.placeholderImage(col)
                val placement = cells.placeholderPlacement(col)
                val marks = cells.placeholderMarks(col).map(KittyPlaceholder::index)
                val prev = previous?.takeIf { it.image == image && it.placement == placement }
                val row = marks.getOrNull(0) ?: prev?.row ?: 0
                val column = marks.getOrNull(1) ?: prev?.takeIf { it.row == row }?.let { it.col + 1 } ?: 0
                val high = marks.getOrNull(2) ?: prev?.takeIf { it.row == row && it.col + 1 == column }?.high ?: 0
                previous = Previous(image, placement, row, column, high)
                if (row < 0 || column < 0 || high !in 0..255) continue
                val id = image or (high.toLong() shl 24)
                val p = placements.firstOrNull { it.virtual && it.asset.id == id && (placement == 0L || it.id == placement) && it.alternate == alternate } ?: continue
                if (row >= p.height || column >= p.width) continue
                if (screenRow != null && placeholderAnchors.values.sumOf { it.size } < limits.maxPlacements) {
                    placeholderAnchors.getOrPut(p.asset.id to p.id) { mutableSetOf() }.add(screenRow - row to col - column)
                }
                add(ImageSlice(p.asset, col, col + 1, row, p.height, column, p.width, p.crop, p.z, renderWidth = p.renderWidth, renderHeight = p.renderHeight))
            }
        }
    }

    @Synchronized
    fun request(asset: ImageAsset, width: Int, height: Int) {
        if (assets[asset.id] !== asset || asset.frames.isEmpty()) return
        val frame = asset.frames[asset.frameIndex.coerceIn(asset.frames.indices)]
        val w = maxOf(width, if (asset.bitmap != null || asset.drawable != null) asset.targetWidth else 0).coerceIn(1, frame.width)
        val h = maxOf(height, if (asset.bitmap != null || asset.drawable != null) asset.targetHeight else 0).coerceIn(1, frame.height)
        asset.lastDraw = SystemClock.uptimeMillis()
        if (asset.pending || ((asset.bitmap != null || asset.drawable != null) && asset.bitmapGeneration == asset.generation && asset.targetWidth == w && asset.targetHeight == h)) return
        // Sampling can round up by almost two on either axis. Frame graph recursion
        // retains at most one output canvas per level plus a leaf's decode scratch.
        val animatedSource = frame.source?.takeIf { it.mime == "image/gif" || (it.mime == "image/webp" && Build.VERSION.SDK_INT >= 28) }
        val nativeBytes = animatedSource?.let {
            it.bytes.size.toLong() * 2 + (if (Build.VERSION.SDK_INT >= 28) w.toLong() * h else it.width.toLong() * it.height) * 12
        } ?: 0
        val borrowedSources = mutableSetOf<ImageSource>().also { frame.sources(it) }
        val borrowedBytes = borrowedSources.sumOf { source -> source.bytes.chunks.sumOf { it.size.toLong() } }
        // A queued worker may outlive eviction/disable. Reserve its borrowed
        // encoded sources as well, so they cannot escape the memory accounting.
        val reservation = max(frame.workingBytes(w, h), nativeBytes + w.toLong() * h * 4) + borrowedBytes
        assets.values.filter { it !== asset && !visibleAsset(it) }.forEach { it.clearDecoded() }
        while (true) retainedResources.remove(releasedResources.poll() ?: break)
        val retained = retainedResources.sumOf { it.bytes }
        if (reservation + decodedReservation + retained > limits.decodedBytes) return
        decodedReservation += reservation
        asset.pending = true
        val generation = asset.generation
        asset.targetWidth = w
        asset.targetHeight = h
        decoder.execute {
            var drawable: Drawable? = null

            @Suppress("DEPRECATION")
            var movie: Movie? = null
            val decoded = try {
                if (animatedSource != null && Build.VERSION.SDK_INT >= 28) {
                    drawable = AnimatedImage.decode(animatedSource, w, h)
                    null
                } else if (animatedSource != null) {
                    movie = asset.movie ?: AnimatedImage.movie(animatedSource)
                    movie?.let { AnimatedImage.frame(it, w, h, SystemClock.uptimeMillis() - asset.created) }
                } else {
                    frame.decode(w, h)
                }
            } catch (_: Exception) {
                null
            }
            maintenance.execute {
                synchronized(this) {
                    decodedReservation -= reservation
                    asset.pending = false
                    // Even a stale decoder result owns native memory until GC.
                    (drawable ?: movie)?.let { retainResource(it, nativeBytes) }
                    if (assets[asset.id] === asset && generation == asset.generation) {
                        retain(decoded)
                        asset.bitmap = decoded
                        asset.drawable = drawable
                        asset.movie = movie
                        asset.decoderBytes = if (drawable != null || movie != null) nativeBytes else 0
                        asset.bitmapGeneration = generation
                        asset.presentation.publish(decoded, drawable)
                    } else {
                        decoded?.recycle()
                    }
                }
            }
        }
    }

    @Synchronized
    fun advanceAnimations(now: Long) {
        assets.values.forEach { asset ->
            if (!visibleAsset(asset)) {
                asset.clearDecoded()
                return@forEach
            }
            @Suppress("DEPRECATION")
            if (asset.movie != null && asset.movie!!.duration() > 0 && now >= asset.deadline && !asset.pending) {
                asset.deadline = now + 40
                asset.changed()
                request(asset, asset.targetWidth, asset.targetHeight)
            }
            if (asset.animationState == 1 || asset.frames.size < 2) return@forEach
            if (asset.deadline == 0L) asset.deadline = now + asset.gaps[asset.frameIndex]
            var steps = 0
            while (now >= asset.deadline && steps++ < asset.frames.size * 2) {
                if (asset.frameIndex == asset.frames.lastIndex) {
                    if (asset.animationState == 2) break
                    asset.completedLoops++
                    if (asset.loops > 0 && asset.completedLoops >= asset.loops) {
                        asset.animationState = 1
                        break
                    }
                    asset.frameIndex = 0
                } else {
                    asset.frameIndex++
                }
                asset.deadline += asset.gaps[asset.frameIndex]
                asset.changed()
            }
            if (asset.bitmap == null && !asset.pending) request(asset, asset.targetWidth.coerceAtLeast(1), asset.targetHeight.coerceAtLeast(1))
        }
    }

    companion object {
        private val decoder = Executors.newSingleThreadExecutor { task -> Thread(task, "terminal-images").apply { isDaemon = true } }
    }
}

internal class ImageAsset(val id: Long, val number: Long?, private val store: InlineImageStore, frame: ImageFrame) {
    val presentation = ImagePresentation(Handler(android.os.Looper.getMainLooper()))
    val created = SystemClock.uptimeMillis()
    val width = frame.width
    val height = frame.height
    val frames = mutableListOf(frame)
    val gaps = mutableListOf(0)
    var frameIndex = 0
    var generation = 0
    var bitmapGeneration = -1
    var bitmap: Bitmap? = null
    var drawable: Drawable? = null

    @Suppress("DEPRECATION")
    var movie: Movie? = null
    var decoderBytes = 0L
    var pending = false
    var targetWidth = 0
    var targetHeight = 0
    var lastDraw = 0L
    var animationState = 1
    var loops = 0
    var completedLoops = 0
    var deadline = 0L

    fun changed() {
        generation++
    }
    fun clearDecoded() {
        changed()
        drawable = null
        movie = null
        decoderBytes = 0
        bitmap = null
        presentation.publish(null, null)
    }
    fun release() {
        changed()
        clearDecoded()
        frames.clear()
        gaps.clear()
    }
    fun request(w: Int, h: Int) = store.request(this, w, h)
}

internal class ImagePlacement(
    val asset: ImageAsset,
    val id: Long,
    var top: Int,
    var left: Int,
    val width: Int,
    val height: Int,
    val crop: Rect,
    val z: Int,
    val iterm: Boolean,
    val alternate: Boolean,
    val virtual: Boolean = false,
    val parent: Pair<Long, Long>? = null,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val renderWidth: Float = width.toFloat(),
    val renderHeight: Float = height.toFloat(),
    val sourceRow: Int = 0,
    val sourceCol: Int = 0,
) {
    var clipTop = 0
    var clipBottom = height
    var clipLeft = 0
    var clipRight = width
    var coverage = if (iterm) MutableList(height) { listOf(left until left + width) } else mutableListOf()

    fun erase(row: Int, start: Int, end: Int) {
        coverage[row] = coverage[row].flatMap { range ->
            if (end <= range.first || start > range.last) {
                listOf(range)
            } else {
                buildList {
                    if (start > range.first) add(range.first until start)
                    if (end <= range.last) add(end..range.last)
                }
            }
        }
    }

    fun fragment(row: Int, start: Int, end: Int): ImagePlacement = ImagePlacement(
        asset, id, top + row, start, end - start, 1,
        crop, z, true, alternate,
        offsetX = offsetX, offsetY = offsetY, renderWidth = renderWidth, renderHeight = renderHeight,
        sourceRow = sourceRow + row, sourceCol = sourceCol + start - left,
    )
}

internal data class ImageSlice(
    val asset: ImageAsset,
    val left: Int,
    val right: Int,
    val sourceRow: Int,
    val rows: Int,
    val sourceCol: Int,
    val columns: Int,
    val crop: Rect,
    val z: Int,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val renderWidth: Float = columns.toFloat(),
    val renderHeight: Float = rows.toFloat(),
) {
    fun draw(canvas: Canvas, row: Int, cellWidth: Float, cellHeight: Float) {
        asset.presentation.redraw
        val frame = asset.presentation.frame
        val drawable = frame.drawable
        if (drawable != null) {
            canvas.save()
            canvas.clipRect(left * cellWidth, row * cellHeight, right * cellWidth, (row + 1) * cellHeight)
            val sx = renderWidth * cellWidth / crop.width()
            val sy = renderHeight * cellHeight / crop.height()
            canvas.translate(
                (left - sourceCol) * cellWidth + offsetX - crop.left * sx,
                (row - sourceRow) * cellHeight + offsetY - crop.top * sy,
            )
            canvas.scale(sx, sy)
            drawable.setBounds(0, 0, asset.width, asset.height)
            drawable.draw(canvas)
            canvas.restore()
            return
        }
        val bitmap = frame.bitmap ?: return
        val sx = renderWidth * cellWidth / crop.width()
        val sy = renderHeight * cellHeight / crop.height()
        val x = (left - sourceCol) * cellWidth + offsetX - crop.left * sx
        val y = (row - sourceRow) * cellHeight + offsetY - crop.top * sy
        canvas.save()
        canvas.clipRect(left * cellWidth, row * cellHeight, right * cellWidth, (row + 1) * cellHeight)
        canvas.clipRect(
            (left - sourceCol) * cellWidth + offsetX,
            (row - sourceRow) * cellHeight + offsetY,
            (left - sourceCol) * cellWidth + offsetX + renderWidth * cellWidth,
            (row - sourceRow) * cellHeight + offsetY + renderHeight * cellHeight,
        )
        canvas.drawBitmap(bitmap, null, RectF(x, y, x + asset.width * sx, y + asset.height * sy), Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
    }
}
