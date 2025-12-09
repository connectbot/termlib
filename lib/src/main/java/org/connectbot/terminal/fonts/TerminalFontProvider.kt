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

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.provider.FontRequest
import androidx.core.provider.FontsContractCompat
import org.connectbot.terminal.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides downloadable programming fonts via Google Fonts API.
 *
 * This provider manages font loading, caching, and fallback behavior for terminal fonts.
 * Fonts are downloaded on demand and cached for subsequent use.
 *
 * ## Usage
 *
 * ```kotlin
 * // Create provider instance
 * val fontProvider = TerminalFontProvider(context)
 *
 * // Check if Google Fonts provider is available
 * if (fontProvider.isProviderAvailable()) {
 *     // Load font asynchronously
 *     fontProvider.loadFont(TerminalFont.JETBRAINS_MONO) { typeface ->
 *         // Use the loaded typeface (or fallback if loading failed)
 *     }
 * }
 *
 * // Or get cached font (returns fallback if not yet loaded)
 * val typeface = fontProvider.getTypeface(TerminalFont.FIRA_CODE)
 * ```
 *
 * @param context Android context for accessing resources and font provider
 */
class TerminalFontProvider(private val context: Context) {

    private val fontCache = ConcurrentHashMap<TerminalFont, Typeface>()
    private val loadingFonts = ConcurrentHashMap<TerminalFont, Boolean>()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Load a font asynchronously.
     *
     * If the font is already cached, the callback is invoked immediately with the cached typeface.
     * If loading fails, the callback receives [Typeface.MONOSPACE] as a fallback.
     *
     * @param font The terminal font to load
     * @param callback Callback invoked when the font is ready (or fallback on error)
     */
    fun loadFont(font: TerminalFont, callback: (Typeface) -> Unit) {
        // Return cached font if available
        fontCache[font]?.let { cached ->
            callback(cached)
            return
        }

        // Prevent duplicate loading requests
        if (loadingFonts.putIfAbsent(font, true) == true) {
            // Already loading, wait for it
            waitForFont(font, callback)
            return
        }

        val request = FontRequest(
            PROVIDER_AUTHORITY,
            PROVIDER_PACKAGE,
            font.googleFontName,
            R.array.com_google_android_gms_fonts_certs
        )

        val fontCallback = object : FontsContractCompat.FontRequestCallback() {
            override fun onTypefaceRetrieved(typeface: Typeface) {
                Log.d(TAG, "Font loaded successfully: ${font.displayName}")
                fontCache[font] = typeface
                loadingFonts.remove(font)
                callback(typeface)
            }

            override fun onTypefaceRequestFailed(reason: Int) {
                Log.w(TAG, "Font loading failed for ${font.displayName}: reason=$reason")
                loadingFonts.remove(font)
                callback(Typeface.MONOSPACE)
            }
        }

        FontsContractCompat.requestFont(context, request, fontCallback, handler)
    }

    /**
     * Get a font synchronously, returning a fallback if not yet loaded.
     *
     * This method returns immediately. If the font is cached, it returns the cached typeface.
     * Otherwise, it initiates loading and returns the fallback typeface.
     *
     * @param font The terminal font to get
     * @param fallback The fallback typeface if font is not cached (default: [Typeface.MONOSPACE])
     * @return The cached typeface or fallback
     */
    fun getTypeface(font: TerminalFont, fallback: Typeface = Typeface.MONOSPACE): Typeface {
        return fontCache[font] ?: run {
            // Start loading in background if not already loading
            if (loadingFonts.putIfAbsent(font, true) == null) {
                loadFont(font) { /* Cache will be updated */ }
            }
            fallback
        }
    }

    /**
     * Get a cached font if available, without triggering a load.
     *
     * @param font The terminal font to check
     * @return The cached typeface, or null if not loaded
     */
    fun getCachedTypeface(font: TerminalFont): Typeface? = fontCache[font]

    /**
     * Check if a font is currently being loaded.
     *
     * @param font The terminal font to check
     * @return true if the font is currently loading
     */
    fun isLoading(font: TerminalFont): Boolean = loadingFonts[font] == true

    /**
     * Check if the Google Fonts provider is available on this device.
     *
     * The provider requires Google Play Services. On devices without GMS
     * (e.g., some Huawei devices, custom ROMs), this will return false.
     *
     * @return true if downloadable fonts are available
     */
    fun isProviderAvailable(): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(PROVIDER_PACKAGE, 0)
            packageInfo != null
        } catch (e: Exception) {
            Log.d(TAG, "Google Fonts provider not available: ${e.message}")
            false
        }
    }

    /**
     * Preload multiple fonts in the background.
     *
     * Use this to warm up the font cache during app initialization.
     *
     * @param fonts The fonts to preload
     * @param onComplete Optional callback when all fonts are loaded
     */
    fun preloadFonts(fonts: List<TerminalFont>, onComplete: (() -> Unit)? = null) {
        if (fonts.isEmpty()) {
            onComplete?.invoke()
            return
        }

        var remaining = fonts.size
        val lock = Any()

        fonts.forEach { font ->
            loadFont(font) {
                synchronized(lock) {
                    remaining--
                    if (remaining == 0) {
                        onComplete?.invoke()
                    }
                }
            }
        }
    }

    /**
     * Clear the font cache.
     *
     * Cached fonts will need to be reloaded on next access.
     */
    fun clearCache() {
        fontCache.clear()
    }

    private fun waitForFont(font: TerminalFont, callback: (Typeface) -> Unit) {
        handler.postDelayed({
            fontCache[font]?.let { cached ->
                callback(cached)
            } ?: if (loadingFonts[font] == true) {
                // Still loading, keep waiting
                waitForFont(font, callback)
            } else {
                // Loading finished but no cache = failed
                callback(Typeface.MONOSPACE)
            }
        }, POLL_INTERVAL_MS)
    }

    companion object {
        private const val TAG = "TerminalFontProvider"
        private const val PROVIDER_AUTHORITY = "com.google.android.gms.fonts"
        private const val PROVIDER_PACKAGE = "com.google.android.gms"
        private const val POLL_INTERVAL_MS = 100L

        /**
         * Get a list of all available terminal fonts.
         */
        val availableFonts: List<TerminalFont> = TerminalFont.entries
    }
}
