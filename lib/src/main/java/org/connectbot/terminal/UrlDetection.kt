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
