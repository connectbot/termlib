/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.text.TextUtils
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Identity distinguishes repeated feedback with identical text. */
internal class TerminalAccessibilityMessage(val text: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerminalAccessibilityControls(
    review: TerminalReviewSession,
    view: TerminalAccessibilityView?,
    composeMode: ComposeMode,
    keyboardEnabled: Boolean,
    searchRequested: Boolean,
    onSearchRequested: (Boolean) -> Unit,
    onCompose: () -> Unit,
    onSend: (Boolean) -> Unit,
    message: TerminalAccessibilityMessage?,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    feedbackEnabled: Boolean = true,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searchText by remember { mutableStateOf("") }
    val matches = remember(review.document, query) { review.document?.search(query).orEmpty() }
    var matchIndex by remember(review.document, query) { mutableStateOf(0) }
    fun showMatch(index: Int) {
        if (matches.isEmpty()) {
            onMessage(resources.getString(R.string.terminal_a11y_search_none))
            return
        }
        matchIndex = index.mod(matches.size)
        val match = matches[matchIndex]
        view?.focusRow(match.first, match.second)
        onMessage(resources.getString(R.string.terminal_a11y_search_results, matchIndex + 1, matches.size))
    }
    LaunchedEffect(review.document, query, searchRequested) {
        if (review.isReviewing && query.isNotEmpty() && !searchRequested) {
            // Let the search dialog detach before transferring accessibility focus.
            delay(100)
            showMatch(0)
        }
    }
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        FlowRow {
            // Preserve the focused semantics node when returning to live output.
            // Removing the Resume button makes TalkBack recover focus outside the terminal.
            TextButton(onClick = {
                if (review.isReviewing) {
                    review.resumeLiveOutput()
                } else {
                    review.startReview()
                    view?.focusRow(review.focusedRow ?: 0)
                }
            }) { Text(stringResource(if (review.isReviewing) R.string.terminal_a11y_resume else R.string.terminal_a11y_review)) }
            if (review.isReviewing) {
                TextButton(onClick = { review.refreshReview() }) { Text(stringResource(R.string.terminal_a11y_refresh)) }
            }
            if (keyboardEnabled) {
                TextButton(onClick = { review.returnToInput() }) { Text(stringResource(R.string.terminal_a11y_input)) }
                TextButton(onClick = onCompose) { Text(stringResource(R.string.terminal_a11y_compose)) }
            }
            TextButton(onClick = {
                review.startReview()
                onSearchRequested(true)
            }) { Text(stringResource(R.string.terminal_a11y_search)) }
            if (review.isReviewing) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text(stringResource(R.string.terminal_a11y_review_actions)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val actions = listOf(
                            R.id.terminal_a11y_previous_line to R.string.terminal_a11y_previous_line,
                            R.id.terminal_a11y_next_line to R.string.terminal_a11y_next_line,
                            R.id.terminal_a11y_start_line to R.string.terminal_a11y_start_line,
                            R.id.terminal_a11y_end_line to R.string.terminal_a11y_end_line,
                            R.id.terminal_a11y_previous_link to R.string.terminal_a11y_previous_link,
                            R.id.terminal_a11y_next_link to R.string.terminal_a11y_next_link,
                            R.id.terminal_a11y_open_link to R.string.terminal_a11y_open_link,
                            R.id.terminal_a11y_start_output to R.string.terminal_a11y_start_output,
                            R.id.terminal_a11y_end_output to R.string.terminal_a11y_end_output,
                            R.id.terminal_a11y_mark to if (view?.isMarking == true) R.string.terminal_a11y_stop_mark else R.string.terminal_a11y_mark,
                            R.id.terminal_a11y_switch_endpoint to R.string.terminal_a11y_switch_endpoint,
                            R.id.terminal_a11y_select_all to R.string.terminal_a11y_select_all,
                            androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_COPY to R.string.terminal_a11y_copy_selection,
                            R.id.terminal_a11y_clear_selection to R.string.terminal_a11y_clear_selection,
                            R.id.terminal_a11y_formatting to R.string.terminal_a11y_formatting,
                        )
                        actions.forEach { (action, label) ->
                            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                menuOpen = false
                                scope.launch {
                                    // Detach the popup before transferring accessibility focus.
                                    delay(100)
                                    if (review.isReviewing) view?.reviewAction(action)
                                }
                            })
                        }
                    }
                }
            }
            if (matches.isNotEmpty()) {
                TextButton(onClick = { showMatch(matchIndex - 1) }) { Text(stringResource(R.string.terminal_a11y_previous_match)) }
                TextButton(onClick = { showMatch(matchIndex + 1) }) { Text(stringResource(R.string.terminal_a11y_next_match)) }
            }
        }
        val color = MaterialTheme.colorScheme.onSurface.toArgb()
        val status = message?.text ?: stringResource(if (review.isReviewing) R.string.terminal_a11y_paused else R.string.terminal_a11y_live)
        AndroidView(
            factory = {
                TextView(it).apply {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    setLines(2)
                    ellipsize = TextUtils.TruncateAt.END
                }
            },
            update = {
                it.accessibilityLiveRegion = if (feedbackEnabled) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
                it.setTextColor(color)
                if (it.tag !== message || it.text.toString() != status) {
                    it.tag = message
                    it.text = status
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (searchRequested) {
        val focus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { onSearchRequested(false) },
            title = { Text(stringResource(R.string.terminal_a11y_search)) },
            text = {
                OutlinedTextField(searchText, { searchText = it }, label = { Text(stringResource(R.string.terminal_a11y_search_query)) }, modifier = Modifier.focusRequester(focus))
                LaunchedEffect(Unit) { focus.requestFocus() }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSearchRequested(false)
                    query = searchText
                }) { Text(stringResource(R.string.terminal_a11y_search)) }
            },
            dismissButton = { TextButton(onClick = { onSearchRequested(false) }) { Text(stringResource(R.string.terminal_a11y_cancel)) } },
        )
    }
    if (keyboardEnabled && composeMode.isActive) {
        val focus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { composeMode.deactivate() },
            title = { Text(stringResource(R.string.terminal_a11y_compose)) },
            text = {
                OutlinedTextField(composeMode.buffer, composeMode::replaceText, modifier = Modifier.focusRequester(focus), label = { Text(stringResource(R.string.terminal_a11y_command)) })
                LaunchedEffect(Unit) { focus.requestFocus() }
            },
            confirmButton = {
                FlowRow {
                    TextButton(onClick = { onSend(false) }, enabled = composeMode.buffer.isNotEmpty()) { Text(stringResource(R.string.terminal_a11y_send)) }
                    TextButton(onClick = { onSend(true) }) { Text(stringResource(R.string.terminal_a11y_send_enter)) }
                }
            },
            dismissButton = { TextButton(onClick = { composeMode.deactivate() }) { Text(stringResource(R.string.terminal_a11y_cancel)) } },
        )
    }
}
