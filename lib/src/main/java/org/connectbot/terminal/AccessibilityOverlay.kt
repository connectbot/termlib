/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

internal fun getLastCommandOutput(lines: List<TerminalLine>): String? {
    // Find the most recent COMMAND_FINISHED marker
    var finishedIndex = -1
    var finishedPromptId = -1
    for (i in lines.indices.reversed()) {
        val segments = lines[i].getSegmentsOfType(SemanticType.COMMAND_FINISHED)
        if (segments.isNotEmpty()) {
            finishedIndex = i
            finishedPromptId = segments.first().promptId
            break
        }
    }
    if (finishedIndex < 0) return null

    // Find the COMMAND_INPUT line with the same promptId
    var inputIndex = -1
    for (i in (0 until finishedIndex).reversed()) {
        val segments = lines[i].getSegmentsOfType(SemanticType.COMMAND_INPUT)
        if (segments.any { it.promptId == finishedPromptId }) {
            inputIndex = i
            break
        }
    }
    if (inputIndex < 0) return null

    // Output is between the command input line (exclusive) and the finished marker line (exclusive)
    val outputStart = inputIndex + 1
    val outputEnd = finishedIndex - 1
    if (outputStart > outputEnd) return null

    val outputText = buildString {
        for (index in outputStart..outputEnd) {
            val line = lines[index]
            append(if (line.softWrapped) line.text else line.text.trimEnd())
            if (!line.softWrapped) append('\n')
        }
    }.trimEnd()

    return outputText.ifEmpty { null }
}
