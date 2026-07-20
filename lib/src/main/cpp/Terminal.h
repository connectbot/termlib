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
#include <mutex>

template<typename T>
class ScopedLocalRef {
public:
    ScopedLocalRef(JNIEnv* env, T ref) : mEnv(env), mRef(ref) {}
    ~ScopedLocalRef() { if (mRef) mEnv->DeleteLocalRef(mRef); }
    T get() const { return mRef; }
    operator T() const { return mRef; }
    ScopedLocalRef(ScopedLocalRef&& other) noexcept : mEnv(other.mEnv), mRef(other.mRef) {
        other.mRef = nullptr;
    }
    ScopedLocalRef(const ScopedLocalRef&) = delete;
    ScopedLocalRef& operator=(ScopedLocalRef&& other) noexcept {
        if (mRef) mEnv->DeleteLocalRef(mRef);
        mEnv = other.mEnv;
        mRef = other.mRef;
        other.mRef = nullptr;
        return *this;
    }
private:
    JNIEnv* mEnv;
    T mRef;
};

class Terminal {
public:
    Terminal(JNIEnv* env, jobject callbacks, int rows = 24, int cols = 80);
    ~Terminal();
    bool ready() const { return mVt && mVts; }

    // Input handling - receives data from PTY/transport
    int writeInput(const uint8_t* data, size_t length);

    // Terminal control
    int resize(int rows, int cols);

    // Keyboard input - generates escape sequences
    bool dispatchKey(int modifiers, int key);
    bool dispatchCharacter(int modifiers, int codepoint);

    // Cell data retrieval for rendering
    int getCells(JNIEnv* env, jobject buffer, int requests);

    // Color configuration
    int setPaletteColors(const uint32_t* colors, int count);
    int setDefaultColors(uint32_t fgColor, uint32_t bgColor);

    // Terminal behavior options
    int setBoldHighbright(int enabled);

private:
    // libvterm screen callbacks (called by libvterm while mLock may be held).
    // Implementations must not synchronously call back into Terminal methods.
    static int termDamage(VTermRect rect, void* user);
    static int termMoverect(VTermRect dest, VTermRect src, void* user);
    static int termMovecursor(VTermPos pos, VTermPos oldpos, int visible, void* user);
    static int termSettermprop(VTermProp prop, VTermValue* val, void* user);
    static int termBell(void* user);
    static int termSbPushline(int cols, const VTermScreenCell* cells, void* user);
    static int termSbPopline(int cols, VTermScreenCell* cells, void* user);
    static int termSbClear(void* user);

    // libvterm output callback (keyboard generates this)
    static void termOutput(const char* s, size_t len, void* user);

    // libvterm state fallback for OSC sequences. Same no-synchronous-reentry
    // rule as screen callbacks.
    static int termOscFallback(int command, VTermStringFragment frag, void* user);
    static int termApcFallback(VTermStringFragment frag, void* user);
    static int termCsiFallback(const char* leader, const long args[], int argcount, const char* intermed, char command, void* user);
    static int termControlFallback(unsigned char control, void* user);
    static int termImageResize(int buffer, int delta, int rows, int cols, void* user);
    static int termImageEdit(VTermRect rect, void* user);
    static int termImageScroll(VTermRect rect, int downward, int rightward, void* user);
    static int termImageClear(void* user);
    int imageEdit(int kind, VTermRect rect, int downward = 0, int rightward = 0);
    jlong imageFragment(bool kitty, VTermStringFragment frag);
    bool mReservingImage = false;
    bool mImageTracking = false;
    bool mOsc1337Image = false;
    jmethodID mImageFragmentMethod{};
    jmethodID mImageEditMethod{};
    jmethodID mImageQueryMethod{};

    // libvterm selection callbacks for OSC 52 clipboard. Same no-synchronous-
    // reentry rule as screen callbacks.
    static int termSelectionSet(VTermSelectionMask mask, VTermStringFragment frag, void* user);
    static int termSelectionQuery(VTermSelectionMask mask, void* user);

    // Java callback invocation helpers
    void invokeDamage(int startRow, int endRow, int startCol, int endCol);
    int invokeMoverect(VTermRect dest, VTermRect src);
    void invokeMoveCursor(int row, int col, int oldRow, int oldCol, bool visible);
    void invokeSetTermProp(VTermProp prop, VTermValue* val);
    void invokeBell();
    void invokePushScrollbackLine(int cols, const VTermScreenCell* cells, bool softWrapped);
    int invokePopScrollbackLine(int cols, VTermScreenCell* cells);
    void invokeClearScrollback();
    void invokeKeyboardOutput(const char* data, size_t len);
    int invokeTextFragment(int kind, int command, VTermStringFragment frag, int row, int col);
    void packCell(const VTermScreenCell& cell, jint* out);

    // Helper functions
    void resolveColor(const VTermColor& color, uint8_t& r, uint8_t& g, uint8_t& b);

    // libvterm state
    VTerm* mVt{};
    VTermScreen* mVts{};
    VTermScreenCallbacks mScreenCallbacks{};
    VTermStateFallbacks mStateFallbacks{};
    VTermSelectionCallbacks mSelectionCallbacks{};

    // Selection buffer for OSC 52 clipboard (libvterm uses this for base64 decoding)
    static constexpr size_t SELECTION_BUFFER_SIZE = 8192;
    char mSelectionBuffer[SELECTION_BUFFER_SIZE]{};

    VTermPos mOscCursorPos{0, 0};  // Cursor position when OSC sequence started

    // Terminal dimensions
    int mRows;
    int mCols;

    // Java callback object and method IDs
    JavaVM* mJavaVM{};
    jobject mCallbacks{};  // Global reference
    jmethodID mDamageMethod{};
    jmethodID mMoverectMethod{};
    jmethodID mMoveCursorMethod{};
    jmethodID mSetTermPropMethod{};
    jmethodID mBellMethod{};
    jmethodID mPushScrollbackMethod{};
    jmethodID mPopScrollbackMethod{};
    jmethodID mClearScrollbackMethod{};
    jmethodID mKeyboardInputMethod{};
    jmethodID mTextFragmentMethod{};
    jmethodID mCellBufferMethod{};

    // Cached Java classes and methods for callbacks (avoid FindClass/GetMethodID overhead)
    jclass mTermRectClass{};
    jmethodID mTermRectConstructor{};
    jclass mCursorPositionClass{};
    jmethodID mCursorPositionConstructor{};
    jclass mTerminalPropertyBoolClass{};
    jmethodID mTerminalPropertyBoolConstructor{};
    jclass mTerminalPropertyIntClass{};
    jmethodID mTerminalPropertyIntConstructor{};
    jclass mTerminalPropertyColorClass{};
    jmethodID mTerminalPropertyColorConstructor{};

    // Thread safety. Native entrypoints are serialized by this non-recursive
    // mutex. Java callbacks invoked from libvterm must not synchronously call
    // back into Terminal methods; post/defer any work that needs native state.
    mutable std::mutex mLock;
};

#endif // TERMSCREEN_TERMINAL_H
