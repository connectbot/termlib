/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/** Keep platform types introduced after minSdk isolated from the portable review model. */
@RequiresApi(37)
internal class TerminalExtendedSelection(
    private val view: View,
    private val document: () -> TerminalReviewDocument,
    private val id: (Int) -> Int,
    private val row: (Int) -> Int?,
) {
    fun populate(node: AccessibilityNodeInfoCompat) {
        val selection = document().selection
        fun position(point: ReviewPosition) = AccessibilityNodeInfo.SelectionPosition(view, id(point.row), point.offset)
        node.unwrap().selection = AccessibilityNodeInfo.Selection(position(selection.anchor), position(selection.active))
        node.unwrap().addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_EXTENDED_SELECTION)
    }

    fun perform(arguments: Bundle?): Boolean {
        val model = document().selection
        val key = AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_PARCELABLE
        if (arguments == null || !arguments.containsKey(key)) {
            model.clear()
            return true
        }
        val selection = arguments.getParcelable(key, AccessibilityNodeInfo.Selection::class.java) ?: return false
        fun resolve(position: AccessibilityNodeInfo.SelectionPosition): ReviewPosition? {
            if (position.view !== view) return null
            val index = row(position.virtualDescendantId) ?: return null
            return ReviewPosition(index, position.offset).takeIf(model::valid)
        }
        val start = resolve(selection.start) ?: return false
        val end = resolve(selection.end) ?: return false
        model.set(start, end)
        return true
    }
}
