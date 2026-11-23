#ifndef VTERM_COMPACT_SCREEN_H
#define VTERM_COMPACT_SCREEN_H

#include "vterm.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Termlib's compact JNI record format. Count is the requested size on entry
 * and the number of whole cells written on return. Output may be unaligned. */
size_t vterm_screen_pack_row(const VTermScreen *screen, VTermPos start, int *count,
    uint8_t *output, size_t capacity, const int32_t palette[256]);

/* Preserve scrollback's leading-cell width rule when a row spans chunks. */
size_t vterm_screen_pack_scrollback(const VTermScreen *screen, VTermPos start, int *count,
    uint8_t *output, size_t capacity, const int32_t palette[256], bool *continuation);

/* Called before an ordinary scroll moves the outgoing row. Resize retains the
 * normal scrollback callback because it may operate on an inactive buffer. */
void vterm_screen_set_compact_scrollback_callback(VTermScreen *screen,
    void (*callback)(int row, int cols, void *user));

#ifdef __cplusplus
}
#endif

static inline uint32_t vterm_compact_meta(uint32_t base, unsigned extra, unsigned width)
{
  return (base <= 0x10ffff ? base : 0xfffd) | (extra << 21) | (width << 25);
}

#endif
