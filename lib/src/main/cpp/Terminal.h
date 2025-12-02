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
#ifndef TERMSCREEN_TERMINAL_H
#define TERMSCREEN_TERMINAL_H

#include <jni.h>
#include <vterm.h>
#include <memory>
#include <mutex>

class Terminal {
public:
    Terminal(JNIEnv* env, jobject callbacks, int rows = 24, int cols = 80, int scrollRows = 100);
    ~Terminal();

    // Input handling - receives data from PTY/transport
    int writeInput(const uint8_t* data, size_t length);

    // Terminal control
    int resize(int rows, int cols, int scrollRows);
    int getRows() const { return mRows; }
    int getCols() const { return mCols; }
    int getScrollRows() const { return mScrollRows; }

    // Keyboard input - generates escape sequences
    bool dispatchKey(int modifiers, int key);
    bool dispatchCharacter(int modifiers, int codepoint);

    // Cell data retrieval for rendering
    int getCellRun(JNIEnv* env, int row, int col, jobject runObject);

    // Color configuration
    int setPaletteColors(const uint32_t* colors, int count);
    int setDefaultColors(uint32_t fgColor, uint32_t bgColor);

private:
    // libvterm screen callbacks (called by libvterm)
    static int termDamage(VTermRect rect, void* user);
    static int termMoverect(VTermRect dest, VTermRect src, void* user);
    static int termMovecursor(VTermPos pos, VTermPos oldpos, int visible, void* user);
    static int termSettermprop(VTermProp prop, VTermValue* val, void* user);
    static int termBell(void* user);
    static int termSbPushline(int cols, const VTermScreenCell* cells, void* user);
    static int termSbPopline(int cols, VTermScreenCell* cells, void* user);

    // libvterm output callback (keyboard generates this)
    static void termOutput(const char* s, size_t len, void* user);

    // Java callback invocation helpers
    void invokeDamage(int startRow, int endRow, int startCol, int endCol);
    int invokeMoverect(VTermRect dest, VTermRect src);
    void invokeMoveCursor(int row, int col, int oldRow, int oldCol, bool visible);
    void invokeSetTermProp(VTermProp prop, VTermValue* val);
    void invokeBell();
    void invokePushScrollbackLine(int cols, const VTermScreenCell* cells);
    int invokePopScrollbackLine(int cols, VTermScreenCell* cells);
    void invokeKeyboardOutput(const char* data, size_t len);

    // Helper functions
    static bool cellStyleEqual(const VTermScreenCell& a, const VTermScreenCell& b);
    void resolveColor(const VTermColor& color, uint8_t& r, uint8_t& g, uint8_t& b);

    // libvterm state
    VTerm* mVt;
    VTermScreen* mVts;
    VTermScreenCallbacks mScreenCallbacks{};

    // Terminal dimensions
    int mRows;
    int mCols;
    int mScrollRows;

    // Java callback object and method IDs
    JavaVM* mJavaVM{};
    jobject mCallbacks;  // Global reference
    jmethodID mDamageMethod;
    jmethodID mMoverectMethod;
    jmethodID mMoveCursorMethod;
    jmethodID mSetTermPropMethod;
    jmethodID mBellMethod;
    jmethodID mPushScrollbackMethod;
    jmethodID mPopScrollbackMethod;
    jmethodID mKeyboardInputMethod;

    // Cached Java class and field IDs for CellRun
    jclass mCellRunClass;
    jfieldID mFgRedField;
    jfieldID mFgGreenField;
    jfieldID mFgBlueField;
    jfieldID mBgRedField;
    jfieldID mBgGreenField;
    jfieldID mBgBlueField;
    jfieldID mBoldField;
    jfieldID mUnderlineField;
    jfieldID mItalicField;
    jfieldID mBlinkField;
    jfieldID mReverseField;
    jfieldID mStrikeField;
    jfieldID mFontField;
    jfieldID mDwlField;
    jfieldID mDhlField;
    jfieldID mCharsField;
    jfieldID mRunLengthField;

    // Cached Java classes and methods for callbacks (avoid FindClass/GetMethodID overhead)
    jclass mTermRectClass;
    jmethodID mTermRectConstructor;
    jclass mCursorPositionClass;
    jmethodID mCursorPositionConstructor;
    jclass mScreenCellClass;
    jmethodID mScreenCellConstructor;
    jclass mArrayListClass;
    jmethodID mArrayListConstructor;
    jmethodID mArrayListAdd;
    jclass mCharacterClass;
    jmethodID mCharacterValueOf;
    jclass mTerminalPropertyBoolClass;
    jmethodID mTerminalPropertyBoolConstructor;
    jclass mTerminalPropertyIntClass;
    jmethodID mTerminalPropertyIntConstructor;
    jclass mTerminalPropertyStringClass;
    jmethodID mTerminalPropertyStringConstructor;
    jclass mTerminalPropertyColorClass;
    jmethodID mTerminalPropertyColorConstructor;

    // Thread safety (recursive mutex for reentrant calls via callbacks)
    mutable std::recursive_mutex mLock;
};

#endif // TERMSCREEN_TERMINAL_H
