/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.icu.text.BreakIterator
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import kotlin.math.ceil
import kotlin.math.floor

/** The app owns virtual row identities and focus, while TalkBack owns its gestures. */
internal class TerminalAccessibilityView(context: Context, val review: TerminalReviewSession) : View(context) {
    private val manager = context.getSystemService(AccessibilityManager::class.java)
    private var live = TerminalReviewDocument(review.screenState.snapshot)
    private var liveTop = 0
    private var documentSnapshot = live.snapshot
    private var nextId = 1
    private val rowIds = mutableMapOf<Int, Int>()
    private val idRows = mutableMapOf<Int, Int>()
    private var focused = INVALID
    private var hovered = INVALID
    private var selectionStart = 0
    private var selectionEnd = 0
    private var lastTop = 0
    private var lastColumn = 0
    private var wasReviewing = false
    private var lastReviewDocument: TerminalReviewDocument? = null
    private val focusPaint = Paint().apply { color = 0x334caf50 }
    var charWidth = 1f
    var charHeight = 1f
    var onSearch: () -> Unit = {}
    var onCompose: (() -> Unit)? = null
    var onMessage: (String) -> Unit = {}
    var onLink: (String) -> Unit = {}

    // Renderer's mapping handles bidi and shaped cell positions for horizontal reveal.
    var visualColumn: (Int, Int) -> Int = { _, column -> column }
    private var dragRows = 0f
    private var dragColumns = 0f
    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                dragRows = 0f
                dragColumns = 0f
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                freeze()
                dragRows += distanceY / charHeight
                dragColumns += distanceX / charWidth
                val rows = dragRows.toInt()
                val columns = dragColumns.toInt()
                dragRows -= rows
                dragColumns -= columns
                if (review.scroll(rows, columns)) scrolled()
                return true
            }
        },
    )

    private val document: TerminalReviewDocument get() = review.document ?: live
    private val firstVisibleRow: Int get() = if (review.isReviewing) review.firstRow else liveTop
    private val column: Int get() = if (review.isReviewing) review.firstColumn else 0
    private val visibleRows: Int get() = (height / charHeight).toInt().coerceAtLeast(1)
    private val visibleColumns: Int get() = (width / charWidth).toInt().coerceAtLeast(1)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        isFocusableInTouchMode = true
        setWillNotDraw(false)
        ViewCompat.setAccessibilityDelegate(
            this,
            object : AccessibilityDelegateCompat() {
                override fun getAccessibilityNodeProvider(host: View) = provider
            },
        )
    }

    fun update(snapshot: TerminalSnapshot, scrollbackPosition: Int, width: Float, height: Float) {
        charWidth = width.coerceAtLeast(1f)
        charHeight = height.coerceAtLeast(1f)
        if (live.snapshot !== snapshot) live = TerminalReviewDocument(snapshot)
        liveTop = if (snapshot.alternateScreen) 0 else snapshot.scrollback.size - scrollbackPosition
        review.resize(visibleRows, visibleColumns)
        val current = document.snapshot
        val reviewReplaced = lastReviewDocument != null && lastReviewDocument !== review.document
        val enteringReview = !wasReviewing && review.isReviewing
        if (documentSnapshot !== current || reviewReplaced) {
            clearFocusNode()
            rowIds.clear()
            idRows.clear()
            documentSnapshot = current
            event(HOST, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        wasReviewing = review.isReviewing
        lastReviewDocument = review.document
        if ((enteringReview || reviewReplaced) && review.isReviewing && focused == INVALID) {
            review.focusedRow?.let { focusRow(it) }
        }
        if (lastTop != firstVisibleRow || lastColumn != column) scrolled()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        review.resize(visibleRows, visibleColumns)
        event(HOST, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    internal fun id(row: Int): Int = rowIds.getOrPut(row) { nextId++.also { idRows[it] = row } }

    internal val provider = object : AccessibilityNodeProviderCompat() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfoCompat? {
            if (virtualViewId == HOST) {
                return AccessibilityNodeInfoCompat.obtain(this@TerminalAccessibilityView).apply {
                    setSource(this@TerminalAccessibilityView)
                    (this@TerminalAccessibilityView.parent as? View)?.let { setParent(it) }
                    className = "android.widget.ScrollView"
                    packageName = context.packageName
                    contentDescription = context.getString(if (review.isReviewing) R.string.terminal_a11y_review else R.string.terminal_a11y_output)
                    isEnabled = true
                    isVisibleToUser = isShown
                    isScrollable = document.lines.size > visibleRows || document.snapshot.cols > visibleColumns
                    setBoundsInScreen(screenBounds(Rect(0, 0, width, height)))
                    setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(document.lines.size, 1, false))
                    for (row in firstVisibleRow until minOf(firstVisibleRow + visibleRows, document.lines.size)) addChild(this@TerminalAccessibilityView, id(row))
                    if (firstVisibleRow > 0) {
                        addAction(AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
                        addAction(AccessibilityActionCompat.ACTION_SCROLL_UP)
                    }
                    if (firstVisibleRow + visibleRows < document.lines.size) {
                        addAction(AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
                        addAction(AccessibilityActionCompat.ACTION_SCROLL_DOWN)
                    }
                    if (column > 0) addAction(AccessibilityActionCompat.ACTION_SCROLL_LEFT)
                    if (column + visibleColumns < document.snapshot.cols) addAction(AccessibilityActionCompat.ACTION_SCROLL_RIGHT)
                    actions(this)
                }
            }
            val row = idRows[virtualViewId] ?: return null
            val line = document.lines.getOrNull(row) ?: return null
            return AccessibilityNodeInfoCompat.obtain().apply {
                setSource(this@TerminalAccessibilityView, virtualViewId)
                setParent(this@TerminalAccessibilityView)
                className = "android.widget.TextView"
                packageName = context.packageName
                text = line.text
                // Keep actual text and offsets intact, including empty rows.
                if (line.text.isBlank()) stateDescription = context.getString(R.string.terminal_a11y_blank)
                isEnabled = true
                isFocusable = true
                isScreenReaderFocusable = true
                isAccessibilityFocused = focused == virtualViewId
                isVisibleToUser = isShown && row in firstVisibleRow until firstVisibleRow + visibleRows
                val bounds = Rect(0, floor((row - firstVisibleRow) * charHeight).toInt(), width, ceil((row - firstVisibleRow + 1) * charHeight).toInt())
                setBoundsInParent(bounds)
                setBoundsInScreen(screenBounds(bounds))
                setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(row, 1, 0, 1, line.hasPrompt()))
                addAction(if (isAccessibilityFocused) AccessibilityActionCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS else AccessibilityActionCompat.ACTION_ACCESSIBILITY_FOCUS)
                addAction(AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN)
                addAction(AccessibilityActionCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
                addAction(AccessibilityActionCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
                addAction(AccessibilityActionCompat.ACTION_SET_SELECTION)
                addAction(AccessibilityActionCompat.ACTION_COPY)
                movementGranularities = GRANULARITIES
                isTextSelectable = true
                if (Build.VERSION.SDK_INT >= 26) availableExtraData = listOf(CHARACTER_LOCATION)
                if (focused == virtualViewId) setTextSelection(selectionStart, selectionEnd)
                actions(this)
                addAction(AccessibilityActionCompat(R.id.terminal_a11y_copy_line, context.getString(R.string.terminal_a11y_copy_line)))
                addAction(AccessibilityActionCompat(R.id.terminal_a11y_describe, context.getString(R.string.terminal_a11y_describe)))
                if (document.prompt(row, false) != null) addAction(AccessibilityActionCompat(R.id.terminal_a11y_previous_prompt, context.getString(R.string.terminal_a11y_previous_prompt)))
                if (document.prompt(row, true) != null) addAction(AccessibilityActionCompat(R.id.terminal_a11y_next_prompt, context.getString(R.string.terminal_a11y_next_prompt)))
                if (line.semanticSegments.any { it.semanticType == SemanticType.HYPERLINK }) addAction(AccessibilityActionCompat(R.id.terminal_a11y_open_link, context.getString(R.string.terminal_a11y_open_link)))
            }
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfoCompat? = if (focus == AccessibilityNodeInfoCompat.FOCUS_ACCESSIBILITY && focused != INVALID) createAccessibilityNodeInfo(focused) else null

        override fun addExtraDataToAccessibilityNodeInfo(virtualViewId: Int, info: AccessibilityNodeInfoCompat, extraDataKey: String, arguments: Bundle?) {
            if (Build.VERSION.SDK_INT < 26 || extraDataKey != CHARACTER_LOCATION || arguments == null) return
            val row = idRows[virtualViewId] ?: return
            val text = document.text(row)
            val start = arguments.getInt(android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, -1)
            val length = arguments.getInt(android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, -1)
            if (start < 0 || length < 0 || start > text.text.length || length > text.text.length - start) return
            val hostBounds = screenBounds(Rect(0, 0, width, height))
            val rectangles = arrayOfNulls<RectF>(length)
            if (row in firstVisibleRow until firstVisibleRow + visibleRows) {
                for (index in rectangles.indices) {
                    val cell = text.column(start + index)
                    val x = (visualColumn(row - firstVisibleRow, cell) - column) * charWidth + hostBounds.left
                    val y = (row - firstVisibleRow) * charHeight + hostBounds.top
                    val bounds = RectF(x, y, x + text.line.cells.width(cell).coerceAtLeast(1) * charWidth, y + charHeight)
                    if (bounds.intersect(RectF(hostBounds))) rectangles[index] = bounds
                }
            }
            info.extras.putParcelableArray(CHARACTER_LOCATION, rectangles)
        }

        override fun findAccessibilityNodeInfosByText(text: String, virtualViewId: Int): List<AccessibilityNodeInfoCompat> = document.search(text).map { it.first }.distinct().mapNotNull { row -> createAccessibilityNodeInfo(id(row)) }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val row = idRows[virtualViewId]
            if (virtualViewId != HOST && row == null) return false
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS -> return row?.let { focusRow(it) } ?: false

                AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (focused != virtualViewId) return false
                    clearFocusNode()
                    return true
                }

                AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD,
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
                -> {
                    freeze()
                    val backwards = action == AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD || action == AccessibilityActionCompat.ACTION_SCROLL_UP.id
                    val moved = review.scroll(rows = (visibleRows - 1).coerceAtLeast(1) * if (backwards) -1 else 1)
                    if (moved) scrolled()
                    return moved
                }

                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
                -> {
                    freeze()
                    val moved = review.scroll(columns = visibleColumns * if (action == AccessibilityActionCompat.ACTION_SCROLL_LEFT.id) -1 else 1)
                    if (moved) scrolled()
                    return moved
                }

                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id -> return row?.let {
                    freeze()
                    review.focus(it).also { scrolled() }
                } ?: false

                AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY -> return row != null && traverse(row, action, arguments)

                AccessibilityNodeInfoCompat.ACTION_SET_SELECTION -> {
                    if (row == null || arguments == null) return false
                    val start = arguments.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, -1)
                    val end = arguments.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, -1)
                    val length = document.lines[row].text.length
                    if (start !in 0..length || end !in 0..length) return false
                    focusRow(row)
                    selectionStart = start
                    selectionEnd = end
                    revealOffset(row, end)
                    selectionEvent()
                    return true
                }

                AccessibilityNodeInfoCompat.ACTION_COPY -> {
                    if (row == null || focused != virtualViewId || selectionStart == selectionEnd) return false
                    copy(document.lines[row].text.substring(minOf(selectionStart, selectionEnd), maxOf(selectionStart, selectionEnd)))
                    return true
                }

                R.id.terminal_a11y_review -> {
                    freeze()
                    return focusRow(document.cursorIndex.coerceIn(document.lines.indices))
                }

                R.id.terminal_a11y_refresh -> {
                    clearFocusNode()
                    review.refreshReview()
                    resetDocument()
                    return focusRow(review.focusedRow ?: 0)
                }

                R.id.terminal_a11y_resume -> {
                    clearFocusNode()
                    review.resumeLiveOutput()
                    resetDocument()
                    return true
                }

                R.id.terminal_a11y_input -> {
                    clearFocusNode()
                    review.returnToInput()
                    resetDocument()
                    return true
                }

                R.id.terminal_a11y_search -> {
                    freeze()
                    onSearch()
                    return true
                }

                R.id.terminal_a11y_compose -> {
                    onCompose?.invoke() ?: return false
                    return true
                }

                R.id.terminal_a11y_copy_line -> {
                    if (row == null) return false
                    copy(document.lines[row].text.trimEnd())
                    return true
                }

                R.id.terminal_a11y_last_output -> {
                    onMessage(document.lastOutput() ?: return false)
                    return true
                }

                R.id.terminal_a11y_copy_output -> {
                    copy(document.lastOutput() ?: return false)
                    return true
                }

                R.id.terminal_a11y_previous_prompt, R.id.terminal_a11y_next_prompt -> {
                    val target = document.prompt(row ?: firstVisibleRow, action == R.id.terminal_a11y_next_prompt) ?: return false
                    return focusRow(target)
                }

                R.id.terminal_a11y_describe -> {
                    if (row == null) return false
                    val types = document.lines[row].semanticSegments.map { it.semanticType }.distinct()
                    onMessage(
                        context.getString(R.string.terminal_a11y_line_number, row + 1, document.lines.size) +
                            types.joinToString(prefix = ". ") { context.getString(semanticLabel(it)) },
                    )
                    return true
                }

                R.id.terminal_a11y_open_link -> {
                    val link = row?.let { document.lines[it].semanticSegments.firstOrNull { segment -> segment.semanticType == SemanticType.HYPERLINK }?.metadata } ?: return false
                    onLink(link)
                    return true
                }
            }
            return false
        }
    }

    private fun semanticLabel(type: SemanticType) = when (type) {
        SemanticType.PROMPT -> R.string.terminal_a11y_prompt
        SemanticType.COMMAND_INPUT -> R.string.terminal_a11y_command
        SemanticType.COMMAND_OUTPUT -> R.string.terminal_a11y_output
        SemanticType.COMMAND_FINISHED -> R.string.terminal_a11y_finished
        SemanticType.HYPERLINK -> R.string.terminal_a11y_link
        SemanticType.ANNOTATION -> R.string.terminal_a11y_annotation
        SemanticType.DEFAULT -> R.string.terminal_a11y_text
    }

    private fun actions(node: AccessibilityNodeInfoCompat) {
        fun add(id: Int, label: Int) = node.addAction(AccessibilityActionCompat(id, context.getString(label)))
        if (review.isReviewing) {
            add(R.id.terminal_a11y_refresh, R.string.terminal_a11y_refresh)
            add(R.id.terminal_a11y_resume, R.string.terminal_a11y_resume)
        } else {
            add(R.id.terminal_a11y_review, R.string.terminal_a11y_review)
        }
        add(R.id.terminal_a11y_input, R.string.terminal_a11y_input)
        add(R.id.terminal_a11y_search, R.string.terminal_a11y_search)
        if (onCompose != null) add(R.id.terminal_a11y_compose, R.string.terminal_a11y_compose)
        if (document.lastOutput() != null) {
            add(R.id.terminal_a11y_last_output, R.string.terminal_a11y_last_output)
            add(R.id.terminal_a11y_copy_output, R.string.terminal_a11y_copy_output)
        }
    }

    private fun freeze() {
        review.begin(live.snapshot, top = liveTop)
    }

    private fun resetDocument() {
        rowIds.clear()
        idRows.clear()
        focused = INVALID
        hovered = INVALID
        documentSnapshot = document.snapshot
        wasReviewing = review.isReviewing
        lastReviewDocument = review.document
        event(HOST, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    internal fun focusRow(row: Int, offset: Int? = null): Boolean {
        if (row !in document.lines.indices) return false
        freeze()
        val target = id(row)
        if (focused != target) {
            clearFocusNode()
            selectionStart = (offset ?: 0).coerceIn(0, document.lines[row].text.length)
            selectionEnd = selectionStart
            focused = target
            review.focus(row)
            if (lastTop != firstVisibleRow) scrolled()
            event(target, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        } else {
            review.focus(row)
            if (offset != null) {
                selectionStart = offset.coerceIn(0, document.lines[row].text.length)
                selectionEnd = selectionStart
                selectionEvent()
            }
        }
        if (offset != null) revealOffset(row, offset)
        invalidate()
        return true
    }

    private fun revealOffset(row: Int, offset: Int) {
        val cell = document.text(row).column(offset).coerceAtMost(document.snapshot.cols - 1)
        val visual = visualColumn(row - firstVisibleRow, cell)
        val delta = when {
            visual < column -> visual - column
            visual >= column + visibleColumns -> visual - column - visibleColumns + 1
            else -> 0
        }
        if (delta != 0 && review.scroll(columns = delta)) scrolled()
    }

    private fun clearFocusNode() {
        if (focused != INVALID) event(focused, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
        focused = INVALID
        invalidate()
    }

    private fun screenBounds(local: Rect): Rect {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return Rect(local).apply { offset(location[0], location[1]) }
    }

    private fun event(id: Int, type: Int, populate: AccessibilityEvent.() -> Unit = {}) {
        if (!manager.isEnabled) return
        val event = AccessibilityEvent.obtain(type)
        event.packageName = context.packageName
        event.className = if (id == HOST) "android.widget.ScrollView" else "android.widget.TextView"
        event.isEnabled = true
        event.setSource(this, id)
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) event.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        event.populate()
        parent?.requestSendAccessibilityEvent(this, event)
    }

    private fun scrolled() {
        lastTop = firstVisibleRow
        lastColumn = column
        event(HOST, AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            fromIndex = firstVisibleRow
            toIndex = minOf(firstVisibleRow + visibleRows, document.lines.size) - 1
            itemCount = document.lines.size
            scrollY = (firstVisibleRow * charHeight).toInt()
            maxScrollY = ((document.lines.size - visibleRows).coerceAtLeast(0) * charHeight).toInt()
            scrollX = (column * charWidth).toInt()
            maxScrollX = ((document.snapshot.cols - visibleColumns).coerceAtLeast(0) * charWidth).toInt()
        }
        invalidate()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (!manager.isTouchExplorationEnabled) return super.dispatchHoverEvent(event)
        val row = firstVisibleRow + (event.y / charHeight).toInt()
        val target = if (event.actionMasked != MotionEvent.ACTION_HOVER_EXIT && event.x >= 0 && event.x < width && event.y >= 0 && event.y < height && row in document.lines.indices) id(row) else INVALID
        if (target != hovered) {
            if (target != INVALID) this.event(target, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            if (hovered != INVALID) this.event(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
            hovered = target
        }
        return target != INVALID || event.actionMasked == MotionEvent.ACTION_HOVER_EXIT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = if (review.isReviewing || manager.isTouchExplorationEnabled) gestures.onTouchEvent(event) else false

    override fun performClick(): Boolean = super.performClick()

    private fun traverse(row: Int, action: Int, arguments: Bundle?): Boolean {
        val text = document.lines[row].text
        val granularity = arguments?.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT) ?: return false
        val forward = action == AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
        focusRow(row)
        val iterator = when (granularity) {
            AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER -> BreakIterator.getCharacterInstance()
            AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD -> BreakIterator.getWordInstance()
            AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_LINE, AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PARAGRAPH -> null
            else -> return false
        }
        iterator?.setText(text)
        val previous = selectionEnd
        val next = if (forward) iterator?.following(previous) ?: text.length else iterator?.preceding(previous) ?: 0
        if (next == BreakIterator.DONE || next == previous) return false
        selectionEnd = next
        if (!arguments.getBoolean(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN)) selectionStart = next
        selectionEvent()
        revealOffset(row, next)
        event(focused, AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY) {
            this.text.add(text)
            fromIndex = minOf(previous, next)
            toIndex = maxOf(previous, next)
            this.action = action
            movementGranularity = granularity
        }
        return true
    }

    private fun selectionEvent() = event(focused, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
        val row = idRows[focused] ?: return@event
        text.add(document.lines[row].text)
        fromIndex = selectionStart
        toIndex = selectionEnd
        itemCount = document.lines[row].text.length
    }

    private fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.terminal_a11y_output), text))
    }

    fun reviewKey(event: KeyEvent): Boolean {
        if (!review.isReviewing) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val row = idRows[focused] ?: review.focusedRow ?: firstVisibleRow
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> focusRow((row - 1).coerceAtLeast(0))

            KeyEvent.KEYCODE_DPAD_DOWN -> focusRow((row + 1).coerceAtMost(document.lines.lastIndex))

            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                review.scroll(rows = visibleRows * if (event.keyCode == KeyEvent.KEYCODE_PAGE_UP) -1 else 1)
                focusRow(review.firstRow)
                scrolled()
            }

            KeyEvent.KEYCODE_MOVE_HOME -> focusRow(0)

            KeyEvent.KEYCODE_MOVE_END -> focusRow(document.lines.lastIndex)

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> traverse(
                row,
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY else AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
                Bundle().apply {
                    putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, if (event.isCtrlPressed) AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD else AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER)
                    putBoolean(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, event.isShiftPressed)
                },
            )

            KeyEvent.KEYCODE_ESCAPE -> review.returnToInput()

            KeyEvent.KEYCODE_C -> if (event.isCtrlPressed) provider.performAction(focused, AccessibilityNodeInfoCompat.ACTION_COPY, null)

            else -> return false
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = reviewKey(event) || super.onKeyDown(keyCode, event)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val row = idRows[focused] ?: return
        if (row in firstVisibleRow until firstVisibleRow + visibleRows) canvas.drawRect(0f, (row - firstVisibleRow) * charHeight, width.toFloat(), (row - firstVisibleRow + 1) * charHeight, focusPaint)
        if (selectionStart != selectionEnd && row in firstVisibleRow until firstVisibleRow + visibleRows) {
            val text = document.text(row)
            var lastCell = -1
            for (offset in minOf(selectionStart, selectionEnd) until maxOf(selectionStart, selectionEnd)) {
                val cell = text.column(offset)
                if (cell == lastCell) continue
                lastCell = cell
                val x = (visualColumn(row - firstVisibleRow, cell) - column) * charWidth
                canvas.drawRect(x, (row - firstVisibleRow) * charHeight, x + text.line.cells.width(cell).coerceAtLeast(1) * charWidth, (row - firstVisibleRow + 1) * charHeight, focusPaint)
            }
        }
    }

    companion object {
        private const val HOST = AccessibilityNodeProviderCompat.HOST_VIEW_ID
        private const val INVALID = Int.MIN_VALUE
        private const val CHARACTER_LOCATION = "android.view.accessibility.extra.DATA_TEXT_CHARACTER_LOCATION_KEY"
        private const val GRANULARITIES = AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER or AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD or AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_LINE or AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PARAGRAPH
    }
}
