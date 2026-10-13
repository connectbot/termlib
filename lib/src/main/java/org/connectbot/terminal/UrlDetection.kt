/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.connectbot.terminal

private val TRAILING_DETECTED_URL_PUNCTUATION = setOf('.', ',', ';', ':', '!')

/** True if the character commonly appears in URLs. */
internal fun Char.isUrlSafe(): Boolean = isLetterOrDigit() || this in "/:@!$&'()*+,;=-._~%?#[]"

internal fun Char.isUrlPrefixDecoration(): Boolean = this in "|│├└┌┬┼`>•●⎿\""

/**
 * Regexes intentionally match URL-ish spans broadly; trim punctuation that is
 * usually prose around a URL rather than part of it.
 */
internal fun String.trimDetectedUrl(): String {
    var end = length
    while (end > 0) {
        val ch = this[end - 1]
        val shouldTrim = ch in TRAILING_DETECTED_URL_PUNCTUATION ||
            (ch == ')' && countOpenLessThanClose(this, end, '(', ')')) ||
            (ch == ']' && countOpenLessThanClose(this, end, '[', ']'))
        if (!shouldTrim) break
        end--
    }
    return substring(0, end)
}

private fun countOpenLessThanClose(s: String, end: Int, openChar: Char, closeChar: Char): Boolean {
    var openCount = 0
    var closeCount = 0
    for (i in 0 until end) {
        if (s[i] == openChar) {
            openCount++
        } else if (s[i] == closeChar) {
            closeCount++
        }
    }
    return openCount < closeCount
}

/** One occurrence, with half-open cell ranges on each participating grid row. */
internal data class TerminalLinkRange(val row: Int, val start: Int, val end: Int)

internal data class TerminalLinkOccurrence(
    val url: String,
    val source: TerminalUrlSource,
    val ranges: List<TerminalLinkRange>,
)

/** Shared by hit testing, URL extraction and accessibility. Repeated URLs stay distinct. */
internal fun resolveTerminalLinks(lines: List<TerminalLine>, autoDetect: Boolean): List<TerminalLinkOccurrence> {
    val links = mutableListOf<TerminalLinkOccurrence>()
    val occupied = mutableMapOf<Int, MutableList<IntRange>>()
    lines.forEachIndexed { row, line ->
        line.getSegmentsOfType(SemanticType.HYPERLINK).forEach { segment ->
            val start = segment.startCol.coerceAtLeast(0)
            val end = segment.endCol.coerceAtMost(line.cells.size)
            val url = segment.metadata
            if (!url.isNullOrEmpty() && start < end) {
                links.add(TerminalLinkOccurrence(url, TerminalUrlSource.Osc8, listOf(TerminalLinkRange(row, start, end))))
            }
        }
        if (autoDetect) {
            for ((start, end, fallback) in line.autoDetectedUrls) {
                if (occupied[row]?.any { start in it } == true) continue
                val spans = buildWrappedUrlSpans(lines, row, start).ifEmpty { mapOf(row to (start until end)) }
                for ((index, span) in spans) occupied.getOrPut(index) { mutableListOf() }.add(span)
                if (spansOverlapOsc8(lines, spans)) continue
                val url = readWrappedUrl(lines, spans) ?: fallback
                var remaining = url.length
                val ranges = spans.mapNotNull { (index, span) ->
                    val length = minOf(remaining, span.last - span.first + 1)
                    remaining -= length
                    if (length > 0) TerminalLinkRange(index, span.first, span.first + length) else null
                }
                if (ranges.isNotEmpty()) links.add(TerminalLinkOccurrence(url, TerminalUrlSource.AutoDetected, ranges))
            }
        }
    }
    return links.sortedWith(compareBy({ it.ranges.first().row }, { it.ranges.first().start }, { it.source != TerminalUrlSource.Osc8 }))
}

private fun buildWrappedUrlSpans(lines: List<TerminalLine>, anchorRow: Int, anchorCol: Int): Map<Int, IntRange> {
    val spans = linkedMapOf<Int, IntRange>()
    var row = anchorRow
    var startCol = anchorCol

    while (row < lines.size && (row - anchorRow) < 6) {
        val text = lines[row].columnText
        if (startCol !in text.indices || !text[startCol].isUrlSafe()) break

        var endCol = startCol
        while (endCol < text.length && text[endCol].isUrlSafe()) {
            endCol++
        }

        val nextStart = continuationStart(lines, row, endCol)
        if (nextStart != null) {
            spans[row] = startCol until endCol
            row++
            startCol = nextStart
        } else {
            val trimmed = text.substring(startCol, endCol).trimDetectedUrl()
            if (trimmed.isNotEmpty()) {
                spans[row] = startCol until (startCol + trimmed.length)
            }
            break
        }
    }

    return spans
}

private fun continuationStart(lines: List<TerminalLine>, previousRow: Int, previousEndCol: Int): Int? {
    if (previousRow + 1 >= lines.size) return null

    val previousLine = lines[previousRow]
    val previousText = previousLine.columnText
    val previousTrimmedEnd = previousText.indexOfLast { it != '\u0000' && !it.isWhitespace() } + 1
    if (previousTrimmedEnd <= 0 || previousEndCol < previousTrimmedEnd) return null

    val rowFilled = previousEndCol >= previousLine.cells.size || previousLine.softWrapped
    val nextText = lines[previousRow + 1].columnText
    val start = firstUrlSafeAfterPrefix(nextText) ?: return null

    var end = start
    while (end < nextText.length && nextText[end].isUrlSafe()) {
        end++
    }
    val run = nextText.substring(start, end)
    if (run.trimDetectedUrl().isEmpty()) return null

    val prevEndsWithDelimiter = previousEndCol > 0 && previousText[previousEndCol - 1] in "/?&=#"
    val nextStartsWithQueryOrFragment = run.isNotEmpty() && run[0] in "?&#"
    val previousRun = previousText.substring(0, previousEndCol)
    val previousWouldTrim = previousRun.trimDetectedUrl().length < previousRun.length
    val rowFilledContinuation = rowFilled &&
        (!previousWouldTrim || prevEndsWithDelimiter || nextStartsWithQueryOrFragment)
    val continues = rowFilledContinuation || prevEndsWithDelimiter || nextStartsWithQueryOrFragment

    return if (continues) start else null
}

private fun firstUrlSafeAfterPrefix(text: String): Int? {
    for (index in text.indices) {
        val ch = text[index]
        if (ch == '\u0000' || ch.isWhitespace()) continue
        if (ch.isUrlPrefixDecoration()) continue
        return if (ch.isUrlSafe()) index else null
    }
    return null
}

private fun readWrappedUrl(lines: List<TerminalLine>, spans: Map<Int, IntRange>): String? {
    if (spans.isEmpty()) return null
    val joined = buildString {
        for ((spanRow, span) in spans.toSortedMap()) {
            val text = lines[spanRow].columnText
            val end = (span.last + 1).coerceAtMost(text.length)
            if (span.first < end) append(text.substring(span.first, end))
        }
    }
    return TerminalLine.URL_REGEX.findAll(joined).firstOrNull()?.value?.trimDetectedUrl()?.takeIf { it.isNotEmpty() }
}

private fun spansOverlapOsc8(lines: List<TerminalLine>, spans: Map<Int, IntRange>): Boolean = spans.any { (row, span) ->
    val endCol = span.last + 1
    lines.getOrNull(row)?.getSegmentsOfType(SemanticType.HYPERLINK)?.any {
        it.startCol < endCol && span.first < it.endCol
    } == true
}
