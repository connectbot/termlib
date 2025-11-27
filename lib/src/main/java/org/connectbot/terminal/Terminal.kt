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

import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gesture type for unified gesture handling state machine.
 */
private enum class GestureType {
    Undetermined,
    Scroll,
    Selection,
    Zoom,
    HandleDrag
}

/**
 * Terminal - A Jetpack Compose terminal screen component.
 *
 * This component:
 * - Renders terminal output using Canvas
 * - Handles terminal resize based on available space
 * - Displays cursor
 * - Supports colors, bold, italic, underline, etc.
 *
 * @param terminalBuffer The terminal buffer containing screen state
 * @param modifier Modifier for the composable
 * @param typeface Typeface for terminal text (default: Typeface.MONOSPACE)
 * @param initialFontSize Initial font size for terminal text (can be changed with pinch-to-zoom)
 * @param minFontSize Minimum font size for pinch-to-zoom
 * @param maxFontSize Maximum font size for pinch-to-zoom
 * @param backgroundColor Default background color
 * @param foregroundColor Default foreground color
 * @param keyboardEnabled Enable keyboard input handling (default: false for display-only mode)
 * @param forcedSize Force terminal to specific dimensions (rows, cols). When set, font size is calculated to fit.
 */
@Composable
fun Terminal(
    terminalBuffer: TerminalBuffer,
    modifier: Modifier = Modifier,
    typeface: Typeface = Typeface.MONOSPACE,
    initialFontSize: TextUnit = 11.sp,
    minFontSize: TextUnit = 6.sp,
    maxFontSize: TextUnit = 30.sp,
    backgroundColor: Color = Color.Black,
    foregroundColor: Color = Color.White,
    keyboardEnabled: Boolean = false,
    forcedSize: Pair<Int, Int>? = null
) {
    val density = LocalDensity.current
    val clipboardManager = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    val keyboardHandler = remember(terminalBuffer.terminalNative) {
        KeyboardHandler(terminalBuffer.terminalNative)
    }

    // Font size and zoom state
    var zoomScale by remember { mutableStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }
    var isZooming by remember { mutableStateOf(false) }
    var calculatedFontSize by remember { mutableStateOf(initialFontSize) }

    // Magnifying glass state
    var showMagnifier by remember { mutableStateOf(false) }
    var magnifierPosition by remember { mutableStateOf(Offset.Zero) }

    // Request focus when keyboard is enabled
    LaunchedEffect(keyboardEnabled) {
        if (keyboardEnabled) {
            focusRequester.requestFocus()
        }
    }

    // Create TextPaint for measuring and drawing (base size)
    val textPaint = remember(typeface, calculatedFontSize) {
        TextPaint().apply {
            this.typeface = typeface
            textSize = with(density) { calculatedFontSize.toPx() }
            isAntiAlias = true
        }
    }

    // Base character dimensions (unzoomed)
    val baseCharWidth = remember(textPaint) {
        textPaint.measureText("M")
    }

    val baseCharHeight = remember(textPaint) {
        val metrics = textPaint.fontMetrics
        metrics.descent - metrics.ascent
    }

    val baseCharBaseline = remember(textPaint) {
        -textPaint.fontMetrics.ascent
    }

    // Actual dimensions with zoom applied
    val charWidth = baseCharWidth * zoomScale
    val charHeight = baseCharHeight * zoomScale
    val charBaseline = baseCharBaseline * zoomScale

    // Scroll animation state
    val scrollOffset = remember { Animatable(0f) }
    val maxScroll = remember(terminalBuffer.scrollback.size, baseCharHeight) {
        terminalBuffer.scrollback.size * baseCharHeight
    }

    // Selection manager
    val selectionManager = remember(terminalBuffer) {
        SelectionManager(terminalBuffer)
    }

    // Coroutine scope for animations
    val coroutineScope = rememberCoroutineScope()
    val viewConfiguration = LocalViewConfiguration.current

    // Handle resize based on available space
    BoxWithConstraints(
        modifier = modifier
            .then(
                if (keyboardEnabled) {
                    Modifier
                        .focusRequester(focusRequester)
                        .focusable()
                        .onKeyEvent { keyboardHandler.onKeyEvent(it) }
                } else {
                    Modifier
                }
            )
    ) {
        val availableWidth = constraints.maxWidth
        val availableHeight = constraints.maxHeight

        // Calculate font size if forcedSize is specified
        if (forcedSize != null) {
            val (forcedRows, forcedCols) = forcedSize
            LaunchedEffect(availableWidth, availableHeight, forcedRows, forcedCols) {
                val optimalSize = findOptimalFontSize(
                    targetRows = forcedRows,
                    targetCols = forcedCols,
                    availableWidth = availableWidth,
                    availableHeight = availableHeight,
                    minSize = minFontSize.value,
                    maxSize = maxFontSize.value,
                    typeface = typeface,
                    density = density.density
                )
                calculatedFontSize = optimalSize.sp
            }
        }

        // Use base dimensions for terminal sizing (not zoomed dimensions)
        val newCols =
            if (forcedSize != null) forcedSize.second else (availableWidth / baseCharWidth).toInt()
                .coerceAtLeast(1)
        val newRows =
            if (forcedSize != null) forcedSize.first else (availableHeight / baseCharHeight).toInt()
                .coerceAtLeast(1)

        // Resize terminal when dimensions change
        LaunchedEffect(newRows, newCols) {
            if (newRows != terminalBuffer.rows || newCols != terminalBuffer.cols) {
                terminalBuffer.resize(newRows, newCols)
            }
        }

        // Auto-scroll to bottom when new content arrives (if not manually scrolled)
        val wasAtBottom = terminalBuffer.scrollbackPosition == 0
        LaunchedEffect(terminalBuffer.lines.size, terminalBuffer.scrollback.size) {
            // Only auto-scroll if user was already at bottom
            if (wasAtBottom) {
                terminalBuffer.scrollToBottom()
                scrollOffset.snapTo(0f)
            }
        }

        // Sync scrollOffset when scrollbackPosition changes externally (but not during user scrolling)
        LaunchedEffect(terminalBuffer.scrollbackPosition) {
            val targetOffset = terminalBuffer.scrollbackPosition * baseCharHeight
            if (!scrollOffset.isRunning && scrollOffset.value != targetOffset) {
                scrollOffset.snapTo(targetOffset)
            }
        }

        // Calculate actual terminal dimensions in pixels
        val terminalWidthPx = newCols * baseCharWidth
        val terminalHeightPx = newRows * baseCharHeight

        // Draw terminal content with context menu overlay
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = if (forcedSize != null && !isZooming && zoomScale == 1f) {
                    // Add border outside the terminal content (only when not zooming)
                    Modifier
                        .size(
                            width = with(density) { terminalWidthPx.toDp() },
                            height = with(density) { terminalHeightPx.toDp() }
                        )
                        .border(
                            width = 2.dp,
                            color = Color(0xFF4CAF50).copy(alpha = 0.6f)
                        )
                } else {
                    Modifier.fillMaxSize()
                }
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = zoomOffset.x * zoomScale
                            translationY = zoomOffset.y * zoomScale
                            scaleX = zoomScale
                            scaleY = zoomScale
                        }
                        .pointerInput(Unit) {
                            coroutineScope {
                                awaitEachGesture {
                                    var gestureType: GestureType = GestureType.Undetermined
                                    val down = awaitFirstDown(requireUnconsumed = false)

                                    // 1. Check if touching a selection handle first
                                    if (selectionManager.mode != SelectionMode.NONE && !selectionManager.isSelecting) {
                                        val range = selectionManager.selectionRange
                                        if (range != null) {
                                            val (touchingStart, touchingEnd) = isTouchingHandle(
                                                down.position,
                                                range,
                                                baseCharWidth,
                                                baseCharHeight
                                            )
                                            if (touchingStart || touchingEnd) {
                                                gestureType = GestureType.HandleDrag
                                                // Handle drag
                                                showMagnifier = true
                                                magnifierPosition = down.position

                                                drag(down.id) { change ->
                                                    val newCol =
                                                        (change.position.x / baseCharWidth).toInt()
                                                            .coerceIn(0, terminalBuffer.cols - 1)
                                                    val newRow =
                                                        (change.position.y / baseCharHeight).toInt()
                                                            .coerceIn(0, terminalBuffer.rows - 1)

                                                    if (touchingStart) {
                                                        selectionManager.updateSelectionStart(
                                                            newRow,
                                                            newCol
                                                        )
                                                    } else {
                                                        selectionManager.updateSelectionEnd(
                                                            newRow,
                                                            newCol
                                                        )
                                                    }

                                                    magnifierPosition = change.position
                                                    change.consume()
                                                }

                                                showMagnifier = false
                                                // Don't auto-show menu again after dragging handle
                                                return@awaitEachGesture
                                            }
                                        }
                                    }

                                    // 2. Start long press detection for selection
                                    var longPressDetected = false
                                    val longPressJob = launch {
                                        delay(viewConfiguration.longPressTimeoutMillis)
                                        if (gestureType == GestureType.Undetermined) {
                                            longPressDetected = true
                                            gestureType = GestureType.Selection

                                            // Start selection
                                            val col = (down.position.x / baseCharWidth).toInt()
                                                .coerceIn(0, terminalBuffer.cols - 1)
                                            val row = (down.position.y / baseCharHeight).toInt()
                                                .coerceIn(0, terminalBuffer.rows - 1)
                                            selectionManager.startSelection(
                                                row,
                                                col,
                                                SelectionMode.BLOCK
                                            )
                                            showMagnifier = true
                                            magnifierPosition = down.position
                                        }
                                    }

                                    // 3. Check for multi-touch (zoom)
                                    val secondPointer = withTimeoutOrNull(40) {
                                        awaitPointerEvent().changes.firstOrNull { it.id != down.id && it.pressed }
                                    }

                                    if (secondPointer != null) {
                                        longPressJob.cancel()
                                        gestureType = GestureType.Zoom

                                        // Handle zoom using Compose's built-in gesture calculations
                                        isZooming = true

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.changes.all { !it.pressed }) break

                                            if (event.changes.size > 1) {
                                                val gestureZoom = event.calculateZoom()
                                                val gesturePan = event.calculatePan()

                                                val oldScale = zoomScale
                                                val newScale =
                                                    (oldScale * gestureZoom).coerceIn(0.5f, 3f)

                                                zoomOffset += gesturePan
                                                zoomScale = newScale

                                                event.changes.forEach { it.consume() }
                                            }
                                        }

                                        // Gesture ended - reset
                                        isZooming = false
                                        zoomScale = 1f
                                        zoomOffset = Offset.Zero

                                        return@awaitEachGesture
                                    }

                                    // 4. Track velocity for scroll fling
                                    val velocityTracker = VelocityTracker()
                                    velocityTracker.addPosition(down.uptimeMillis, down.position)

                                    // 5. Event loop for single-touch gestures
                                    while (true) {
                                        val event: PointerEvent =
                                            awaitPointerEvent(PointerEventPass.Main)
                                        if (event.changes.all { !it.pressed }) break

                                        val change = event.changes.first()
                                        velocityTracker.addPosition(
                                            change.uptimeMillis,
                                            change.position
                                        )
                                        val dragAmount = change.positionChange()

                                        // Determine gesture if still undetermined
                                        if (gestureType == GestureType.Undetermined && !longPressDetected) {
                                            if (dragAmount.getDistanceSquared() > 100f) {  // Touch slop
                                                longPressJob.cancel()
                                                gestureType = GestureType.Scroll
                                            }
                                        }

                                        // Handle based on gesture type
                                        when (gestureType) {
                                            GestureType.Selection -> {
                                                if (selectionManager.isSelecting) {
                                                    val dragCol =
                                                        (change.position.x / baseCharWidth).toInt()
                                                            .coerceIn(0, terminalBuffer.cols - 1)
                                                    val dragRow =
                                                        (change.position.y / baseCharHeight).toInt()
                                                            .coerceIn(0, terminalBuffer.rows - 1)
                                                    selectionManager.updateSelection(
                                                        dragRow,
                                                        dragCol
                                                    )
                                                    magnifierPosition = change.position
                                                }
                                            }

                                            GestureType.Scroll -> {
                                                // Update scroll offset
                                                // Drag down (positive dragAmount.y) = view older content (increase scrollbackPosition)
                                                // Drag up (negative dragAmount.y) = view newer content (decrease scrollbackPosition)
                                                val newOffset = (scrollOffset.value + dragAmount.y)
                                                    .coerceIn(0f, maxScroll)
                                                coroutineScope.launch {
                                                    scrollOffset.snapTo(newOffset)
                                                }

                                                // Update terminal buffer scrollback position
                                                val scrolledLines =
                                                    (scrollOffset.value / baseCharHeight).toInt()
                                                terminalBuffer.scrollbackPosition = scrolledLines
                                                    .coerceIn(0, terminalBuffer.scrollback.size)
                                            }

                                            else -> {}
                                        }

                                        change.consume()
                                    }

                                    // 6. Gesture ended - cleanup
                                    longPressJob.cancel()

                                    when (gestureType) {
                                        GestureType.Scroll -> {
                                            // Apply fling animation
                                            val velocity = velocityTracker.calculateVelocity()
                                            coroutineScope.launch {
                                                var targetValue = scrollOffset.targetValue
                                                scrollOffset.animateDecay(
                                                    initialVelocity = velocity.y,
                                                    animationSpec = splineBasedDecay(density)
                                                ) {
                                                    targetValue = value
                                                    // Update terminal buffer during animation
                                                    val scrolledLines =
                                                        (value / baseCharHeight).toInt()
                                                    terminalBuffer.scrollbackPosition =
                                                        scrolledLines
                                                            .coerceIn(
                                                                0,
                                                                terminalBuffer.scrollback.size
                                                            )
                                                }

                                                // Clamp final position if needed
                                                if (targetValue < 0f) {
                                                    scrollOffset.snapTo(0f)
                                                    terminalBuffer.scrollbackPosition = 0
                                                } else if (targetValue > maxScroll) {
                                                    scrollOffset.snapTo(maxScroll)
                                                    val scrolledLines =
                                                        (maxScroll / baseCharHeight).toInt()
                                                    terminalBuffer.scrollbackPosition =
                                                        scrolledLines
                                                            .coerceIn(
                                                                0,
                                                                terminalBuffer.scrollback.size
                                                            )
                                                }
                                            }
                                        }

                                        GestureType.Selection -> {
                                            showMagnifier = false
                                            if (selectionManager.isSelecting) {
                                                selectionManager.endSelection()
                                            }
                                        }

                                        GestureType.Undetermined -> {
                                            // Tap - clear selection if exists
                                            if (selectionManager.mode != SelectionMode.NONE) {
                                                selectionManager.clearSelection()
                                            }
                                        }

                                        else -> {}
                                    }
                                }
                            }
                        }
                ) {
                    // Fill background
                    drawRect(
                        color = backgroundColor,
                        size = size
                    )

                    // Draw each line (zoom/pan applied via graphicsLayer)
                    for (row in 0 until terminalBuffer.rows) {
                        val line = terminalBuffer.getLine(row)
                        drawLine(
                            line = line,
                            row = row,
                            charWidth = baseCharWidth,
                            charHeight = baseCharHeight,
                            charBaseline = baseCharBaseline,
                            textPaint = textPaint,
                            defaultFg = foregroundColor,
                            defaultBg = backgroundColor,
                            selectionManager = selectionManager
                        )
                    }

                    // Draw cursor (only when viewing current screen, not scrollback)
                    if (terminalBuffer.cursorVisible && terminalBuffer.scrollbackPosition == 0) {
                        drawCursor(
                            row = terminalBuffer.cursorRow,
                            col = terminalBuffer.cursorCol,
                            charWidth = baseCharWidth,
                            charHeight = baseCharHeight,
                            foregroundColor = foregroundColor
                        )
                    }

                    // Draw selection handles
                    if (selectionManager.mode != SelectionMode.NONE && !selectionManager.isSelecting) {
                        val range = selectionManager.selectionRange
                        if (range != null) {
                            // Start handle
                            val startPosition = range.getStartPosition()
                            drawSelectionHandle(
                                row = startPosition.first,
                                col = startPosition.second,
                                charWidth = baseCharWidth,
                                charHeight = baseCharHeight,
                                pointingDown = false,
                            )

                            // End handle
                            val endPosition = range.getEndPosition()
                            drawSelectionHandle(
                                row = endPosition.first,
                                col = endPosition.second,
                                charWidth = baseCharWidth,
                                charHeight = baseCharHeight,
                                pointingDown = true,
                            )
                        }
                    }
                }
            }

            // Magnifying glass
            if (showMagnifier) {
                MagnifyingGlass(
                    position = magnifierPosition,
                    terminalBuffer = terminalBuffer,
                    baseCharWidth = baseCharWidth,
                    baseCharHeight = baseCharHeight,
                    baseCharBaseline = baseCharBaseline,
                    textPaint = textPaint,
                    backgroundColor = backgroundColor,
                    foregroundColor = foregroundColor,
                    selectionManager = selectionManager
                )
            }

            // Copy button when text is selected
            if (selectionManager.mode != SelectionMode.NONE && !selectionManager.isSelecting) {
                val range = selectionManager.selectionRange
                if (range != null) {
                    // Position copy button above the selection
                    val endPosition = range.getEndPosition()
                    val buttonX = endPosition.second * baseCharWidth
                    val buttonY = endPosition.first * baseCharHeight - with(density) { 48.dp.toPx() }

                    Box(
                        modifier = Modifier
                            .offset(
                                x = with(density) { buttonX.toDp() },
                                y = with(density) { buttonY.coerceAtLeast(0f).toDp() }
                            )
                    ) {
                        FloatingActionButton(
                            onClick = {
                                val selectedText = selectionManager.getSelectedText()
                                clipboardManager.setText(AnnotatedString(selectedText))
                                selectionManager.clearSelection()
                            },
                            modifier = Modifier.size(48.dp),
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ) {
                            Text("Copy", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Draw a single terminal line.
 */
private fun DrawScope.drawLine(
    line: TerminalLine,
    row: Int,
    charWidth: Float,
    charHeight: Float,
    charBaseline: Float,
    textPaint: TextPaint,
    defaultFg: Color,
    defaultBg: Color,
    selectionManager: SelectionManager
) {
    val y = row * charHeight
    var x = 0f

    line.cells.forEachIndexed { col, cell ->
        val cellWidth = charWidth * cell.width

        // Check if this cell is selected
        val isSelected = selectionManager.isCellSelected(row, col)

        // Determine colors (handle reverse video and selection)
        val fgColor = if (cell.reverse) cell.bgColor else cell.fgColor
        val bgColor = if (cell.reverse) cell.fgColor else cell.bgColor

        // Draw background (with selection highlight)
        val finalBgColor = if (isSelected) Color(0xFF4A90E2) else bgColor
        if (finalBgColor != defaultBg || isSelected) {
            drawRect(
                color = finalBgColor,
                topLeft = Offset(x, y),
                size = Size(cellWidth, charHeight)
            )
        }

        // Draw character
        if (cell.char != ' ' || cell.combiningChars.isNotEmpty()) {
            val text = buildString {
                append(cell.char)
                cell.combiningChars.forEach { append(it) }
            }

            // Configure text paint for this cell
            textPaint.color = fgColor.toArgb()
            textPaint.isFakeBoldText = cell.bold
            textPaint.textSkewX = if (cell.italic) -0.25f else 0f
            textPaint.isUnderlineText = cell.underline > 0
            textPaint.isStrikeThruText = cell.strike

            // Draw text
            drawContext.canvas.nativeCanvas.drawText(
                text,
                x,
                y + charBaseline,
                textPaint
            )
        }

        x += cellWidth
    }
}

/**
 * Check if a touch position is near a selection handle.
 * Returns (touchingStart, touchingEnd).
 */
private fun isTouchingHandle(
    touchPos: Offset,
    range: SelectionRange,
    charWidth: Float,
    charHeight: Float,
    hitRadius: Float = 50f
): Pair<Boolean, Boolean> {
    val startPos = Offset(
        range.startCol * charWidth + charWidth / 2,
        range.startRow * charHeight
    )
    val endPos = Offset(
        range.endCol * charWidth + charWidth / 2,
        range.endRow * charHeight + charHeight
    )

    val distToStart = (touchPos - startPos).getDistance()
    val distToEnd = (touchPos - endPos).getDistance()

    return Pair(
        distToStart < hitRadius,
        distToEnd < hitRadius
    )
}

/**
 * Magnifying glass for text selection.
 */
@Composable
private fun MagnifyingGlass(
    position: Offset,
    terminalBuffer: TerminalBuffer,
    baseCharWidth: Float,
    baseCharHeight: Float,
    baseCharBaseline: Float,
    textPaint: TextPaint,
    backgroundColor: Color,
    foregroundColor: Color,
    selectionManager: SelectionManager
) {
    val magnifierSize = 100.dp
    val magnifierScale = 2.5f
    val density = LocalDensity.current
    val magnifierSizePx = with(density) { magnifierSize.toPx() }

    // Position magnifying glass well above the finger (so it's visible)
    val verticalOffset = with(density) { 40.dp.toPx() }
    val magnifierPos = Offset(
        x = (position.x - magnifierSizePx / 2).coerceIn(0f, Float.MAX_VALUE),
        y = (position.y - verticalOffset - magnifierSizePx).coerceAtLeast(0f)
    )

    // The actual touch point that should be centered in the magnifier
    val centerOffset = Offset(
        x = position.x - (magnifierSizePx / magnifierScale) * 1.2f,
        y = position.y - (magnifierSizePx / magnifierScale) * 1.2f,
    )

    Box(
        modifier = Modifier
            .offset(
                x = with(density) { magnifierPos.x.toDp() },
                y = with(density) { magnifierPos.y.toDp() }
            )
            .size(magnifierSize)
            .border(
                width = 2.dp,
                color = Color.Gray,
                shape = CircleShape
            )
            .background(
                color = Color.White.copy(alpha = 0.9f),
                shape = CircleShape
            )
            .clip(CircleShape)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Fill background
            drawRect(
                color = backgroundColor,
                size = size
            )

            // Apply magnification and translate to center the touch point
            translate(-centerOffset.x * magnifierScale, -centerOffset.y * magnifierScale) {
                scale(magnifierScale, magnifierScale) {
                    // Calculate which rows and columns to draw
                    val centerRow = (position.y / baseCharHeight).toInt().coerceIn(0, terminalBuffer.rows - 1)
                    val centerCol = (position.x / baseCharWidth).toInt().coerceIn(0, terminalBuffer.cols - 1)

                    // Draw a few rows around the touch point
                    val rowRange = 3
                    for (rowOffset in -rowRange..rowRange) {
                        val row = (centerRow + rowOffset).coerceIn(0, terminalBuffer.rows - 1)
                        val line = terminalBuffer.getLine(row)
                        drawLine(
                            line = line,
                            row = row,
                            charWidth = baseCharWidth,
                            charHeight = baseCharHeight,
                            charBaseline = baseCharBaseline,
                            textPaint = textPaint,
                            defaultFg = foregroundColor,
                            defaultBg = backgroundColor,
                            selectionManager = selectionManager
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draw a selection handle (teardrop shape).
 */
private fun DrawScope.drawSelectionHandle(
    row: Int,
    col: Int,
    charWidth: Float,
    charHeight: Float,
    pointingDown: Boolean,
    color: Color = Color.White,
) {
    val handleWidth = 24.dp
    val handleWidthPx = handleWidth.toPx()

    // Position handle at the character position
    val charX = col * charWidth
    val charY = row * charHeight

    // Center the handle horizontally on the character
    val handleX = charX + charWidth / 2

    // Position vertically based on direction
    val handleY = if (pointingDown) {
        charY + charHeight // Bottom of character
    } else {
        charY // Top of character
    }

    val circleRadius = handleWidthPx / 2
    val circleY = if (pointingDown) {
        handleY + circleRadius
    } else {
        handleY - circleRadius
    }

    drawCircle(
        color = color,
        radius = circleRadius,
        center = Offset(handleX, circleY)
    )
}

/**
 * Draw the cursor.
 */
private fun DrawScope.drawCursor(
    row: Int,
    col: Int,
    charWidth: Float,
    charHeight: Float,
    foregroundColor: Color
) {
    val x = col * charWidth
    val y = row * charHeight

    // Draw cursor as a rectangle outline
    drawRect(
        color = foregroundColor,
        topLeft = Offset(x, y),
        size = Size(charWidth, charHeight),
        alpha = 0.7f
    )
}

/**
 * Calculate pixel dimensions for a specific row/column count at a given font size.
 *
 * @param rows Number of rows
 * @param cols Number of columns
 * @param fontSize Font size in sp
 * @param typeface The typeface to use for measurement
 * @param density Screen density for sp to px conversion
 * @return Pair of (width in pixels, height in pixels)
 */
private fun calculateDimensions(
    rows: Int,
    cols: Int,
    fontSize: Float,
    typeface: Typeface,
    density: Float
): Pair<Float, Float> {
    val textPaint = TextPaint().apply {
        this.typeface = typeface
        textSize = fontSize * density
    }

    val charWidth = textPaint.measureText("M")
    val metrics = textPaint.fontMetrics
    val charHeight = metrics.descent - metrics.ascent

    val width = cols * charWidth
    val height = rows * charHeight

    return Pair(width, height)
}

/**
 * Find the optimal font size that allows the terminal to fit within the available space
 * while maintaining the exact target rows and columns.
 *
 * Uses binary search to efficiently find the largest font size that fits.
 *
 * @param targetRows Target number of rows
 * @param targetCols Target number of columns
 * @param availableWidth Available width in pixels
 * @param availableHeight Available height in pixels
 * @param minSize Minimum font size in sp
 * @param maxSize Maximum font size in sp
 * @param typeface The typeface to use for measurement
 * @param density Screen density for sp to px conversion
 * @return Optimal font size in sp
 */
private fun findOptimalFontSize(
    targetRows: Int,
    targetCols: Int,
    availableWidth: Int,
    availableHeight: Int,
    minSize: Float,
    maxSize: Float,
    typeface: Typeface,
    density: Float
): Float {
    var minSizeCurrent = minSize
    var maxSizeCurrent = maxSize
    val epsilon = 0.1f // Convergence threshold

    // Binary search for optimal font size
    while (maxSizeCurrent - minSizeCurrent > epsilon) {
        val midSize = (minSizeCurrent + maxSizeCurrent) / 2f
        val (width, height) = calculateDimensions(
            rows = targetRows,
            cols = targetCols,
            fontSize = midSize,
            typeface = typeface,
            density = density
        )

        if (width <= availableWidth && height <= availableHeight) {
            // This size fits, try larger
            minSizeCurrent = midSize
        } else {
            // This size doesn't fit, try smaller
            maxSizeCurrent = midSize
        }
    }

    // Return the largest size that fits
    return minSizeCurrent.coerceIn(minSize, maxSize)
}
