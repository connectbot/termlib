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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
 * The rate at which the cursor blinks in milliseconds when enabled.
 */
private const val CURSOR_BLINK_RATE_MS = 500L

/**
 * Terminal - A Jetpack Compose terminal screen component.
 *
 * This component:
 * - Renders terminal output using Canvas
 * - Handles terminal resize based on available space
 * - Displays cursor
 * - Supports colors, bold, italic, underline, etc.
 *
 * @param terminalEmulator The terminal emulator containing terminal state
 * @param modifier Modifier for the composable
 * @param typeface Typeface for terminal text (default: Typeface.MONOSPACE)
 * @param initialFontSize Initial font size for terminal text (can be changed with pinch-to-zoom)
 * @param minFontSize Minimum font size for pinch-to-zoom
 * @param maxFontSize Maximum font size for pinch-to-zoom
 * @param backgroundColor Default background color
 * @param foregroundColor Default foreground color
 * @param keyboardEnabled Enable keyboard input handling (default: false for display-only mode).
 *                        When false, no keyboard input (hardware or soft) is accepted.
 * @param showSoftKeyboard Whether to show the soft keyboard/IME (default: true when keyboardEnabled=true).
 *                         Only applies when keyboardEnabled=true. Hardware keyboard always works when keyboardEnabled=true.
 * @param focusRequester Focus requester for keyboard input (if enabled)
 * @param onTerminalTap Callback for a simple tap event on the terminal (when no selection is active)
 * @param forcedSize Force terminal to specific dimensions (rows, cols). When set, font size is calculated to fit.
 */
@Composable
fun Terminal(
    terminalEmulator: TerminalEmulator,
    modifier: Modifier = Modifier,
    typeface: Typeface = Typeface.MONOSPACE,
    initialFontSize: TextUnit = 11.sp,
    minFontSize: TextUnit = 6.sp,
    maxFontSize: TextUnit = 30.sp,
    backgroundColor: Color = Color.Black,
    foregroundColor: Color = Color.White,
    keyboardEnabled: Boolean = false,
    showSoftKeyboard: Boolean = true,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onTerminalTap: () -> Unit = {},
    forcedSize: Pair<Int, Int>? = null,
    modifierManager: ModifierManager? = null
) {
    if (terminalEmulator !is TerminalEmulatorImpl) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Text("Unknown TerminalEmulator type")
        }
        return
    }

    val terminalEmulator: TerminalEmulatorImpl = terminalEmulator

    val density = LocalDensity.current
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Observe terminal state via StateFlow
    val screenState = rememberTerminalScreenState(terminalEmulator)

    val keyboardHandler = remember(terminalEmulator) {
        KeyboardHandler(terminalEmulator, modifierManager)
    }

    // Font size and zoom state
    var zoomScale by remember(terminalEmulator) { mutableStateOf(1f) }
    var zoomOffset by remember(terminalEmulator) { mutableStateOf(Offset.Zero) }
    var zoomOrigin by remember(terminalEmulator) { mutableStateOf(TransformOrigin.Center) }
    var isZooming by remember(terminalEmulator) { mutableStateOf(false) }
    var calculatedFontSize by remember(terminalEmulator) { mutableStateOf(initialFontSize) }

    // Magnifying glass state
    var showMagnifier by remember(terminalEmulator) { mutableStateOf(false) }
    var magnifierPosition by remember(terminalEmulator) { mutableStateOf(Offset.Zero) }

    // Cursor blink state
    var cursorBlinkVisible by remember(terminalEmulator) { mutableStateOf(true) }

    // Hardware keyboard detection
    val configuration = LocalConfiguration.current
    val hasHardwareKeyboard = remember(configuration) {
        val keyboardType = configuration.keyboard
        keyboardType == android.content.res.Configuration.KEYBOARD_QWERTY ||
                keyboardType == android.content.res.Configuration.KEYBOARD_12KEY
    }

    // Determine if IME should be shown
    // IME is shown when:
    // 1. keyboardEnabled is true (master switch)
    // 2. showSoftKeyboard is true (user wants IME visible)
    // Note: We don't check hasHardwareKeyboard because hardware keyboard users may still
    // want IME for special input methods (Chinese, Japanese, emoji, etc.)
    val shouldShowIme = keyboardEnabled && showSoftKeyboard

    // Manage focus and IME visibility
    // Using SideEffect + LaunchedEffect combo for reliable keyboard control
    LaunchedEffect(keyboardEnabled, shouldShowIme) {
        if (!keyboardEnabled) {
            // Keyboard disabled entirely - hide IME
            keyboardController?.hide()
            return@LaunchedEffect
        }

        if (shouldShowIme) {
            // Need to show IME - request focus and show keyboard
            // Use a small delay to ensure the AndroidView is ready
            delay(100)
            focusRequester.requestFocus()
            delay(50)
            keyboardController?.show()
        } else {
            // Need to hide IME - just hide, don't clear focus (hardware keyboard needs it)
            keyboardController?.hide()
        }
    }

    // Cursor blink animation
    LaunchedEffect(
        screenState.snapshot.cursorVisible,
        screenState.snapshot.cursorBlink,
        screenState.snapshot.cursorRow,
        screenState.snapshot.cursorCol
    ) {
        if (screenState.snapshot.cursorVisible) {
            cursorBlinkVisible = true
            if (screenState.snapshot.cursorBlink) {
                // Show cursor immediately when it moves or becomes visible
                while (true) {
                    delay(CURSOR_BLINK_RATE_MS)
                    cursorBlinkVisible = !cursorBlinkVisible
                }
            }
        } else {
            cursorBlinkVisible = false
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
    val scrollOffset = remember(terminalEmulator) { Animatable(0f) }
    val maxScroll = remember(screenState.snapshot.scrollback.size, baseCharHeight) {
        screenState.snapshot.scrollback.size * baseCharHeight
    }

    // Selection manager
    val selectionManager = remember(terminalEmulator) {
        SelectionManager()
    }

    // Coroutine scope for animations
    val coroutineScope = rememberCoroutineScope()
    val viewConfiguration = LocalViewConfiguration.current

    // Handle resize based on available space
    BoxWithConstraints(
        modifier = modifier
            .onSizeChanged {
                terminalEmulator.resize(
                    charsPerDimension(it.height, baseCharHeight),
                    charsPerDimension(it.width, baseCharWidth)
                )
            }
            .then(
                if (keyboardEnabled) {
                    Modifier
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
        } else {
            // When not forcing size, reset the font size to the initial value.
            LaunchedEffect(initialFontSize) {
                if (calculatedFontSize != initialFontSize) {
                    calculatedFontSize = initialFontSize
                }
            }
        }

        // Use base dimensions for terminal sizing (not zoomed dimensions)
        val newCols =
            forcedSize?.second ?: charsPerDimension(availableWidth, baseCharWidth)
        val newRows =
            forcedSize?.first ?: charsPerDimension(availableHeight, baseCharHeight)

        // Resize terminal when dimensions change
        LaunchedEffect(terminalEmulator, newRows, newCols) {
            val dimensions = terminalEmulator.dimensions
            if (newRows != dimensions.rows || newCols != dimensions.columns) {
                terminalEmulator.resize(newRows, newCols)
            }
        }

        // Auto-scroll to bottom when new content arrives (if not manually scrolled)
        val wasAtBottom = screenState.scrollbackPosition == 0
        LaunchedEffect(screenState.snapshot.lines.size, screenState.snapshot.scrollback.size) {
            // Only auto-scroll if user was already at bottom
            if (wasAtBottom && screenState.scrollbackPosition != 0) {
                screenState.scrollToBottom()
                scrollOffset.snapTo(0f)
            }
        }

        // Sync scrollOffset when scrollbackPosition changes externally (but not during user scrolling)
        LaunchedEffect(screenState.scrollbackPosition) {
            val targetOffset = screenState.scrollbackPosition * baseCharHeight
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
                            transformOrigin = zoomOrigin
                        }
                        .pointerInput(terminalEmulator, baseCharHeight) {
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
                                                            .coerceIn(0, screenState.snapshot.cols - 1)
                                                    val newRow =
                                                        (change.position.y / baseCharHeight).toInt()
                                                            .coerceIn(0, screenState.snapshot.rows - 1)

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
                                                .coerceIn(0, screenState.snapshot.cols - 1)
                                            val row = (down.position.y / baseCharHeight).toInt()
                                                .coerceIn(0, screenState.snapshot.rows - 1)
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

                                        val centerX = (down.position.x + secondPointer.position.x) / 2f
                                        val centerY = (down.position.y + secondPointer.position.y) / 2f
                                        zoomOrigin = TransformOrigin(
                                            pivotFractionX = centerX / size.width,
                                            pivotFractionY = centerY / size.height
                                        )

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
                                                            .coerceIn(0, screenState.snapshot.cols - 1)
                                                    val dragRow =
                                                        (change.position.y / baseCharHeight).toInt()
                                                            .coerceIn(0, screenState.snapshot.rows - 1)
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
                                                    (newOffset / baseCharHeight).toInt()
                                                screenState.scrollBy(scrolledLines - screenState.scrollbackPosition)
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
                                                    screenState.scrollBy(scrolledLines - screenState.scrollbackPosition)
                                                }

                                                // Clamp final position if needed
                                                if (targetValue < 0f) {
                                                    scrollOffset.snapTo(0f)
                                                    screenState.scrollToBottom()
                                                } else if (targetValue > maxScroll) {
                                                    scrollOffset.snapTo(maxScroll)
                                                    screenState.scrollToTop()
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
                                            // This is a tap. If a selection is active, clear it.
                                            // Otherwise, forward the tap.
                                            if (selectionManager.mode != SelectionMode.NONE) {
                                                selectionManager.clearSelection()
                                            } else {
                                                // Request focus when terminal is tapped to show keyboard
                                                if (keyboardEnabled) {
                                                    focusRequester.requestFocus()
                                                }
                                                onTerminalTap()
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
                    for (row in 0 until screenState.snapshot.rows) {
                        val line = screenState.getVisibleLine(row)
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
                    if (screenState.snapshot.cursorVisible && screenState.scrollbackPosition == 0 && cursorBlinkVisible) {
                        drawCursor(
                            row = screenState.snapshot.cursorRow,
                            col = screenState.snapshot.cursorCol,
                            charWidth = baseCharWidth,
                            charHeight = baseCharHeight,
                            foregroundColor = foregroundColor,
                            cursorShape = screenState.snapshot.cursorShape
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
        }

        // Magnifying glass
        if (showMagnifier) {
            MagnifyingGlass(
                position = magnifierPosition,
                screenState = screenState,
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
                            val selectedText =
                                selectionManager.getSelectedText(screenState.snapshot)
                            clipboardManager.setText(AnnotatedString(selectedText))
                            selectionManager.clearSelection()
                        },
                        modifier = Modifier.size(48.dp),
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ) {
                        Text("Copy", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Hidden AndroidView with custom InputConnection for IME (software keyboard) input
        // This provides proper backspace, enter key, and keyboard type handling
        if (keyboardEnabled) {
            AndroidView(
                factory = { context ->
                    ImeInputView(context, keyboardHandler).apply {
                        // Set up key event handling
                        setOnKeyListener { _, keyCode, event ->
                            keyboardHandler.onKeyEvent(
                                androidx.compose.ui.input.key.KeyEvent(event)
                            )
                        }
                    }
                },
                modifier = Modifier
                    .size(1.dp)
                    .focusRequester(focusRequester)
                    .focusable()
            )
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
    screenState: TerminalScreenState,
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
                    val centerRow = (position.y / baseCharHeight).toInt().coerceIn(0, screenState.snapshot.rows - 1)
                    val centerCol = (position.x / baseCharWidth).toInt().coerceIn(0, screenState.snapshot.cols - 1)

                    // Draw a few rows around the touch point
                    val rowRange = 3
                    for (rowOffset in -rowRange..rowRange) {
                        val row = (centerRow + rowOffset).coerceIn(0, screenState.snapshot.rows - 1)
                        val line = screenState.getVisibleLine(row)
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
 * Draw the cursor with shape support (block, underline, bar).
 */
private fun DrawScope.drawCursor(
    row: Int,
    col: Int,
    charWidth: Float,
    charHeight: Float,
    foregroundColor: Color,
    cursorShape: CursorShape = CursorShape.BLOCK
) {
    val x = col * charWidth
    val y = row * charHeight

    when (cursorShape) {
        CursorShape.BLOCK -> {
            // Block cursor - full cell rectangle outline
            drawRect(
                color = foregroundColor,
                topLeft = Offset(x, y),
                size = Size(charWidth, charHeight),
                alpha = 0.7f
            )
        }

        CursorShape.UNDERLINE -> {
            // Underline cursor - line at bottom of cell
            val underlineHeight = charHeight * 0.15f  // 15% of cell height
            drawRect(
                color = foregroundColor,
                topLeft = Offset(x, y + charHeight - underlineHeight),
                size = Size(charWidth, underlineHeight),
                alpha = 0.9f
            )
        }

        CursorShape.BAR_LEFT -> {
            // Bar cursor - vertical line at left of cell
            val barWidth = charWidth * 0.15f  // 15% of cell width
            drawRect(
                color = foregroundColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, charHeight),
                alpha = 0.9f
            )
        }
    }
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

private fun charsPerDimension(pixels: Int, charPixels: Float) =
    (pixels / charPixels).toInt().coerceAtLeast(1)
