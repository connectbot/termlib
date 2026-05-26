/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalUrlExtractionTest {
    @Test
    fun plainUrlOnVisiblePrimaryScreenIsReturned() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 80)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("see https://example.com/path")
        drain(impl)

        assertEquals(
            listOf(
                TerminalUrl(
                    url = "https://example.com/path",
                    source = TerminalUrlSource.AutoDetected,
                ),
            ),
            emulator.getUrls(),
        )
    }

    @Test
    fun wrappedPlainUrlIsReturnedAsSingleUrl() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 39)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("https://example.com/very/long/path/xxxxyyyy/zzzz")
        drain(impl)

        assertEquals(
            listOf("https://example.com/very/long/path/xxxxyyyy/zzzz"),
            emulator.getUrls().map { it.url },
        )
    }

    @Test
    fun primaryScrollbackUrlIsReturnedForCurrentViewOutsideAltScreen() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 3, initialCols = 80)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("https://old.example.com\r\n")
        emulator.send("line 1\r\n")
        emulator.send("line 2\r\n")
        emulator.send("line 3\r\n")
        drain(impl)

        val urls = emulator.getUrls()
        assertTrue(urls.any { it.url == "https://old.example.com" })
        assertEquals("scrollback should be first in scan order", "https://old.example.com", urls.first().url)
    }

    @Test
    fun osc8HyperlinkMetadataIsReturned() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 80)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("Click ${osc8Start("https://target.example")}here${osc8End()}")
        drain(impl)

        assertEquals(
            listOf(
                TerminalUrl(
                    url = "https://target.example",
                    source = TerminalUrlSource.Osc8,
                ),
            ),
            emulator.getUrls(),
        )
    }

    @Test
    fun osc8OverridesAutoDetectedUrlOnSameRange() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 80)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("${osc8Start("https://osc8.example")}https://example.com${osc8End()}")
        drain(impl)

        assertEquals(
            listOf(
                TerminalUrl(
                    url = "https://osc8.example",
                    source = TerminalUrlSource.Osc8,
                ),
            ),
            emulator.getUrls(),
        )
    }

    @Test
    fun duplicateUrlsAreReturnedOnceInFirstSeenOrder() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 80)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("https://first.com\r\n")
        emulator.send("https://second.com\r\n")
        emulator.send("again https://first.com")
        drain(impl)

        assertEquals(
            listOf("https://first.com", "https://second.com"),
            emulator.getUrls().map { it.url },
        )
    }

    @Test
    fun currentViewWhileAltScreenActiveExcludesPrimaryScrollback() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 3, initialCols = 80)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("https://primary.com\r\n")
        emulator.send("line 1\r\n")
        emulator.send("line 2\r\n")
        emulator.send("line 3\r\n")
        drain(impl)

        emulator.send("\u001B[?1049h")
        emulator.send("\u001B[2J\u001B[H")
        emulator.send("https://alt.com")
        drain(impl)

        val urls = emulator.getUrls()
        assertEquals(listOf("https://alt.com"), urls.map { it.url })
        assertFalse(urls.any { it.url == "https://primary.com" })
    }

    @Test
    fun currentViewAfterLeavingAltScreenReturnsPrimaryUrlsAgain() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 3, initialCols = 80)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("https://primary.com\r\n")
        emulator.send("line 1\r\n")
        emulator.send("line 2\r\n")
        emulator.send("line 3\r\n")
        drain(impl)

        emulator.send("\u001B[?1049h")
        emulator.send("\u001B[2J\u001B[H")
        emulator.send("https://alt.com")
        drain(impl)

        emulator.send("\u001B[?1049l")
        drain(impl)

        val urls = emulator.getUrls()
        assertTrue(urls.any { it.url == "https://primary.com" })
        assertFalse(urls.any { it.url == "https://alt.com" })
    }

    private fun drain(impl: TerminalEmulatorImpl) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        impl.processPendingUpdates()
    }

    private fun TerminalEmulator.send(s: String) {
        writeInput(s.toByteArray())
    }

    private fun osc8Start(url: String): String = "\u001B]8;;$url\u001B\\"

    private fun osc8End(): String = "\u001B]8;;\u001B\\"
}
