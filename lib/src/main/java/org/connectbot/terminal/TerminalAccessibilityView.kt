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
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
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
    private var touched: ReviewPosition? = null
    private var linkGeneration = Any()
    private val linkedRows = mutableMapOf<Int, CharSequence>()
    private val touchSettled = Runnable { event(HOST, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) }
    var autoDetectUrls = false
        set(value) {
            if (field == value) return
            field = value
            clearLinkSpans()
            event(HOST, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    private var lastTop = 0
    private var lastColumn = 0

    // Restoring a retained document must not focus it before the host is ready.
    private var wasReviewing = review.isReviewing
    private var lastReviewDocument: TerminalReviewDocument? = review.document
    private var initialFocusConsumed = false
    private val initialFocus = Runnable {
        if (requestInitialFocus && !initialFocusConsumed && isAttachedToWindow &&
            isShown && hasWindowFocus() && width > 0 && height > 0 &&
            manager.isEnabled && manager.isTouchExplorationEnabled
        ) {
            if (review.isReviewing) {
                review.focusedRow?.let { focusRow(it, preserveSelection = true) }
            } else {
                focusHost()
            }
        }
    }
    var requestInitialFocus = false
        set(value) {
            field = value
            scheduleInitialFocus()
        }

    internal fun requestReadingFocus() {
        if (!isAttachedToWindow || !isShown || !hasWindowFocus() || !manager.isEnabled) return
        // A different window may have owned focus while this view retained its
        // local target. Send a fresh focus event without touching the selection.
        clearFocusNode()
        if (review.isReviewing) {
            review.focusedRow?.let { focusRow(it, preserveSelection = true) }
        } else {
            focusHost()
        }
    }

    private fun scheduleInitialFocus() {
        removeCallbacks(initialFocus)
        if (requestInitialFocus && !initialFocusConsumed) postOnAnimation(initialFocus)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleInitialFocus()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) scheduleInitialFocus()
    }
    private val focusPaint = Paint().apply { color = 0x334caf50 }
    private var metricsReady = false
    var charWidth = 1f
    var charHeight = 1f
    var onSearch: () -> Unit = {}
    var onCompose: (() -> Unit)? = null
    var onMessage: (String) -> Unit = {}
    var onLink: (String) -> Unit = {}

    // Renderer's mapping handles bidi and shaped cell positions for horizontal reveal.
    var visualColumn: (Int, Int) -> Int = { _, column -> column }
    var logicalColumn: (Int, Int) -> Int = { _, column -> column }
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
            override fun onLongPress(e: MotionEvent) {
                // Double-tap-and-hold can delegate a held touch at accessibility
                // focus (to allow dragging), rather than send ACTION_LONG_CLICK.
                performLongClick()
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
    private val selection get() = document.selection
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
        metricsReady = true
        charWidth = width.coerceAtLeast(1f)
        charHeight = height.coerceAtLeast(1f)
        if (live.snapshot !== snapshot) live = TerminalReviewDocument(snapshot)
        liveTop = if (snapshot.alternateScreen) 0 else snapshot.scrollback.size - scrollbackPosition
        review.resize(visibleRows, visibleColumns)
        val current = document.snapshot
        val reviewReplaced = lastReviewDocument != null && lastReviewDocument !== review.document
        val enteringReview = !wasReviewing && review.isReviewing
        if (documentSnapshot !== current || reviewReplaced) {
            if (focused != HOST) clearFocusNode()
            clearTouch()
            clearLinkSpans()
            rowIds.clear()
            idRows.clear()
            documentSnapshot = current
            event(HOST, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        wasReviewing = review.isReviewing
        lastReviewDocument = review.document
        if ((enteringReview || reviewReplaced) && review.isReviewing && focused == INVALID) {
            review.focusedRow?.let { focusRow(it, preserveSelection = true) }
        }
        if (lastTop != firstVisibleRow || lastColumn != column) scrolled()
        invalidate()
        scheduleInitialFocus()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clearTouch()
        if (metricsReady) review.resize(visibleRows, visibleColumns)
        scheduleInitialFocus()
        event(HOST, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    internal fun id(row: Int): Int {
        val first = document.accessibleRows(row).first
        return rowIds.getOrPut(first) { nextId++.also { idRows[it] = first } }
    }

    internal val provider = object : AccessibilityNodeProviderCompat() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfoCompat? {
            ensureDocumentGeneration()
            if (virtualViewId == HOST) {
                return AccessibilityNodeInfoCompat.obtain(this@TerminalAccessibilityView).apply {
                    setSource(this@TerminalAccessibilityView)
                    (this@TerminalAccessibilityView.parent as? View)?.let { setParent(it) }
                    className = "android.widget.ScrollView"
                    packageName = context.packageName
                    contentDescription = context.getString(if (review.isReviewing) R.string.terminal_a11y_review else R.string.terminal_a11y_output)
                    isEnabled = true
                    setRequestInitialAccessibilityFocus(requestInitialFocus && !initialFocusConsumed && !review.isReviewing)
                    isAccessibilityFocused = focused == HOST
                    addAction(if (focused == HOST) AccessibilityActionCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS else AccessibilityActionCompat.ACTION_ACCESSIBILITY_FOCUS)
                    isVisibleToUser = isShown
                    isScrollable = document.lines.size > visibleRows || document.snapshot.cols > visibleColumns
                    setBoundsInScreen(screenBounds(Rect(0, 0, width, height)))
                    setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(document.lines.size, 1, false))
                    var row = firstVisibleRow
                    while (row < minOf(firstVisibleRow + visibleRows, document.lines.size)) {
                        addChild(this@TerminalAccessibilityView, id(row))
                        row = document.accessibleRows(row).last + 1
                    }
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
                    if (Build.VERSION.SDK_INT >= 37) extendedSelection().populate(this)
                }
            }
            val row = idRows[virtualViewId] ?: return null
            val line = document.lines.getOrNull(row) ?: return null
            val range = document.accessibleRows(row)
            val count = range.last - range.first + 1
            return AccessibilityNodeInfoCompat.obtain().apply {
                setSource(this@TerminalAccessibilityView, virtualViewId)
                setParent(this@TerminalAccessibilityView)
                className = "android.widget.TextView"
                packageName = context.packageName
                text = if (count > 1) document.accessibleText(row) else linkedText(row)
                // Keep actual text and offsets intact, including empty rows.
                if (line.text.isBlank()) {
                    stateDescription = if (count == 1) context.getString(R.string.terminal_a11y_blank) else resources.getQuantityString(R.plurals.terminal_a11y_blank_lines, count, count)
                }
                isEnabled = true
                isFocusable = true
                isScreenReaderFocusable = true
                setRequestInitialAccessibilityFocus(requestInitialFocus && !initialFocusConsumed && review.focusedRow?.let { document.accessibleRows(it).first == row } == true)
                isAccessibilityFocused = focused == virtualViewId
                isVisibleToUser = isShown && range.last >= firstVisibleRow && range.first < firstVisibleRow + visibleRows
                val first = if (count == 1) row else maxOf(range.first, firstVisibleRow)
                val last = if (count == 1) row + 1 else maxOf(first, minOf(range.last + 1, firstVisibleRow + visibleRows))
                val bounds = Rect(0, floor((first - firstVisibleRow) * charHeight).toInt(), width, ceil((last - firstVisibleRow) * charHeight).toInt())
                setBoundsInParent(bounds)
                setBoundsInScreen(screenBounds(bounds))
                setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(row, count, 0, 1, line.hasPrompt()))
                addAction(if (isAccessibilityFocused) AccessibilityActionCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS else AccessibilityActionCompat.ACTION_ACCESSIBILITY_FOCUS)
                addAction(AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN)
                addAction(AccessibilityActionCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
                addAction(AccessibilityActionCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
                addAction(AccessibilityActionCompat.ACTION_SET_SELECTION)
                addAction(AccessibilityActionCompat.ACTION_COPY)
                movementGranularities = GRANULARITIES
                isTextSelectable = true
                if (Build.VERSION.SDK_INT >= 26) availableExtraData = listOf(CHARACTER_LOCATION)
                document.accessibleSelection(row)?.let { (start, end) -> setTextSelection(start, end) }
                if (row > firstVisibleRow && row < firstVisibleRow + visibleRows) setTraversalAfter(this@TerminalAccessibilityView, id(row - 1))
                actions(this)
                addAction(AccessibilityActionCompat(R.id.terminal_a11y_copy_line, context.getString(R.string.terminal_a11y_copy_line)))
                addAction(AccessibilityActionCompat(R.id.terminal_a11y_describe, context.getString(R.string.terminal_a11y_describe)))
                if (document.prompt(row, false) != null) addAction(AccessibilityActionCompat(R.id.terminal_a11y_previous_prompt, context.getString(R.string.terminal_a11y_previous_prompt)))
                if (document.prompt(row, true) != null) addAction(AccessibilityActionCompat(R.id.terminal_a11y_next_prompt, context.getString(R.string.terminal_a11y_next_prompt)))
            }
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfoCompat? {
            ensureDocumentGeneration()
            return if (focus == AccessibilityNodeInfoCompat.FOCUS_ACCESSIBILITY && focused != INVALID) createAccessibilityNodeInfo(focused) else null
        }

        override fun addExtraDataToAccessibilityNodeInfo(virtualViewId: Int, info: AccessibilityNodeInfoCompat, extraDataKey: String, arguments: Bundle?) {
            ensureDocumentGeneration()
            if (Build.VERSION.SDK_INT < 26 || extraDataKey != CHARACTER_LOCATION || arguments == null) return
            val row = idRows[virtualViewId] ?: return
            val text = document.accessibleText(row)
            val start = arguments.getInt(android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, -1)
            val length = arguments.getInt(android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, -1)
            if (start < 0 || length < 0 || start > text.length || length > text.length - start) return
            val hostBounds = screenBounds(Rect(0, 0, width, height))
            val rectangles = arrayOfNulls<RectF>(length)
            for (index in rectangles.indices) {
                val position = document.accessiblePosition(row, start + index) ?: continue
                if (position.row !in firstVisibleRow until firstVisibleRow + visibleRows) continue
                val rowText = document.text(position.row)
                // The separator between physical blank rows has no screen cell.
                if (position.offset >= rowText.text.length) continue
                val cell = rowText.column(position.offset)
                val x = (visualColumn(position.row - firstVisibleRow, cell) - column) * charWidth + hostBounds.left
                val y = (position.row - firstVisibleRow) * charHeight + hostBounds.top
                val bounds = RectF(x, y, x + rowText.line.cells.width(cell).coerceAtLeast(1) * charWidth, y + charHeight)
                if (bounds.intersect(RectF(hostBounds))) rectangles[index] = bounds
            }
            info.extras.putParcelableArray(CHARACTER_LOCATION, rectangles)
        }

        override fun findAccessibilityNodeInfosByText(text: String, virtualViewId: Int): List<AccessibilityNodeInfoCompat> {
            ensureDocumentGeneration()
            return document.search(text).map { it.first }.distinct().mapNotNull { row -> createAccessibilityNodeInfo(id(row)) }
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            ensureDocumentGeneration()
            val row = idRows[virtualViewId]
            if (virtualViewId != HOST && row == null) return false
            if (Build.VERSION.SDK_INT >= 37 && action == android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_EXTENDED_SELECTION.id) {
                if (virtualViewId != HOST) return false
                freeze()
                return extendedSelection().perform(arguments).also { if (it) showSelection() }
            }
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (virtualViewId == HOST) return focusHost()
                    if (row == null) return false
                    // TalkBack can deliver focus after newer hover coordinates have arrived.
                    val range = document.accessibleRows(row)
                    val touchRow = touched?.row?.takeIf { it in range }
                    if (hovered != INVALID && touchRow == null) return false
                    val keepTouch = touchRow != null
                    if (!keepTouch) clearTouch()
                    return focusRow(touchRow ?: row.coerceAtLeast(firstVisibleRow).coerceAtMost(range.last), preserveSelection = keepTouch || (!selection.isEmpty && !selection.marking))
                }

                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> {
                    freeze()
                    // The gesture activates the focused item, not the place where
                    // the user double-taps. Retain precise touch/reading positions.
                    val range = row?.let(document::accessibleRows)
                    val point = touched?.takeIf { range == null || it.row in range }
                        ?: selection.active.takeIf { range == null || it.row in range }
                        ?: ReviewPosition(row!!, 0)
                    toggleSelection(point)
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    event(id(selection.active.row), AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
                    return true
                }

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
                    var distance = (visibleRows - 1).coerceAtLeast(1) * if (backwards) -1 else 1
                    // A blank run can fill several pages. TalkBack's automatic scroll
                    // must reveal the next item in one gesture, not another blank page.
                    idRows[focused]?.let { focusedRow ->
                        val range = document.accessibleRows(focusedRow)
                        if (range.last > range.first) {
                            distance = if (backwards) minOf(distance, range.first - 1 - firstVisibleRow) else maxOf(distance, range.last + 2 - firstVisibleRow - visibleRows)
                        }
                    }
                    val moved = review.scroll(rows = distance)
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
                    val range = document.accessibleRows(it)
                    review.focus(it.coerceAtLeast(firstVisibleRow).coerceAtMost(range.last)).also { scrolled() }
                } ?: false

                AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY -> return row != null && traverse(row, action, arguments)

                AccessibilityNodeInfoCompat.ACTION_SET_SELECTION -> {
                    if (row == null || arguments == null) return false
                    val start = arguments.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, -1)
                    val end = arguments.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, -1)
                    val first = document.accessiblePosition(row, start) ?: return false
                    val last = document.accessiblePosition(row, end) ?: return false
                    freeze()
                    selection.set(first, last)
                    showSelection()
                    return true
                }

                AccessibilityNodeInfoCompat.ACTION_COPY -> {
                    if (selection.isEmpty) return false
                    copy(selection.copyText())
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
                    return focusHost()
                }

                R.id.terminal_a11y_input -> {
                    clearFocusNode()
                    review.returnToInput()
                    resetDocument()
                    return focusHost()
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

                R.id.terminal_a11y_previous_line, R.id.terminal_a11y_next_line -> {
                    freeze()
                    if (row != null && row != selection.active.row) selection.move(ReviewPosition(row, 0))
                    val next = selection.next(ReviewUnit.LINE, action == R.id.terminal_a11y_next_line) ?: return false
                    selection.move(next)
                    showSelection()
                    return true
                }

                R.id.terminal_a11y_start_line, R.id.terminal_a11y_end_line -> {
                    freeze()
                    val targetRow = row ?: selection.active.row
                    selection.move(ReviewPosition(targetRow, if (action == R.id.terminal_a11y_start_line) 0 else document.lines[targetRow].text.length))
                    showSelection()
                    return true
                }

                R.id.terminal_a11y_start_output, R.id.terminal_a11y_end_output -> {
                    freeze()
                    selection.move(if (action == R.id.terminal_a11y_start_output) ReviewPosition(0, 0) else selection.last)
                    showSelection()
                    return true
                }

                R.id.terminal_a11y_mark -> {
                    toggleSelection()
                    return true
                }

                R.id.terminal_a11y_switch_endpoint -> {
                    freeze()
                    selection.switchEndpoint()
                    showSelection()
                    return true
                }

                R.id.terminal_a11y_select_all -> {
                    freeze()
                    selection.selectAll()
                    showSelection()
                    return true
                }

                R.id.terminal_a11y_clear_selection -> {
                    selection.clear()
                    showSelection()
                    return true
                }

                R.id.terminal_a11y_formatting -> {
                    freeze()
                    onMessage(describeReviewFormatting(context, document))
                    return true
                }

                R.id.terminal_a11y_previous_link, R.id.terminal_a11y_next_link ->
                    return moveLink(action == R.id.terminal_a11y_next_link)

                R.id.terminal_a11y_open_link -> return openLink()
            }
            return false
        }
    }

    private fun clearLinkSpans() {
        linkedRows.clear()
        linkGeneration = Any()
    }

    internal fun linkedText(row: Int): CharSequence {
        if (Build.VERSION.SDK_INT < 26) return document.lines[row].text
        return linkedRows.getOrPut(row) {
            val generation = linkGeneration
            SpannableString(document.lines[row].text).apply {
                for (link in document.links(autoDetectUrls)) {
                    for (range in link.ranges.filter { it.cells.row == row && it.start < it.end }) {
                        // Android resolves remote span activation against the original span ID.
                        // Keep these instances stable when nodes are rebuilt or review freezes.
                        setSpan(
                            object : URLSpan(link.url) {
                                override fun onClick(widget: View) {
                                    ensureDocumentGeneration()
                                    if (generation === linkGeneration) onLink(link.url)
                                }
                            },
                            range.start,
                            range.end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
            }
        }
    }

    private fun currentLink(): TerminalReviewLink? = document.links(autoDetectUrls).firstOrNull { it.contains(touched ?: selection.active) }

    private fun openLink(): Boolean {
        val link = currentLink() ?: return false
        onLink(link.url)
        return true
    }

    private fun moveLink(forward: Boolean, extend: Boolean = selection.marking): Boolean {
        freeze()
        val point = touched ?: selection.active
        val links = document.links(autoDetectUrls)
        val current = links.indexOfFirst { it.contains(point) }
        val index = if (current >= 0) {
            current + if (forward) 1 else -1
        } else if (forward) {
            links.indexOfFirst { it.start > point }
        } else {
            links.indexOfLast { it.start < point }
        }
        val link = links.getOrNull(index) ?: return false
        selection.move(link.start, extend)
        showSelection()
        onMessage(context.getString(R.string.terminal_a11y_link_position, link.label, link.url, index + 1, links.size))
        return true
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
        add(R.id.terminal_a11y_previous_line, R.string.terminal_a11y_previous_line)
        add(R.id.terminal_a11y_next_line, R.string.terminal_a11y_next_line)
        add(R.id.terminal_a11y_start_line, R.string.terminal_a11y_start_line)
        add(R.id.terminal_a11y_end_line, R.string.terminal_a11y_end_line)
        if (document.links(autoDetectUrls).isNotEmpty()) {
            add(R.id.terminal_a11y_previous_link, R.string.terminal_a11y_previous_link)
            add(R.id.terminal_a11y_next_link, R.string.terminal_a11y_next_link)
            if (currentLink() != null) add(R.id.terminal_a11y_open_link, R.string.terminal_a11y_open_link)
        }
        add(R.id.terminal_a11y_start_output, R.string.terminal_a11y_start_output)
        add(R.id.terminal_a11y_end_output, R.string.terminal_a11y_end_output)
        add(R.id.terminal_a11y_mark, if (selection.marking) R.string.terminal_a11y_stop_mark else R.string.terminal_a11y_mark)
        node.isLongClickable = true
        node.addAction(AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, context.getString(if (selection.marking) R.string.terminal_a11y_stop_mark else R.string.terminal_a11y_mark)))
        add(R.id.terminal_a11y_select_all, R.string.terminal_a11y_select_all)
        add(R.id.terminal_a11y_formatting, R.string.terminal_a11y_formatting)
        if (!selection.isEmpty) {
            add(R.id.terminal_a11y_switch_endpoint, R.string.terminal_a11y_switch_endpoint)
            add(R.id.terminal_a11y_clear_selection, R.string.terminal_a11y_clear_selection)
            node.addAction(AccessibilityActionCompat.ACTION_COPY)
        }
        if (onCompose != null) add(R.id.terminal_a11y_compose, R.string.terminal_a11y_compose)
        if (document.lastOutput() != null) {
            add(R.id.terminal_a11y_last_output, R.string.terminal_a11y_last_output)
            add(R.id.terminal_a11y_copy_output, R.string.terminal_a11y_copy_output)
        }
    }

    private fun toggleSelection(position: ReviewPosition? = null) {
        freeze()
        if (selection.marking) {
            selection.marking = false
        } else {
            selection.clear()
            if (position != null) selection.move(position)
            selection.marking = true
        }
        showSelection()
        onMessage(context.getString(if (selection.marking) R.string.terminal_a11y_selection_started else R.string.terminal_a11y_selection_stopped))
    }

    private fun freeze() {
        review.begin(live.snapshot, top = liveTop)
        if (lastReviewDocument == null) lastReviewDocument = review.document
    }

    /** Controller calls can replace review before Compose delivers the next view update. */
    private fun ensureDocumentGeneration() {
        if (documentSnapshot !== document.snapshot || (lastReviewDocument != null && lastReviewDocument !== review.document)) {
            if (focused != HOST) clearFocusNode()
            resetDocument()
            if (review.isReviewing) focusRow(review.focusedRow ?: 0, preserveSelection = true)
        } else if (review.isReviewing) {
            lastReviewDocument = review.document
        }
    }

    private fun resetDocument() {
        clearTouch()
        clearLinkSpans()
        rowIds.clear()
        idRows.clear()
        if (focused != HOST) focused = INVALID
        hovered = INVALID
        documentSnapshot = document.snapshot
        wasReviewing = review.isReviewing
        lastReviewDocument = review.document
        event(HOST, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    internal fun focusRow(row: Int, offset: Int? = null, preserveSelection: Boolean = false): Boolean {
        if (row !in document.lines.indices) return false
        initialFocusConsumed = true
        removeCallbacks(initialFocus)
        freeze()
        if (!preserveSelection) clearTouch()
        val target = id(row)
        val changed = focused != target
        if (!preserveSelection && ((changed && selection.active.row != row) || offset != null)) {
            selection.move(ReviewPosition(row, (offset ?: 0).coerceIn(0, document.lines[row].text.length)))
        }
        if (changed) clearFocusNode()
        focused = target
        review.focus(row)
        if (lastTop != firstVisibleRow) scrolled()
        if (offset != null) revealOffset(row, offset)
        if (changed) event(target, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        if (!preserveSelection && selection.marking) {
            selectionEvent()
            event(HOST, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        invalidate()
        return true
    }

    private fun showSelection() {
        clearTouch()
        focusRow(selection.active.row, selection.active.offset, preserveSelection = true)
        selectionEvent()
        event(HOST, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        invalidate()
    }

    @androidx.annotation.RequiresApi(37)
    private fun extendedSelection() = TerminalExtendedSelection(this, { document }, ::id, { idRows[it] })

    internal val isMarking get() = selection.marking

    internal fun reviewAction(action: Int): Boolean = provider.performAction(HOST, action, null)

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

    /** Live rows freeze on focus; the persistent host is the live-mode focus target. */
    private fun focusHost(): Boolean {
        initialFocusConsumed = true
        removeCallbacks(initialFocus)
        if (focused == HOST) return false
        clearFocusNode()
        focused = HOST
        event(HOST, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        return true
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
        clearTouch()
        lastTop = firstVisibleRow
        lastColumn = column
        event(HOST, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
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

    private fun clearTouch() {
        touched = null
        hovered = INVALID
        removeCallbacks(touchSettled)
    }

    /** Invert renderer geometry before translating cells to grapheme-safe UTF-16 offsets. */
    internal fun touchPosition(x: Float, y: Float): ReviewPosition? {
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width || y >= height) return null
        val viewportRow = floor(y / charHeight).toInt()
        val row = firstVisibleRow + viewportRow
        val line = document.lines.getOrNull(row) ?: return null
        val visual = column + floor(x / charWidth).toInt()
        if (visual !in 0 until document.snapshot.cols) return null
        val cell = logicalColumn(viewportRow, visual)
        if (cell !in line.cells.indices) return null
        return ReviewPosition(row, document.text(row).graphemeOffset(cell))
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (!manager.isTouchExplorationEnabled) return super.dispatchHoverEvent(event)
        ensureDocumentGeneration()
        val point = if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) null else touchPosition(event.x, event.y)
        val target = if (point == null) {
            INVALID
        } else {
            freeze()
            if (touched != point) {
                touched = point
                if (selection.isEmpty || selection.marking) selection.move(point)
                review.focus(point.row)
                removeCallbacks(touchSettled)
                // Invalidate cached actions/selection after the finger settles, without
                // speaking a character for every move across an already focused row.
                postDelayed(touchSettled, 150)
                invalidate()
            }
            id(point.row)
        }
        if (target != hovered) {
            if (target != INVALID) this.event(target, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            if (hovered != INVALID) this.event(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
            hovered = target
        }
        return target != INVALID || event.actionMasked == MotionEvent.ACTION_HOVER_EXIT
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(initialFocus)
        clearTouch()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = if (review.isReviewing || manager.isTouchExplorationEnabled) gestures.onTouchEvent(event) else false

    override fun performClick(): Boolean = super.performClick()

    override fun performLongClick(): Boolean = provider.performAction(
        if (focused != INVALID) focused else HOST,
        AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
        null,
    )

    private fun traverse(row: Int, action: Int, arguments: Bundle?): Boolean {
        val granularity = arguments?.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT) ?: return false
        val unit = when (granularity) {
            AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER -> ReviewUnit.CHARACTER
            AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD -> ReviewUnit.WORD
            AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_LINE -> ReviewUnit.LINE
            AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PARAGRAPH -> ReviewUnit.PARAGRAPH
            else -> return false
        }
        freeze()
        if (selection.active.row !in document.accessibleRows(row)) selection.move(ReviewPosition(row, 0), selection.marking || arguments.getBoolean(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN))
        val previous = selection.active
        val forward = action == AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
        val next = selection.next(unit, forward) ?: return false
        selection.move(next, selection.marking || arguments.getBoolean(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN))
        showSelection()
        if (unit == ReviewUnit.LINE || unit == ReviewUnit.PARAGRAPH) {
            if (previous.row == next.row) onMessage(document.lines[next.row].text)
            return true
        }
        // Each event's offsets address its source row. A word spanning a soft wrap
        // is spoken as one unit through the existing feedback region instead.
        var start = minOf(previous, next)
        var end = maxOf(previous, next)
        if (start.row != end.row && !document.lines[start.row].softWrapped) {
            if (forward) start = ReviewPosition(end.row, 0) else end = ReviewPosition(start.row, document.lines[start.row].text.length)
        }
        val pieces = (start.row..end.row).mapNotNull { index ->
            val text = document.lines[index].text
            val first = if (index == start.row) start.offset else 0
            val last = if (index == end.row) end.offset else text.length
            if (first == last) null else Triple(index, first, last)
        }
        if (pieces.size > 1) {
            onMessage(pieces.joinToString("") { (index, first, last) -> document.lines[index].text.substring(first, last) })
        } else {
            pieces.firstOrNull()?.let { (index, first, last) ->
                event(id(index), AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY) {
                    text.add(document.accessibleText(index))
                    fromIndex = document.accessibleOffset(ReviewPosition(index, first))
                    toIndex = document.accessibleOffset(ReviewPosition(index, last))
                    this.action = action
                    movementGranularity = granularity
                }
            }
        }
        return true
    }

    private fun selectionEvent() {
        var row = firstVisibleRow
        while (row < minOf(firstVisibleRow + visibleRows, document.lines.size)) {
            val range = document.accessibleSelection(row)
            event(id(row), AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                val value = document.accessibleText(row)
                text.add(value)
                fromIndex = range?.first ?: -1
                toIndex = range?.second ?: -1
                itemCount = value.length
            }
            row = document.accessibleRows(row).last + 1
        }
    }

    private fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.terminal_a11y_output), text))
    }

    fun reviewKey(event: KeyEvent): Boolean {
        if (!review.isReviewing) return false
        if (event.keyCode == KeyEvent.KEYCODE_TAB) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val row = selection.active.row
        val extend = selection.marking || event.isShiftPressed
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            -> {
                if (event.isAltPressed && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                    moveLink(event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN, extend)
                    return true
                }
                val rows = when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> -1
                    KeyEvent.KEYCODE_DPAD_DOWN -> 1
                    KeyEvent.KEYCODE_PAGE_UP -> -visibleRows
                    else -> visibleRows
                }
                if (selection.vertical(rows, extend)) showSelection()
            }

            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> {
                val home = event.keyCode == KeyEvent.KEYCODE_MOVE_HOME
                val target = if (event.isCtrlPressed) {
                    if (home) ReviewPosition(0, 0) else selection.last
                } else {
                    ReviewPosition(row, if (home) 0 else document.lines[row].text.length)
                }
                selection.move(target, extend)
                showSelection()
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> traverse(
                row,
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY else AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
                Bundle().apply {
                    putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, if (event.isCtrlPressed) AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD else AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER)
                    putBoolean(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, extend)
                },
            )

            KeyEvent.KEYCODE_ENTER -> if (event.isCtrlPressed) openLink() else return false

            KeyEvent.KEYCODE_ESCAPE -> review.returnToInput()

            KeyEvent.KEYCODE_C -> if (event.isCtrlPressed) reviewAction(AccessibilityNodeInfoCompat.ACTION_COPY) else return false

            KeyEvent.KEYCODE_A -> if (event.isCtrlPressed) reviewAction(R.id.terminal_a11y_select_all) else return false

            KeyEvent.KEYCODE_M -> if (event.isCtrlPressed && event.isShiftPressed) reviewAction(R.id.terminal_a11y_mark) else return false

            KeyEvent.KEYCODE_X -> if (event.isCtrlPressed && event.isShiftPressed) reviewAction(R.id.terminal_a11y_switch_endpoint) else return false

            else -> return false
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = reviewKey(event) || super.onKeyDown(keyCode, event)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val row = idRows[focused]
        if (row != null && row in firstVisibleRow until firstVisibleRow + visibleRows) canvas.drawRect(0f, (row - firstVisibleRow) * charHeight, width.toFloat(), (row - firstVisibleRow + 1) * charHeight, focusPaint)
        if (!selection.isEmpty) {
            for (selectedRow in firstVisibleRow until minOf(firstVisibleRow + visibleRows, document.lines.size)) {
                val (start, end) = selection.intersection(selectedRow) ?: continue
                val text = document.text(selectedRow)
                var lastCell = -1
                for (offset in start until end) {
                    val cell = text.column(offset)
                    if (cell == lastCell) continue
                    lastCell = cell
                    val x = (visualColumn(selectedRow - firstVisibleRow, cell) - column) * charWidth
                    canvas.drawRect(x, (selectedRow - firstVisibleRow) * charHeight, x + text.line.cells.width(cell).coerceAtLeast(1) * charWidth, (selectedRow - firstVisibleRow + 1) * charHeight, focusPaint)
                }
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
