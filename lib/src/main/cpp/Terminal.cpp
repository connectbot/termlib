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
#include "Terminal.h"
#include "mutf8.h"
#include <cstring>
#include <algorithm>
#include <vector>

#ifdef __ANDROID__
#  include <android/log.h>
#  define LOG_TAG "TermNative"
#  define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#  define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#  include <cstdio>
#  define LOG_TAG "TermNative"
#  define LOGD(...) do { fprintf(stderr, "[D/" LOG_TAG "] " __VA_ARGS__); fputc('\n', stderr); } while(0)
#  define LOGE(...) do { fprintf(stderr, "[E/" LOG_TAG "] " __VA_ARGS__); fputc('\n', stderr); } while(0)
#endif

#define JNI_CHECK_EXCEPTION_RETURN(env, retval) \
    do { if ((env)->ExceptionCheck()) { (env)->ExceptionDescribe(); (env)->ExceptionClear(); return (retval); } } while (0)
#define JNI_CHECK_EXCEPTION_RETURN_VOID(env) \
    do { if ((env)->ExceptionCheck()) { (env)->ExceptionDescribe(); (env)->ExceptionClear(); return; } } while (0)
#define JNI_CHECK_EXCEPTION(env) \
    do { if ((env)->ExceptionCheck()) { (env)->ExceptionDescribe(); (env)->ExceptionClear(); } } while (0)

// Thread-local CharArray pool to eliminate per-frame allocations
// Each thread gets one reusable CharArray, sized to handle typical cell runs
static thread_local jcharArray tls_charArray = nullptr;
static thread_local jsize tls_charArraySize = 0;

// Terminal implementation
Terminal::Terminal(JNIEnv* env, jobject callbacks, int rows, int cols)
    : mRows(rows), mCols(cols) {

    LOGD("Terminal constructor: rows=%d, cols=%d", rows, cols);

    // Get JavaVM for callback invocations from any thread
    env->GetJavaVM(&mJavaVM);

    // Store global reference to callbacks
    mCallbacks = env->NewGlobalRef(callbacks);

    // Cache method IDs
    ScopedLocalRef<jclass> callbacksClass(env, env->GetObjectClass(callbacks));
    mDamageMethod = env->GetMethodID(callbacksClass, "damage", "(IIII)I");
    if (!mDamageMethod || env->ExceptionCheck()) {
        LOGE("Failed to find damage method");
        env->ExceptionClear();
    }
    mMoverectMethod = env->GetMethodID(callbacksClass, "moverect",
        "(Lorg/connectbot/terminal/TermRect;Lorg/connectbot/terminal/TermRect;)I");
    if (!mMoverectMethod) {
        LOGE("Failed to find moverect method");
    }
    mMoveCursorMethod = env->GetMethodID(callbacksClass, "moveCursor",
        "(Lorg/connectbot/terminal/CursorPosition;Lorg/connectbot/terminal/CursorPosition;Z)I");
    if (!mMoveCursorMethod) {
        LOGE("Failed to find moveCursor method");
    }
    mSetTermPropMethod = env->GetMethodID(callbacksClass, "setTermProp",
        "(ILorg/connectbot/terminal/TerminalProperty;)I");
    if (!mSetTermPropMethod) {
        LOGE("Failed to find setTermProp method");
    }
    mBellMethod = env->GetMethodID(callbacksClass, "bell", "()I");
    if (!mBellMethod) {
        LOGE("Failed to find bell method");
    }
    mPushScrollbackMethod = env->GetMethodID(callbacksClass, "pushScrollbackLine",
        "(I[Lorg/connectbot/terminal/ScreenCell;Z)I");
    if (!mPushScrollbackMethod) {
        LOGE("Failed to find pushScrollbackLine method");
    }
    mPopScrollbackMethod = env->GetMethodID(callbacksClass, "popScrollbackLine",
        "(I[Lorg/connectbot/terminal/ScreenCell;)I");
    if (!mPopScrollbackMethod) {
        LOGE("Failed to find popScrollbackLine method");
    }
    mClearScrollbackMethod = env->GetMethodID(callbacksClass, "clearScrollback", "()I");
    if (!mClearScrollbackMethod) {
        LOGE("Failed to find clearScrollback method");
    }
    mKeyboardInputMethod = env->GetMethodID(callbacksClass, "onKeyboardInput", "([B)I");
    if (!mKeyboardInputMethod) {
        LOGE("Failed to find onKeyboardInput method");
    }
    mOscSequenceMethod = env->GetMethodID(callbacksClass, "onOscSequence", "(ILjava/lang/String;II)I");
    if (!mOscSequenceMethod) {
        LOGE("Failed to find onOscSequence method");
    }

    // Cache CellRun class and field IDs
    ScopedLocalRef<jclass> cellRunLocal(env, env->FindClass("org/connectbot/terminal/CellRun"));
    mCellRunClass = (jclass)env->NewGlobalRef(cellRunLocal);

    mFgRedField = env->GetFieldID(mCellRunClass, "fgRed", "I");
    mFgGreenField = env->GetFieldID(mCellRunClass, "fgGreen", "I");
    mFgBlueField = env->GetFieldID(mCellRunClass, "fgBlue", "I");
    mBgRedField = env->GetFieldID(mCellRunClass, "bgRed", "I");
    mBgGreenField = env->GetFieldID(mCellRunClass, "bgGreen", "I");
    mBgBlueField = env->GetFieldID(mCellRunClass, "bgBlue", "I");
    mBoldField = env->GetFieldID(mCellRunClass, "bold", "Z");
    mUnderlineField = env->GetFieldID(mCellRunClass, "underline", "I");
    mItalicField = env->GetFieldID(mCellRunClass, "italic", "Z");
    mBlinkField = env->GetFieldID(mCellRunClass, "blink", "Z");
    mReverseField = env->GetFieldID(mCellRunClass, "reverse", "Z");
    mStrikeField = env->GetFieldID(mCellRunClass, "strike", "Z");
    mFontField = env->GetFieldID(mCellRunClass, "font", "I");
    mDwlField = env->GetFieldID(mCellRunClass, "dwl", "Z");
    mDhlField = env->GetFieldID(mCellRunClass, "dhl", "I");
    mCharsField = env->GetFieldID(mCellRunClass, "chars", "[C");
    mRunLengthField = env->GetFieldID(mCellRunClass, "runLength", "I");

    // Cache all callback-related classes and methods to avoid repeated FindClass/GetMethodID
    LOGD("Caching callback classes and methods...");

    // TermRect
    ScopedLocalRef<jclass> termRectLocal(env, env->FindClass("org/connectbot/terminal/TermRect"));
    mTermRectClass = (jclass)env->NewGlobalRef(termRectLocal);
    mTermRectConstructor = env->GetMethodID(mTermRectClass, "<init>", "(IIII)V");

    // CursorPosition
    ScopedLocalRef<jclass> cursorPosLocal(env, env->FindClass("org/connectbot/terminal/CursorPosition"));
    mCursorPositionClass = (jclass)env->NewGlobalRef(cursorPosLocal);
    mCursorPositionConstructor = env->GetMethodID(mCursorPositionClass, "<init>", "(II)V");

    // ScreenCell
    ScopedLocalRef<jclass> screenCellLocal(env, env->FindClass("org/connectbot/terminal/ScreenCell"));
    mScreenCellClass = (jclass)env->NewGlobalRef(screenCellLocal);
    mScreenCellConstructor = env->GetMethodID(mScreenCellClass, "<init>",
        "(CLjava/util/List;IIIIIIZZIZZI)V");

    // ArrayList
    ScopedLocalRef<jclass> arrayListLocal(env, env->FindClass("java/util/ArrayList"));
    mArrayListClass = (jclass)env->NewGlobalRef(arrayListLocal);
    mArrayListConstructor = env->GetMethodID(mArrayListClass, "<init>", "()V");
    mArrayListAdd = env->GetMethodID(mArrayListClass, "add", "(Ljava/lang/Object;)Z");

    // Character
    ScopedLocalRef<jclass> charLocal(env, env->FindClass("java/lang/Character"));
    mCharacterClass = (jclass)env->NewGlobalRef(charLocal);
    mCharacterValueOf = env->GetStaticMethodID(mCharacterClass, "valueOf", "(C)Ljava/lang/Character;");

    // TerminalProperty classes
    ScopedLocalRef<jclass> boolLocal(env, env->FindClass("org/connectbot/terminal/TerminalProperty$BoolValue"));
    mTerminalPropertyBoolClass = (jclass)env->NewGlobalRef(boolLocal);
    mTerminalPropertyBoolConstructor = env->GetMethodID(mTerminalPropertyBoolClass, "<init>", "(Z)V");

    ScopedLocalRef<jclass> intLocal(env, env->FindClass("org/connectbot/terminal/TerminalProperty$IntValue"));
    mTerminalPropertyIntClass = (jclass)env->NewGlobalRef(intLocal);
    mTerminalPropertyIntConstructor = env->GetMethodID(mTerminalPropertyIntClass, "<init>", "(I)V");

    ScopedLocalRef<jclass> stringLocal(env, env->FindClass("org/connectbot/terminal/TerminalProperty$StringValue"));
    mTerminalPropertyStringClass = (jclass)env->NewGlobalRef(stringLocal);
    mTerminalPropertyStringConstructor = env->GetMethodID(mTerminalPropertyStringClass, "<init>", "(Ljava/lang/String;)V");

    ScopedLocalRef<jclass> colorLocal(env, env->FindClass("org/connectbot/terminal/TerminalProperty$ColorValue"));
    mTerminalPropertyColorClass = (jclass)env->NewGlobalRef(colorLocal);
    mTerminalPropertyColorConstructor = env->GetMethodID(mTerminalPropertyColorClass, "<init>", "(III)V");

    LOGD("All callback classes and methods cached successfully");

    // Create VTerm instance
    mVt = vterm_new(mRows, mCols);
    if (!mVt) {
        LOGE("Failed to create VTerm instance");
        return;
    }

    vterm_set_utf8(mVt, 1);

    // Set up output handler for keyboard input
    vterm_output_set_callback(mVt, termOutput, this);

    // Get screen and set up callbacks
    mVts = vterm_obtain_screen(mVt);
    vterm_screen_enable_altscreen(mVts, 1);

    // Initialize callback structure as member variable so it doesn't go out of scope
    mScreenCallbacks = {
        .damage = termDamage,
        .moverect = termMoverect,
        .movecursor = termMovecursor,
        .settermprop = termSettermprop,
        .bell = termBell,
        .resize = nullptr,  // We handle resize explicitly
        .sb_pushline = termSbPushline,
        .sb_popline = termSbPopline,
        .sb_clear = termSbClear
    };
    vterm_screen_set_callbacks(mVts, &mScreenCallbacks, this);

    // Set up OSC fallback handlers for shell integration
    VTermState* state = vterm_obtain_state(mVt);
    VTermStateFallbacks fallbacks = {
        .control = nullptr,
        .csi = nullptr,
        .osc = termOscFallback,
        .dcs = nullptr,
        .apc = nullptr,
        .pm = nullptr,
        .sos = nullptr
    };
    mStateFallbacks = fallbacks;
    vterm_state_set_unrecognised_fallbacks(state, &mStateFallbacks, this);

    // Set up selection callbacks for OSC 52 clipboard support
    // Note: libvterm handles base64 decoding internally
    mSelectionCallbacks = {
        .set = termSelectionSet,
        .query = termSelectionQuery
    };
    vterm_state_set_selection_callbacks(state, &mSelectionCallbacks, this,
        mSelectionBuffer, SELECTION_BUFFER_SIZE);
    LOGD("Selection callbacks registered for OSC 52 clipboard support");

    // Configure damage merging
    vterm_screen_set_damage_merge(mVts, VTERM_DAMAGE_SCROLL);

    vterm_screen_reset(mVts, 1);

    LOGD("Terminal initialized successfully");
}

Terminal::~Terminal() {
    LOGD("Terminal destructor");

    std::lock_guard<std::recursive_mutex> lock(mLock);

    if (mVt) {
        vterm_free(mVt);
        mVt = nullptr;
    }

    // Release global references
    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
        if (mCallbacks) {
            env->DeleteGlobalRef(mCallbacks);
            mCallbacks = nullptr;
        }
        if (mCellRunClass) {
            env->DeleteGlobalRef(mCellRunClass);
            mCellRunClass = nullptr;
        }
        // Clean up cached callback classes
        if (mTermRectClass) env->DeleteGlobalRef(mTermRectClass);
        if (mCursorPositionClass) env->DeleteGlobalRef(mCursorPositionClass);
        if (mScreenCellClass) env->DeleteGlobalRef(mScreenCellClass);
        if (mArrayListClass) env->DeleteGlobalRef(mArrayListClass);
        if (mCharacterClass) env->DeleteGlobalRef(mCharacterClass);
        if (mTerminalPropertyBoolClass) env->DeleteGlobalRef(mTerminalPropertyBoolClass);
        if (mTerminalPropertyIntClass) env->DeleteGlobalRef(mTerminalPropertyIntClass);
        if (mTerminalPropertyStringClass) env->DeleteGlobalRef(mTerminalPropertyStringClass);
        if (mTerminalPropertyColorClass) env->DeleteGlobalRef(mTerminalPropertyColorClass);
    }
}

// Input handling - KEY METHOD
int Terminal::writeInput(const uint8_t* data, size_t length) {
    std::lock_guard<std::recursive_mutex> lock(mLock);

    if (!mVt) {
        LOGE("writeInput: VTerm not initialized");
        return 0;
    }

    // Feed data to libvterm for processing
    size_t written = vterm_input_write(mVt, (const char*)data, length);

    // Flush screen state to trigger callbacks
    vterm_screen_flush_damage(mVts);

    return static_cast<int>(written);
}

// Resize
int Terminal::resize(int rows, int cols) {
    std::lock_guard<std::recursive_mutex> lock(mLock);

    mRows = rows;
    mCols = cols;

    if (mVt) {
        vterm_set_size(mVt, rows, cols);
        vterm_screen_flush_damage(mVts);
    }

    return 0;
}

// Color configuration
int Terminal::setPaletteColors(const uint32_t* colors, int count) {
    std::lock_guard<std::recursive_mutex> lock(mLock);

    if (!mVt) {
        LOGE("setPaletteColors: VTerm not initialized");
        return -1;
    }

    VTermState* state = vterm_obtain_state(mVt);
    if (!state) {
        LOGE("setPaletteColors: Failed to obtain VTermState");
        return -1;
    }

    // Only set ANSI colors (0-15)
    int colorCount = std::min(count, 16);

    for (int i = 0; i < colorCount; i++) {
        VTermColor vtColor;

        // Convert ARGB to RGB (libvterm uses RGB, Android uses ARGB 0xAARRGGBB)
        vterm_color_rgb(&vtColor,
                       (colors[i] >> 16) & 0xFF,  // Red
                       (colors[i] >> 8) & 0xFF,   // Green
                       colors[i] & 0xFF);         // Blue

        vterm_state_set_palette_color(state, i, &vtColor);
    }

    return colorCount;
}

int Terminal::setBoldHighbright(int enabled) {
    std::lock_guard<std::recursive_mutex> lock(mLock);

    if (!mVt) {
        LOGE("setBoldHighbright: VTerm not initialized");
        return -1;
    }

    VTermState* state = vterm_obtain_state(mVt);
    if (!state) {
        LOGE("setBoldHighbright: Failed to obtain VTermState");
        return -1;
    }

    vterm_state_set_bold_highbright(state, enabled);
    return 0;
}

int Terminal::setDefaultColors(uint32_t fgColor, uint32_t bgColor) {
    std::lock_guard<std::recursive_mutex> lock(mLock);

    if (!mVt) {
        LOGE("setDefaultColors: VTerm not initialized");
        return -1;
    }

    VTermScreen* screen = vterm_obtain_screen(mVt);
    if (!screen) {
        LOGE("setDefaultColors: Failed to obtain VTermScreen");
        return -1;
    }

    // Convert ARGB to RGB for foreground
    VTermColor vtFg;
    vterm_color_rgb(&vtFg,
                   (fgColor >> 16) & 0xFF,  // Red
                   (fgColor >> 8) & 0xFF,   // Green
                   fgColor & 0xFF);         // Blue

    // Convert ARGB to RGB for background
    VTermColor vtBg;
    vterm_color_rgb(&vtBg,
                   (bgColor >> 16) & 0xFF,  // Red
                   (bgColor >> 8) & 0xFF,   // Green
                   bgColor & 0xFF);         // Blue

    vterm_screen_set_default_colors(screen, &vtFg, &vtBg);

    return 0;
}

// Keyboard input handlers
bool Terminal::dispatchKey(int modifiers, int key) {
    std::lock_guard<std::recursive_mutex> lock(mLock);

    if (!mVt) {
        return false;
    }

    VTermModifier mod = VTERM_MOD_NONE;
    if (modifiers & 1) mod = (VTermModifier)(mod | VTERM_MOD_SHIFT);
    if (modifiers & 2) mod = (VTermModifier)(mod | VTERM_MOD_ALT);
    if (modifiers & 4) mod = (VTermModifier)(mod | VTERM_MOD_CTRL);

    vterm_keyboard_key(mVt, (VTermKey)key, mod);
    return true;
}

bool Terminal::dispatchCharacter(int modifiers, int codepoint) {
    std::lock_guard<std::recursive_mutex> lock(mLock);

    if (!mVt) {
        return false;
    }

    VTermModifier mod = VTERM_MOD_NONE;
    if (modifiers & 1) mod = (VTermModifier)(mod | VTERM_MOD_SHIFT);
    if (modifiers & 2) mod = (VTermModifier)(mod | VTERM_MOD_ALT);
    if (modifiers & 4) mod = (VTermModifier)(mod | VTERM_MOD_CTRL);

    vterm_keyboard_unichar(mVt, codepoint, mod);
    return true;
}

// Cell run retrieval
int Terminal::getCellRun(JNIEnv* env, int row, int col, jobject runObject) {
    std::lock_guard<std::recursive_mutex> lock(mLock);

    if (!mVts || row < 0 || row >= mRows || col < 0 || col >= mCols) {
        return 0;
    }

    // Get first cell
    VTermPos pos = { row, col };
    VTermScreenCell cell;
    vterm_screen_get_cell(mVts, pos, &cell);

    // Collect cells with same attributes
    int runLength = 0;
    jchar chars[256];  // Max run length

    for (int c = col; c < mCols && runLength < 256; c++) {
        VTermPos currentPos = { row, c };
        VTermScreenCell currentCell;
        vterm_screen_get_cell(mVts, currentPos, &currentCell);

        // Check if attributes match (skip for first cell)
        if (c > col && !cellStyleEqual(cell, currentCell)) {
            break;
        }

        // Add character(s) to run
        if (currentCell.chars[0] == 0) {
            // Empty cell
            chars[runLength++] = ' ';
        } else {
            // Convert UTF-32 to UTF-16 (handle surrogate pairs)
            for (int i = 0; i < VTERM_MAX_CHARS_PER_CELL && currentCell.chars[i]; i++) {
                uint32_t codepoint = currentCell.chars[i];

                if (codepoint <= 0xFFFF) {
                    chars[runLength++] = (jchar)codepoint;
                } else {
                    // Surrogate pair for codepoints > U+FFFF
                    codepoint -= 0x10000;
                    chars[runLength++] = (jchar)(0xD800 + (codepoint >> 10));
                    chars[runLength++] = (jchar)(0xDC00 + (codepoint & 0x3FF));
                }
            }
        }

        // Skip next column if this is a wide character
        if (currentCell.width == 2) {
            c++;
        }
    }

    // Resolve colors
    uint8_t fgRed, fgGreen, fgBlue;
    uint8_t bgRed, bgGreen, bgBlue;
    resolveColor(cell.fg, fgRed, fgGreen, fgBlue);
    resolveColor(cell.bg, bgRed, bgGreen, bgBlue);

    // Set fields
    env->SetIntField(runObject, mFgRedField, fgRed);
    env->SetIntField(runObject, mFgGreenField, fgGreen);
    env->SetIntField(runObject, mFgBlueField, fgBlue);
    env->SetIntField(runObject, mBgRedField, bgRed);
    env->SetIntField(runObject, mBgGreenField, bgGreen);
    env->SetIntField(runObject, mBgBlueField, bgBlue);

    env->SetBooleanField(runObject, mBoldField, cell.attrs.bold);
    env->SetIntField(runObject, mUnderlineField, cell.attrs.underline);
    env->SetBooleanField(runObject, mItalicField, cell.attrs.italic);
    env->SetBooleanField(runObject, mBlinkField, cell.attrs.blink);
    env->SetBooleanField(runObject, mReverseField, cell.attrs.reverse);
    env->SetBooleanField(runObject, mStrikeField, cell.attrs.strike);
    env->SetIntField(runObject, mFontField, cell.attrs.font);
    env->SetBooleanField(runObject, mDwlField, cell.attrs.dwl);
    env->SetIntField(runObject, mDhlField, cell.attrs.dhl);

    // Set character array using thread-local pool to eliminate allocations
    // If this is the first call on this thread, or array is too small, allocate/resize
    if (tls_charArray == nullptr || tls_charArraySize < runLength) {
        // Clean up old array if it exists
        if (tls_charArray != nullptr) {
            env->DeleteGlobalRef(tls_charArray);
        }

        // Allocate new array with some headroom (round up to nearest 64)
        jsize newSize = ((runLength + 63) / 64) * 64;
        ScopedLocalRef<jcharArray> localArray(env, env->NewCharArray(newSize));
        tls_charArray = (jcharArray)env->NewGlobalRef(localArray);
        tls_charArraySize = newSize;

        LOGD("Allocated thread-local CharArray: size=%d", newSize);
    }

    // Reuse the thread-local array
    env->SetCharArrayRegion(tls_charArray, 0, runLength, chars);
    env->SetObjectField(runObject, mCharsField, tls_charArray);

    env->SetIntField(runObject, mRunLengthField, runLength);

    return runLength;
}

// Callback implementations
int Terminal::termDamage(VTermRect rect, void* user) {
    auto* term = static_cast<Terminal*>(user);
    term->invokeDamage(rect.start_row, rect.end_row, rect.start_col, rect.end_col);
    return 1;
}

int Terminal::termMoverect(VTermRect dest, VTermRect src, void* user) {
    auto* term = static_cast<Terminal*>(user);
    return term->invokeMoverect(dest, src);
}

int Terminal::termMovecursor(VTermPos pos, VTermPos oldpos, int visible, void* user) {
    auto* term = static_cast<Terminal*>(user);
    term->invokeMoveCursor(pos.row, pos.col, oldpos.row, oldpos.col, visible != 0);
    return 1;
}

int Terminal::termSettermprop(VTermProp prop, VTermValue* val, void* user) {
    auto* term = static_cast<Terminal*>(user);
    term->invokeSetTermProp(prop, val);
    return 1;
}

int Terminal::termBell(void* user) {
    auto* term = static_cast<Terminal*>(user);
    term->invokeBell();
    return 1;
}

int Terminal::termSbPushline(int cols, const VTermScreenCell* cells, void* user) {
    auto* term = static_cast<Terminal*>(user);

    // Check if row 1 (the next line after the one being pushed) is a continuation.
    // If row 1 has continuation=1, it means the line being pushed (row 0) ended with
    // a soft wrap rather than a hard line break.
    VTermState* state = vterm_obtain_state(term->mVt);
    bool softWrapped = false;
    if (state && term->mRows > 1) {
        const VTermLineInfo* nextLineInfo = vterm_state_get_lineinfo(state, 1);
        if (nextLineInfo) {
            softWrapped = nextLineInfo->continuation != 0;
        }
    }

    term->invokePushScrollbackLine(cols, cells, softWrapped);
    return 1;
}

int Terminal::termSbPopline(int cols, VTermScreenCell* cells, void* user) {
    auto* term = static_cast<Terminal*>(user);
    return term->invokePopScrollbackLine(cols, cells);
}

int Terminal::termSbClear(void* user) {
    auto* term = static_cast<Terminal*>(user);
    term->invokeClearScrollback();
    return 1;
}

void Terminal::termOutput(const char* s, size_t len, void* user) {
    auto* term = static_cast<Terminal*>(user);
    term->invokeKeyboardOutput(s, len);
}

// OSC sequence fallback handler
// Handles fragmented OSC sequences by accumulating data across callbacks
int Terminal::termOscFallback(int command, VTermStringFragment frag, void* user) {
    auto* term = static_cast<Terminal*>(user);

    // Start of a new OSC sequence
    if (frag.initial) {
        term->mOscData.clear();
        term->mOscCommand = command;
        // For OSC 8 (hyperlinks), save cursor position at the START of the sequence
        // because libvterm processes text before calling callbacks, causing the
        // cursor position at frag.final to reflect the position AFTER the hyperlink text
        if (command == 8) {
            VTermState* state = vterm_obtain_state(term->mVt);
            vterm_state_get_cursorpos(state, &term->mOscCursorPos);
        }
    }

    // Accumulate fragment data
    if (frag.len > 0) {
        term->mOscData.append(frag.str, frag.len);
    }

    // When we have the final fragment, send complete payload to Java
    if (frag.final) {
        int cursorRow, cursorCol;
        if (term->mOscCommand == 8) {
            // For OSC 8, use the position saved at frag.initial
            cursorRow = term->mOscCursorPos.row;
            cursorCol = term->mOscCursorPos.col;
        } else {
            // For other OSC commands, get current cursor position
            VTermState* state = vterm_obtain_state(term->mVt);
            VTermPos cursorPos;
            vterm_state_get_cursorpos(state, &cursorPos);
            cursorRow = cursorPos.row;
            cursorCol = cursorPos.col;
        }

        int result = term->invokeOscSequence(term->mOscCommand, term->mOscData, cursorRow, cursorCol);
        term->mOscData.clear();
        term->mOscCommand = -1;
        return result;
    }

    return 1;  // Indicate we're handling this (continue accumulating)
}

// OSC 52 selection set callback - receives base64-decoded clipboard data from libvterm
int Terminal::termSelectionSet(VTermSelectionMask mask, VTermStringFragment frag, void* user) {
    auto* term = static_cast<Terminal*>(user);

    LOGD("termSelectionSet: mask=%04X, len=%d, initial=%d, final=%d",
         mask, static_cast<int>(frag.len), frag.initial, frag.final);

    // Accumulate data across fragments
    if (frag.initial) {
        term->mSelectionData.clear();
    }

    if (frag.len > 0) {
        term->mSelectionData.append(frag.str, frag.len);
    }

    // When we have the final fragment, send to Java via OSC sequence callback
    if (frag.final && !term->mSelectionData.empty()) {
        LOGD("termSelectionSet: final data length=%d", static_cast<int>(term->mSelectionData.size()));
        // Use OSC 52 command number and pass the decoded data as payload
        // Format: selection;data - but since libvterm already decoded base64, we pass raw data
        std::string payload = "c;" + term->mSelectionData;  // 'c' for clipboard
        // Cursor position not relevant for OSC 52, pass 0,0
        term->invokeOscSequence(52, payload, 0, 0);
        term->mSelectionData.clear();
    }

    return 1;
}

// OSC 52 selection query callback - we don't support clipboard read for security
int Terminal::termSelectionQuery(VTermSelectionMask mask, void* user) {
    LOGD("termSelectionQuery: mask=%04X (ignored for security)", mask);
    // Return 0 to indicate we don't support clipboard read
    return 0;
}

// Java callback invocations
void Terminal::invokeDamage(int startRow, int endRow, int startCol, int endCol) {
    if (!mDamageMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    env->CallIntMethod(mCallbacks, mDamageMethod, startRow, endRow, startCol, endCol);
    JNI_CHECK_EXCEPTION(env);
}

int Terminal::invokeMoverect(VTermRect dest, VTermRect src) {
    if (!mMoverectMethod) {
        return 0;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return 0;
    }

    ScopedLocalRef<jobject> destObj(env, env->NewObject(mTermRectClass, mTermRectConstructor,
        dest.start_row, dest.end_row, dest.start_col, dest.end_col));
    ScopedLocalRef<jobject> srcObj(env, env->NewObject(mTermRectClass, mTermRectConstructor,
        src.start_row, src.end_row, src.start_col, src.end_col));

    jint result = env->CallIntMethod(mCallbacks, mMoverectMethod, destObj.get(), srcObj.get());
    JNI_CHECK_EXCEPTION_RETURN(env, 0);
    return result;
}

void Terminal::invokeMoveCursor(int row, int col, int oldRow, int oldCol, bool visible) {
    if (!mMoveCursorMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    ScopedLocalRef<jobject> posObj(env, env->NewObject(mCursorPositionClass, mCursorPositionConstructor, row, col));
    ScopedLocalRef<jobject> oldPosObj(env, env->NewObject(mCursorPositionClass, mCursorPositionConstructor, oldRow, oldCol));

    env->CallIntMethod(mCallbacks, mMoveCursorMethod, posObj.get(), oldPosObj.get(), visible);
    JNI_CHECK_EXCEPTION(env);
}

void Terminal::invokeSetTermProp(VTermProp prop, VTermValue* val) {
    if (!mSetTermPropMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    ScopedLocalRef<jobject> propValue(env, nullptr);

    switch (vterm_get_prop_type(prop)) {
        case VTERM_VALUETYPE_BOOL:
            propValue = ScopedLocalRef<jobject>(env, env->NewObject(mTerminalPropertyBoolClass, mTerminalPropertyBoolConstructor, val->boolean));
            break;

        case VTERM_VALUETYPE_INT:
            propValue = ScopedLocalRef<jobject>(env, env->NewObject(mTerminalPropertyIntClass, mTerminalPropertyIntConstructor, val->number));
            break;

        case VTERM_VALUETYPE_STRING:
            if (val->string.str) {
                char* utf8_str = mutf8_to_utf8(val->string.str, val->string.len, nullptr);
                ScopedLocalRef<jstring> str(env, env->NewStringUTF(utf8_str));
                propValue = ScopedLocalRef<jobject>(env, env->NewObject(mTerminalPropertyStringClass, mTerminalPropertyStringConstructor, str.get()));
                free(utf8_str);
            }
            break;

        case VTERM_VALUETYPE_COLOR: {
            uint8_t r, g, b;
            resolveColor(val->color, r, g, b);
            propValue = ScopedLocalRef<jobject>(env, env->NewObject(mTerminalPropertyColorClass, mTerminalPropertyColorConstructor, r, g, b));
            break;
        }

        case VTERM_N_VALUETYPES:
            break;
    }

    if (propValue.get()) {
        env->CallIntMethod(mCallbacks, mSetTermPropMethod, prop, propValue.get());
        JNI_CHECK_EXCEPTION(env);
    }
}

void Terminal::invokeBell() {
    if (!mBellMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    env->CallIntMethod(mCallbacks, mBellMethod);
    JNI_CHECK_EXCEPTION(env);
}

void Terminal::invokePushScrollbackLine(int cols, const VTermScreenCell* cells, bool softWrapped) {
    if (!mPushScrollbackMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    // Build a list to hold actual cells (excluding fullwidth placeholders)
    std::vector<ScopedLocalRef<jobject>> screenCells;

#define CELL_JNI_EXCEPTION_CONTINUE(env) \
    do { if ((env)->ExceptionCheck()) { (env)->ExceptionDescribe(); (env)->ExceptionClear(); continue; } } while (0)
#define CELL_JNI_EXCEPTION_BREAK(env) \
    do { if ((env)->ExceptionCheck()) { (env)->ExceptionDescribe(); (env)->ExceptionClear(); break; } } while (0)

    for (int i = 0; i < cols; i++) {
        const VTermScreenCell& cell = cells[i];

        // Get the primary character and handle surrogate pairs
        jchar primaryChar = ' ';
        ScopedLocalRef<jobject> combiningList(env, env->NewObject(mArrayListClass, mArrayListConstructor));

        if (cell.chars[0] != 0) {
            uint32_t codepoint = cell.chars[0];

            if (codepoint <= 0xFFFF) {
                // BMP character - fits in single jchar
                primaryChar = (jchar)codepoint;
            } else {
                // Surrogate pair needed for codepoints > U+FFFF
                codepoint -= 0x10000;
                primaryChar = (jchar)(0xD800 + (codepoint >> 10));  // High surrogate
                jchar lowSurrogate = (jchar)(0xDC00 + (codepoint & 0x3FF));  // Low surrogate

                ScopedLocalRef<jobject> lowSurrogateObj(env, env->CallStaticObjectMethod(mCharacterClass, mCharacterValueOf, lowSurrogate));
                CELL_JNI_EXCEPTION_CONTINUE(env);  // Orphaned high surrogate; skip cell
                env->CallBooleanMethod(combiningList, mArrayListAdd, lowSurrogateObj.get());
                CELL_JNI_EXCEPTION_CONTINUE(env);  // Inconsistent combining list; skip cell
            }

            // Add any actual combining characters (chars[1] onwards)
            for (int j = 1; j < VTERM_MAX_CHARS_PER_CELL && cell.chars[j] != 0; j++) {
                uint32_t combiningCodepoint = cell.chars[j];

                if (combiningCodepoint <= 0xFFFF) {
                    ScopedLocalRef<jobject> charObj(env, env->CallStaticObjectMethod(mCharacterClass, mCharacterValueOf, (jchar)combiningCodepoint));
                    CELL_JNI_EXCEPTION_BREAK(env);
                    env->CallBooleanMethod(combiningList, mArrayListAdd, charObj.get());
                    CELL_JNI_EXCEPTION_BREAK(env);
                } else {
                    // Combining character is also a surrogate pair
                    combiningCodepoint -= 0x10000;
                    jchar highSurr = (jchar)(0xD800 + (combiningCodepoint >> 10));
                    jchar lowSurr = (jchar)(0xDC00 + (combiningCodepoint & 0x3FF));

                    ScopedLocalRef<jobject> highObj(env, env->CallStaticObjectMethod(mCharacterClass, mCharacterValueOf, highSurr));
                    CELL_JNI_EXCEPTION_BREAK(env);
                    env->CallBooleanMethod(combiningList, mArrayListAdd, highObj.get());
                    CELL_JNI_EXCEPTION_BREAK(env);

                    ScopedLocalRef<jobject> lowObj(env, env->CallStaticObjectMethod(mCharacterClass, mCharacterValueOf, lowSurr));
                    CELL_JNI_EXCEPTION_BREAK(env);
                    env->CallBooleanMethod(combiningList, mArrayListAdd, lowObj.get());
                    CELL_JNI_EXCEPTION_BREAK(env);
                }
            }
        }

        // Resolve colors
        uint8_t fgRed, fgGreen, fgBlue;
        uint8_t bgRed, bgGreen, bgBlue;
        resolveColor(cell.fg, fgRed, fgGreen, fgBlue);
        resolveColor(cell.bg, bgRed, bgGreen, bgBlue);

        // Create ScreenCell object using cached class/constructor
        // Signature: (CLjava/util/List;IIIIIIZZIZZI)V
        // Parameters: char, combiningChars, fgRed, fgGreen, fgBlue, bgRed, bgGreen, bgBlue,
        //             bold, italic, underline, reverse, strike, width
        ScopedLocalRef<jobject> screenCell(env, env->NewObject(mScreenCellClass, mScreenCellConstructor,
            primaryChar,                    // char
            combiningList.get(),            // combiningChars: List<Char>
            (jint)fgRed,                    // fgRed
            (jint)fgGreen,                  // fgGreen
            (jint)fgBlue,                   // fgBlue
            (jint)bgRed,                    // bgRed
            (jint)bgGreen,                  // bgGreen
            (jint)bgBlue,                   // bgBlue
            (jboolean)cell.attrs.bold,      // bold (Z)
            (jboolean)cell.attrs.italic,    // italic (Z)
            (jint)cell.attrs.underline,     // underline (I)
            (jboolean)cell.attrs.reverse,   // reverse (Z)
            (jboolean)cell.attrs.strike,    // strike (Z)
            (jint)cell.width                // width (I)
        ));

        screenCells.push_back(std::move(screenCell));

        // Skip next cell if this is a fullwidth character
        if (cell.width == 2) {
            i++;  // Skip the placeholder cell
        }
    }

#undef CELL_JNI_EXCEPTION_CONTINUE
#undef CELL_JNI_EXCEPTION_BREAK

    // Create array with actual cell count
    int actualCells = screenCells.size();
    ScopedLocalRef<jobjectArray> actualCellArray(env, env->NewObjectArray(actualCells, mScreenCellClass, nullptr));
    for (int i = 0; i < actualCells; i++) {
        env->SetObjectArrayElement(actualCellArray, i, screenCells[i].get());
    }

    // Call the Java callback with actual cell count and soft wrap status
    env->CallIntMethod(mCallbacks, mPushScrollbackMethod, actualCells, actualCellArray.get(), (jboolean)softWrapped);
    JNI_CHECK_EXCEPTION(env);
}

int Terminal::invokePopScrollbackLine(int cols, VTermScreenCell* cells) {
    if (!mPopScrollbackMethod) {
        return 0;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return 0;
    }

    ScopedLocalRef<jclass> screenCellClass(env, env->FindClass("org/connectbot/terminal/ScreenCell"));
    if (!screenCellClass.get()) {
        LOGE("Failed to find ScreenCell class");
        return 0;
    }

    ScopedLocalRef<jobjectArray> cellArray(env, env->NewObjectArray(cols, screenCellClass, nullptr));
    if (!cellArray.get()) {
        LOGE("Failed to create cell array");
        return 0;
    }

    jint result = env->CallIntMethod(mCallbacks, mPopScrollbackMethod, cols, cellArray.get());
    JNI_CHECK_EXCEPTION_RETURN(env, 0);

    if (result == 0) {
        return 0;
    }

    // Get field IDs for ScreenCell
    jfieldID charField = env->GetFieldID(screenCellClass, "char", "C");
    jfieldID combiningCharsField = env->GetFieldID(screenCellClass, "combiningChars", "Ljava/util/List;");
    jfieldID fgRedField = env->GetFieldID(screenCellClass, "fgRed", "I");
    jfieldID fgGreenField = env->GetFieldID(screenCellClass, "fgGreen", "I");
    jfieldID fgBlueField = env->GetFieldID(screenCellClass, "fgBlue", "I");
    jfieldID bgRedField = env->GetFieldID(screenCellClass, "bgRed", "I");
    jfieldID bgGreenField = env->GetFieldID(screenCellClass, "bgGreen", "I");
    jfieldID bgBlueField = env->GetFieldID(screenCellClass, "bgBlue", "I");
    jfieldID boldField = env->GetFieldID(screenCellClass, "bold", "Z");
    jfieldID italicField = env->GetFieldID(screenCellClass, "italic", "Z");
    jfieldID underlineField = env->GetFieldID(screenCellClass, "underline", "I");
    jfieldID reverseField = env->GetFieldID(screenCellClass, "reverse", "Z");
    jfieldID strikeField = env->GetFieldID(screenCellClass, "strike", "Z");
    jfieldID widthField = env->GetFieldID(screenCellClass, "width", "I");

    ScopedLocalRef<jclass> listClass(env, env->FindClass("java/util/List"));
    jmethodID listSize = env->GetMethodID(listClass, "size", "()I");
    jmethodID listGet = env->GetMethodID(listClass, "get", "(I)Ljava/lang/Object;");
    ScopedLocalRef<jclass> charClass(env, env->FindClass("java/lang/Character"));
    jmethodID charValue = env->GetMethodID(charClass, "charValue", "()C");

    // Convert Java ScreenCell array to VTermScreenCell
    for (int i = 0; i < cols; i++) {
        ScopedLocalRef<jobject> screenCell(env, env->GetObjectArrayElement(cellArray, i));
        if (!screenCell.get()) {
            // Initialize empty cell
            VTermScreenCell& cell = cells[i];
            cell.chars[0] = ' ';
            for (int j = 1; j < VTERM_MAX_CHARS_PER_CELL; j++) {
                cell.chars[j] = 0;
            }
            cell.width = 1;
            cell.attrs.bold = 0;
            cell.attrs.italic = 0;
            cell.attrs.underline = 0;
            cell.attrs.reverse = 0;
            cell.attrs.strike = 0;
            vterm_color_rgb(&cell.fg, 192, 192, 192);
            vterm_color_rgb(&cell.bg, 0, 0, 0);
            continue;
        }

        VTermScreenCell& cell = cells[i];

        // Get primary character
        jchar primaryChar = env->GetCharField(screenCell, charField);
        cell.chars[0] = primaryChar;

        // Get combining characters
        ScopedLocalRef<jobject> combiningList(env, env->GetObjectField(screenCell, combiningCharsField));
        int charIndex = 1;
        if (combiningList.get()) {
            jint listLen = env->CallIntMethod(combiningList, listSize);
            JNI_CHECK_EXCEPTION_RETURN(env, 0);
            for (int j = 0; j < listLen && charIndex < VTERM_MAX_CHARS_PER_CELL; j++) {
                ScopedLocalRef<jobject> charObj(env, env->CallObjectMethod(combiningList, listGet, j));
                JNI_CHECK_EXCEPTION_RETURN(env, 0);
                if (charObj.get()) {
                    cell.chars[charIndex++] = env->CallCharMethod(charObj, charValue);
                    JNI_CHECK_EXCEPTION_RETURN(env, 0);
                }
            }
        }
        // Fill remaining with zeros
        for (; charIndex < VTERM_MAX_CHARS_PER_CELL; charIndex++) {
            cell.chars[charIndex] = 0;
        }

        // Get colors
        uint8_t fgRed = env->GetIntField(screenCell, fgRedField);
        uint8_t fgGreen = env->GetIntField(screenCell, fgGreenField);
        uint8_t fgBlue = env->GetIntField(screenCell, fgBlueField);
        uint8_t bgRed = env->GetIntField(screenCell, bgRedField);
        uint8_t bgGreen = env->GetIntField(screenCell, bgGreenField);
        uint8_t bgBlue = env->GetIntField(screenCell, bgBlueField);
        vterm_color_rgb(&cell.fg, fgRed, fgGreen, fgBlue);
        vterm_color_rgb(&cell.bg, bgRed, bgGreen, bgBlue);

        // Get attributes
        cell.attrs.bold = env->GetBooleanField(screenCell, boldField);
        cell.attrs.italic = env->GetBooleanField(screenCell, italicField);
        cell.attrs.underline = env->GetIntField(screenCell, underlineField);
        cell.attrs.reverse = env->GetBooleanField(screenCell, reverseField);
        cell.attrs.strike = env->GetBooleanField(screenCell, strikeField);
        cell.width = env->GetIntField(screenCell, widthField);
    }

    return 1;
}

void Terminal::invokeClearScrollback() {
    if (!mClearScrollbackMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    env->CallIntMethod(mCallbacks, mClearScrollbackMethod);
    JNI_CHECK_EXCEPTION(env);
}

void Terminal::invokeKeyboardOutput(const char* data, size_t len) {
    if (!mKeyboardInputMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    ScopedLocalRef<jbyteArray> array(env, env->NewByteArray(len));
    env->SetByteArrayRegion(array, 0, len, reinterpret_cast<const jbyte*>(data));

    env->CallIntMethod(mCallbacks, mKeyboardInputMethod, array.get());
    JNI_CHECK_EXCEPTION(env);
}

int Terminal::invokeOscSequence(int command, const std::string& payload, int cursorRow, int cursorCol) {
    LOGD("invokeOscSequence: command=%d, payload='%s' (len=%zu), cursor=(%d,%d)",
         command, payload.c_str(), payload.length(), cursorRow, cursorCol);

    if (!mOscSequenceMethod) {
        LOGE("invokeOscSequence: mOscSequenceMethod is null");
        return 0;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        LOGE("invokeOscSequence: Failed to get JNI env");
        return 0;
    }

    ScopedLocalRef<jstring> payloadStr(env, env->NewStringUTF(payload.c_str()));
    if (!payloadStr.get()) {
        LOGE("Failed to create jstring for OSC payload");
        return 0;
    }

    jint result = env->CallIntMethod(mCallbacks, mOscSequenceMethod, command, payloadStr.get(), cursorRow, cursorCol);
    JNI_CHECK_EXCEPTION_RETURN(env, 0);
    LOGD("invokeOscSequence: Java callback returned %d", result);

    return result;
}

// Line info retrieval
bool Terminal::getLineContinuation(int row) {
    std::lock_guard<std::recursive_mutex> lock(mLock);

    if (!mVt || row < 0 || row >= mRows) {
        return false;
    }

    VTermState* state = vterm_obtain_state(mVt);
    if (!state) {
        return false;
    }

    const VTermLineInfo* lineInfo = vterm_state_get_lineinfo(state, row);
    if (!lineInfo) {
        return false;
    }

    return lineInfo->continuation != 0;
}

// Helper functions
bool Terminal::cellStyleEqual(const VTermScreenCell& a, const VTermScreenCell& b) {
    return memcmp(&a.fg, &b.fg, sizeof(VTermColor)) == 0 &&
           memcmp(&a.bg, &b.bg, sizeof(VTermColor)) == 0 &&
           a.attrs.bold == b.attrs.bold &&
           a.attrs.underline == b.attrs.underline &&
           a.attrs.italic == b.attrs.italic &&
           a.attrs.blink == b.attrs.blink &&
           a.attrs.reverse == b.attrs.reverse &&
           a.attrs.strike == b.attrs.strike &&
           a.attrs.font == b.attrs.font &&
           a.attrs.dwl == b.attrs.dwl &&
           a.attrs.dhl == b.attrs.dhl;
}

void Terminal::resolveColor(const VTermColor& color, uint8_t& r, uint8_t& g, uint8_t& b) {
    if (VTERM_COLOR_IS_INDEXED(&color)) {
        // Get color from palette
        VTermColor resolved;
        VTermState* state = vterm_obtain_state(mVt);
        vterm_state_get_palette_color(state, color.indexed.idx, &resolved);
        r = resolved.rgb.red;
        g = resolved.rgb.green;
        b = resolved.rgb.blue;
    } else if (VTERM_COLOR_IS_RGB(&color)) {
        r = color.rgb.red;
        g = color.rgb.green;
        b = color.rgb.blue;
    } else if (VTERM_COLOR_IS_DEFAULT_FG(&color)) {
        // Get configured default foreground from libvterm
        VTermState* state = vterm_obtain_state(mVt);
        VTermColor fg, bg;
        vterm_state_get_default_colors(state, &fg, &bg);
        r = fg.rgb.red;
        g = fg.rgb.green;
        b = fg.rgb.blue;
    } else if (VTERM_COLOR_IS_DEFAULT_BG(&color)) {
        // Get configured default background from libvterm
        VTermState* state = vterm_obtain_state(mVt);
        VTermColor fg, bg;
        vterm_state_get_default_colors(state, &fg, &bg);
        r = bg.rgb.red;
        g = bg.rgb.green;
        b = bg.rgb.blue;
    } else {
        // Fallback
        r = g = b = 128;
    }
}

// JNI function implementations
extern "C" {

JNIEXPORT jlong JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeInit(JNIEnv* env, jobject /* thiz */, jobject callbacks) {
    auto* term = new Terminal(env, callbacks);
    return reinterpret_cast<jlong>(term);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeDestroy(JNIEnv* /* env */, jobject /* thiz */, jlong ptr) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    delete term;
    return 0;
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeWriteInputBuffer(JNIEnv* env, jobject /* thiz */,
                                                                   jlong ptr, jobject buffer, jint length) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    const auto* data = static_cast<const uint8_t*>(
        env->GetDirectBufferAddress(buffer));
    if (!data) {
        return 0;
    }
    return term->writeInput(data, length);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeWriteInputArray(JNIEnv* env, jobject /* thiz */,
                                                                  jlong ptr, jbyteArray data, jint offset, jint length) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    int result = term->writeInput(
        reinterpret_cast<const uint8_t*>(bytes + offset), length);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeResize(JNIEnv* /* env */, jobject /* thiz */,
                                                         jlong ptr, jint rows, jint cols) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    return term->resize(rows, cols);
}

JNIEXPORT jboolean JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeDispatchKey(JNIEnv* /* env */, jobject /* thiz */,
                                                              jlong ptr, jint modifiers, jint key) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    return term->dispatchKey(modifiers, key);
}

JNIEXPORT jboolean JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeDispatchCharacter(JNIEnv* /* env */, jobject /* thiz */,
                                                                    jlong ptr, jint modifiers, jint character) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    return term->dispatchCharacter(modifiers, character);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeGetCellRun(JNIEnv* env, jobject /* thiz */,
                                                             jlong ptr, jint row, jint col, jobject runObject) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    return term->getCellRun(env, row, col, runObject);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeSetPaletteColors(JNIEnv* env, jobject /* thiz */,
                                                                   jlong ptr, jintArray colors, jint count) {
    auto* term = reinterpret_cast<Terminal*>(ptr);

    // Get array elements
    jint* colorData = env->GetIntArrayElements(colors, nullptr);
    if (!colorData) {
        LOGE("nativeSetPaletteColors: Failed to get array elements");
        return -1;
    }

    // Convert to uint32_t array and call native method
    int result = term->setPaletteColors(reinterpret_cast<const uint32_t*>(colorData), count);

    // Release array (JNI_ABORT = don't copy back, read-only)
    env->ReleaseIntArrayElements(colors, colorData, JNI_ABORT);

    return result;
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeSetDefaultColors(JNIEnv* /* env */, jobject /* thiz */,
                                                                   jlong ptr, jint fgColor, jint bgColor) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    return term->setDefaultColors(static_cast<uint32_t>(fgColor), static_cast<uint32_t>(bgColor));
}

JNIEXPORT jboolean JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeGetLineContinuation(JNIEnv* /* env */, jobject /* thiz */,
                                                                       jlong ptr, jint row) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    return term->getLineContinuation(row);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeSetBoldHighbright(JNIEnv* /* env */, jobject /* thiz */,
                                                                     jlong ptr, jboolean enabled) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    return term->setBoldHighbright(enabled ? 1 : 0);
}

} // extern "C"
