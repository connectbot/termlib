/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.Shader
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Font-independent geometry. One instance per renderer paint; no global mutable Canvas state. */
internal class TerminalBoxDrawing(private val cacheRows: Boolean = true) {
    private var rows: TerminalBoxRows? = null
    private val matrix = Matrix()
    private val transform = FloatArray(9)
    private var pixelAligned = false
    private val paint = Paint().apply { isAntiAlias = false }
    private val path = Path()
    private val clip = Rect()
    private val stippleShaders = arrayOfNulls<BitmapShader>(3)
    private val stipplePaints = arrayOfNulls<Paint>(3)
    private val stipplePaintColors = arrayOfNulls<Int>(3)
    private val stippleScales = FloatArray(3) { Float.NaN }
    private val stippleMatrix = Matrix()
    private var stippleColor: Int? = null
    private var stippleFilter: PorterDuffColorFilter? = null
    private var geometryColor: Int? = null
    private var geometryWasCurved = false
    private val rectangularGeometry = HashMap<GeometryKey, RectangularGeometry>()
    private val positionedGeometry = Path()
    private var geometryWidth = Float.NaN
    private var geometryHeight = Float.NaN
    private var geometryTextSize = Float.NaN
    private var dashPeriod = 1L
    private var geometryRectangleBytes = 0
    private val batches = HashMap<Int, RectangleBatch>()
    private val batchPaint = Paint().apply {
        isAntiAlias = false
        strokeCap = Paint.Cap.BUTT
    }
    private var batchCanvas: Canvas? = null
    private var batchColor = 0
    private var batchCapacityBytes = 0

    val retainedRowPixelBytes: Int get() = rows?.retainedPixelBytes ?: 0

    fun clearCaches() {
        endBatch()
        rows = null
        rectangularGeometry.clear()
        geometryRectangleBytes = 0
        geometryWidth = Float.NaN
        batches.clear()
        batchCapacityBytes = 0
        stippleShaders.fill(null)
        stipplePaints.fill(null)
        stipplePaintColors.fill(null)
        stippleScales.fill(Float.NaN)
        stippleFilter = null
        stippleColor = null
    }

    fun beginBatch(canvas: Canvas, color: Int) {
        if (batchCanvas !== canvas || batchColor != color) {
            endBatch()
            batchCanvas = canvas
            batchColor = color
        }
    }

    fun endBatch() {
        flushBatch()
        batchCanvas = null
    }

    private fun flushBatch() {
        val canvas = batchCanvas ?: return
        batchPaint.color = batchColor
        for (batch in batches.values) {
            if (batch.count == 0) continue
            val drawingPaint = batch.shadePaint ?: batchPaint
            drawingPaint.strokeWidth = batch.height.toFloat()
            canvas.drawLines(batch.points, 0, batch.count, drawingPaint)
            batch.count = 0
        }
    }

    private fun rectangle(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, drawingPaint: Paint, shade: Int = -1) {
        if (left >= right || top >= bottom) return
        if (batchCanvas !== canvas) {
            canvas.drawRect(left, top, right, bottom, drawingPaint)
            return
        }
        val height = (bottom - top).toInt()
        // Bound reusable scratch, including large viewports and unusual metrics.
        if (height !in 1..4096) {
            flushBatch()
            canvas.drawRect(left, top, right, bottom, drawingPaint)
            return
        }
        val key = height * 4 + shade + 1
        val batch = batches[key] ?: run {
            if (batchCapacityBytes + 128 * 4 > MAX_BATCH_BYTES) {
                flushBatch()
                canvas.drawRect(left, top, right, bottom, drawingPaint)
                return
            }
            RectangleBatch(height, if (shade >= 0) drawingPaint else null).also {
                batches[key] = it
                batchCapacityBytes += it.points.size * 4
            }
        }
        if (batch.count + 4 > batch.points.size) {
            val extraBytes = batch.points.size * 4
            if (batchCapacityBytes + extraBytes <= MAX_BATCH_BYTES) {
                batch.points = batch.points.copyOf(batch.points.size * 2)
                batchCapacityBytes += extraBytes
            } else {
                flushBatch()
            }
        }
        batch.points[batch.count++] = left
        batch.points[batch.count++] = (top + bottom) / 2f
        batch.points[batch.count++] = right
        batch.points[batch.count++] = (top + bottom) / 2f
    }

    fun prepareDraw(canvas: Canvas) {
        canvas.getClipBounds(clip)
        if (cacheRows) {
            @Suppress("DEPRECATION")
            canvas.getMatrix(matrix)
            matrix.getValues(transform)
            pixelAligned = transform[Matrix.MSCALE_X] == 1f && transform[Matrix.MSCALE_Y] == 1f &&
                transform[Matrix.MSKEW_X] == 0f && transform[Matrix.MSKEW_Y] == 0f &&
                transform[Matrix.MPERSP_0] == 0f && transform[Matrix.MPERSP_1] == 0f && transform[Matrix.MPERSP_2] == 1f &&
                transform[Matrix.MTRANS_X] == round(transform[Matrix.MTRANS_X]) &&
                transform[Matrix.MTRANS_Y] == round(transform[Matrix.MTRANS_Y])
        }
    }

    fun drawRow(canvas: Canvas, cells: PackedCells, text: String, above: PackedCells?, below: PackedCells?, y: Float, width: Float, height: Float, textSize: Float): Boolean {
        if (!cacheRows || !pixelAligned) return false
        val color = cells.uniformBoxForeground() ?: return false
        val cache = rows ?: TerminalBoxRows().also { rows = it }
        return cache.draw(canvas, this, cells, text, above, below, y, width, height, color, textSize)
    }

    private fun drawStipple(
        canvas: Canvas,
        shade: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        cellWidth: Float,
        color: Int,
    ) {
        val shader = stippleShaders[shade] ?: run {
            val pixels = when (shade) {
                0 -> intArrayOf(-1, 0, 0, 0)
                1 -> intArrayOf(-1, 0, 0, -1)
                else -> intArrayOf(0, -1, -1, -1)
            }
            val bitmap = Bitmap.createBitmap(pixels, 2, 2, Bitmap.Config.ARGB_8888)
            BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { stippleShaders[shade] = it }
        }
        // Anchor the pattern to terminal coordinates across fractional and odd cell sizes.
        val tile = max(1f, floor(cellWidth / 12f))
        if (stippleScales[shade] != tile) {
            flushBatch()
            stippleMatrix.setScale(tile, tile)
            shader.setLocalMatrix(stippleMatrix)
            stippleScales[shade] = tile
        }
        val shadePaint = stipplePaints[shade] ?: Paint().apply {
            isAntiAlias = false
            this.color = android.graphics.Color.WHITE
            this.shader = shader
            isFilterBitmap = false
        }.also { stipplePaints[shade] = it }
        if (stipplePaintColors[shade] != color) {
            flushBatch()
            if (stippleColor != color) {
                stippleColor = color
                stippleFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            }
            shadePaint.colorFilter = stippleFilter
            stipplePaintColors[shade] = color
        }
        // One draw operation per cell instead of one per stipple dot.
        rectangle(canvas, left, top, left + width, top + height, shadePaint, shade)
    }

    fun draw(
        canvas: Canvas,
        ch: Char,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        color: Int,
        textSize: Float,
        neighboringDashes: Int = 0,
        clipPrepared: Boolean = false,
    ) {
        val left = round(x)
        val top = round(y)
        val w = round(x + width) - left
        val h = round(y + height) - top
        if (w <= 0 || h <= 0) return
        if (!clipPrepared) prepareDraw(canvas)
        if (clip.isEmpty || left >= clip.right || top >= clip.bottom || left + w <= clip.left || top + h <= clip.top) return
        if (ch.code in 0x2591..0x2593) {
            drawStipple(canvas, ch.code - 0x2591, left, top, w, h, width, color)
            return
        }
        if (geometryColor != color) {
            paint.color = color
            geometryColor = color
        }
        if (geometryWasCurved) {
            paint.style = Paint.Style.FILL
            paint.isAntiAlias = false
            geometryWasCurved = false
        }
        // Rectangles can be clipped numerically. Curves still need Canvas clipping
        // because their antialiased stroke extends past the cell boundary.
        val curved = ch.code in 0x256D..0x2573
        if (curved) flushBatch()
        // Opaque rectangle unions have exactly the same pixels as their individual
        // arms, but need only one Canvas command. Keep translucent overlaps on the
        // original path so repeated source-over blending is preserved.
        val cacheable = color ushr 24 == 255 && !curved && w < 4096 && h < 4096
        var geometryKey: GeometryKey? = null
        if (cacheable) {
            if (geometryWidth != width || geometryHeight != height || geometryTextSize != textSize) {
                rectangularGeometry.clear()
                geometryRectangleBytes = 0
                // All three possible cadences repeat after this common period.
                // Including both origin phases preserves dashes across cells.
                dashPeriod = 1L
                for (count in 2..4) {
                    val period = max(2, round(width / count).toInt()).toLong()
                    var a = dashPeriod
                    var b = period
                    while (b != 0L) {
                        val remainder = a % b
                        a = b
                        b = remainder
                    }
                    dashPeriod = dashPeriod / a * period
                }
                geometryWidth = width
                geometryHeight = height
                geometryTextSize = textSize
            }
            val dashed = neighboringDashes != 0 || ch.code in 0x2504..0x250B || ch.code in 0x254C..0x254F
            geometryKey = GeometryKey(
                (ch.code - 0x2500).toLong() or (w.toLong() shl 8) or (h.toLong() shl 20),
                neighboringDashes,
                if (dashed) Math.floorMod(left.toLong(), dashPeriod) else 0L,
                if (dashed) Math.floorMod(top.toLong(), dashPeriod) else 0L,
            )
            rectangularGeometry[geometryKey]?.let {
                if (batchCanvas === canvas) {
                    val rectangles = it.rectangles
                    for (index in rectangles.indices step 4) {
                        rectangle(canvas, left + rectangles[index], top + rectangles[index + 1], left + rectangles[index + 2], top + rectangles[index + 3], paint)
                    }
                } else {
                    it.path.offset(left, top, positionedGeometry)
                    canvas.drawPath(positionedGeometry, paint)
                }
                return
            }
        }
        val geometry = if (cacheable) Path() else null
        val geometryRectangles = if (cacheable) ArrayList<Float>(32) else null
        val saved = if (curved) canvas.save() else -1
        try {
            if (curved) {
                if (!canvas.clipRect(left, top, left + w, top + h)) return
                canvas.translate(left, top)
            }
            // Consistent half-up rounding preserves one-pixel dash gaps.
            fun rect(l: Float, t: Float, r: Float, b: Float) {
                val originX = if (curved) 0f else left
                val originY = if (curved) 0f else top
                val clippedLeft = floor(l + 0.5f).coerceIn(0f, w)
                val clippedTop = floor(t + 0.5f).coerceIn(0f, h)
                val clippedRight = floor(r + 0.5f).coerceIn(0f, w)
                val clippedBottom = floor(b + 0.5f).coerceIn(0f, h)
                if (geometry != null) {
                    if (clippedLeft < clippedRight && clippedTop < clippedBottom) {
                        geometry.addRect(clippedLeft, clippedTop, clippedRight, clippedBottom, Path.Direction.CW)
                        geometryRectangles!!.add(clippedLeft)
                        geometryRectangles.add(clippedTop)
                        geometryRectangles.add(clippedRight)
                        geometryRectangles.add(clippedBottom)
                    }
                } else {
                    if (curved) {
                        canvas.drawRect(clippedLeft, clippedTop, clippedRight, clippedBottom, paint)
                    } else {
                        rectangle(canvas, originX + clippedLeft, originY + clippedTop, originX + clippedRight, originY + clippedBottom, paint)
                    }
                }
            }
            val cp = ch.code
            if (cp >= 0x2580) {
                fun eighth(n: Int, extent: Float) = round(extent * n / 8)
                when (cp) {
                    0x2580 -> rect(0f, 0f, w, eighth(4, h))

                    in 0x2581..0x2588 -> rect(0f, eighth(0x2588 - cp, h), w, h)

                    in 0x2589..0x258F -> rect(0f, 0f, eighth(0x2590 - cp, w), h)

                    0x2590 -> rect(eighth(4, w), 0f, w, h)

                    0x2594 -> rect(0f, 0f, w, eighth(1, h))

                    0x2595 -> rect(eighth(7, w), 0f, w, h)

                    else -> {
                        val mask = quadrants[cp - 0x2596]
                        val mx = eighth(4, w)
                        val my = eighth(4, h)
                        for (q in 0..3) {
                            if (mask and (1 shl q) != 0) {
                                rect(
                                    if (q % 2 == 0) 0f else mx,
                                    if (q < 2) 0f else my,
                                    if (q % 2 == 0) mx else w,
                                    if (q < 2) my else h,
                                )
                            }
                        }
                    }
                }
                return
            }
            val thin = min(max(1f, round(textSize / 16f)), max(1f, min(w, h) / 3f))
            val cx = round((w - thin) / 2) + thin / 2
            val cy = round((h - thin) / 2) + thin / 2
            if (cp in 0x2571..0x2573) {
                paint.isAntiAlias = true
                geometryWasCurved = true
                paint.strokeWidth = thin
                paint.strokeCap = Paint.Cap.SQUARE
                if (cp != 0x2572) canvas.drawLine(0f, h, w, 0f, paint)
                if (cp != 0x2571) canvas.drawLine(0f, 0f, w, h, paint)
                return
            }
            val arms = connections[cp - 0x2500]
            val ownDash = when (cp) {
                in 0x254C..0x254F -> 1
                in 0x2504..0x2507 -> 2
                in 0x2508..0x250B -> 3
                else -> 0
            }
            fun dashStyle(d: Int): Int = if (ownDash != 0 && arms[d] != '0') ownDash else (neighboringDashes shr (d * 2)) and 3
            if (cp in 0x256D..0x2570 && neighboringDashes == 0) {
                val sx = if (arms[1] != '0') 1 else -1
                val sy = if (arms[3] != '0') 1 else -1
                val radius = min(min(cx, w - cx), min(cy, h - cy))
                path.reset()
                path.moveTo(if (sx > 0) w else 0f, cy)
                path.lineTo(cx + sx * radius, cy)
                path.quadTo(cx, cy, cx, cy + sy * radius)
                path.lineTo(cx, if (sy > 0) h else 0f)
                paint.isAntiAlias = true
                geometryWasCurved = true
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = thin
                paint.strokeCap = Paint.Cap.SQUARE
                canvas.drawPath(path, paint)
                return
            }
            // Draw each arm to its perpendicular rail. Double corners keep two
            // distinct elbows; tees/crosses connect the inner rails by quadrant.
            for (d in 0..3) {
                val style = arms[d].digitToInt()
                if (style == 0) continue
                val horizontal = d < 2
                val sign = if (d % 2 == 0) -1 else 1
                val near = if (horizontal) 2 else 0
                val center = if (horizontal) cx else cy
                val cross = if (horizontal) cy else cx
                val edge = if (sign < 0) {
                    0f
                } else if (horizontal) {
                    w
                } else {
                    h
                }
                val weight = if (style == 2) 2 * thin else thin
                val dashStyle = dashStyle(d)
                if (dashStyle != 0) {
                    // Derive the cadence from cell width for both axes. This
                    // keeps square-looking dashes in the taller vertical cell.
                    val nominalCount = dashStyle + 1
                    val period = max(2, round(width / nominalCount).toInt())
                    val gap = max(1, round(width / 8f).toInt())
                    val ink = max(1, period - gap)
                    val start = min(edge, center).toInt()
                    val finish = max(edge, center).toInt()
                    var runStart = -1
                    for (p in start..finish) {
                        val absolute = round(if (horizontal) left + p else top + p).toInt()
                        val painted = Math.floorMod(absolute, period) < ink
                        if (painted && runStart < 0) runStart = p
                        if ((!painted || p == finish) && runStart >= 0) {
                            val runEnd = if (painted && p == finish) p + 1 else p
                            if (horizontal) {
                                rect(runStart.toFloat(), cross - weight / 2, runEnd.toFloat(), cross + weight / 2)
                            } else {
                                rect(cross - weight / 2, runStart.toFloat(), cross + weight / 2, runEnd.toFloat())
                            }
                            runStart = -1
                        }
                    }
                    continue
                }
                val rails = if (style == 3) 2 else 1
                for (rail in 0 until rails) {
                    val offset = if (style == 3) (if (rail == 0) -thin else thin) else 0f
                    var end = center
                    if (style == 3) {
                        val side = if (rail == 0) 0 else 1
                        val perpendicular = arms[near + side].digitToInt()
                        val opposite = arms[near + 1 - side].digitToInt()
                        end += when {
                            perpendicular == 3 -> sign * thin
                            perpendicular != 0 -> -sign * (if (perpendicular == 2) thin else thin / 2)
                            opposite == 3 -> -sign * thin
                            opposite != 0 -> -sign * (if (opposite == 2) thin else thin / 2)
                            else -> 0f
                        }
                    } else {
                        val perpendicular = max(arms[near].digitToInt(), arms[near + 1].digitToInt())
                        end -= sign * when (perpendicular) {
                            3 -> 1.5f * thin
                            2 -> thin
                            1 -> thin / 2
                            else -> 0f
                        }
                    }
                    // Include the meeting rail's half-width at double elbows.
                    if (style == 3 && (arms[near] == '3' || arms[near + 1] == '3')) end -= sign * thin / 2
                    val a = min(edge, end)
                    val b = max(edge, end)
                    if (horizontal) {
                        rect(a, cross + offset - weight / 2, b, cross + offset + weight / 2)
                    } else {
                        rect(cross + offset - weight / 2, a, cross + offset + weight / 2, b)
                    }
                }
            }
        } finally {
            if (geometry != null) {
                val rectangles = geometryRectangles!!.toFloatArray()
                if (batchCanvas === canvas) {
                    for (index in rectangles.indices step 4) {
                        rectangle(canvas, left + rectangles[index], top + rectangles[index + 1], left + rectangles[index + 2], top + rectangles[index + 3], paint)
                    }
                } else {
                    geometry.offset(left, top, positionedGeometry)
                    canvas.drawPath(positionedGeometry, paint)
                }
                // Bound both entry count and rectangle payload; long dashed arms
                // may contain many more rectangles than a plain junction.
                val bytes = rectangles.size * 4
                if (rectangularGeometry.size < 256 && geometryRectangleBytes + bytes <= 32 * 1024) {
                    rectangularGeometry[geometryKey!!] = RectangularGeometry(geometry, rectangles)
                    geometryRectangleBytes += bytes
                }
            }
            if (saved >= 0) canvas.restoreToCount(saved)
        }
    }

    private companion object {
        const val MAX_BATCH_BYTES = 32 * 1024

        // Quadrant bits: upper-left, upper-right, lower-left, lower-right.
        val quadrants = intArrayOf(4, 8, 1, 13, 9, 7, 11, 2, 6, 14)

        // Unicode box connections in left/right/up/down order:
        // 0 absent, 1 light/single, 2 heavy, 3 double. Indexed from U+2500.
        val connections = arrayOf(
            "1100", // ─ U+2500
            "2200", // ━ U+2501
            "0011", // │ U+2502
            "0022", // ┃ U+2503
            "1100", // ┄ U+2504
            "2200", // ┅ U+2505
            "0011", // ┆ U+2506
            "0022", // ┇ U+2507
            "1100", // ┈ U+2508
            "2200", // ┉ U+2509
            "0011", // ┊ U+250A
            "0022", // ┋ U+250B
            "0101", // ┌ U+250C
            "0201", // ┍ U+250D
            "0102", // ┎ U+250E
            "0202", // ┏ U+250F
            "1001", // ┐ U+2510
            "2001", // ┑ U+2511
            "1002", // ┒ U+2512
            "2002", // ┓ U+2513
            "0110", // └ U+2514
            "0210", // ┕ U+2515
            "0120", // ┖ U+2516
            "0220", // ┗ U+2517
            "1010", // ┘ U+2518
            "2010", // ┙ U+2519
            "1020", // ┚ U+251A
            "2020", // ┛ U+251B
            "0111", // ├ U+251C
            "0211", // ┝ U+251D
            "0121", // ┞ U+251E
            "0112", // ┟ U+251F
            "0122", // ┠ U+2520
            "0221", // ┡ U+2521
            "0212", // ┢ U+2522
            "0222", // ┣ U+2523
            "1011", // ┤ U+2524
            "2011", // ┥ U+2525
            "1021", // ┦ U+2526
            "1012", // ┧ U+2527
            "1022", // ┨ U+2528
            "2021", // ┩ U+2529
            "2012", // ┪ U+252A
            "2022", // ┫ U+252B
            "1101", // ┬ U+252C
            "2101", // ┭ U+252D
            "1201", // ┮ U+252E
            "2201", // ┯ U+252F
            "1102", // ┰ U+2530
            "2102", // ┱ U+2531
            "1202", // ┲ U+2532
            "2202", // ┳ U+2533
            "1110", // ┴ U+2534
            "2110", // ┵ U+2535
            "1210", // ┶ U+2536
            "2210", // ┷ U+2537
            "1120", // ┸ U+2538
            "2120", // ┹ U+2539
            "1220", // ┺ U+253A
            "2220", // ┻ U+253B
            "1111", // ┼ U+253C
            "2111", // ┽ U+253D
            "1211", // ┾ U+253E
            "2211", // ┿ U+253F
            "1121", // ╀ U+2540
            "1112", // ╁ U+2541
            "1122", // ╂ U+2542
            "2121", // ╃ U+2543
            "1221", // ╄ U+2544
            "2112", // ╅ U+2545
            "1212", // ╆ U+2546
            "2221", // ╇ U+2547
            "2212", // ╈ U+2548
            "2122", // ╉ U+2549
            "1222", // ╊ U+254A
            "2222", // ╋ U+254B
            "1100", // ╌ U+254C
            "2200", // ╍ U+254D
            "0011", // ╎ U+254E
            "0022", // ╏ U+254F
            "3300", // ═ U+2550
            "0033", // ║ U+2551
            "0301", // ╒ U+2552
            "0103", // ╓ U+2553
            "0303", // ╔ U+2554
            "3001", // ╕ U+2555
            "1003", // ╖ U+2556
            "3003", // ╗ U+2557
            "0310", // ╘ U+2558
            "0130", // ╙ U+2559
            "0330", // ╚ U+255A
            "3010", // ╛ U+255B
            "1030", // ╜ U+255C
            "3030", // ╝ U+255D
            "0311", // ╞ U+255E
            "0133", // ╟ U+255F
            "0333", // ╠ U+2560
            "3011", // ╡ U+2561
            "1033", // ╢ U+2562
            "3033", // ╣ U+2563
            "3301", // ╤ U+2564
            "1103", // ╥ U+2565
            "3303", // ╦ U+2566
            "3310", // ╧ U+2567
            "1130", // ╨ U+2568
            "3330", // ╩ U+2569
            "3311", // ╪ U+256A
            "1133", // ╫ U+256B
            "3333", // ╬ U+256C
            "0101", // ╭ U+256D
            "1001", // ╮ U+256E
            "1010", // ╯ U+256F
            "0110", // ╰ U+2570
            "0000", // ╱ U+2571
            "0000", // ╲ U+2572
            "0000", // ╳ U+2573
            "1000", // ╴ U+2574
            "0010", // ╵ U+2575
            "0100", // ╶ U+2576
            "0001", // ╷ U+2577
            "2000", // ╸ U+2578
            "0020", // ╹ U+2579
            "0200", // ╺ U+257A
            "0002", // ╻ U+257B
            "1200", // ╼ U+257C
            "0012", // ╽ U+257D
            "2100", // ╾ U+257E
            "0021", // ╿ U+257F
        )
    }
}

private class RectangularGeometry(val path: Path, val rectangles: FloatArray)

private class RectangleBatch(val height: Int, val shadePaint: Paint?) {
    var points = FloatArray(128)
    var count = 0
}

private data class GeometryKey(val cell: Long, val neighbors: Int, val xPhase: Long, val yPhase: Long)
