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
package org.connectbot.terminal

/**
 * Controls how the right-alt key (AltGr) is interpreted by the terminal keyboard handler.
 *
 * A future `AUTO` value is reserved for when the Android system exposes the distinction
 * between AltGr and Meta natively, at which point the correct behavior can be inferred
 * automatically from the active keyboard layout.
 */
sealed class RightAltMode {
    /**
     * Right-alt is passed to [android.view.KeyCharacterMap] as a character-level modifier,
     * allowing international keyboard layouts (e.g. Swiss German `{` via AltGr+APOSTROPHE)
     * to produce the correct characters. This is the default.
     */
    data object CharacterModifier : RightAltMode()

    /**
     * Right-alt is treated as a terminal Meta modifier (escape prefix), identical to
     * left-alt. Use this for US keyboard users who want right-alt to behave like left-alt.
     */
    data object Meta : RightAltMode()
}
