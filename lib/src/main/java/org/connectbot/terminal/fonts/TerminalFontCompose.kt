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
package org.connectbot.terminal.fonts

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * State representing the loading status of a downloadable font.
 */
sealed class FontLoadingState {
    /**
     * Font is currently being downloaded.
     */
    data object Loading : FontLoadingState()

    /**
     * Font has been successfully loaded.
     *
     * @property typeface The loaded typeface ready for use
     */
    data class Loaded(val typeface: Typeface) : FontLoadingState()

    /**
     * Font loading failed, using fallback.
     *
     * @property fallback The fallback typeface being used
     * @property reason Optional error description
     */
    data class Error(val fallback: Typeface, val reason: String? = null) : FontLoadingState()
}

/**
 * Remember and load a terminal font with full loading state management.
 *
 * This composable provides detailed loading state information, useful when you want
 * to show loading indicators or handle errors explicitly.
 *
 * ## Usage
 *
 * ```kotlin
 * @Composable
 * fun MyTerminal() {
 *     val fontState = rememberTerminalFontState(TerminalFont.JETBRAINS_MONO)
 *
 *     when (fontState) {
 *         is FontLoadingState.Loading -> {
 *             CircularProgressIndicator()
 *         }
 *         is FontLoadingState.Loaded -> {
 *             Terminal(typeface = fontState.typeface, ...)
 *         }
 *         is FontLoadingState.Error -> {
 *             // Show terminal with fallback font
 *             Terminal(typeface = fontState.fallback, ...)
 *         }
 *     }
 * }
 * ```
 *
 * @param font The terminal font to load
 * @param fallback The fallback typeface if loading fails (default: [Typeface.MONOSPACE])
 * @return Current loading state
 */
@Composable
fun rememberTerminalFontState(
    font: TerminalFont,
    fallback: Typeface = Typeface.MONOSPACE
): FontLoadingState {
    val context = LocalContext.current
    val fontProvider = remember { TerminalFontProvider(context) }

    var state by remember(font) {
        // Check if already cached
        val cached = fontProvider.getCachedTypeface(font)
        mutableStateOf(
            if (cached != null) {
                FontLoadingState.Loaded(cached)
            } else {
                FontLoadingState.Loading
            }
        )
    }

    DisposableEffect(font, fontProvider) {
        if (state is FontLoadingState.Loading) {
            fontProvider.loadFont(font) { typeface ->
                state = if (typeface == Typeface.MONOSPACE && fontProvider.getCachedTypeface(font) == null) {
                    // Loading failed, using fallback
                    FontLoadingState.Error(fallback, "Font download failed")
                } else {
                    FontLoadingState.Loaded(typeface)
                }
            }
        }
        onDispose { }
    }

    return state
}

/**
 * Remember and load a terminal font, automatically using fallback during loading.
 *
 * This is a simpler API that always returns a usable typeface. During loading,
 * the fallback is used. Once loaded, the actual font is returned.
 *
 * ## Usage
 *
 * ```kotlin
 * @Composable
 * fun MyTerminal() {
 *     val typeface = rememberTerminalTypeface(TerminalFont.FIRA_CODE)
 *
 *     Terminal(
 *         terminalEmulator = emulator,
 *         typeface = typeface,
 *         ...
 *     )
 * }
 * ```
 *
 * @param font The terminal font to load
 * @param fallback The fallback typeface during loading or on error (default: [Typeface.MONOSPACE])
 * @return The loaded typeface or fallback
 */
@Composable
fun rememberTerminalTypeface(
    font: TerminalFont,
    fallback: Typeface = Typeface.MONOSPACE
): Typeface {
    val state = rememberTerminalFontState(font, fallback)
    return when (state) {
        is FontLoadingState.Loading -> fallback
        is FontLoadingState.Loaded -> state.typeface
        is FontLoadingState.Error -> state.fallback
    }
}

/**
 * Remember a [TerminalFontProvider] instance scoped to the composable.
 *
 * Use this when you need direct access to the font provider for more
 * advanced operations like preloading multiple fonts.
 *
 * ## Usage
 *
 * ```kotlin
 * @Composable
 * fun MyApp() {
 *     val fontProvider = rememberTerminalFontProvider()
 *
 *     LaunchedEffect(Unit) {
 *         fontProvider.preloadFonts(listOf(
 *             TerminalFont.JETBRAINS_MONO,
 *             TerminalFont.FIRA_CODE
 *         ))
 *     }
 *
 *     // ... rest of app
 * }
 * ```
 *
 * @return A remembered [TerminalFontProvider] instance
 */
@Composable
fun rememberTerminalFontProvider(): TerminalFontProvider {
    val context = LocalContext.current
    return remember { TerminalFontProvider(context) }
}

/**
 * Remember whether the Google Fonts provider is available on this device.
 *
 * @return State containing true if downloadable fonts are available
 */
@Composable
fun rememberFontProviderAvailable(): State<Boolean> {
    val context = LocalContext.current
    return remember {
        val provider = TerminalFontProvider(context)
        mutableStateOf(provider.isProviderAvailable())
    }
}
