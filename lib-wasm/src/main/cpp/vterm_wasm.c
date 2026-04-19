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

/*
 * Pure-C Wasm shim for libvterm.
 *
 * Exports plain C functions that the Java host calls via Chicory.
 * Imports callback functions that Java provides as Chicory host functions.
 * No JNI, no JavaVM*, no Android dependencies.
 *
 * All pointers are i32 (Wasm linear memory addresses).
 * Strings passed to Java are (ptr, len) pairs; Java reads them from Wasm memory.
 */

#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include "vterm.h"

/* -------------------------------------------------------------------------
 * Imported callbacks (implemented in Java as Chicory host functions)
 * ------------------------------------------------------------------------- */

/* Module name used for all imports */
#define IMPORT_MODULE "vterm_cb"

__attribute__((import_module(IMPORT_MODULE), import_name("damage")))
extern int cb_damage(int startRow, int endRow, int startCol, int endCol);

__attribute__((import_module(IMPORT_MODULE), import_name("moverect")))
extern int cb_moverect(int dstStartRow, int dstEndRow, int dstStartCol, int dstEndCol,
                       int srcStartRow, int srcEndRow, int srcStartCol, int srcEndCol);

__attribute__((import_module(IMPORT_MODULE), import_name("movecursor")))
extern int cb_movecursor(int row, int col, int oldRow, int oldCol, int visible);

__attribute__((import_module(IMPORT_MODULE), import_name("settermprop")))
extern int cb_settermprop(int prop, int type, int iVal, int ptr, int len);

__attribute__((import_module(IMPORT_MODULE), import_name("bell")))
extern int cb_bell(void);

__attribute__((import_module(IMPORT_MODULE), import_name("sb_pushline")))
extern int cb_sb_pushline(int cols, int cellsPtr, int softWrapped);

__attribute__((import_module(IMPORT_MODULE), import_name("sb_popline")))
extern int cb_sb_popline(int cols, int cellsPtr);

__attribute__((import_module(IMPORT_MODULE), import_name("output")))
extern void cb_output(int ptr, int len);

__attribute__((import_module(IMPORT_MODULE), import_name("osc")))
extern int cb_osc(int command, int ptr, int len, int cursorRow, int cursorCol);

/* -------------------------------------------------------------------------
 * Packed cell layout for scrollback exchange
 *
 * Each cell is a fixed-size struct in Wasm linear memory that Java reads
 * directly. The layout must match WasmScreenCell in VTermWasm.kt.
 *
 *   offset  size  field
 *   0       4     chars[0]  (uint32, primary Unicode codepoint)
 *   4       4     chars[1]  (uint32, first combining char or 0)
 *   8       4     chars[2]
 *   12      4     chars[3]
 *   16      4     chars[4]
 *   20      4     chars[5]
 *   24      1     fgRed
 *   25      1     fgGreen
 *   26      1     fgBlue
 *   27      1     bgRed
 *   28      1     bgGreen
 *   29      1     bgBlue
 *   30      1     attrs  (bit 0=bold, 1=italic, 2=reverse, 3=strike, 4=blink)
 *   31      1     underline (0-4)
 *   32      1     width (1 or 2)
 *   33      3     padding (alignment)
 *   total = 36 bytes
 * ------------------------------------------------------------------------- */
#define PACKED_CELL_SIZE 36

typedef struct {
    uint32_t chars[VTERM_MAX_CHARS_PER_CELL];
    uint8_t  fgRed, fgGreen, fgBlue;
    uint8_t  bgRed, bgGreen, bgBlue;
    uint8_t  attrs;
    uint8_t  underline;
    uint8_t  width;
    uint8_t  _pad[3];
} PackedCell;

/* -------------------------------------------------------------------------
 * Global state (single terminal instance per Wasm module instance)
 * ------------------------------------------------------------------------- */
static VTerm*        g_vt   = NULL;
static VTermScreen*  g_vts  = NULL;

/* OSC fragment accumulation */
static char  g_osc_buf[4096];
static int   g_osc_len   = 0;
static int   g_osc_cmd   = -1;
static int   g_osc_start_row = 0;
static int   g_osc_start_col = 0;

/* Selection (OSC 52) accumulation */
static char  g_sel_buf[8192];

/* -------------------------------------------------------------------------
 * Color resolution helper
 * ------------------------------------------------------------------------- */
static void resolve_color(VTerm* vt, VTermColor c, uint8_t* r, uint8_t* g, uint8_t* b) {
    if (VTERM_COLOR_IS_INDEXED(&c)) {
        VTermColor resolved;
        vterm_state_get_palette_color(vterm_obtain_state(vt), c.indexed.idx, &resolved);
        *r = resolved.rgb.red;
        *g = resolved.rgb.green;
        *b = resolved.rgb.blue;
    } else if (VTERM_COLOR_IS_RGB(&c)) {
        *r = c.rgb.red;
        *g = c.rgb.green;
        *b = c.rgb.blue;
    } else if (VTERM_COLOR_IS_DEFAULT_FG(&c)) {
        VTermColor fg, bg;
        vterm_state_get_default_colors(vterm_obtain_state(vt), &fg, &bg);
        *r = fg.rgb.red;
        *g = fg.rgb.green;
        *b = fg.rgb.blue;
    } else if (VTERM_COLOR_IS_DEFAULT_BG(&c)) {
        VTermColor fg, bg;
        vterm_state_get_default_colors(vterm_obtain_state(vt), &fg, &bg);
        *r = bg.rgb.red;
        *g = bg.rgb.green;
        *b = bg.rgb.blue;
    } else {
        *r = *g = *b = 128;
    }
}

/* -------------------------------------------------------------------------
 * libvterm screen callbacks
 * ------------------------------------------------------------------------- */
static int screen_damage(VTermRect rect, void* user) {
    return cb_damage(rect.start_row, rect.end_row, rect.start_col, rect.end_col);
}

static int screen_moverect(VTermRect dest, VTermRect src, void* user) {
    return cb_moverect(dest.start_row, dest.end_row, dest.start_col, dest.end_col,
                       src.start_row,  src.end_row,  src.start_col,  src.end_col);
}

static int screen_movecursor(VTermPos pos, VTermPos oldpos, int visible, void* user) {
    return cb_movecursor(pos.row, pos.col, oldpos.row, oldpos.col, visible);
}

/*
 * settermprop type encoding (matches VTermValueType):
 *   1 = bool  (iVal = 0/1)
 *   2 = int   (iVal = value)
 *   3 = string (ptr/len point into Wasm memory)
 *   4 = color (iVal = (r<<16)|(g<<8)|b)
 */
static int screen_settermprop(VTermProp prop, VTermValue* val, void* user) {
    VTermValueType type = vterm_get_prop_type(prop);
    switch (type) {
        case VTERM_VALUETYPE_BOOL:
            return cb_settermprop((int)prop, 1, val->boolean ? 1 : 0, 0, 0);
        case VTERM_VALUETYPE_INT:
            return cb_settermprop((int)prop, 2, val->number, 0, 0);
        case VTERM_VALUETYPE_STRING:
            if (val->string.str) {
                return cb_settermprop((int)prop, 3, 0,
                    (int)(uintptr_t)val->string.str, (int)val->string.len);
            }
            return cb_settermprop((int)prop, 3, 0, 0, 0);
        case VTERM_VALUETYPE_COLOR: {
            uint8_t r, g, b;
            resolve_color(g_vt, val->color, &r, &g, &b);
            return cb_settermprop((int)prop, 4, (r << 16) | (g << 8) | b, 0, 0);
        }
        default:
            return 0;
    }
}

static int screen_bell(void* user) {
    return cb_bell();
}

static int screen_sb_pushline(int cols, const VTermScreenCell* cells, void* user) {
    /* Check soft-wrap: if row 1's continuation flag is set, row 0 was soft-wrapped */
    int soft_wrapped = 0;
    if (g_vt) {
        VTermState* state = vterm_obtain_state(g_vt);
        if (state) {
            const VTermLineInfo* info = vterm_state_get_lineinfo(state, 1);
            if (info) soft_wrapped = info->continuation ? 1 : 0;
        }
    }

    /* Pack cells into a temporary stack buffer (max 256 cols) */
    PackedCell packed[256];
    int n = cols < 256 ? cols : 256;
    for (int i = 0; i < n; i++) {
        for (int j = 0; j < VTERM_MAX_CHARS_PER_CELL; j++)
            packed[i].chars[j] = cells[i].chars[j];
        resolve_color(g_vt, cells[i].fg,
            &packed[i].fgRed, &packed[i].fgGreen, &packed[i].fgBlue);
        resolve_color(g_vt, cells[i].bg,
            &packed[i].bgRed, &packed[i].bgGreen, &packed[i].bgBlue);
        packed[i].attrs = (cells[i].attrs.bold    ? 0x01 : 0)
                        | (cells[i].attrs.italic  ? 0x02 : 0)
                        | (cells[i].attrs.reverse ? 0x04 : 0)
                        | (cells[i].attrs.strike  ? 0x08 : 0)
                        | (cells[i].attrs.blink   ? 0x10 : 0);
        packed[i].underline = (uint8_t)cells[i].attrs.underline;
        packed[i].width     = (uint8_t)cells[i].width;
        packed[i]._pad[0] = packed[i]._pad[1] = packed[i]._pad[2] = 0;
    }
    return cb_sb_pushline(n, (int)(uintptr_t)packed, soft_wrapped);
}

static int screen_sb_popline(int cols, VTermScreenCell* cells, void* user) {
    PackedCell packed[256];
    int n = cols < 256 ? cols : 256;
    int result = cb_sb_popline(n, (int)(uintptr_t)packed);
    if (!result) return 0;

    for (int i = 0; i < n; i++) {
        for (int j = 0; j < VTERM_MAX_CHARS_PER_CELL; j++)
            cells[i].chars[j] = packed[i].chars[j];
        vterm_color_rgb(&cells[i].fg,
            packed[i].fgRed, packed[i].fgGreen, packed[i].fgBlue);
        vterm_color_rgb(&cells[i].bg,
            packed[i].bgRed, packed[i].bgGreen, packed[i].bgBlue);
        cells[i].attrs.bold     = (packed[i].attrs & 0x01) ? 1 : 0;
        cells[i].attrs.italic   = (packed[i].attrs & 0x02) ? 1 : 0;
        cells[i].attrs.reverse  = (packed[i].attrs & 0x04) ? 1 : 0;
        cells[i].attrs.strike   = (packed[i].attrs & 0x08) ? 1 : 0;
        cells[i].attrs.blink    = (packed[i].attrs & 0x10) ? 1 : 0;
        cells[i].attrs.underline = packed[i].underline;
        cells[i].width          = packed[i].width;
    }
    return 1;
}

static void term_output(const char* s, size_t len, void* user) {
    cb_output((int)(uintptr_t)s, (int)len);
}

/* -------------------------------------------------------------------------
 * OSC fallback
 * ------------------------------------------------------------------------- */
static int osc_fallback(int command, VTermStringFragment frag, void* user) {
    if (frag.initial) {
        g_osc_len = 0;
        g_osc_cmd = command;
        if (command == 8) {
            VTermPos pos;
            vterm_state_get_cursorpos(vterm_obtain_state(g_vt), &pos);
            g_osc_start_row = pos.row;
            g_osc_start_col = pos.col;
        }
    }
    if (frag.len > 0) {
        int space = (int)sizeof(g_osc_buf) - g_osc_len - 1;
        int copy  = (int)frag.len < space ? (int)frag.len : space;
        memcpy(g_osc_buf + g_osc_len, frag.str, copy);
        g_osc_len += copy;
    }
    if (frag.final) {
        int row, col;
        if (g_osc_cmd == 8) {
            row = g_osc_start_row;
            col = g_osc_start_col;
        } else {
            VTermPos pos;
            vterm_state_get_cursorpos(vterm_obtain_state(g_vt), &pos);
            row = pos.row;
            col = pos.col;
        }
        int result = cb_osc(g_osc_cmd, (int)(uintptr_t)g_osc_buf, g_osc_len, row, col);
        g_osc_len = 0;
        g_osc_cmd = -1;
        return result;
    }
    return 1;
}

/* -------------------------------------------------------------------------
 * OSC 52 selection set
 * ------------------------------------------------------------------------- */
static int selection_set(VTermSelectionMask mask, VTermStringFragment frag, void* user) {
    static char sel_acc[8192];
    static int  sel_len = 0;

    if (frag.initial) sel_len = 0;

    if (frag.len > 0) {
        int space = (int)sizeof(sel_acc) - sel_len - 1;
        int copy  = (int)frag.len < space ? (int)frag.len : space;
        memcpy(sel_acc + sel_len, frag.str, copy);
        sel_len += copy;
    }

    if (frag.final && sel_len > 0) {
        /* Prefix "c;" to match existing TerminalCallbacks convention */
        char payload[8196];
        memcpy(payload, "c;", 2);
        memcpy(payload + 2, sel_acc, sel_len);
        cb_osc(52, (int)(uintptr_t)payload, sel_len + 2, 0, 0);
        sel_len = 0;
    }
    return 1;
}

static int selection_query(VTermSelectionMask mask, void* user) {
    return 0;
}

/* -------------------------------------------------------------------------
 * Exported API (called from Java via Chicory)
 * ------------------------------------------------------------------------- */

__attribute__((export_name("vterm_wasm_init")))
int vterm_wasm_init(int rows, int cols) {
    if (g_vt) {
        vterm_free(g_vt);
        g_vt  = NULL;
        g_vts = NULL;
    }

    g_vt = vterm_new(rows, cols);
    if (!g_vt) return -1;

    vterm_set_utf8(g_vt, 1);
    vterm_output_set_callback(g_vt, term_output, NULL);

    g_vts = vterm_obtain_screen(g_vt);
    vterm_screen_enable_altscreen(g_vts, 1);

    static VTermScreenCallbacks scb = {
        .damage       = screen_damage,
        .moverect     = screen_moverect,
        .movecursor   = screen_movecursor,
        .settermprop  = screen_settermprop,
        .bell         = screen_bell,
        .resize       = NULL,
        .sb_pushline  = screen_sb_pushline,
        .sb_popline   = screen_sb_popline,
        .sb_clear     = NULL,
    };
    vterm_screen_set_callbacks(g_vts, &scb, NULL);

    VTermState* state = vterm_obtain_state(g_vt);
    static VTermStateFallbacks fb = {
        .osc = osc_fallback,
    };
    vterm_state_set_unrecognised_fallbacks(state, &fb, NULL);

    static VTermSelectionCallbacks selcb = {
        .set   = selection_set,
        .query = selection_query,
    };
    vterm_state_set_selection_callbacks(state, &selcb, NULL, g_sel_buf, sizeof(g_sel_buf));

    vterm_screen_set_damage_merge(g_vts, VTERM_DAMAGE_SCROLL);
    vterm_screen_reset(g_vts, 1);
    return 0;
}

__attribute__((export_name("vterm_wasm_free")))
void vterm_wasm_free(void) {
    if (g_vt) {
        vterm_free(g_vt);
        g_vt  = NULL;
        g_vts = NULL;
    }
}

__attribute__((export_name("vterm_wasm_write_input")))
int vterm_wasm_write_input(int ptr, int len) {
    if (!g_vt) return -1;
    size_t written = vterm_input_write(g_vt, (const char*)(uintptr_t)ptr, (size_t)len);
    vterm_screen_flush_damage(g_vts);
    return (int)written;
}

__attribute__((export_name("vterm_wasm_resize")))
int vterm_wasm_resize(int rows, int cols) {
    if (!g_vt) return -1;
    vterm_set_size(g_vt, rows, cols);
    vterm_screen_flush_damage(g_vts);
    return 0;
}

__attribute__((export_name("vterm_wasm_dispatch_key")))
int vterm_wasm_dispatch_key(int modifiers, int key) {
    if (!g_vt) return 0;
    VTermModifier mod = VTERM_MOD_NONE;
    if (modifiers & 1) mod |= VTERM_MOD_SHIFT;
    if (modifiers & 2) mod |= VTERM_MOD_ALT;
    if (modifiers & 4) mod |= VTERM_MOD_CTRL;
    vterm_keyboard_key(g_vt, (VTermKey)key, mod);
    return 1;
}

__attribute__((export_name("vterm_wasm_dispatch_char")))
int vterm_wasm_dispatch_char(int modifiers, int codepoint) {
    if (!g_vt) return 0;
    VTermModifier mod = VTERM_MOD_NONE;
    if (modifiers & 1) mod |= VTERM_MOD_SHIFT;
    if (modifiers & 2) mod |= VTERM_MOD_ALT;
    if (modifiers & 4) mod |= VTERM_MOD_CTRL;
    vterm_keyboard_unichar(g_vt, codepoint, mod);
    return 1;
}

/*
 * Get cell run starting at (row, col).
 * Fills a PackedCell array at outPtr (caller must allocate cols * PACKED_CELL_SIZE bytes).
 * Returns number of cells in the run (cells with identical style).
 */
__attribute__((export_name("vterm_wasm_get_cell_run")))
int vterm_wasm_get_cell_run(int row, int col, int outPtr) {
    if (!g_vts) return 0;

    int rows, cols;
    vterm_get_size(g_vt, &rows, &cols);
    if (row < 0 || row >= rows || col < 0 || col >= cols) return 0;

    VTermPos firstPos = { row, col };
    VTermScreenCell firstCell;
    vterm_screen_get_cell(g_vts, firstPos, &firstCell);

    PackedCell* out = (PackedCell*)(uintptr_t)outPtr;
    int runLen = 0;

    for (int c = col; c < cols && runLen < cols; c++) {
        VTermPos pos = { row, c };
        VTermScreenCell cell;
        vterm_screen_get_cell(g_vts, pos, &cell);

        if (c > col) {
            /* Stop run if style differs */
            if (memcmp(&firstCell.fg, &cell.fg, sizeof(VTermColor)) != 0 ||
                memcmp(&firstCell.bg, &cell.bg, sizeof(VTermColor)) != 0 ||
                firstCell.attrs.bold      != cell.attrs.bold      ||
                firstCell.attrs.underline != cell.attrs.underline ||
                firstCell.attrs.italic    != cell.attrs.italic    ||
                firstCell.attrs.blink     != cell.attrs.blink     ||
                firstCell.attrs.reverse   != cell.attrs.reverse   ||
                firstCell.attrs.strike    != cell.attrs.strike    ||
                firstCell.attrs.font      != cell.attrs.font      ||
                firstCell.attrs.dwl       != cell.attrs.dwl       ||
                firstCell.attrs.dhl       != cell.attrs.dhl) {
                break;
            }
        }

        for (int j = 0; j < VTERM_MAX_CHARS_PER_CELL; j++)
            out[runLen].chars[j] = cell.chars[j];
        resolve_color(g_vt, cell.fg,
            &out[runLen].fgRed, &out[runLen].fgGreen, &out[runLen].fgBlue);
        resolve_color(g_vt, cell.bg,
            &out[runLen].bgRed, &out[runLen].bgGreen, &out[runLen].bgBlue);
        out[runLen].attrs = (cell.attrs.bold    ? 0x01 : 0)
                          | (cell.attrs.italic  ? 0x02 : 0)
                          | (cell.attrs.reverse ? 0x04 : 0)
                          | (cell.attrs.strike  ? 0x08 : 0)
                          | (cell.attrs.blink   ? 0x10 : 0);
        out[runLen].underline = (uint8_t)cell.attrs.underline;
        out[runLen].width     = (uint8_t)cell.width;
        out[runLen]._pad[0] = out[runLen]._pad[1] = out[runLen]._pad[2] = 0;
        runLen++;

        if (cell.width == 2) c++;
    }
    return runLen;
}

__attribute__((export_name("vterm_wasm_set_palette_colors")))
int vterm_wasm_set_palette_colors(int ptr, int count) {
    if (!g_vt) return -1;
    const uint32_t* colors = (const uint32_t*)(uintptr_t)ptr;
    VTermState* state = vterm_obtain_state(g_vt);
    int n = count < 16 ? count : 16;
    for (int i = 0; i < n; i++) {
        VTermColor c;
        vterm_color_rgb(&c,
            (colors[i] >> 16) & 0xFF,
            (colors[i] >>  8) & 0xFF,
             colors[i]        & 0xFF);
        vterm_state_set_palette_color(state, i, &c);
    }
    return n;
}

__attribute__((export_name("vterm_wasm_set_default_colors")))
int vterm_wasm_set_default_colors(int fg, int bg) {
    if (!g_vt) return -1;
    VTermScreen* screen = vterm_obtain_screen(g_vt);
    VTermColor vtFg, vtBg;
    vterm_color_rgb(&vtFg, (fg >> 16) & 0xFF, (fg >> 8) & 0xFF, fg & 0xFF);
    vterm_color_rgb(&vtBg, (bg >> 16) & 0xFF, (bg >> 8) & 0xFF, bg & 0xFF);
    vterm_screen_set_default_colors(screen, &vtFg, &vtBg);
    return 0;
}

__attribute__((export_name("vterm_wasm_get_line_continuation")))
int vterm_wasm_get_line_continuation(int row) {
    if (!g_vt) return 0;
    int rows, cols;
    vterm_get_size(g_vt, &rows, &cols);
    if (row < 0 || row >= rows) return 0;
    VTermState* state = vterm_obtain_state(g_vt);
    const VTermLineInfo* info = vterm_state_get_lineinfo(state, row);
    return (info && info->continuation) ? 1 : 0;
}

__attribute__((export_name("vterm_wasm_set_bold_highbright")))
int vterm_wasm_set_bold_highbright(int enabled) {
    if (!g_vt) return -1;
    vterm_state_set_bold_highbright(vterm_obtain_state(g_vt), enabled);
    return 0;
}

/* Expose linear memory so Java can read string data by pointer */
__attribute__((export_name("vterm_wasm_memory_base")))
int vterm_wasm_memory_base(void) {
    /* Returns 0; caller uses instance.memory() directly */
    return 0;
}

/* Allocate/free helpers so Java can write input data into Wasm memory */
__attribute__((export_name("vterm_wasm_alloc")))
int vterm_wasm_alloc(int size) {
    return (int)(uintptr_t)malloc((size_t)size);
}

__attribute__((export_name("vterm_wasm_dealloc")))
void vterm_wasm_dealloc(int ptr) {
    free((void*)(uintptr_t)ptr);
}

/* Out-buffer for get_cell_run: allocated once, reused each call.
 * Returns pointer to a buffer large enough for 256 PackedCells. */
static PackedCell g_cell_run_buf[256];

__attribute__((export_name("vterm_wasm_cell_run_buf")))
int vterm_wasm_cell_run_buf(void) {
    return (int)(uintptr_t)g_cell_run_buf;
}

/* Out-buffer for get_all_rows: rows * cols PackedCells, max 300*300. */
static PackedCell g_screen_buf[300 * 300];

__attribute__((export_name("vterm_wasm_screen_buf")))
int vterm_wasm_screen_buf(void) {
    return (int)(uintptr_t)g_screen_buf;
}

/*
 * Fill all cells for a single row into outPtr (caller must allocate cols * PACKED_CELL_SIZE bytes).
 * Returns cols, or 0 on error.
 * Lets Java fetch an entire row in one Wasm call, avoiding per-run dispatch overhead.
 */
/*
 * Fill all cells for every row into outPtr in row-major order.
 * outPtr must point to rows*cols*PACKED_CELL_SIZE bytes (use vterm_wasm_screen_buf).
 * Returns rows*cols, or 0 on error.
 */
__attribute__((export_name("vterm_wasm_get_all_rows")))
int vterm_wasm_get_all_rows(int outPtr) {
    if (!g_vts) return 0;
    int rows, cols;
    vterm_get_size(g_vt, &rows, &cols);

    PackedCell* out = (PackedCell*)(uintptr_t)outPtr;
    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            VTermPos pos = { r, c };
            VTermScreenCell cell;
            vterm_screen_get_cell(g_vts, pos, &cell);
            int idx = r * cols + c;
            for (int j = 0; j < VTERM_MAX_CHARS_PER_CELL; j++)
                out[idx].chars[j] = cell.chars[j];
            resolve_color(g_vt, cell.fg,
                &out[idx].fgRed, &out[idx].fgGreen, &out[idx].fgBlue);
            resolve_color(g_vt, cell.bg,
                &out[idx].bgRed, &out[idx].bgGreen, &out[idx].bgBlue);
            out[idx].attrs = (cell.attrs.bold    ? 0x01 : 0)
                           | (cell.attrs.italic  ? 0x02 : 0)
                           | (cell.attrs.reverse ? 0x04 : 0)
                           | (cell.attrs.strike  ? 0x08 : 0)
                           | (cell.attrs.blink   ? 0x10 : 0);
            out[idx].underline = (uint8_t)cell.attrs.underline;
            out[idx].width     = (uint8_t)cell.width;
            out[idx]._pad[0] = out[idx]._pad[1] = out[idx]._pad[2] = 0;
        }
    }
    return rows * cols;
}

__attribute__((export_name("vterm_wasm_get_row")))
int vterm_wasm_get_row(int row, int outPtr) {
    if (!g_vts) return 0;
    int rows, cols;
    vterm_get_size(g_vt, &rows, &cols);
    if (row < 0 || row >= rows) return 0;

    PackedCell* out = (PackedCell*)(uintptr_t)outPtr;
    for (int c = 0; c < cols; c++) {
        VTermPos pos = { row, c };
        VTermScreenCell cell;
        vterm_screen_get_cell(g_vts, pos, &cell);

        for (int j = 0; j < VTERM_MAX_CHARS_PER_CELL; j++)
            out[c].chars[j] = cell.chars[j];
        resolve_color(g_vt, cell.fg,
            &out[c].fgRed, &out[c].fgGreen, &out[c].fgBlue);
        resolve_color(g_vt, cell.bg,
            &out[c].bgRed, &out[c].bgGreen, &out[c].bgBlue);
        out[c].attrs = (cell.attrs.bold    ? 0x01 : 0)
                     | (cell.attrs.italic  ? 0x02 : 0)
                     | (cell.attrs.reverse ? 0x04 : 0)
                     | (cell.attrs.strike  ? 0x08 : 0)
                     | (cell.attrs.blink   ? 0x10 : 0);
        out[c].underline = (uint8_t)cell.attrs.underline;
        out[c].width     = (uint8_t)cell.width;
        out[c]._pad[0] = out[c]._pad[1] = out[c]._pad[2] = 0;
    }
    return cols;
}
