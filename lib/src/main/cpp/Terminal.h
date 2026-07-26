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
#include <string>

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

    // Input handling - receives data from PTY/transport
    int writeInput(const uint8_t* data, size_t length);

    // Terminal control
    int resize(int rows, int cols);

    // Keyboard input - generates escape sequences
    bool dispatchKey(int modifiers, int key);
    bool dispatchCharacter(int modifiers, int codepoint);

    // Mouse input - generates escape sequences only when the application has
    // requested mouse tracking (DECSET 1000/1002/1003). Encoding follows the
    // protocol the application selected (X10, UTF-8, SGR or rxvt).
    //
    // Coordinates are clamped to the screen; see positionMouseLocked().
    bool mouseMove(int row, int col, int modifiers);
    bool mouseButton(int row, int col, int button, bool pressed, int modifiers);
    bool mouseClick(int row, int col, int button, int modifiers);
    bool scrollWheel(int row, int col, int button, int steps, int modifiers);

    // Most wheel detents one scrollWheel() call may report. The loop runs with
    // mLock held and emits a report per iteration, so the bound belongs here
    // rather than with any particular caller.
    static constexpr int MAX_WHEEL_STEPS_PER_CALL = 32;

    // Cell data retrieval for rendering
    int getCellRun(JNIEnv* env, int row, int col, jobject runObject);

    // Line info retrieval (for continuation/soft-wrap detection)
    bool getLineContinuation(int row);

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

    // libvterm selection callbacks for OSC 52 clipboard. Same no-synchronous-
    // reentry rule as screen callbacks.
    static int termSelectionSet(VTermSelectionMask mask, VTermStringFragment frag, void* user);
    static int termSelectionQuery(VTermSelectionMask mask, void* user);

    // Property identifiers as sent to Java.
    //
    // VTermProp is an unnumbered C enum, so its ordinals shift whenever
    // upstream inserts a property. Translating by name here, where the compiler
    // checks the cases against vterm.h, means Java holds identifiers this repo
    // defines rather than a hand-copied snapshot of upstream's numbering.
    // Getting that copy wrong is not hypothetical: TITLE and CURSORSHAPE were
    // read at the wrong ordinals until 6ed03a6, which left OSC 0/2 and DECSCUSR
    // silently doing nothing.
    //
    // These are the wrapper's own protocol. Keep them in step with VTermProp in
    // TerminalEmulator.kt -- never with vterm.h.
    enum class PropCode : jint {
        Unknown = 0,
        CursorVisible = 1,
        CursorBlink = 2,
        AltScreen = 3,
        Title = 4,
        IconName = 5,
        Reverse = 6,
        CursorShape = 7,
        Mouse = 8,
        FocusReport = 9,
    };

    // Deliberately has no default case: a property added upstream then draws a
    // -Wswitch warning here, naming the enumerator, rather than silently
    // arriving in Java as the wrong identifier. (Warning, not error -- the
    // build sets no -Werror.)
    static PropCode toPropCode(VTermProp prop);

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
    int invokeOscSequence(int command, const std::string& payload, int cursorRow, int cursorCol);

    // Helper functions
    static bool cellStyleEqual(const VTermScreenCell& a, const VTermScreenCell& b);
    void resolveColor(const VTermColor& color, uint8_t& r, uint8_t& g, uint8_t& b);

    // The single path by which any mouse report reaches libvterm. Clamps
    // row/col to the screen and positions the pointer, returning the translated
    // modifiers for the caller's own report. Every public mouse method goes
    // through it, so none of them can report at a coordinate off the screen -
    // libvterm's X10 encoder clamps only the high end and would otherwise emit
    // a control byte into the PTY stream for a negative coordinate.
    //
    // Clamping against mRows/mCols under mLock, rather than against a size the
    // caller believes, means a resize racing a gesture cannot slip through.
    //
    // Caller must hold mLock and must have checked mVt.
    VTermModifier positionMouseLocked(int row, int col, int modifiers);

    // Collects everything libvterm emits within its scope and delivers it as a
    // single keyboard-output callback.
    //
    // libvterm has no output buffer installed, so each report it generates
    // upcalls into Java on its own - a jbyteArray, a JNI call and a Message for
    // six bytes. That is fine for a keystroke but wasteful for a wheel burst,
    // which produces one report per detent. Concatenating them changes nothing
    // about the bytes reaching the PTY, only how many trips they take.
    //
    // Construct under mLock, after checking mVt.
    class CoalescedOutput {
    public:
        explicit CoalescedOutput(Terminal* term);
        ~CoalescedOutput();

        CoalescedOutput(const CoalescedOutput&) = delete;
        CoalescedOutput& operator=(const CoalescedOutput&) = delete;

    private:
        Terminal* mTerm;
        std::string mBuffer;
    };

    // Where termOutput() sends bytes while a CoalescedOutput is in scope; null
    // means upcall immediately. Guarded by mLock like the rest of the output
    // path.
    std::string* mOutputSink{nullptr};

    // libvterm state
    VTerm* mVt;
    VTermScreen* mVts;
    VTermScreenCallbacks mScreenCallbacks{};
    VTermStateFallbacks mStateFallbacks{};
    VTermSelectionCallbacks mSelectionCallbacks{};

    // Selection buffer for OSC 52 clipboard (libvterm uses this for base64 decoding)
    static constexpr size_t SELECTION_BUFFER_SIZE = 8192;
    char mSelectionBuffer[SELECTION_BUFFER_SIZE]{};
    std::string mSelectionData;  // Accumulates decoded clipboard data across fragments

    // OSC fallback buffer for accumulating fragmented OSC sequences (e.g., OSC 8 hyperlinks)
    std::string mOscData;  // Accumulates OSC payload across fragments
    int mOscCommand{-1};   // Current OSC command being accumulated
    VTermPos mOscCursorPos{0, 0};  // Cursor position when OSC sequence started

    // Terminal dimensions
    int mRows;
    int mCols;

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
    jmethodID mClearScrollbackMethod;
    jmethodID mKeyboardInputMethod;
    jmethodID mOscSequenceMethod;

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

    // Thread safety. Native entrypoints are serialized by this non-recursive
    // mutex. Java callbacks invoked from libvterm must not synchronously call
    // back into Terminal methods; post/defer any work that needs native state.
    mutable std::mutex mLock;
};

#endif // TERMSCREEN_TERMINAL_H
