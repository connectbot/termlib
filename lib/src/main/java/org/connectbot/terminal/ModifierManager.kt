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
 * Manages modifier key state for terminal keyboard input. This can be used to combine some
 * external state about the Ctrl, Alt, and Shift keys. For instance, combining a keyboard with an
 * on-screen button.
 *
 * @see TerminalEmulatorFactory.create
 */
interface ModifierManager {
    /**
     * Check if Ctrl modifier is active (transient or locked).
     */
    fun isCtrlActive(): Boolean

    /**
     * Check if Alt modifier is active (transient or locked).
     */
    fun isAltActive(): Boolean

    /**
     * Check if Shift modifier is active (transient or locked).
     */
    fun isShiftActive(): Boolean

    /**
     * Clear transient modifiers after a key press.
     *
     * This should be called by KeyboardHandler after each key is dispatched
     * to the terminal. Transient modifiers are one-shot and clear automatically,
     * while locked modifiers persist.
     */
    fun clearTransients()
}
