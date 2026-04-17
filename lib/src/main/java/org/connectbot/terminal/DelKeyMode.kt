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
 * Controls what byte sequence the backspace key sends.
 */
sealed class DelKeyMode {
    /**
     * Backspace key sends DEL (0x7f). This is the default.
     * The Delete key sends ESC[3~.
     */
    data object Delete : DelKeyMode()

    /**
     * Backspace key sends ^H (0x08). Use for servers that expect the traditional backspace byte.
     * The Delete key sends DEL (0x7f) instead.
     */
    data object Backspace : DelKeyMode()
}
