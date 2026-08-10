/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
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
 * Controls whether the cursor blinks.
 */
sealed class CursorBlinkMode {
    /**
     * Cursor blink follows the terminal program's escape sequences
     * (DECSCUSR / DEC private mode 12). This is the default.
     */
    data object Terminal : CursorBlinkMode()

    /**
     * Cursor never blinks; it is drawn solid whenever visible, regardless of
     * what the terminal program requests. Useful on e-ink displays, where a
     * blinking cursor causes continuous partial refreshes.
     */
    data object Never : CursorBlinkMode()
}
