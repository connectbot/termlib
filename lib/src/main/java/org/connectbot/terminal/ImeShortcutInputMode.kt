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

/** How a full IME editor should request terminal shortcut characters. */
enum class ImeShortcutInputMode {
    /** Leave the active IME editor unchanged. */
    DISABLED,

    /** Temporarily expose a non-rich editor so supporting IMEs generate raw key events. */
    TYPE_NULL,

    /** Keep the rich editor but ask the IME to provide Roman alphabet characters. */
    FORCE_ASCII,
}
