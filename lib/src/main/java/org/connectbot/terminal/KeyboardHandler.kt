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

import android.util.Log
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * Handles keyboard input conversion for terminal emulation.
 *
 * Converts Android/Compose keyboard events to terminal escape sequences
 * and control characters that can be sent to the terminal via Terminal.dispatchKey()
 * or Terminal.dispatchCharacter().
 */
class KeyboardHandler(private val terminalNative: TerminalNative) {

    /**
     * Process a Compose KeyEvent and send to terminal.
     * Returns true if the event was handled.
     */
    fun onKeyEvent(event: ComposeKeyEvent): Boolean {
        Log.d("KeyboardHandler", "onKeyEvent: $event")
        if (event.type != KeyEventType.KeyDown) {
            return false
        }

        val key = event.key
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val shift = event.isShiftPressed

        // Build modifier mask for libvterm
        val modifiers = buildModifierMask(ctrl, alt, shift)

        // Check if this is a special key that libvterm handles
        val vtermKey = mapToVTermKey(key)
        if (vtermKey != null) {
            Log.d("KeyboardHandler", "onKeyEvent: vtermKey: $vtermKey")
            return terminalNative.dispatchKey(modifiers, vtermKey)
        }

        // Check for control character shortcuts
        if (ctrl) {
            val controlChar = getControlCharacter(key)
            if (controlChar != null) {
                Log.d("KeyboardHandler", "onKeyEvent: controlChar: $controlChar")
                return terminalNative.dispatchCharacter(modifiers, controlChar.code)
            }
        }

        // Handle regular printable characters
        val char = getCharacterFromKey(key, shift)
        if (char != null) {
            Log.d("KeyboardHandler", "onKeyEvent: printable char: '$char' (${char.code})")
            return terminalNative.dispatchCharacter(modifiers, char.code)
        }

        Log.d("KeyboardHandler", "onKeyEvent: not handled")
        return false
    }

    /**
     * Process a character input (from IME or hardware keyboard).
     * This is called for printable characters.
     */
    fun onCharacterInput(char: Char, ctrl: Boolean = false, alt: Boolean = false): Boolean {
        val modifiers = buildModifierMask(ctrl, alt, false)

        // For control characters (Ctrl+letter), convert to control code
        if (ctrl && char.isLetter()) {
            val controlCode = char.uppercaseChar().code - 'A'.code + 1
            return terminalNative.dispatchCharacter(modifiers, controlCode)
        }

        return terminalNative.dispatchCharacter(modifiers, char.code)
    }

    /**
     * Build VTerm modifier mask.
     * Bit 0: Shift
     * Bit 1: Alt
     * Bit 2: Ctrl
     */
    private fun buildModifierMask(ctrl: Boolean, alt: Boolean, shift: Boolean): Int {
        var mask = 0
        if (shift) mask = mask or 1
        if (alt) mask = mask or 2
        if (ctrl) mask = mask or 4
        return mask
    }

    /**
     * Convert a Compose Key to its character representation.
     * Returns null if not a printable character.
     */
    private fun getCharacterFromKey(key: Key, shift: Boolean): Char? {
        return when (key) {
            // Letters
            Key.A -> if (shift) 'A' else 'a'
            Key.B -> if (shift) 'B' else 'b'
            Key.C -> if (shift) 'C' else 'c'
            Key.D -> if (shift) 'D' else 'd'
            Key.E -> if (shift) 'E' else 'e'
            Key.F -> if (shift) 'F' else 'f'
            Key.G -> if (shift) 'G' else 'g'
            Key.H -> if (shift) 'H' else 'h'
            Key.I -> if (shift) 'I' else 'i'
            Key.J -> if (shift) 'J' else 'j'
            Key.K -> if (shift) 'K' else 'k'
            Key.L -> if (shift) 'L' else 'l'
            Key.M -> if (shift) 'M' else 'm'
            Key.N -> if (shift) 'N' else 'n'
            Key.O -> if (shift) 'O' else 'o'
            Key.P -> if (shift) 'P' else 'p'
            Key.Q -> if (shift) 'Q' else 'q'
            Key.R -> if (shift) 'R' else 'r'
            Key.S -> if (shift) 'S' else 's'
            Key.T -> if (shift) 'T' else 't'
            Key.U -> if (shift) 'U' else 'u'
            Key.V -> if (shift) 'V' else 'v'
            Key.W -> if (shift) 'W' else 'w'
            Key.X -> if (shift) 'X' else 'x'
            Key.Y -> if (shift) 'Y' else 'y'
            Key.Z -> if (shift) 'Z' else 'z'

            // Numbers (top row)
            Key.Zero -> if (shift) ')' else '0'
            Key.One -> if (shift) '!' else '1'
            Key.Two -> if (shift) '@' else '2'
            Key.Three -> if (shift) '#' else '3'
            Key.Four -> if (shift) '$' else '4'
            Key.Five -> if (shift) '%' else '5'
            Key.Six -> if (shift) '^' else '6'
            Key.Seven -> if (shift) '&' else '7'
            Key.Eight -> if (shift) '*' else '8'
            Key.Nine -> if (shift) '(' else '9'

            // Symbols
            Key.Spacebar -> ' '
            Key.Minus -> if (shift) '_' else '-'
            Key.Equals -> if (shift) '+' else '='
            Key.LeftBracket -> if (shift) '{' else '['
            Key.RightBracket -> if (shift) '}' else ']'
            Key.Backslash -> if (shift) '|' else '\\'
            Key.Semicolon -> if (shift) ':' else ';'
            Key.Apostrophe -> if (shift) '"' else '\''
            Key.Grave -> if (shift) '~' else '`'
            Key.Comma -> if (shift) '<' else ','
            Key.Period -> if (shift) '>' else '.'
            Key.Slash -> if (shift) '?' else '/'

            else -> null
        }
    }

    /**
     * Map Compose Key to VTerm key code.
     * Returns null if not a special key.
     */
    private fun mapToVTermKey(key: Key): Int? {
        return when (key) {
            // Function keys
            Key.F1 -> VTermKey.FUNCTION_1
            Key.F2 -> VTermKey.FUNCTION_2
            Key.F3 -> VTermKey.FUNCTION_3
            Key.F4 -> VTermKey.FUNCTION_4
            Key.F5 -> VTermKey.FUNCTION_5
            Key.F6 -> VTermKey.FUNCTION_6
            Key.F7 -> VTermKey.FUNCTION_7
            Key.F8 -> VTermKey.FUNCTION_8
            Key.F9 -> VTermKey.FUNCTION_9
            Key.F10 -> VTermKey.FUNCTION_10
            Key.F11 -> VTermKey.FUNCTION_11
            Key.F12 -> VTermKey.FUNCTION_12

            // Arrow keys
            Key.DirectionUp -> VTermKey.UP
            Key.DirectionDown -> VTermKey.DOWN
            Key.DirectionLeft -> VTermKey.LEFT
            Key.DirectionRight -> VTermKey.RIGHT

            // Editing keys
            Key.Insert -> VTermKey.INS
            Key.Delete -> VTermKey.DEL
            Key.Home -> VTermKey.HOME
            Key.MoveEnd -> VTermKey.END
            Key.PageUp -> VTermKey.PAGEUP
            Key.PageDown -> VTermKey.PAGEDOWN

            // Special keys
            Key.Enter -> VTermKey.ENTER
            Key.Tab -> VTermKey.TAB
            Key.Backspace -> VTermKey.BACKSPACE
            Key.Escape -> VTermKey.ESCAPE

            // KP (Keypad) keys
            Key.NumPad0 -> VTermKey.KP_0
            Key.NumPad1 -> VTermKey.KP_1
            Key.NumPad2 -> VTermKey.KP_2
            Key.NumPad3 -> VTermKey.KP_3
            Key.NumPad4 -> VTermKey.KP_4
            Key.NumPad5 -> VTermKey.KP_5
            Key.NumPad6 -> VTermKey.KP_6
            Key.NumPad7 -> VTermKey.KP_7
            Key.NumPad8 -> VTermKey.KP_8
            Key.NumPad9 -> VTermKey.KP_9
            Key.NumPadMultiply -> VTermKey.KP_MULT
            Key.NumPadAdd -> VTermKey.KP_PLUS
            Key.NumPadComma -> VTermKey.KP_COMMA
            Key.NumPadSubtract -> VTermKey.KP_MINUS
            Key.NumPadDot -> VTermKey.KP_PERIOD
            Key.NumPadDivide -> VTermKey.KP_DIVIDE
            Key.NumPadEnter -> VTermKey.KP_ENTER
            Key.NumPadEquals -> VTermKey.KP_EQUAL

            else -> null
        }
    }

    /**
     * Get control character for Ctrl+key combinations.
     */
    private fun getControlCharacter(key: Key): Char? {
        return when (key) {
            // Ctrl+A through Ctrl+Z map to ASCII 1-26
            Key.A -> '\u0001'
            Key.B -> '\u0002'
            Key.C -> '\u0003'  // ETX (often used as SIGINT)
            Key.D -> '\u0004'  // EOT (end of transmission)
            Key.E -> '\u0005'
            Key.F -> '\u0006'
            Key.G -> '\u0007'  // BEL (bell)
            Key.H -> '\u0008'  // BS (backspace)
            Key.I -> '\u0009'  // HT (tab)
            Key.J -> '\u000A'  // LF (line feed)
            Key.K -> '\u000B'  // VT (vertical tab)
            Key.L -> '\u000C'  // FF (form feed)
            Key.M -> '\u000D'  // CR (carriage return)
            Key.N -> '\u000E'
            Key.O -> '\u000F'
            Key.P -> '\u0010'
            Key.Q -> '\u0011'
            Key.R -> '\u0012'
            Key.S -> '\u0013'
            Key.T -> '\u0014'
            Key.U -> '\u0015'
            Key.V -> '\u0016'
            Key.W -> '\u0017'
            Key.X -> '\u0018'
            Key.Y -> '\u0019'
            Key.Z -> '\u001A'  // SUB

            // Special control characters
            Key.LeftBracket -> '\u001B'   // ESC (Ctrl+[)
            Key.Backslash -> '\u001C'     // FS (Ctrl+\)
            Key.RightBracket -> '\u001D'  // GS (Ctrl+])
            Key.Six -> '\u001E'           // RS (Ctrl+6 or Ctrl+^)
            Key.Minus -> '\u001F'         // US (Ctrl+- or Ctrl+_)
            Key.Spacebar -> '\u0000'      // NUL (Ctrl+Space)

            else -> null
        }
    }
}

/**
 * VTerm key codes from libvterm.
 * These correspond to VTermKey enum in vterm.h
 */
object VTermKey {
    const val NONE = 0
    const val ENTER = 1
    const val TAB = 2
    const val BACKSPACE = 3
    const val ESCAPE = 4

    const val UP = 5
    const val DOWN = 6
    const val LEFT = 7
    const val RIGHT = 8

    const val INS = 9
    const val DEL = 10
    const val HOME = 11
    const val END = 12
    const val PAGEUP = 13
    const val PAGEDOWN = 14

    const val FUNCTION_0 = 15
    const val FUNCTION_1 = 16
    const val FUNCTION_2 = 17
    const val FUNCTION_3 = 18
    const val FUNCTION_4 = 19
    const val FUNCTION_5 = 20
    const val FUNCTION_6 = 21
    const val FUNCTION_7 = 22
    const val FUNCTION_8 = 23
    const val FUNCTION_9 = 24
    const val FUNCTION_10 = 25
    const val FUNCTION_11 = 26
    const val FUNCTION_12 = 27

    // Keypad keys
    const val KP_0 = 28
    const val KP_1 = 29
    const val KP_2 = 30
    const val KP_3 = 31
    const val KP_4 = 32
    const val KP_5 = 33
    const val KP_6 = 34
    const val KP_7 = 35
    const val KP_8 = 36
    const val KP_9 = 37
    const val KP_MULT = 38
    const val KP_PLUS = 39
    const val KP_COMMA = 40
    const val KP_MINUS = 41
    const val KP_PERIOD = 42
    const val KP_DIVIDE = 43
    const val KP_ENTER = 44
    const val KP_EQUAL = 45
}
