/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalScreenStateUrlTest {
    @Test
    fun singleLineUrlDoesNotIncludeFollowingProse() {
        val state = screenState(80, "see https://example.com/path somemoretext here")

        assertEquals("https://example.com/path", state.getHyperlinkUrlAt(0, 10, autoDetectUrls = true))
        assertNull(state.getHyperlinkUrlAt(0, 31, autoDetectUrls = true))
    }

    @Test
    fun singleLineUrlTrimsTrailingPunctuation() {
        val state = screenState(80, "see https://example.com/path. next")

        assertEquals("https://example.com/path", state.getHyperlinkUrlAt(0, 10, autoDetectUrls = true))
        assertNull(state.getHyperlinkUrlAt(0, 28, autoDetectUrls = true))
    }

    @Test
    fun wrappedUrlAcrossTwoRowsReturnsFullUrlFromEitherRow() {
        val row0 = "https://example.com/very/long/path/xxxx"
        val row1 = "yyyy/zzzz"
        val state = screenState(39, row0, row1)

        assertEquals(
            "https://example.com/very/long/path/xxxxyyyy/zzzz",
            state.getHyperlinkUrlAt(0, 10, autoDetectUrls = true),
        )
        assertEquals(
            "https://example.com/very/long/path/xxxxyyyy/zzzz",
            state.getHyperlinkUrlAt(1, 2, autoDetectUrls = true),
        )
    }

    @Test
    fun wrappedUrlAcrossThreeRowsReturnsFullUrl() {
        val state = screenState(
            20,
            "https://example.com/",
            "a/b/c/d/e/f/g/h/i/j/",
            "k/l/m/",
        )

        assertEquals(
            "https://example.com/a/b/c/d/e/f/g/h/i/j/k/l/m/",
            state.getHyperlinkUrlAt(1, 5, autoDetectUrls = true),
        )
    }

    @Test
    fun osc8TakesPrecedenceOverPlainTextUrl() {
        val text = "Click https://example.com here"
        val line = lineOf(
            row = 0,
            text = text,
            cols = 80,
            semanticSegments = listOf(
                SemanticSegment(
                    startCol = 6,
                    endCol = 25,
                    semanticType = SemanticType.HYPERLINK,
                    metadata = "https://osc8.example",
                ),
            ),
        )
        val state = screenState(80, listOf(line))

        assertEquals("https://osc8.example", state.getHyperlinkUrlAt(0, 10, autoDetectUrls = true))
    }

    @Test
    fun indentedProseAfterUrlIsNotContinuation() {
        val state = screenState(
            80,
            "see https://example.com/path",
            "  i think this is prose",
        )

        assertEquals("https://example.com/path", state.getHyperlinkUrlAt(0, 10, autoDetectUrls = true))
        assertNull(state.getHyperlinkUrlAt(1, 5, autoDetectUrls = true))
    }

    @Test
    fun queryAndFragmentContinuationRowsAreJoined() {
        val queryState = screenState(
            80,
            "See https://search.example.com/results",
            "        ?q=terminal&limit=50",
        )
        assertEquals(
            "https://search.example.com/results?q=terminal&limit=50",
            queryState.getHyperlinkUrlAt(0, 10, autoDetectUrls = true),
        )

        val fragmentState = screenState(
            80,
            "See https://github.com/connectbot/termlib/issues/",
            "        106#issuecomment-4298664962",
        )
        assertEquals(
            "https://github.com/connectbot/termlib/issues/106#issuecomment-4298664962",
            fragmentState.getHyperlinkUrlAt(1, 12, autoDetectUrls = true),
        )
    }

    @Test
    fun blankRowBreaksContinuation() {
        val state = screenState(
            40,
            "https://example.com/something/that/is/lo",
            "",
            "ng/should/not/be/joined",
        )

        assertEquals(
            "https://example.com/something/that/is/lo",
            state.getHyperlinkUrlAt(0, 10, autoDetectUrls = true),
        )
        assertNull(state.getHyperlinkUrlAt(2, 2, autoDetectUrls = true))
    }

    @Test
    fun autoDetectionCanBeDisabledWithoutDisablingOsc8() {
        val line = lineOf(
            row = 0,
            text = "plain https://example.com osc8",
            cols = 80,
            semanticSegments = listOf(
                SemanticSegment(26, 30, SemanticType.HYPERLINK, "https://osc8.example"),
            ),
        )
        val state = screenState(80, listOf(line))

        assertNull(state.getHyperlinkUrlAt(0, 8, autoDetectUrls = false))
        assertEquals("https://osc8.example", state.getHyperlinkUrlAt(0, 27, autoDetectUrls = false))
    }

    private fun screenState(cols: Int, vararg lineTexts: String): TerminalScreenState = screenState(cols, lineTexts.mapIndexed { index, text -> lineOf(index, text, cols) })

    private fun screenState(cols: Int, lines: List<TerminalLine>): TerminalScreenState {
        val snapshot = TerminalSnapshot(
            lines = lines,
            scrollback = emptyList(),
            cursorRow = 0,
            cursorCol = 0,
            cursorVisible = true,
            cursorBlink = true,
            cursorShape = CursorShape.BLOCK,
            terminalTitle = "",
            rows = lines.size,
            cols = cols,
            timestamp = 0L,
            sequenceNumber = 1L,
        )
        return TerminalScreenState(snapshot)
    }

    private fun lineOf(
        row: Int,
        text: String,
        cols: Int,
        semanticSegments: List<SemanticSegment> = emptyList(),
    ): TerminalLine = TerminalLine(
        row = row,
        cells = cells(text, cols),
        semanticSegments = semanticSegments,
        softWrapped = text.length >= cols,
    )

    private fun cells(text: String, cols: Int): List<TerminalLine.Cell> = text.padEnd(cols).take(cols).map { char ->
        TerminalLine.Cell(char = char, fgColor = Color.White, bgColor = Color.Black)
    }

    @Test
    fun multipleUrlsOnAnchorLineWrapped() {
        val state = screenState(
            58,
            "Here is https://url1.com and https://url2.com/very/long/pa",
            "th/wrapped",
        )
        assertEquals("https://url1.com", state.getHyperlinkUrlAt(0, 10, autoDetectUrls = true))
        assertEquals("https://url2.com/very/long/path/wrapped", state.getHyperlinkUrlAt(0, 31, autoDetectUrls = true))
        assertEquals("https://url2.com/very/long/path/wrapped", state.getHyperlinkUrlAt(1, 2, autoDetectUrls = true))
    }

    @Test
    fun unrelatedIndentedTextIsNotContinuation() {
        val state = screenState(
            80,
            "Check out https://example.com/foo",
            "   /usr/bin/local",
        )
        assertEquals("https://example.com/foo", state.getHyperlinkUrlAt(0, 15, autoDetectUrls = true))
        assertNull(state.getHyperlinkUrlAt(1, 5, autoDetectUrls = true))
    }

    @Test
    fun trailingPunctuationDoesNotForceContinuation() {
        val state = screenState(
            24,
            "https://example.com/sub.",
            "dir/file",
        )
        assertEquals("https://example.com/sub", state.getHyperlinkUrlAt(0, 5, autoDetectUrls = true))
        assertNull(state.getHyperlinkUrlAt(1, 2, autoDetectUrls = true))
    }

    @Test
    fun urlWrappedOnSafePrefixCharacter() {
        val state = screenState(
            24,
            "https://example.com/path",
            "-continued",
        )
        assertEquals("https://example.com/path-continued", state.getHyperlinkUrlAt(0, 5, autoDetectUrls = true))
        assertEquals("https://example.com/path-continued", state.getHyperlinkUrlAt(1, 2, autoDetectUrls = true))
    }

    @Test
    fun urlWithDoubleClosingParenthesesTrimsOnlyUnmatched() {
        val state1 = screenState(80, "https://example.com/wiki/Foo_(bar)")
        assertEquals("https://example.com/wiki/Foo_(bar)", state1.getHyperlinkUrlAt(0, 0, autoDetectUrls = true))

        val state2 = screenState(80, "https://example.com/wiki/Foo_(bar))")
        assertEquals("https://example.com/wiki/Foo_(bar)", state2.getHyperlinkUrlAt(0, 0, autoDetectUrls = true))

        val state3 = screenState(80, "https://example.com/wiki/Foo_((bar))")
        assertEquals("https://example.com/wiki/Foo_((bar))", state3.getHyperlinkUrlAt(0, 0, autoDetectUrls = true))

        val state4 = screenState(80, "(https://example.com/wiki/Foo_(bar))")
        assertEquals("https://example.com/wiki/Foo_(bar)", state4.getHyperlinkUrlAt(0, 1, autoDetectUrls = true))
    }

    @Test
    fun bareDomainUrlIsDetectedInUI() {
        val state = screenState(80, "Check out connectbot.org/help for info")
        assertEquals("connectbot.org/help", state.getHyperlinkUrlAt(0, 10, autoDetectUrls = true))
    }

    /**
     * The bare-domain branch had no trailing boundary, so it
     * matched a *prefix* of an ordinary word (`nginx.conf` → `nginx.co`,
     * `Thread.run(` → `java.lang.Thread.ru`), and its TLD list contained
     * labels that are far commoner as file extensions than as domains
     * (`Makefile.in`, `main.cc`). Those cells were underlined and a single
     * tap launched a browser.
     */
    @Test
    fun filenamesAndDottedIdentifiersAreNotDetectedAsUrls() {
        val notUrls = listOf(
            "vi /etc/nginx/nginx.conf", // .co inside .conf
            "vi /etc/php/8.2/php.ini", // .in inside .ini
            "cp app.config web.config /srv", // .co inside .config
            "ls Makefile.in config.h.in", // .in as a real file extension
            "grep -rn foo src/main.cc", // .cc as a real file extension
            "ping db.internal", // .in inside .internal
            "  at java.lang.Thread.run(Thread.java:840)", // .ru inside .run
            "import scipy.io.wavfile", // .io mid-identifier
            "sh.haven.app.agent.McpTools", // .app mid-identifier
        )
        for (text in notUrls) {
            val state = screenState(80, text)
            for (col in text.indices) {
                assertNull(
                    "\"$text\" col $col should not be a link",
                    state.getHyperlinkUrlAt(0, col, autoDetectUrls = true),
                )
            }
        }
    }

    @Test
    fun realBareDomainsStillDetectedAfterFilenameGuard() {
        assertEquals(
            "google.com",
            screenState(80, "PING google.com (142.250.187.238) 56 bytes").getHyperlinkUrlAt(0, 6, autoDetectUrls = true),
        )
        assertEquals(
            "example.co.uk",
            screenState(80, "visit example.co.uk.").getHyperlinkUrlAt(0, 7, autoDetectUrls = true),
        )
        assertEquals(
            "example.com:8080/x",
            screenState(80, "at example.com:8080/x ok").getHyperlinkUrlAt(0, 4, autoDetectUrls = true),
        )
    }

    @Test
    fun ipPortUrlIsDetectedInUI() {
        val state = screenState(80, "Connecting to 192.168.1.1:8080/path/to/resource...")
        assertEquals("192.168.1.1:8080/path/to/resource", state.getHyperlinkUrlAt(0, 14, autoDetectUrls = true))
    }

    @Test
    fun urlAfterCombiningCharacterUsesCellColumn() {
        val lineCells = listOf(
            TerminalLine.Cell(
                char = 'e',
                combiningChars = listOf('\u0301'),
                fgColor = Color.White,
                bgColor = Color.Black,
            ),
        ) + cells(" https://example.com", 79)
        val state = screenState(80, listOf(TerminalLine(row = 0, cells = lineCells)))

        assertEquals("https://example.com", state.getHyperlinkUrlAt(0, 2, autoDetectUrls = true))
    }

    @Test
    fun maxContinuationRowsLimitBreaksScanning() {
        // MAX_URL_CONTINUATION_ROWS is 6. Let's create a URL spanning 7 rows.
        val state = screenState(
            20,
            "https://example.com/", // row 0
            "a/", // row 1
            "b/", // row 2
            "c/", // row 3
            "d/", // row 4
            "e/", // row 5
            "f/", // row 6
        )
        // Row 6 (f/) should not be joined because it exceeds MAX_URL_CONTINUATION_ROWS limit (row - anchorRow = 6)
        assertEquals(
            "https://example.com/a/b/c/d/e/",
            state.getHyperlinkUrlAt(0, 5, autoDetectUrls = true),
        )
    }
}
