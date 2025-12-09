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

/**
 * Available programming fonts that can be downloaded via Google Fonts API.
 *
 * These fonts are optimized for terminal/code display with:
 * - Monospace character widths
 * - Clear distinction between similar characters (0/O, 1/l/I)
 * - Good readability at various sizes
 *
 * @property displayName Human-readable name for UI display
 * @property googleFontName The exact font name as registered in Google Fonts
 */
enum class TerminalFont(
    val displayName: String,
    val googleFontName: String
) {
    /**
     * JetBrains Mono - A typeface designed for developers.
     * Features increased height for lowercase letters, 142 code-specific ligatures,
     * and clear distinction between similar characters.
     */
    JETBRAINS_MONO("JetBrains Mono", "JetBrains Mono"),

    /**
     * Fira Code - A monospaced font with programming ligatures.
     * Based on Fira Mono with ligatures for common multi-character combinations
     * like arrows (->), comparisons (==), and more.
     */
    FIRA_CODE("Fira Code", "Fira Code"),

    /**
     * Fira Mono - Mozilla's monospace font without ligatures.
     * A clean, readable font designed for Firefox OS.
     */
    FIRA_MONO("Fira Mono", "Fira Mono"),

    /**
     * Source Code Pro - Adobe's monospace font family.
     * Designed for coding environments with excellent readability.
     */
    SOURCE_CODE_PRO("Source Code Pro", "Source Code Pro"),

    /**
     * Noto Sans Mono - Google's monospace font with wide language support.
     * Part of the Noto font family, covering many scripts and symbols.
     */
    NOTO_SANS_MONO("Noto Sans Mono", "Noto Sans Mono"),

    /**
     * Roboto Mono - The monospace variant of Roboto.
     * A modern, clean font that works well at various sizes.
     */
    ROBOTO_MONO("Roboto Mono", "Roboto Mono"),

    /**
     * Ubuntu Mono - The monospace font from the Ubuntu font family.
     * Designed for clarity on screen with a modern feel.
     */
    UBUNTU_MONO("Ubuntu Mono", "Ubuntu Mono"),

    /**
     * Inconsolata - A monospace font designed for code listings.
     * Clean and highly legible with a neutral character.
     */
    INCONSOLATA("Inconsolata", "Inconsolata"),

    /**
     * Space Mono - A monospace typeface with retro-futuristic style.
     * Designed for editorial use with distinctive character.
     */
    SPACE_MONO("Space Mono", "Space Mono"),

    /**
     * IBM Plex Mono - IBM's monospace font.
     * Part of the IBM Plex family, designed for IBM's brand guidelines
     * with excellent technical documentation readability.
     */
    IBM_PLEX_MONO("IBM Plex Mono", "IBM Plex Mono")
}
