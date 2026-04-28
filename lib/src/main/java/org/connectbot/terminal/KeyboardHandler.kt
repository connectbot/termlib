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

import android.view.KeyCharacterMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import java.text.Normalizer
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * Handles keyboard input conversion for terminal emulation.
 *
 * Converts Android/Compose keyboard events to terminal escape sequences
 * and control characters that can be sent to the terminal via TerminalEmulator.dispatchKey()
 * or TerminalEmulator.dispatchCharacter().
 *
 * @param terminalEmulator Terminal to send keyboard events to
 * @param modifierManager Optional modifier manager for sticky modifier support.
 *                        If provided, sticky modifiers from UI buttons will be combined
 *                        with hardware keyboard modifiers. If null, only hardware
 *                        keyboard modifiers are used.
 * @param selectionController Optional selection controller for keyboard-based selection.
 *                            When provided and selection is active, arrow keys will move
 *                            the selection instead of sending to terminal.
 * @param onInputProcessed Optional callback invoked after input is successfully processed
 *                         and sent to terminal. Use this to reset scroll position to bottom.
 */
internal class KeyboardHandler(
    private val terminalEmulator: TerminalEmulator,
    var modifierManager: ModifierManager? = null,
    var selectionController: SelectionController? = null,
    var onInputProcessed: (() -> Unit)? = null,
    /**
     * Controls how the right-alt key (AltGr) is interpreted. Defaults to
     * [RightAltMode.CharacterModifier] so that international keyboard layouts work correctly.
     * Set to [RightAltMode.Meta] for US keyboard users who want right-alt to behave like
     * left-alt (escape prefix).
     */
    var rightAltMode: RightAltMode = RightAltMode.CharacterModifier,
    /**
     * Controls whether the backspace key sends DEL (0x7f) or ^H (0x08). Defaults to
     * [DelKeyMode.Delete]. Set to [DelKeyMode.Backspace] for servers that expect ^H.
     * When [DelKeyMode.Backspace] is active, the Delete key sends DEL (0x7f) instead.
     */
    var delKeyMode: DelKeyMode = DelKeyMode.Delete,
    /**
     * Resolves a (deviceId, keyCode, metaState) triple to a raw Unicode value as returned by
     * [android.view.KeyEvent.getUnicodeChar]. Injectable for testing dead-key logic without
     * requiring a physical keyboard with dead keys.
     *
     * The `deviceId` is the keyboard device ID from the [android.view.KeyEvent] and is used to
     * load the correct [KeyCharacterMap] so that physical keyboard layouts (e.g. German) are
     * honored. Falls back to [KeyCharacterMap.VIRTUAL_KEYBOARD] if the device map is
     * unavailable.
     */
    internal var unicodeCharLookup: (deviceId: Int, keyCode: Int, metaState: Int) -> Int =
        { deviceId, keyCode, metaState ->
            try {
                KeyCharacterMap.load(deviceId)
            } catch (_: KeyCharacterMap.UnavailableException) {
                KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            }.get(keyCode, metaState)
        },
) {
    var composeMode: ComposeMode? = null
        set(value) {
            if (field != value) {
                pendingDeadChar = 0
            }
            field = value
        }

    var pendingDeadChar: Int by mutableStateOf(0)
        internal set

    /**
     * Process a Compose KeyEvent and send to terminal.
     * Returns true if the event was handled.
     */
    @Suppress("DEPRECATION")
    fun onKeyEvent(event: ComposeKeyEvent): Boolean {
        val nativeEvent = event.nativeKeyEvent
        if (nativeEvent.action == AndroidKeyEvent.ACTION_MULTIPLE &&
            nativeEvent.keyCode == AndroidKeyEvent.KEYCODE_UNKNOWN
        ) {
            val characters = nativeEvent.characters
            if (!characters.isNullOrEmpty()) {
                onTextInput(characters.toByteArray(Charsets.UTF_8))
                return true
            }
        }

        if (event.type != KeyEventType.KeyDown) {
            return false
        }

        val key = event.key
        val ctrl = event.isCtrlPressed
        val shift = event.isShiftPressed
        val modifierState = resolveEventModifierState(event)
        val alt = modifierState.alt
        val stripAltGr = modifierState.stripAltGr

        // If compose mode is active, intercept all input
        val compose = composeMode
        if (compose != null && compose.isActive) {
            when (key) {
                Key.Enter -> {
                    // Flush any IME-composed text, then dispatch a real Enter so the shell
                    // sees a newline. Compose mode stays active (sticky toggle).
                    val text = compose.commit()
                    text?.codePoints()?.forEach { codepoint ->
                        terminalEmulator.dispatchCharacter(0, codepoint)
                    }
                    val modifiers = buildModifierMask(ctrl, alt, shift)
                    terminalEmulator.dispatchKey(modifiers, VTermKey.ENTER)
                    modifierManager?.clearTransients()
                    onInputProcessed?.invoke()
                }

                Key.Escape -> {
                    // If a composition is in progress, Esc just cancels it (vim-like). With
                    // an empty buffer, Esc passes through to the shell. Compose mode stays
                    // active either way.
                    if (compose.buffer.isNotEmpty()) {
                        compose.cancel()
                    } else {
                        val modifiers = buildModifierMask(ctrl, alt, shift)
                        terminalEmulator.dispatchKey(modifiers, VTermKey.ESCAPE)
                        modifierManager?.clearTransients()
                        onInputProcessed?.invoke()
                    }
                }

                Key.Backspace -> {
                    // With a composition in progress, Backspace edits the buffer. With an
                    // empty buffer it passes through so users can still delete characters
                    // that were typed into the shell before entering compose mode.
                    if (compose.buffer.isNotEmpty()) {
                        compose.deleteLastChar()
                    } else {
                        val modifiers = buildModifierMask(ctrl, alt, shift)
                        if (delKeyMode is DelKeyMode.Backspace) {
                            terminalEmulator.dispatchCharacter(modifiers, 0x08)
                        } else {
                            terminalEmulator.dispatchKey(modifiers, VTermKey.BACKSPACE)
                        }
                        modifierManager?.clearTransients()
                        onInputProcessed?.invoke()
                    }
                }

                else -> {
                    if (!ctrl && !event.isAltPressed) {
                        val codepoint = getCodePointFromKeyEvent(event)
                        if (codepoint != null) {
                            dispatchCodePoint(codepoint) { cp ->
                                if (Character.isBmpCodePoint(cp)) {
                                    compose.appendChar(cp.toChar())
                                } else {
                                    compose.appendChar(Character.highSurrogate(cp))
                                    compose.appendChar(Character.lowSurrogate(cp))
                                }
                            }
                        }
                    }
                }
            }
            return true
        }

        // If selection is active, intercept arrow keys for selection movement
        val selection = selectionController
        if (selection != null && selection.isSelectionActive) {
            when (key) {
                Key.DirectionUp -> {
                    selection.moveSelectionUp()
                    return true
                }

                Key.DirectionDown -> {
                    selection.moveSelectionDown()
                    return true
                }

                Key.DirectionLeft -> {
                    selection.moveSelectionLeft()
                    return true
                }

                Key.DirectionRight -> {
                    selection.moveSelectionRight()
                    return true
                }

                Key.Enter -> {
                    // Finish selection (stop extending, but keep selected for copying)
                    selection.finishSelection()
                    return true
                }

                Key.Escape -> {
                    // Cancel selection
                    selection.clearSelection()
                    return true
                }

                // Any other key clears selection and goes to terminal
                else -> {
                    selection.clearSelection()
                    // Fall through to normal key handling
                }
            }
        }

        // When DelKeyMode.Backspace is active, swap the byte sequences:
        // Backspace → ^H (0x08), Delete → DEL (0x7f).
        if (delKeyMode is DelKeyMode.Backspace) {
            when (key) {
                Key.Backspace -> {
                    val modifiers = buildModifierMask(ctrl, alt, shift)
                    pendingDeadChar = 0
                    terminalEmulator.dispatchCharacter(modifiers, 0x08)
                    modifierManager?.clearTransients()
                    onInputProcessed?.invoke()
                    return true
                }

                Key.Delete -> {
                    val modifiers = buildModifierMask(ctrl, alt, shift)
                    pendingDeadChar = 0
                    terminalEmulator.dispatchKey(modifiers, VTermKey.BACKSPACE)
                    modifierManager?.clearTransients()
                    onInputProcessed?.invoke()
                    return true
                }

                else -> {}
            }
        }

        // Check if this is a special key that libvterm handles
        val vtermKey = mapToVTermKey(key)
        if (vtermKey != null) {
            val modifiers = buildModifierMask(ctrl, alt, shift)
            pendingDeadChar = 0
            terminalEmulator.dispatchKey(modifiers, vtermKey)
            modifierManager?.clearTransients()
            onInputProcessed?.invoke()
            return true
        }

        // Handle regular printable characters
        val stickyShift = modifierManager?.isShiftActive() == true
        val codepoint = getCodePointFromKeyEvent(event, extraShift = stickyShift, stripAlt = stripAltGr)
        if (codepoint != null) {
            val modifiers = buildModifierMask(ctrl, alt, shift)
            dispatchCodePoint(codepoint) { cp -> terminalEmulator.dispatchCharacter(modifiers, cp) }
            modifierManager?.clearTransients()
            onInputProcessed?.invoke()
            return true
        }

        return false
    }

    /**
     * Process a character input (from IME or hardware keyboard).
     * This is called for printable characters.
     */
    fun onCharacterInput(char: Char, ctrl: Boolean = false, alt: Boolean = false): Boolean {
        val compose = composeMode
        if (compose != null && compose.isActive) {
            compose.appendChar(char)
            return true
        }

        val modifiers = buildModifierMask(ctrl, alt, false)

        dispatchCodepointOrEnter(modifiers, char.code)
        modifierManager?.clearTransients()
        onInputProcessed?.invoke()
        return true
    }

    /**
     * Process text input from IME (Input Method Editor).
     * This handles multi-byte UTF-8 text from the software keyboard.
     */
    fun onTextInput(bytes: ByteArray) {
        if (bytes.isEmpty()) return

        val raw = bytes.toString(Charsets.UTF_8)
        val text = if (Normalizer.isNormalized(raw, Normalizer.Form.NFC)) {
            raw
        } else {
            Normalizer.normalize(raw, Normalizer.Form.NFC)
        }

        val compose = composeMode
        if (compose != null && compose.isActive) {
            compose.appendText(text)
            return
        }

        val modifiers = getModifierMask()

        text.codePoints().forEach { codepoint ->
            dispatchCodepointOrEnter(modifiers, codepoint)
        }
        modifierManager?.clearTransients()
        onInputProcessed?.invoke()
    }

    /**
     * Dispatch finalized IME text directly to the terminal, bypassing composeMode.buffer.
     *
     * setComposingText() updates the local compose overlay while the IME is still composing.
     * Once the IME calls commitText(), that text is final and should become terminal input
     * immediately rather than staying attached to the sticky compose buffer.
     */
    fun onCommittedText(text: String) {
        if (text.isEmpty()) return

        val normalized = if (Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            text
        } else {
            Normalizer.normalize(text, Normalizer.Form.NFC)
        }
        val modifiers = getModifierMask()

        var index = 0
        while (index < normalized.length) {
            when (val ch = normalized[index]) {
                '\n' -> {
                    terminalEmulator.dispatchKey(modifiers, VTermKey.ENTER)
                    index += 1
                }

                '\r' -> {
                    terminalEmulator.dispatchKey(modifiers, VTermKey.ENTER)
                    index += if (index + 1 < normalized.length && normalized[index + 1] == '\n') 2 else 1
                }

                else -> {
                    val codepoint = normalized.codePointAt(index)
                    terminalEmulator.dispatchCharacter(modifiers, codepoint)
                    index += Character.charCount(codepoint)
                }
            }
        }

        modifierManager?.clearTransients()
        onInputProcessed?.invoke()
    }

    /**
     * Build VTerm modifier mask.
     * Bit 0: Shift
     * Bit 1: Alt
     * Bit 2: Ctrl
     */
    private fun buildModifierMask(ctrl: Boolean, alt: Boolean, shift: Boolean): Int {
        var mask = getModifierMask()
        if (shift) mask = mask or 1
        if (alt) mask = mask or 2
        if (ctrl) mask = mask or 4
        return mask
    }

    private fun resolveEventModifierState(event: ComposeKeyEvent): EventModifierState {
        val nativeEvent = event.nativeKeyEvent
        val rightAltPressed = nativeEvent.hasModifiers(AndroidKeyEvent.META_ALT_RIGHT_ON)
        val rightAltIsMeta = rightAltPressed && rightAltMode == RightAltMode.Meta
        val leftAltPressed = nativeEvent.hasModifiers(AndroidKeyEvent.META_ALT_LEFT_ON) ||
            (!rightAltPressed && nativeEvent.metaState and AndroidKeyEvent.META_ALT_ON != 0)

        return EventModifierState(
            alt = leftAltPressed || rightAltIsMeta,
            stripAltGr = rightAltIsMeta,
        )
    }

    /**
     * Get VTerm modifier mask for current sticky state.
     *
     * Returns a bitmask where:
     * - Bit 0 (0x01): Shift
     * - Bit 1 (0x02): Alt
     * - Bit 2 (0x04): Ctrl
     *
     * This matches the format expected by Terminal.dispatchKey() and dispatchCharacter().
     */
    fun getModifierMask(): Int {
        return modifierManager?.let {
            var mask = 0
            if (it.isShiftActive() == true) mask = mask or 1 // Bit 0: Shift
            if (it.isAltActive() == true) mask = mask or 2 // Bit 1: Alt
            if (it.isCtrlActive() == true) mask = mask or 4 // Bit 2: Ctrl
            return mask
        } ?: 0
    }

    /**
     * Convert a Compose KeyEvent to its Unicode codepoint, handling dead-key composition.
     *
     * Uses the Android KeyCharacterMap to handle all key layouts and shift states correctly,
     * including keys not present in the static mapping (e.g. Key.At, non-US layouts).
     *
     * Dead keys (combining accents) are tracked across calls:
     * - On a dead key, the accent is saved and 0L is returned (the key is handled but produces no
     *   immediate output).
     * - On the next printable key, [KeyCharacterMap.getDeadChar] combines them. If the
     *   combination is valid, the composed character is returned. If not (e.g. ´ + z on a
     *   layout with no ź), the pending accent is emitted first and then the base character,
     *   so neither is lost.
     * - A second consecutive dead key of the same type emits the spacing version of the accent
     *   (standard terminal behaviour).
     *
     * @param extraShift if true, adds META_SHIFT_ON to the meta state (for sticky shift)
     * @param stripAlt if true, also strips [AndroidKeyEvent.META_ALT_RIGHT_ON] from the KCM
     *   lookup; left-alt is always stripped. Use when right-alt is [RightAltMode.Meta]
     * @return the resolved codepoint(s), 0L if a dead key was buffered, or null if not handled
     */
    private fun getCodePointFromKeyEvent(
        event: ComposeKeyEvent,
        extraShift: Boolean = false,
        stripAlt: Boolean = false,
    ): Long? {
        val nativeEvent = event.nativeKeyEvent
        // Always strip left-alt and the shared ALT_ON bit; only strip right-alt when it is
        // acting as Meta rather than a character selector.
        val rightAltMask = if (stripAlt) AndroidKeyEvent.META_ALT_RIGHT_ON else 0
        val stripMask = (
            AndroidKeyEvent.META_CTRL_MASK or
                AndroidKeyEvent.META_ALT_LEFT_ON or AndroidKeyEvent.META_ALT_ON or
                rightAltMask
            ).inv()
        val metaState = (nativeEvent.metaState and stripMask) or
            if (extraShift) AndroidKeyEvent.META_SHIFT_ON else 0
        val raw = unicodeCharLookup(nativeEvent.deviceId, nativeEvent.keyCode, metaState)

        if (raw == 0) {
            pendingDeadChar = 0
            return null
        }

        val dead = pendingDeadChar
        val base: Int
        if (raw and KeyCharacterMap.COMBINING_ACCENT != 0) {
            base = raw and KeyCharacterMap.COMBINING_ACCENT_MASK
            if (dead == 0) {
                pendingDeadChar = base
                return 0L
            }
            // No need to handle the double-combining accent here; KCM.getDeadChar does it.
        } else {
            base = raw
        }

        pendingDeadChar = 0

        if (dead != 0) {
            val composed = KeyCharacterMap.getDeadChar(dead, base)
            if (composed != 0) return composed.toLong()

            // Combination not possible: the caller will emit `dead` first, then we return `raw`.
            // Use bit 63 as a flag for multiple codepoints.
            return (1L shl 63) or (dead.toLong() shl 32) or base.toLong()
        }

        return base.toLong()
    }

    private fun dispatchCodepointOrEnter(modifiers: Int, codepoint: Int) {
        if (codepoint == '\n'.code) {
            terminalEmulator.dispatchKey(modifiers, VTermKey.ENTER)
        } else {
            terminalEmulator.dispatchCharacter(modifiers, codepoint)
        }
    }

    /**
     * Dispatch one or more codepoints produced by [getCodePointFromKeyEvent].
     */
    private fun dispatchCodePoint(codepoint: Long, dispatch: (Int) -> Unit) {
        if (codepoint == 0L) return
        if (codepoint < 0) {
            // Failed dead-key combination: dispatch both characters.
            val accent = ((codepoint ushr 32) and 0x7FFFFFFFL).toInt()
            val base = (codepoint and 0xFFFFFFFFL).toInt()
            dispatch(accent)
            dispatch(base)
        } else {
            dispatch(codepoint.toInt())
        }
    }

    /**
     * Map Compose Key to VTerm key code.
     * Returns null if not a special key.
     */
    private fun mapToVTermKey(key: Key): Int? = when (key) {
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

        Key.MoveHome -> VTermKey.HOME

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

private data class EventModifierState(
    val alt: Boolean,
    val stripAltGr: Boolean,
)

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

    // In vterm_keycodes.h enum VTERM_KEY_FUNCTION_0 = 256
    const val FUNCTION_0 = 256
    const val FUNCTION_1 = 257
    const val FUNCTION_2 = 258
    const val FUNCTION_3 = 259
    const val FUNCTION_4 = 260
    const val FUNCTION_5 = 261
    const val FUNCTION_6 = 262
    const val FUNCTION_7 = 263
    const val FUNCTION_8 = 264
    const val FUNCTION_9 = 265
    const val FUNCTION_10 = 266
    const val FUNCTION_11 = 267
    const val FUNCTION_12 = 268

    // Keypad keys (start after FUNCTION_MAX which is 256 + 255 = 511)
    const val KP_0 = 512
    const val KP_1 = 513
    const val KP_2 = 514
    const val KP_3 = 515
    const val KP_4 = 516
    const val KP_5 = 517
    const val KP_6 = 518
    const val KP_7 = 519
    const val KP_8 = 520
    const val KP_9 = 521
    const val KP_MULT = 522
    const val KP_PLUS = 523
    const val KP_COMMA = 524
    const val KP_MINUS = 525
    const val KP_PERIOD = 526
    const val KP_DIVIDE = 527
    const val KP_ENTER = 528
    const val KP_EQUAL = 529
}
