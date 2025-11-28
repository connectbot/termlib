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
#include <android/log.h>
#include <cstring>
#include <algorithm>
#include <vector>

#define LOG_TAG "TermNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Terminal implementation
Terminal::Terminal(JNIEnv* env, jobject callbacks, int rows, int cols, int scrollRows)
    : mRows(rows), mCols(cols), mScrollRows(scrollRows) {

    LOGD("Terminal constructor: rows=%d, cols=%d, scrollRows=%d", rows, cols, scrollRows);

    // Get JavaVM for callback invocations from any thread
    env->GetJavaVM(&mJavaVM);

    // Store global reference to callbacks
    mCallbacks = env->NewGlobalRef(callbacks);

    // Cache method IDs
    jclass callbacksClass = env->GetObjectClass(callbacks);
    mDamageMethod = env->GetMethodID(callbacksClass, "damage", "(IIII)I");
    if (!mDamageMethod || env->ExceptionCheck()) {
        LOGE("Failed to find damage method");
        env->ExceptionClear();
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
        "(I[Lorg/connectbot/terminal/ScreenCell;)I");
    if (!mPushScrollbackMethod) {
        LOGE("Failed to find pushScrollbackLine method");
    }
    mPopScrollbackMethod = env->GetMethodID(callbacksClass, "popScrollbackLine",
        "(I[Lorg/connectbot/terminal/ScreenCell;)I");
    if (!mPopScrollbackMethod) {
        LOGE("Failed to find popScrollbackLine method");
    }
    mKeyboardInputMethod = env->GetMethodID(callbacksClass, "onKeyboardInput", "([B)I");
    if (!mKeyboardInputMethod) {
        LOGE("Failed to find onKeyboardInput method");
    }

    // Cache CellRun class and field IDs
    mCellRunClass = (jclass)env->NewGlobalRef(
        env->FindClass("org/connectbot/terminal/CellRun"));

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
        .sb_clear = nullptr  // Not needed
    };
    vterm_screen_set_callbacks(mVts, &mScreenCallbacks, this);

    // Configure damage merging
    vterm_screen_set_damage_merge(mVts, VTERM_DAMAGE_SCROLL);

    // Reset and initialize
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
int Terminal::resize(int rows, int cols, int scrollRows) {
    std::lock_guard<std::recursive_mutex> lock(mLock);

    mRows = rows;
    mCols = cols;
    mScrollRows = scrollRows;

    if (mVt) {
        vterm_set_size(mVt, rows, cols);
        vterm_screen_flush_damage(mVts);
    }

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

    // Set character array
    jcharArray charArray = env->NewCharArray(runLength);
    env->SetCharArrayRegion(charArray, 0, runLength, chars);
    env->SetObjectField(runObject, mCharsField, charArray);
    env->DeleteLocalRef(charArray);

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
    // Not currently used, but could optimize scrolling
    return 1;
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
    term->invokePushScrollbackLine(cols, cells);
    return 1;
}

int Terminal::termSbPopline(int cols, VTermScreenCell* cells, void* user) {
    // Note: We don't support popping from scrollback because:
    // 1. Scrollback is managed in Kotlin layer
    // 2. Calling back to Kotlin here would cause deadlock (mutex is held)
    // 3. libvterm rarely needs to pop scrollback
    // If we need this in the future, we'd need to defer the callback or use a different approach
    return 0;  // Indicate no scrollback available
}

void Terminal::termOutput(const char* s, size_t len, void* user) {
    auto* term = static_cast<Terminal*>(user);
    term->invokeKeyboardOutput(s, len);
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
}

void Terminal::invokeMoveCursor(int row, int col, int oldRow, int oldCol, bool visible) {
    if (!mMoveCursorMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    // Create CursorPosition objects
    jclass posClass = env->FindClass("org/connectbot/terminal/CursorPosition");
    if (!posClass) {
        return;
    }

    jmethodID posConstructor = env->GetMethodID(posClass, "<init>", "(II)V");
    if (!posConstructor) {
        env->DeleteLocalRef(posClass);
        return;
    }

    jobject posObj = env->NewObject(posClass, posConstructor, row, col);
    jobject oldPosObj = env->NewObject(posClass, posConstructor, oldRow, oldCol);

    env->CallIntMethod(mCallbacks, mMoveCursorMethod, posObj, oldPosObj, visible);

    env->DeleteLocalRef(posObj);
    env->DeleteLocalRef(oldPosObj);
    env->DeleteLocalRef(posClass);
}

void Terminal::invokeSetTermProp(VTermProp prop, VTermValue* val) {
    if (!mSetTermPropMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    jobject propValue = nullptr;
    jclass propClass = nullptr;
    jmethodID constructor = nullptr;

    switch (vterm_get_prop_type(prop)) {
        case VTERM_VALUETYPE_BOOL:
            propClass = env->FindClass("org/connectbot/terminal/TerminalProperty$BoolValue");
            constructor = env->GetMethodID(propClass, "<init>", "(Z)V");
            propValue = env->NewObject(propClass, constructor, val->boolean);
            break;

        case VTERM_VALUETYPE_INT:
            propClass = env->FindClass("org/connectbot/terminal/TerminalProperty$IntValue");
            constructor = env->GetMethodID(propClass, "<init>", "(I)V");
            propValue = env->NewObject(propClass, constructor, val->number);
            break;

        case VTERM_VALUETYPE_STRING:
            propClass = env->FindClass("org/connectbot/terminal/TerminalProperty$StringValue");
            constructor = env->GetMethodID(propClass, "<init>", "(Ljava/lang/String;)V");
            if (val->string.str) {
                // VTermStringFragment has str and len fields
                jstring str = env->NewStringUTF(val->string.str);
                propValue = env->NewObject(propClass, constructor, str);
                env->DeleteLocalRef(str);
            }
            break;

        case VTERM_VALUETYPE_COLOR: {
            propClass = env->FindClass("org/connectbot/terminal/TerminalProperty$ColorValue");
            constructor = env->GetMethodID(propClass, "<init>", "(III)V");
            // Resolve color to RGB
            uint8_t r, g, b;
            resolveColor(val->color, r, g, b);
            propValue = env->NewObject(propClass, constructor, r, g, b);
            break;
        }

        case VTERM_N_VALUETYPES:
            // Not a real value type, just for array sizing
            break;
    }

    if (propValue) {
        env->CallIntMethod(mCallbacks, mSetTermPropMethod, prop, propValue);
        env->DeleteLocalRef(propValue);
    }

    if (propClass) {
        env->DeleteLocalRef(propClass);
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
}

void Terminal::invokePushScrollbackLine(int cols, const VTermScreenCell* cells) {
    if (!mPushScrollbackMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    // Find ScreenCell class
    jclass screenCellClass = env->FindClass("org/connectbot/terminal/ScreenCell");
    if (!screenCellClass) {
        LOGE("Failed to find ScreenCell class");
        return;
    }

    // Get constructor for ScreenCell
    jmethodID screenCellConstructor = env->GetMethodID(screenCellClass, "<init>",
        "(CLjava/util/List;IIIIIIZZIZZI)V");
    if (!screenCellConstructor) {
        LOGE("Failed to find ScreenCell constructor");
        env->DeleteLocalRef(screenCellClass);
        return;
    }

    // Get ArrayList class for combining chars
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID arrayListConstructor = env->GetMethodID(arrayListClass, "<init>", "()V");
    jmethodID arrayListAdd = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    // Get Character class for boxing chars
    jclass charClass = env->FindClass("java/lang/Character");
    jmethodID charValueOf = env->GetStaticMethodID(charClass, "valueOf", "(C)Ljava/lang/Character;");

    // Build a list to hold actual cells (excluding fullwidth placeholders)
    std::vector<jobject> screenCells;

    for (int i = 0; i < cols; i++) {
        const VTermScreenCell& cell = cells[i];

        // Get the primary character and handle surrogate pairs
        jchar primaryChar = ' ';
        jobject combiningList = env->NewObject(arrayListClass, arrayListConstructor);

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

                // Add low surrogate to combining chars
                jobject lowSurrogateObj = env->CallStaticObjectMethod(charClass, charValueOf, lowSurrogate);
                env->CallBooleanMethod(combiningList, arrayListAdd, lowSurrogateObj);
                env->DeleteLocalRef(lowSurrogateObj);
            }

            // Add any actual combining characters (chars[1] onwards)
            for (int j = 1; j < VTERM_MAX_CHARS_PER_CELL && cell.chars[j] != 0; j++) {
                uint32_t combiningCodepoint = cell.chars[j];

                if (combiningCodepoint <= 0xFFFF) {
                    jobject charObj = env->CallStaticObjectMethod(charClass, charValueOf, (jchar)combiningCodepoint);
                    env->CallBooleanMethod(combiningList, arrayListAdd, charObj);
                    env->DeleteLocalRef(charObj);
                } else {
                    // Combining character is also a surrogate pair
                    combiningCodepoint -= 0x10000;
                    jchar highSurr = (jchar)(0xD800 + (combiningCodepoint >> 10));
                    jchar lowSurr = (jchar)(0xDC00 + (combiningCodepoint & 0x3FF));

                    jobject highObj = env->CallStaticObjectMethod(charClass, charValueOf, highSurr);
                    env->CallBooleanMethod(combiningList, arrayListAdd, highObj);
                    env->DeleteLocalRef(highObj);

                    jobject lowObj = env->CallStaticObjectMethod(charClass, charValueOf, lowSurr);
                    env->CallBooleanMethod(combiningList, arrayListAdd, lowObj);
                    env->DeleteLocalRef(lowObj);
                }
            }
        }

        // Resolve colors
        uint8_t fgRed, fgGreen, fgBlue;
        uint8_t bgRed, bgGreen, bgBlue;
        resolveColor(cell.fg, fgRed, fgGreen, fgBlue);
        resolveColor(cell.bg, bgRed, bgGreen, bgBlue);

        // Create ScreenCell object
        // Signature: (CLjava/util/List;IIIIIIZZIZZI)V
        // Parameters: char, combiningChars, fgRed, fgGreen, fgBlue, bgRed, bgGreen, bgBlue,
        //             bold, italic, underline, reverse, strike, width
        jobject screenCell = env->NewObject(screenCellClass, screenCellConstructor,
            primaryChar,                    // char
            combiningList,                  // combiningChars: List<Char>
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
        );

        // Add to vector (will be converted to array later)
        screenCells.push_back(screenCell);
        env->DeleteLocalRef(combiningList);

        // Skip next cell if this is a fullwidth character
        if (cell.width == 2) {
            i++;  // Skip the placeholder cell
        }
    }

    // Create array with actual cell count
    int actualCells = screenCells.size();
    jobjectArray actualCellArray = env->NewObjectArray(actualCells, screenCellClass, nullptr);
    for (int i = 0; i < actualCells; i++) {
        env->SetObjectArrayElement(actualCellArray, i, screenCells[i]);
        env->DeleteLocalRef(screenCells[i]);
    }

    // Call the Java callback with actual cell count
    env->CallIntMethod(mCallbacks, mPushScrollbackMethod, actualCells, actualCellArray);

    // Clean up
    env->DeleteLocalRef(actualCellArray);
    env->DeleteLocalRef(screenCellClass);
    env->DeleteLocalRef(arrayListClass);
    env->DeleteLocalRef(charClass);
}

void Terminal::invokeKeyboardOutput(const char* data, size_t len) {
    if (!mKeyboardInputMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    jbyteArray array = env->NewByteArray(len);
    env->SetByteArrayRegion(array, 0, len, reinterpret_cast<const jbyte*>(data));

    env->CallIntMethod(mCallbacks, mKeyboardInputMethod, array);

    env->DeleteLocalRef(array);
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
        // Use default foreground (white)
        r = g = b = 255;
    } else if (VTERM_COLOR_IS_DEFAULT_BG(&color)) {
        // Use default background (black)
        r = g = b = 0;
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
                                                         jlong ptr, jint rows, jint cols, jint scrollRows) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    return term->resize(rows, cols, scrollRows);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeGetRows(JNIEnv* /* env */, jobject /* thiz */, jlong ptr) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    return term->getRows();
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeGetCols(JNIEnv* /* env */, jobject /* thiz */, jlong ptr) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    return term->getCols();
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeGetScrollRows(JNIEnv* /* env */, jobject /* thiz */, jlong ptr) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    return term->getScrollRows();
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

} // extern "C"
