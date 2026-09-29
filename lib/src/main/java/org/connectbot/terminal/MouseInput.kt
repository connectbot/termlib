/*
 * ConnectBot Terminal
 * Copyright 2026 Termlib contributors
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
 * The level of mouse reporting the application running in the terminal has
 * asked for, mirroring libvterm's `VTERM_PROP_MOUSE` values.
 *
 * Applications request this with DECSET; the terminal must not send mouse
 * reports until they do. Full-screen programs such as vim, tmux and Claude
 * Code's alternate-screen renderer enable it so they can handle scrolling and
 * clicks themselves instead of relying on the terminal's own scrollback.
 *
 * Note that the modes are not additive in the way the escape sequences suggest:
 * whichever mode was enabled last wins. Every mode other than [NONE] reports
 * button presses, which includes the wheel.
 */
enum class MouseTracking {
    /** No reporting. The terminal should handle gestures locally. */
    NONE,

    /** DECSET 1000: button press and release only. */
    CLICK,

    /** DECSET 1002: button events, plus motion while a button is held. */
    DRAG,

    /** DECSET 1003: button events, plus all motion whether or not a button is held. */
    MOVE,

    ;

    /** Whether the application wants mouse reports at all. */
    val isEnabled: Boolean get() = this != NONE
}

/**
 * A physical mouse button, excluding the wheel. Use
 * [TerminalEmulator.scrollWheel] for wheel input.
 */
enum class MouseButton(internal val code: Int) {
    LEFT(1),
    MIDDLE(2),
    RIGHT(3),
}

/**
 * A wheel detent direction. Terminals report wheel input as presses of buttons
 * 4 through 7; there is no matching release.
 */
enum class WheelDirection(internal val code: Int) {
    UP(4),
    DOWN(5),
    LEFT(6),
    RIGHT(7),
}
