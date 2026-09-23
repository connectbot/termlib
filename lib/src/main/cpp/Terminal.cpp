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

#include <cstring>
#include <algorithm>
#include <limits>
#include <new>

#ifdef __ANDROID__
#  include <android/log.h>
#  define LOG_TAG "TermNative"
#  define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#  include <cstdio>
#  define LOG_TAG "TermNative"
#  define LOGE(...) do { fprintf(stderr, "[E/" LOG_TAG "] " __VA_ARGS__); fputc('\n', stderr); } while(0)
#endif

#define JNI_CHECK_EXCEPTION_RETURN(env, retval) \
    do { if ((env)->ExceptionCheck()) { return (retval); } } while (0)

// CellData.kt wire layout: sixteen code points, width, two RGB colors, packed flags.
// Flags use the explicit shifts below, never the native bitfield representation.
static constexpr int CELL_WIDTH = VTERM_MAX_CHARS_PER_CELL;
static constexpr int CELL_FG = CELL_WIDTH + 1;
static constexpr int CELL_BG = CELL_FG + 1;
static constexpr int CELL_FLAGS = CELL_BG + 1;
static constexpr int CELL_STRIDE = CELL_FLAGS + 1;
static constexpr int CELL_BYTES = CELL_STRIDE * sizeof(jint);
static constexpr int BUFFER_BYTES = 64 * 1024;
static constexpr int HEADER_BYTES = 4096;

// Immutable JNI metadata is shared by every terminal created from this library
// load. The global class references remain valid until JNI_OnUnload.
struct CallbackCache {
    jclass termRectClass{};
    jmethodID termRectConstructor{};
    jclass cursorPositionClass{};
    jmethodID cursorPositionConstructor{};
    jclass terminalPropertyBoolClass{};
    jmethodID terminalPropertyBoolConstructor{};
    jclass terminalPropertyIntClass{};
    jmethodID terminalPropertyIntConstructor{};
    jclass terminalPropertyColorClass{};
    jmethodID terminalPropertyColorConstructor{};
    jmethodID damageMethod{};
    jmethodID moverectMethod{};
    jmethodID moveCursorMethod{};
    jmethodID setTermPropMethod{};
    jmethodID bellMethod{};
    jmethodID pushScrollbackMethod{};
    jmethodID popScrollbackMethod{};
    jmethodID clearScrollbackMethod{};
    jmethodID keyboardInputMethod{};
    jmethodID textFragmentMethod{};
    jmethodID imageFragmentMethod{};
    jmethodID imageEditMethod{};
    jmethodID imageQueryMethod{};
    jmethodID cellBufferMethod{};
};

static CallbackCache gCallbackCache;

static jclass globalClass(JNIEnv* env, const char* name) {
    ScopedLocalRef<jclass> local(env, env->FindClass(name));
    if (!local.get()) return nullptr;
    return static_cast<jclass>(env->NewGlobalRef(local));
}

static void clearCallbackCache(JNIEnv* env) {
    if (gCallbackCache.termRectClass) env->DeleteGlobalRef(gCallbackCache.termRectClass);
    if (gCallbackCache.cursorPositionClass) env->DeleteGlobalRef(gCallbackCache.cursorPositionClass);
    if (gCallbackCache.terminalPropertyBoolClass) env->DeleteGlobalRef(gCallbackCache.terminalPropertyBoolClass);
    if (gCallbackCache.terminalPropertyIntClass) env->DeleteGlobalRef(gCallbackCache.terminalPropertyIntClass);
    if (gCallbackCache.terminalPropertyColorClass) env->DeleteGlobalRef(gCallbackCache.terminalPropertyColorClass);
    gCallbackCache = {};
}

static bool initializeCallbackCache(JNIEnv* env) {
    ScopedLocalRef<jclass> callbacksClass(
        env, env->FindClass("org/connectbot/terminal/TerminalCallbacks"));
    if (!callbacksClass.get()) return false;

    auto& cache = gCallbackCache;
    cache.damageMethod = env->GetMethodID(callbacksClass, "damage", "(IIII)I");
    cache.moverectMethod = env->GetMethodID(callbacksClass, "moverect",
        "(Lorg/connectbot/terminal/TermRect;Lorg/connectbot/terminal/TermRect;)I");
    cache.moveCursorMethod = env->GetMethodID(callbacksClass, "moveCursor", "(IIIIZ)I");
    cache.setTermPropMethod = env->GetMethodID(callbacksClass, "setTermProp",
        "(ILorg/connectbot/terminal/TerminalProperty;)I");
    cache.bellMethod = env->GetMethodID(callbacksClass, "bell", "()I");
    cache.pushScrollbackMethod = env->GetMethodID(callbacksClass, "pushScrollbackLine",
        "(IIILjava/nio/ByteBuffer;Z)I");
    cache.popScrollbackMethod = env->GetMethodID(callbacksClass, "popScrollbackLine",
        "(IIILjava/nio/ByteBuffer;)I");
    cache.clearScrollbackMethod = env->GetMethodID(callbacksClass, "clearScrollback", "()I");
    cache.keyboardInputMethod = env->GetMethodID(callbacksClass, "onKeyboardInput", "([B)I");
    cache.textFragmentMethod = env->GetMethodID(callbacksClass, "onTextFragment", "(II[BZZII)I");
    cache.imageFragmentMethod = env->GetMethodID(callbacksClass, "onImageFragment", "(Z[BZZII)J");
    cache.imageEditMethod = env->GetMethodID(callbacksClass, "onImageEdit", "(IIIIIII)V");
    cache.imageQueryMethod = env->GetMethodID(callbacksClass, "onImageQuery", "(I)V");
    cache.cellBufferMethod = env->GetMethodID(callbacksClass, "cellBuffer", "()Ljava/nio/ByteBuffer;");
    if (env->ExceptionCheck()) return false;

    cache.termRectClass = globalClass(env, "org/connectbot/terminal/TermRect");
    cache.cursorPositionClass = globalClass(env, "org/connectbot/terminal/CursorPosition");
    cache.terminalPropertyBoolClass = globalClass(env, "org/connectbot/terminal/TerminalProperty$BoolValue");
    cache.terminalPropertyIntClass = globalClass(env, "org/connectbot/terminal/TerminalProperty$IntValue");
    cache.terminalPropertyColorClass = globalClass(env, "org/connectbot/terminal/TerminalProperty$ColorValue");
    if (env->ExceptionCheck() || !cache.termRectClass || !cache.cursorPositionClass ||
        !cache.terminalPropertyBoolClass || !cache.terminalPropertyIntClass ||
        !cache.terminalPropertyColorClass) return false;

    cache.termRectConstructor = env->GetMethodID(cache.termRectClass, "<init>", "(IIII)V");
    cache.cursorPositionConstructor = env->GetMethodID(cache.cursorPositionClass, "<init>", "(II)V");
    cache.terminalPropertyBoolConstructor = env->GetMethodID(cache.terminalPropertyBoolClass, "<init>", "(Z)V");
    cache.terminalPropertyIntConstructor = env->GetMethodID(cache.terminalPropertyIntClass, "<init>", "(I)V");
    cache.terminalPropertyColorConstructor = env->GetMethodID(cache.terminalPropertyColorClass, "<init>", "(III)V");
    return !env->ExceptionCheck();
}

// Never assume the address of a sliced direct buffer is naturally aligned.
static uint8_t* writableBuffer(JNIEnv* env, jobject buffer, jlong minimum) {
    if (!buffer) return nullptr;
    auto* bytes = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    ScopedLocalRef<jclass> type(env, env->GetObjectClass(buffer));
    if (!type.get()) return nullptr;
    const auto method = env->GetMethodID(type, "isReadOnly", "()Z");
    if (!method || env->ExceptionCheck()) return nullptr;
    if (!bytes || capacity < minimum || env->CallBooleanMethod(buffer, method)) return nullptr;
    return env->ExceptionCheck() ? nullptr : bytes;
}
static_assert(VTERM_MAX_CHARS_PER_CELL == 16);

static void argumentError(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    ScopedLocalRef<jclass> type(env, env->FindClass("java/lang/IllegalArgumentException"));
    if (type.get()) env->ThrowNew(type, message);
}

// Terminal implementation
Terminal::Terminal(JNIEnv* env, jobject callbacks, int rows, int cols)
    : mRows(rows), mCols(cols) {

    // Get JavaVM for callback invocations from any thread
    if (env->GetJavaVM(&mJavaVM) != JNI_OK) return;

    // Store global reference to callbacks
    mCallbacks = env->NewGlobalRef(callbacks);
    if (env->ExceptionCheck()) return;

    const auto& cache = gCallbackCache;
    mDamageMethod = cache.damageMethod;
    mMoverectMethod = cache.moverectMethod;
    mMoveCursorMethod = cache.moveCursorMethod;
    mSetTermPropMethod = cache.setTermPropMethod;
    mBellMethod = cache.bellMethod;
    mPushScrollbackMethod = cache.pushScrollbackMethod;
    mPopScrollbackMethod = cache.popScrollbackMethod;
    mClearScrollbackMethod = cache.clearScrollbackMethod;
    mKeyboardInputMethod = cache.keyboardInputMethod;
    mTextFragmentMethod = cache.textFragmentMethod;
    mImageFragmentMethod = cache.imageFragmentMethod;
    mImageEditMethod = cache.imageEditMethod;
    mImageQueryMethod = cache.imageQueryMethod;
    mCellBufferMethod = cache.cellBufferMethod;
    mTermRectClass = cache.termRectClass;
    mTermRectConstructor = cache.termRectConstructor;
    mCursorPositionClass = cache.cursorPositionClass;
    mCursorPositionConstructor = cache.cursorPositionConstructor;
    mTerminalPropertyBoolClass = cache.terminalPropertyBoolClass;
    mTerminalPropertyBoolConstructor = cache.terminalPropertyBoolConstructor;
    mTerminalPropertyIntClass = cache.terminalPropertyIntClass;
    mTerminalPropertyIntConstructor = cache.terminalPropertyIntConstructor;
    mTerminalPropertyColorClass = cache.terminalPropertyColorClass;
    mTerminalPropertyColorConstructor = cache.terminalPropertyColorConstructor;

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
    if (!mVts) return;
    vterm_screen_enable_altscreen(mVts, 1);

    // Initialize callback structure as member variable so it doesn't go out of scope.
    // These callbacks run while mLock may be held by the native entrypoint that
    // triggered libvterm. Callback implementations must not synchronously call
    // back into Terminal methods; post/defer work that needs native state.
    mScreenCallbacks = {
        .damage = termDamage,
        .moverect = termMoverect,
        .movecursor = termMovecursor,
        .settermprop = termSettermprop,
        .bell = termBell,
        .resize = nullptr,  // We handle resize explicitly
        .sb_pushline = termSbPushline,
        .sb_popline = termSbPopline,
        .sb_clear = termSbClear,
        .edit = termImageEdit,
        .scroll = termImageScroll,
        .clear_images = termImageClear,
        .image_resize = termImageResize
    };
    vterm_screen_set_callbacks(mVts, &mScreenCallbacks, this);

    // Set up OSC fallback handlers for shell integration. These follow the same
    // no-synchronous-reentry rule as screen callbacks above.
    VTermState* state = vterm_obtain_state(mVt);
    VTermStateFallbacks fallbacks = {
        .control = termControlFallback,
        .csi = termCsiFallback,
        .osc = termOscFallback,
        .dcs = nullptr,
        .apc = termApcFallback,
        .pm = nullptr,
        .sos = nullptr
    };
    mStateFallbacks = fallbacks;
    vterm_state_set_unrecognised_fallbacks(state, &mStateFallbacks, this);

    // Set up selection callbacks for OSC 52 clipboard support. These follow the
    // same no-synchronous-reentry rule as screen callbacks above.
    // Note: libvterm handles base64 decoding internally
    mSelectionCallbacks = {
        .set = termSelectionSet,
        .query = termSelectionQuery
    };
    vterm_state_set_selection_callbacks(state, &mSelectionCallbacks, this,
        mSelectionBuffer, SELECTION_BUFFER_SIZE);

    // Configure damage merging
    vterm_screen_set_damage_merge(mVts, VTERM_DAMAGE_SCROLL);

    vterm_screen_reset(mVts, 1);

}

Terminal::~Terminal() {
    std::scoped_lock lock(mLock);

    if (mVt) {
        vterm_free(mVt);
        mVt = nullptr;
    }

    // Release global references
    JNIEnv* env;
    if (mJavaVM && mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
        if (mCallbacks) {
            env->DeleteGlobalRef(mCallbacks);
            mCallbacks = nullptr;
        }

    }
}

// Input handling - KEY METHOD
int Terminal::writeInput(const uint8_t* data, size_t length) {
    std::scoped_lock lock(mLock);

    if (!mVt) {
        LOGE("writeInput: VTerm not initialized");
        return 0;
    }

    // A single transport read can move the cursor thousands of times. Only
    // publish the final rendering position for this input batch.
    beginCursorBatch();

    // Feed data to libvterm for processing
    size_t written = vterm_input_write(mVt, (const char*)data, length);

    // Flush screen state to trigger callbacks
    vterm_screen_flush_damage(mVts);

    finishCursorBatch();

    return static_cast<int>(written);
}

// Resize
int Terminal::resize(int rows, int cols) {
    std::scoped_lock lock(mLock);

    mRows = rows;
    mCols = cols;

    if (mVt) {
        vterm_set_size(mVt, rows, cols);
        vterm_screen_flush_damage(mVts);
    }

    return 0;
}

int Terminal::placeImage(jlong movement, int row, int col) {
    std::scoped_lock lock(mLock);
    if (!mVt || movement <= 0) return -1;
    VTermPos pos{};
    vterm_state_get_cursorpos(vterm_obtain_state(mVt), &pos);
    // Output may have advanced to a shell prompt while consent was pending.
    // Reserving there would erase that prompt and move its cursor a second time.
    if (!(movement & 1) && (pos.row != row || pos.col != col)) return 0;
    const int rows = (movement >> 32) & 0xffff;
    const int cols = (movement >> 1) & 0xffff;
    if (rows <= 0 || cols <= 0) return -1;
    beginCursorBatch();
    mReservingImage = true;
    int anchor = row;
    if (movement & 1) {
        auto* state = vterm_obtain_state(mVt);
        if (pos.row == row && pos.col == col) {
            vterm_state_place_image(state, rows, cols, 1);
            vterm_state_get_cursorpos(state, &pos);
            anchor = pos.row - rows + 1;
        } else {
            // imgcat already emitted its newline. Insert the remaining image
            // rows ahead of that output, retaining the prompt's cursor column.
            // Text on the anchor line itself needs the entire image inserted.
            const int first = pos.row == row ? 0 : 1;
            const int insert = std::max(0, row + first);
            const int count = rows - first + std::min(0, row + first);
            if (count > 0 && insert < mRows) {
                anchor -= vterm_state_insert_lines_at(state, insert, count);
            }
        }
    } else {
        vterm_state_place_image(vterm_obtain_state(mVt), rows, cols, 0);
    }
    mReservingImage = false;
    vterm_screen_flush_damage(mVts);
    finishCursorBatch();
    return anchor;
}

// Color configuration
int Terminal::setPaletteColors(const uint32_t* colors, int count) {
    std::scoped_lock lock(mLock);

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
    std::scoped_lock lock(mLock);

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
    std::scoped_lock lock(mLock);

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
void Terminal::paste(JNIEnv* env, jbyteArray data) {
    std::scoped_lock lock(mLock);
    if (!mVt) return;
    vterm_keyboard_start_paste(mVt);
    const jsize size = env->GetArrayLength(data);
    char chunk[4096];
    for (jsize offset = 0; offset < size;) {
        const jsize count = std::min(jsize(sizeof(chunk)), size - offset);
        env->GetByteArrayRegion(data, offset, count, reinterpret_cast<jbyte*>(chunk));
        if (env->ExceptionCheck()) return;
        invokeKeyboardOutput(chunk, count);
        offset += count;
    }
    vterm_keyboard_end_paste(mVt);
}

bool Terminal::dispatchKey(int modifiers, int key) {
    std::scoped_lock lock(mLock);

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
    std::scoped_lock lock(mLock);

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
void Terminal::packCell(const VTermScreenCell& cell, jint* out) {
    std::fill_n(out, CELL_STRIDE, 0);
    for (int i = 0; i < VTERM_MAX_CHARS_PER_CELL && cell.chars[i]; ++i)
        out[i] = static_cast<jint>(cell.chars[i]);
    out[CELL_WIDTH] = cell.width;
    uint8_t r, g, b;
    resolveColor(cell.fg, r, g, b);
    out[CELL_FG] = (r << 16) | (g << 8) | b;
    resolveColor(cell.bg, r, g, b);
    out[CELL_BG] = (r << 16) | (g << 8) | b;
    if (cell.chars[0] == 0x10eeee) {
        auto colorId = [](const VTermColor& color) -> jint {
            if (VTERM_COLOR_IS_DEFAULT_FG(&color)) return 0;
            return VTERM_COLOR_IS_INDEXED(&color) ? color.indexed.idx :
                (color.rgb.red << 16) | (color.rgb.green << 8) | color.rgb.blue;
        };
        out[CELL_FG] = colorId(cell.fg);
        // Placeholder text needs at most four codepoints; reserve a tail slot
        // for the placement colour without widening ordinary cell records.
        out[4] = 0;
        out[14] = cell.chars[14];
    }
    out[CELL_FLAGS] = cell.attrs.bold | (cell.attrs.underline << 1) | (cell.attrs.italic << 3)
        | (cell.attrs.blink << 4) | (cell.attrs.reverse << 5) | (cell.attrs.conceal << 6)
        | (cell.attrs.strike << 7) | (cell.attrs.font << 8) | (cell.attrs.dwl << 12)
        | (cell.attrs.dhl << 13) | (cell.attrs.small << 15) | (cell.attrs.baseline << 16);
    if (VTERM_COLOR_IS_DEFAULT_BG(&cell.bg)) out[CELL_FLAGS] |= 1 << 18;
}

static void unpackCell(const jint* in, VTermScreenCell& cell) {
    cell = {};
    for (int i = 0; i < VTERM_MAX_CHARS_PER_CELL; ++i) cell.chars[i] = in[i];
    cell.width = in[CELL_WIDTH];
    vterm_color_rgb(&cell.fg, (in[CELL_FG] >> 16) & 255, (in[CELL_FG] >> 8) & 255, in[CELL_FG] & 255);
    vterm_color_rgb(&cell.bg, (in[CELL_BG] >> 16) & 255, (in[CELL_BG] >> 8) & 255, in[CELL_BG] & 255);
    const unsigned flags = static_cast<unsigned>(in[CELL_FLAGS]);
    if (flags & (1 << 18)) cell.bg.type |= VTERM_COLOR_DEFAULT_BG;
    cell.attrs.bold = flags & 1;
    cell.attrs.underline = (flags >> 1) & 3;
    cell.attrs.italic = (flags >> 3) & 1;
    cell.attrs.blink = (flags >> 4) & 1;
    cell.attrs.reverse = (flags >> 5) & 1;
    cell.attrs.conceal = (flags >> 6) & 1;
    cell.attrs.strike = (flags >> 7) & 1;
    cell.attrs.font = (flags >> 8) & 15;
    cell.attrs.dwl = (flags >> 12) & 1;
    cell.attrs.dhl = (flags >> 13) & 3;
    cell.attrs.small = (flags >> 15) & 1;
    cell.attrs.baseline = (flags >> 16) & 3;
}

// A header consists of 16-byte row/column/count/continuation descriptors.
// Cell records follow the fixed header. All requests are validated before writes.
int Terminal::getCells(JNIEnv* env, jobject output, int requests) {
    std::scoped_lock lock(mLock);
    auto* bytes = writableBuffer(env, output, BUFFER_BYTES);
    if (!bytes || requests < 0 || requests > HEADER_BYTES / 16) {
        argumentError(env, "Invalid batch buffer or request count"); return 0;
    }
    int total = 0;
    for (int i = 0; i < requests; ++i) {
        jint request[4];
        std::memcpy(request, bytes + i * 16, 16);
        if (request[0] < 0 || request[0] >= mRows || request[1] < 0 ||
            request[1] >= mCols || request[2] <= 0 || request[2] > mCols - request[1] ||
            request[2] > (BUFFER_BYTES - HEADER_BYTES) / CELL_BYTES - total) {
            argumentError(env, "Invalid cell range"); return 0;
        }
        total += request[2];
    }
    int offset = HEADER_BYTES;
    for (int i = 0; i < requests; ++i) {
        jint request[4];
        std::memcpy(request, bytes + i * 16, 16);
        const auto* info = request[0] + 1 < mRows ? vterm_state_get_lineinfo(vterm_obtain_state(mVt), request[0] + 1) : nullptr;
        request[3] = info && info->continuation;
        std::memcpy(bytes + i * 16, request, 16);
        for (int col = request[1]; col < request[1] + request[2]; ++col) {
            VTermScreenCell cell{};
            jint record[CELL_STRIDE]{};
            if (!vterm_screen_get_cell(mVts, {request[0], col}, &cell)) return 0;
            if (cell.chars[0] != UINT32_MAX) {
                if (cell.width == 2 && col + 1 >= mCols) { cell = {}; cell.width = 1; }
                packCell(cell, record);
            }
            std::memcpy(bytes + offset, record, CELL_BYTES);
            offset += CELL_BYTES;
        }
    }
    return total;
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
    term->mCursorPosition = pos;
    term->mCursorVisible = visible != 0;
    if (term->mCursorBatchActive) {
        if (!term->mCursorPending) term->mCursorOldPosition = oldpos;
        term->mCursorPending = true;
    } else {
        term->invokeMoveCursor(pos.row, pos.col, oldpos.row, oldpos.col, visible != 0);
    }
    return 1;
}

int Terminal::termSettermprop(VTermProp prop, VTermValue* val, void* user) {
    auto* term = static_cast<Terminal*>(user);
    if (prop == VTERM_PROP_CURSORVISIBLE) term->mCursorVisible = val->boolean != 0;
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
    if (command == 1337) {
        if (frag.initial) {
            // Keep ordinary OSC 1337 traffic on the original fast path. A short
            // first fragment remains ambiguous and is safely accumulated by the
            // image parser.
            static constexpr const char* prefixes[] = {
                "File=", "MultipartFile=", "FilePart=", "FileEnd", "Capabilities"
            };
            term->mOsc1337Image = frag.len == 0;
            for (const char* prefix : prefixes) {
                if (term->mOsc1337Image) break;
                const size_t prefixLen = std::strlen(prefix);
                if (std::memcmp(frag.str, prefix, std::min(frag.len, prefixLen)) == 0) {
                    term->mOsc1337Image = true;
                    break;
                }
            }
        }
        if (term->mOsc1337Image && term->imageFragment(false, frag) >= 0) return 1;
    }
    VTermPos cursor{};
    vterm_state_get_cursorpos(vterm_obtain_state(term->mVt), &cursor);
    if (frag.initial && command == 8) term->mOscCursorPos = cursor;
    if (command == 8) cursor = term->mOscCursorPos;
    return term->invokeTextFragment(0, command, frag, cursor.row, cursor.col);
}

jlong Terminal::imageFragment(bool kitty, VTermStringFragment frag) {
    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env->ExceptionCheck()) return 0;
    VTermPos pos{};
    vterm_state_get_cursorpos(vterm_obtain_state(mVt), &pos);
    jlong result = 0;
    size_t offset = 0;
    do {
        const jsize count = static_cast<jsize>(std::min(size_t(4096), frag.len - offset));
        ScopedLocalRef<jbyteArray> bytes(env, env->NewByteArray(count));
        if (!bytes.get()) return 0;
        if (count) env->SetByteArrayRegion(bytes, 0, count, (const jbyte*)frag.str + offset);
        if (env->ExceptionCheck()) return 0;
        result = env->CallLongMethod(mCallbacks, mImageFragmentMethod, kitty, bytes.get(),
            frag.initial && offset == 0, frag.final && offset + count == frag.len, pos.row, pos.col);
        if (env->ExceptionCheck()) return 0;
        offset += count;
    } while (offset < frag.len);
    if (result >= 0) mImageTracking = true;
    if (result > 0) {
        int rows = (result >> 32) & 0xffff;
        int cols = (result >> 1) & 0xffff;
        mReservingImage = true;
        vterm_state_place_image(vterm_obtain_state(mVt), rows, cols, result & 1);
        mReservingImage = false;
    }
    return result;
}

int Terminal::termApcFallback(VTermStringFragment frag, void* user) {
    static_cast<Terminal*>(user)->imageFragment(true, frag);
    return 1;
}

int Terminal::termControlFallback(unsigned char control, void* user) {
    if (control != 0x18) return 0;
    return static_cast<Terminal*>(user)->imageEdit(4, {});
}

int Terminal::termCsiFallback(const char* leader, const long args[], int argcount, const char* intermed, char command, void* user) {
    if (command != 't' || (leader && *leader) || (intermed && *intermed) || argcount != 1 ||
        (args[0] != 14 && args[0] != 16 && args[0] != 18)) return 0;
    auto* term = static_cast<Terminal*>(user);
    JNIEnv* env;
    if (term->mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env->ExceptionCheck()) return 0;
    env->CallVoidMethod(term->mCallbacks, term->mImageQueryMethod, static_cast<jint>(args[0]));
    return 1;
}

int Terminal::termImageResize(int buffer, int delta, int rows, int cols, void* user) {
    return static_cast<Terminal*>(user)->imageEdit(3, {buffer, rows, cols, 0}, delta, 0);
}

int Terminal::imageEdit(int kind, VTermRect rect, int downward, int rightward) {
    if (!mImageTracking) return 1;
    if (mReservingImage && kind != 1) return 1;
    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env->ExceptionCheck()) return 0;
    env->CallVoidMethod(mCallbacks, mImageEditMethod, kind, rect.start_row, rect.end_row,
        rect.start_col, rect.end_col, downward, rightward);
    return 1;
}
int Terminal::termImageEdit(VTermRect rect, void* user) {
    return static_cast<Terminal*>(user)->imageEdit(0, rect);
}
int Terminal::termImageScroll(VTermRect rect, int downward, int rightward, void* user) {
    return static_cast<Terminal*>(user)->imageEdit(1, rect, downward, rightward);
}
int Terminal::termImageClear(void* user) {
    return static_cast<Terminal*>(user)->imageEdit(2, {});
}

// libvterm has already decoded the OSC 52 base64 transport envelope.
int Terminal::termSelectionSet(VTermSelectionMask mask, VTermStringFragment frag, void* user) {
    return static_cast<Terminal*>(user)->invokeTextFragment(2, 52, frag, 0, 0);
}

// OSC 52 selection query callback - we don't support clipboard read for security
int Terminal::termSelectionQuery(VTermSelectionMask mask, void* user) {
    (void)mask;
    // Return 0 to indicate we don't support clipboard read
    return 0;
}

// Java callback invocations
void Terminal::invokeDamage(int startRow, int endRow, int startCol, int endCol) {
    if (!mDamageMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env->ExceptionCheck()) {
        return;
    }

    env->CallIntMethod(mCallbacks, mDamageMethod, startRow, endRow, startCol, endCol);
    if (env->ExceptionCheck()) return;
}

int Terminal::invokeMoverect(VTermRect dest, VTermRect src) {
    if (!mMoverectMethod) {
        return 0;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env->ExceptionCheck()) {
        return 0;
    }

    ScopedLocalRef<jobject> destObj(env, env->NewObject(mTermRectClass, mTermRectConstructor,
        dest.start_row, dest.end_row, dest.start_col, dest.end_col));
    if (!destObj.get()) return 0;
    ScopedLocalRef<jobject> srcObj(env, env->NewObject(mTermRectClass, mTermRectConstructor,
        src.start_row, src.end_row, src.start_col, src.end_col));

    if (!srcObj.get()) return 0;
    jint result = env->CallIntMethod(mCallbacks, mMoverectMethod, destObj.get(), srcObj.get());
    JNI_CHECK_EXCEPTION_RETURN(env, 0);
    return result;
}

void Terminal::beginCursorBatch() {
    mCursorBatchActive = true;
    mCursorPending = false;
}

void Terminal::finishCursorBatch() {
    mCursorBatchActive = false;
    if (!mCursorPending) return;

    mCursorPending = false;
    invokeMoveCursor(
        mCursorPosition.row,
        mCursorPosition.col,
        mCursorOldPosition.row,
        mCursorOldPosition.col,
        mCursorVisible);
}

void Terminal::invokeMoveCursor(int row, int col, int oldRow, int oldCol, bool visible) {
    if (!mMoveCursorMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env->ExceptionCheck()) {
        return;
    }

    env->CallIntMethod(mCallbacks, mMoveCursorMethod, row, col, oldRow, oldCol, visible);
    if (env->ExceptionCheck()) return;
}

void Terminal::invokeSetTermProp(VTermProp prop, VTermValue* val) {
    if (!mSetTermPropMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env->ExceptionCheck()) {
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
            invokeTextFragment(1, prop, val->string, 0, 0);
            return;

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
        if (env->ExceptionCheck()) return;
        env->CallIntMethod(mCallbacks, mSetTermPropMethod, prop, propValue.get());
        if (env->ExceptionCheck()) return;
    }
}

void Terminal::invokeBell() {
    if (!mBellMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env->ExceptionCheck()) {
        return;
    }

    env->CallIntMethod(mCallbacks, mBellMethod);
    if (env->ExceptionCheck()) return;
}

void Terminal::invokePushScrollbackLine(int cols, const VTermScreenCell* cells, bool softWrapped) {
    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env->ExceptionCheck()) return;
    ScopedLocalRef<jobject> buffer(env, env->CallObjectMethod(mCallbacks, mCellBufferMethod));
    if (env->ExceptionCheck()) return;
    auto* bytes = writableBuffer(env, buffer, BUFFER_BYTES);
    if (!bytes) { argumentError(env, "Invalid scrollback buffer"); return; }
    constexpr int capacity = BUFFER_BYTES / CELL_BYTES;
    bool continuation = false;
    for (int start = 0; start < cols; start += capacity) {
        const int count = std::min(capacity, cols - start);
        for (int i = 0; i < count; ++i) {
            jint record[CELL_STRIDE]{};
            if (continuation) { continuation = false; }
            else {
                packCell(cells[start + i], record);
                continuation = cells[start + i].width == 2;
                if (continuation && start + i + 1 == cols) {
                    std::fill_n(record, CELL_STRIDE, 0);
                    record[CELL_WIDTH] = 1;
                    continuation = false;
                }
            }
            std::memcpy(bytes + i * CELL_BYTES, record, CELL_BYTES);
        }
        env->CallIntMethod(mCallbacks, mPushScrollbackMethod, cols, start, count, buffer.get(), (jboolean)softWrapped);
        if (env->ExceptionCheck()) return;
    }
}

int Terminal::invokePopScrollbackLine(int cols, VTermScreenCell* cells) {
    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env->ExceptionCheck()) return 0;
    ScopedLocalRef<jobject> buffer(env, env->CallObjectMethod(mCallbacks, mCellBufferMethod));
    if (env->ExceptionCheck()) return 0;
    auto* bytes = writableBuffer(env, buffer, BUFFER_BYTES);
    if (!bytes) { argumentError(env, "Invalid scrollback buffer"); return 0; }
    constexpr int capacity = BUFFER_BYTES / CELL_BYTES;
    bool continuation = false;
    for (int start = 0; start < cols; start += capacity) {
        const int count = std::min(capacity, cols - start);
        const auto result = env->CallIntMethod(mCallbacks, mPopScrollbackMethod, cols, start, count, buffer.get());
        if (env->ExceptionCheck() || !result) return 0;
        for (int i = 0; i < count; ++i) {
            auto& cell = cells[start + i];
            if (continuation) {
                cell = {};
                cell.chars[0] = UINT32_MAX;
                cell.width = 1;
                continuation = false;
                continue;
            }
            jint record[CELL_STRIDE];
            std::memcpy(record, bytes + i * CELL_BYTES, CELL_BYTES);
            if (record[CELL_WIDTH] < 1 || record[CELL_WIDTH] > 2 || record[CELL_WIDTH] > cols - start - i) {
                argumentError(env, "Invalid cell width");
                return 0;
            }
            unpackCell(record, cell);
            continuation = cell.width == 2;
        }
    }
    return 1;
}

void Terminal::invokeClearScrollback() {
    if (!mClearScrollbackMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env->ExceptionCheck()) {
        return;
    }

    env->CallIntMethod(mCallbacks, mClearScrollbackMethod);
    if (env->ExceptionCheck()) return;
}

void Terminal::invokeKeyboardOutput(const char* data, size_t len) {
    if (!mKeyboardInputMethod) {
        return;
    }

    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env->ExceptionCheck()) {
        return;
    }

    if (len > static_cast<size_t>(std::numeric_limits<jsize>::max())) { argumentError(env, "Output too large"); return; }
    ScopedLocalRef<jbyteArray> array(env, env->NewByteArray(static_cast<jsize>(len)));
    if (!array.get()) return;
    env->SetByteArrayRegion(array, 0, len, reinterpret_cast<const jbyte*>(data));

    if (env->ExceptionCheck()) return;
    env->CallIntMethod(mCallbacks, mKeyboardInputMethod, array.get());
    if (env->ExceptionCheck()) return;
}

int Terminal::invokeTextFragment(int kind, int command, VTermStringFragment frag, int row, int col) {
    JNIEnv* env;
    if (mJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK || env->ExceptionCheck()) return 0;
    // Never expose libvterm-owned memory beyond this callback, nor allocate an
    // array sized by an unbounded remote payload. Kotlin owns assembly and limits.
    constexpr size_t CHUNK = 64 * 1024;
    size_t offset = 0;
    int result = 1;
    do {
        const jsize count = static_cast<jsize>(std::min(CHUNK, frag.len - offset));
        ScopedLocalRef<jbyteArray> data(env, env->NewByteArray(count));
        if (!data.get()) return 0;
        if (count) env->SetByteArrayRegion(data, 0, count, reinterpret_cast<const jbyte*>(frag.str + offset));
        if (env->ExceptionCheck()) return 0;
        result = env->CallIntMethod(mCallbacks, mTextFragmentMethod, kind, command, data.get(),
            (jboolean)(frag.initial && offset == 0), (jboolean)(frag.final && offset + count == frag.len), row, col);
        if (env->ExceptionCheck()) return 0;
        offset += count;
    } while (offset < frag.len);
    return result;
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

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    if (!initializeCallbackCache(env)) {
        clearCallbackCache(env);
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        clearCallbackCache(env);
    }
}

JNIEXPORT jlong JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeInit(JNIEnv* env, jobject /* thiz */, jobject callbacks) {
    auto* term = new (std::nothrow) Terminal(env, callbacks);
    if (!term || env->ExceptionCheck() || !term->ready()) {
        delete term;
        return 0;
    }
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
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (length < 0 || capacity < 0 || length > capacity) {
        argumentError(env, "Invalid direct buffer range");
        return 0;
    }
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
    const jsize size = env->GetArrayLength(data);
    if (offset < 0 || offset > size || length < 0 || length > size - offset) {
        argumentError(env, "Invalid input slice");
        return 0;
    }
    if (length == 0) return 0;
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return 0;
    struct Release {
        JNIEnv* env;
        jbyteArray array;
        jbyte* bytes;
        ~Release() { env->ReleaseByteArrayElements(array, bytes, JNI_ABORT); }
    } release{env, data, bytes};
    int result = term->writeInput(
        reinterpret_cast<const uint8_t*>(bytes + offset), length);
    return result;
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeResize(JNIEnv* env, jobject /* thiz */,
                                                         jlong ptr, jint rows, jint cols) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    if (rows <= 0 || cols <= 0 || rows > std::numeric_limits<int>::max() / cols ||
        cols > std::numeric_limits<int>::max() / CELL_STRIDE) {
        argumentError(env, "Invalid terminal dimensions");
        return -1;
    }
    return term->resize(rows, cols);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativePlaceImage(JNIEnv* /* env */, jobject /* thiz */,
                                                             jlong ptr, jlong movement, jint row, jint col) {
    return reinterpret_cast<Terminal*>(ptr)->placeImage(movement, row, col);
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

JNIEXPORT void JNICALL
Java_org_connectbot_terminal_TerminalNative_nativePaste(JNIEnv* env, jobject, jlong ptr, jbyteArray data) {
    reinterpret_cast<Terminal*>(ptr)->paste(env, data);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeGetCells(JNIEnv* env, jobject, jlong ptr, jobject buffer, jint requests) {
    return reinterpret_cast<Terminal*>(ptr)->getCells(env, buffer, requests);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeSetPaletteColors(JNIEnv* env, jobject /* thiz */,
                                                                   jlong ptr, jintArray colors, jint count) {
    auto* term = reinterpret_cast<Terminal*>(ptr);

    if (count < 0 || count > 16 || count > env->GetArrayLength(colors)) {
        argumentError(env, "Invalid palette count");
        return -1;
    }
    jint colorData[16]{};
    env->GetIntArrayRegion(colors, 0, count, colorData);
    if (env->ExceptionCheck()) return -1;
    uint32_t palette[16]{};
    for (int i = 0; i < count; ++i) palette[i] = static_cast<uint32_t>(colorData[i]);
    return term->setPaletteColors(palette, count);
}

JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeSetDefaultColors(JNIEnv* /* env */, jobject /* thiz */,
                                                                   jlong ptr, jint fgColor, jint bgColor) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    return term->setDefaultColors(static_cast<uint32_t>(fgColor), static_cast<uint32_t>(bgColor));
}



JNIEXPORT jint JNICALL
Java_org_connectbot_terminal_TerminalNative_nativeSetBoldHighbright(JNIEnv* /* env */, jobject /* thiz */,
                                                                     jlong ptr, jboolean enabled) {
    auto* term = reinterpret_cast<Terminal*>(ptr);
    return term->setBoldHighbright(enabled ? 1 : 0);
}

} // extern "C"
