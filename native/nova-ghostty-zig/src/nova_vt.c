// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
// NovaScale's terminal adapter. Only the documented libghostty-vt C API is
// used here; no terminal layout, parser state or Zig implementation is exposed.
#include "nova_vt.h"
#include <ghostty/vt.h>
#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define MEMORY_LIMIT (64u * 1024u * 1024u)
#define PACKET_LIMIT (32u * 1024u * 1024u)
#define IMAGE_LIMIT (8u * 1024u * 1024u)
#define IMAGE_COUNT 128u
#define PLACEMENT_COUNT 512u
#define FRAME_HEADER 80u
#define FRAME_MAGIC 0x4654564eu // NVTF
#define HOLD_MS 1000u

typedef struct { size_t used; } Budget;
typedef struct { uint8_t *data; size_t len, cap; } Buffer;
typedef struct {
    GhosttyRenderStateRowId id;
    uint64_t changed;
    Buffer cells;
} Row;
typedef struct { uint32_t id; uint64_t generation; } AssetKey;
typedef struct {
    Budget vt_memory, auxiliary;
    GhosttyAllocator allocator;
    GhosttyTerminal terminal;
    GhosttyRenderState render;
    GhosttyRenderStateRowIterator row_iterator;
    GhosttyRenderStateRowCells cells;
    GhosttyRenderStateImageIterator image_iterator;
    uint16_t columns, rows, frame_columns, frame_rows;
    uint32_t cell_width, cell_height, frame_cell_width, frame_cell_height;
    uint64_t event_revision, frame_revision, previous_frame, image_revision;
    uint64_t hold_started;
    bool held, needs_capture, capture_failed;
    Row *frame;
    GhosttyRenderStateColors colors;
    GhosttyRenderStateCursor cursor;
    uint32_t modes;
    AssetKey assets[IMAGE_COUNT];
    size_t asset_count, placement_count;
    Buffer asset_bytes, placements, packet, pty, clipboard;
} Terminal;

static bool capture(Terminal *t);
extern bool nova_vt_decode_png(void *, const GhosttyAllocator *, const uint8_t *, size_t, GhosttySysImage *);

static void *budget_alloc(void *ctx, size_t len, uint8_t alignment, uintptr_t address) {
    (void)address;
    Budget *b = ctx;
    if (alignment >= sizeof(size_t) * CHAR_BIT || len > MEMORY_LIMIT - b->used) return NULL;
    size_t align = (size_t)1 << alignment;
    // ghostty_alloc also services the PNG decoder's scratch allocations.
    if (align < 64) align = 64;
    void *p = NULL;
    if (posix_memalign(&p, align, len ? len : 1) != 0) return NULL;
    b->used += len;
    return p;
}
static void budget_free(void *ctx, void *ptr, size_t len, uint8_t alignment, uintptr_t address) {
    (void)alignment; (void)address;
    ((Budget *)ctx)->used -= len;
    free(ptr);
}
static const GhosttyAllocatorVtable allocator_vtable = {.alloc = budget_alloc, .free = budget_free};

static bool reserve(Terminal *t, Buffer *b, size_t length) {
    if (length > PACKET_LIMIT) return false;
    if (length <= b->cap) return true;
    size_t cap = b->cap ? b->cap : 128;
    while (cap < length) cap = cap > PACKET_LIMIT / 2 ? PACKET_LIMIT : cap * 2;
    if (cap - b->cap > MEMORY_LIMIT - t->auxiliary.used) return false;
    void *p = realloc(b->data, cap);
    if (!p) return false;
    t->auxiliary.used += cap - b->cap;
    b->data = p; b->cap = cap;
    return true;
}
static void discard(Terminal *t, Buffer *b) {
    t->auxiliary.used -= b->cap;
    free(b->data); *b = (Buffer){0};
}
static bool append(Terminal *t, Buffer *b, const void *data, size_t length) {
    if (length > PACKET_LIMIT - b->len || !reserve(t, b, b->len + length)) return false;
    if (length) memcpy(b->data + b->len, data, length);
    b->len += length; return true;
}
static void put32(uint8_t *p, uint32_t v) { for (unsigned i = 0; i < 4; i++) p[i] = (uint8_t)(v >> (i * 8)); }
static void put64(uint8_t *p, uint64_t v) { for (unsigned i = 0; i < 8; i++) p[i] = (uint8_t)(v >> (i * 8)); }
static bool word(Terminal *t, Buffer *b, uint32_t v) { uint8_t p[4]; put32(p, v); return append(t, b, p, sizeof(p)); }
static bool wide_word(Terminal *t, Buffer *b, uint64_t v) { uint8_t p[8]; put64(p, v); return append(t, b, p, sizeof(p)); }
static bool real(Terminal *t, Buffer *b, float v) { uint32_t bits; memcpy(&bits, &v, 4); return word(t, b, bits); }
static uint64_t now_ms(void) {
    struct timespec time;
    if (clock_gettime(CLOCK_MONOTONIC, &time) != 0) return 0;
    return (uint64_t)time.tv_sec * 1000 + (uint64_t)time.tv_nsec / 1000000;
}
static uint32_t argb(GhosttyColorRgb c) { return 0xff000000u | (uint32_t)c.r << 16 | (uint32_t)c.g << 8 | c.b; }
static GhosttyColorRgb rgb(uint32_t v) { return (GhosttyColorRgb){(uint8_t)(v >> 16), (uint8_t)(v >> 8), (uint8_t)v}; }
static bool same_id(GhosttyRenderStateRowId a, GhosttyRenderStateRowId b) { return a.bits[0] == b.bits[0] && a.bits[1] == b.bits[1]; }
static bool mode(Terminal *t, GhosttyMode key) {
    GhosttyTerminalModeConfig value = {.mode = key};
    return ghostty_terminal_get(t->terminal, GHOSTTY_TERMINAL_DATA_MODE, &value) == GHOSTTY_SUCCESS && value.value;
}
static void end_hold(Terminal *t) {
    if (!t->held) return;
    GhosttyTerminalModeConfig config = {GHOSTTY_MODE_SYNCHRONIZED_OUTPUT, false};
    ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_MODE, &config);
    t->held = false; t->needs_capture = true;
}
static void on_hold(GhosttyTerminal terminal, void *context, bool held) {
    (void)terminal;
    Terminal *t = context;
    if (held) {
        // The callback runs before any bytes following the hold are parsed.
        // Capture even if another complete frame was never requested by JNI.
        t->capture_failed = !capture(t);
        t->hold_started = now_ms();
    }
    t->held = held;
    if (!held) t->needs_capture = true;
}
static void on_pty(GhosttyTerminal terminal, void *context, const uint8_t *data, size_t length) {
    (void)terminal;
    Terminal *t = context;
    if (length <= 1024u * 1024u - t->pty.len) (void)append(t, &t->pty, data, length);
}
static bool on_size(GhosttyTerminal terminal, void *context, GhosttySizeReportSize *out) {
    (void)terminal;
    Terminal *t = context;
    *out = (GhosttySizeReportSize){.rows = t->rows, .columns = t->columns,
        .cell_width = t->cell_width, .cell_height = t->cell_height};
    return true;
}
static GhosttyString on_version(GhosttyTerminal terminal, void *context) {
    (void)terminal; (void)context;
    static const char value[] = "NovaScale/libghostty-vt";
    return (GhosttyString){(const uint8_t *)value, sizeof(value) - 1};
}
static void on_clipboard(GhosttyTerminal terminal, void *context, const GhosttyClipboardWrite *request) {
    (void)terminal;
    Terminal *t = context;
    GhosttyClipboardWriteReply reply = GHOSTTY_INIT_SIZED(GhosttyClipboardWriteReply);
    reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED;
    for (size_t i = 0; i < request->contents_len; i++) {
        GhosttyClipboardContent content = request->contents[i];
        if (content.mime.len < 10 || memcmp(content.mime.ptr, "text/plain", 10) != 0 ||
            content.data.len == 0 || content.data.len > 1024u * 1024u) continue;
        t->clipboard.len = 0;
        if (append(t, &t->clipboard, content.data.ptr, content.data.len)) reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS;
        break;
    }
    request->reply(request, &reply);
}
static pthread_once_t system_once = PTHREAD_ONCE_INIT;
static void initialize_system(void) {
    ghostty_sys_set(GHOSTTY_SYS_OPT_DECODE_PNG, (const void *)nova_vt_decode_png);
    // Logging and every optional external side effect stay disabled.
}

void nova_ghostty_destroy(uintptr_t handle) {
    Terminal *t = (Terminal *)handle;
    if (!t) return;
    for (size_t i = 0; i < t->frame_rows; i++) discard(t, &t->frame[i].cells);
    free(t->frame);
    discard(t, &t->asset_bytes); discard(t, &t->placements); discard(t, &t->packet);
    discard(t, &t->pty); discard(t, &t->clipboard);
    ghostty_render_state_image_iterator_free(t->image_iterator);
    ghostty_render_state_row_cells_free(t->cells);
    ghostty_render_state_row_iterator_free(t->row_iterator);
    ghostty_render_state_free(t->render);
    ghostty_terminal_free(t->terminal);
    free(t);
}
uintptr_t nova_ghostty_create(uint32_t columns, uint32_t rows, uint32_t history) {
    if (!columns || columns > 500 || !rows || rows > 300) return 0;
    pthread_once(&system_once, initialize_system);
    Terminal *t = calloc(1, sizeof(*t));
    if (!t) return 0;
    t->allocator = (GhosttyAllocator){&t->vt_memory, &allocator_vtable};
    t->columns = (uint16_t)columns; t->rows = (uint16_t)rows;
    t->cell_width = 8; t->cell_height = 16; t->needs_capture = true;
    if (ghostty_terminal_new(&t->allocator, &t->terminal, t->columns, t->rows) != GHOSTTY_SUCCESS ||
        ghostty_render_state_new(&t->allocator, &t->render) != GHOSTTY_SUCCESS ||
        ghostty_render_state_row_iterator_new(&t->allocator, &t->row_iterator) != GHOSTTY_SUCCESS ||
        ghostty_render_state_row_cells_new(&t->allocator, &t->cells) != GHOSTTY_SUCCESS ||
        ghostty_render_state_image_iterator_new(&t->allocator, &t->image_iterator) != GHOSTTY_SUCCESS) goto fail;
    uint64_t history_bytes = history ? UINT64_MAX : 0, lines = history, image_limit = IMAGE_LIMIT;
    size_t apc_limit = IMAGE_LIMIT, clipboard_limit = 1024u * 1024u;
    bool disabled = false;
#define SET(key, value) do { if (ghostty_terminal_set(t->terminal, key, value) != GHOSTTY_SUCCESS) goto fail; } while (0)
    SET(GHOSTTY_TERMINAL_OPT_USERDATA, t);
    SET(GHOSTTY_TERMINAL_OPT_WRITE_PTY, (const void *)on_pty);
    SET(GHOSTTY_TERMINAL_OPT_SIZE, (const void *)on_size);
    SET(GHOSTTY_TERMINAL_OPT_XTVERSION, (const void *)on_version);
    SET(GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE, (const void *)on_clipboard);
    SET(GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE_MAX_BYTES, &clipboard_limit);
    SET(GHOSTTY_TERMINAL_OPT_RENDER_HOLD, (const void *)on_hold);
    SET(GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &history_bytes);
    SET(GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &lines);
    SET(GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT, &image_limit);
    SET(GHOSTTY_TERMINAL_OPT_APC_MAX_BYTES_KITTY, &apc_limit);
    SET(GHOSTTY_TERMINAL_OPT_GLYPH_PROTOCOL, &disabled);
    GhosttyColorRgb fg = rgb(0xf2f2f2), bg = rgb(0), cursor = fg;
    SET(GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &fg);
    SET(GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &bg);
    SET(GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &cursor);
    GhosttyTerminalModeConfig grapheme = {GHOSTTY_MODE_GRAPHEME_CLUSTER, true};
    SET(GHOSTTY_TERMINAL_OPT_MODE, &grapheme);
#undef SET
    if (ghostty_terminal_resize(t->terminal, t->columns, t->rows, t->cell_width, t->cell_height) != GHOSTTY_SUCCESS || !capture(t)) goto fail;
    return (uintptr_t)t;
fail:
    nova_ghostty_destroy((uintptr_t)t); return 0;
}
uintptr_t nova_ghostty_create_bounded(uint32_t columns, uint32_t rows, uint32_t history) {
    // Both SSH and Herdr now bound every auxiliary buffer, as well as VT state.
    return nova_ghostty_create(columns, rows, history);
}
uint64_t nova_ghostty_write(uintptr_t handle, const uint8_t *bytes, size_t length) {
    Terminal *t = (Terminal *)handle;
    if (!t || !bytes || !length) return t ? t->event_revision : 0;
    (void)nova_ghostty_tick(handle);
    ghostty_terminal_vt_write(t->terminal, bytes, length);
    t->needs_capture = true;
    return ++t->event_revision;
}
uint64_t nova_ghostty_resize(uintptr_t handle, uint32_t columns, uint32_t rows, uint32_t cw, uint32_t ch) {
    Terminal *t = (Terminal *)handle;
    if (!t || !columns || columns > 500 || !rows || rows > 300 || !cw || !ch) return 0;
    uint16_t old_columns = t->columns, old_rows = t->rows;
    uint32_t old_width = t->cell_width, old_height = t->cell_height;
    t->columns = columns; t->rows = rows; t->cell_width = cw; t->cell_height = ch;
    if (ghostty_terminal_resize(t->terminal, columns, rows, cw, ch) != GHOSTTY_SUCCESS) {
        t->columns = old_columns; t->rows = old_rows; t->cell_width = old_width; t->cell_height = old_height;
        return 0;
    }
    t->needs_capture = true;
    return ++t->event_revision;
}
uint64_t nova_ghostty_scroll(uintptr_t handle, int32_t rows) {
    Terminal *t = (Terminal *)handle;
    if (!t || !rows) return 0;
    end_hold(t);
    GhosttyTerminalScrollViewport scroll = {.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_DELTA, .value.delta = rows};
    if (ghostty_terminal_scroll_viewport(t->terminal, scroll) != GHOSTTY_SUCCESS) return 0;
    t->needs_capture = true; return ++t->event_revision;
}
uint64_t nova_ghostty_scroll_to_bottom(uintptr_t handle) {
    Terminal *t = (Terminal *)handle;
    if (!t) return 0;
    bool active = false;
    if (ghostty_terminal_get(t->terminal, GHOSTTY_TERMINAL_DATA_VIEWPORT_ACTIVE, &active) != GHOSTTY_SUCCESS || active) return 0;
    end_hold(t);
    GhosttyTerminalScrollViewport scroll = {.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_BOTTOM};
    if (ghostty_terminal_scroll_viewport(t->terminal, scroll) != GHOSTTY_SUCCESS) return 0;
    t->needs_capture = true; return ++t->event_revision;
}
uint64_t nova_ghostty_set_theme(uintptr_t handle, uint32_t fg, uint32_t bg, uint32_t cursor, const uint32_t *palette, size_t count) {
    Terminal *t = (Terminal *)handle;
    if (!t || !palette || count != 16 || fg > 0xffffff || bg > 0xffffff || cursor > 0xffffff) return 0;
    GhosttyColorRgb colors[256];
    if (ghostty_terminal_get(t->terminal, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE_DEFAULT, colors) != GHOSTTY_SUCCESS) return 0;
    for (size_t i = 0; i < 16; i++) { if (palette[i] > 0xffffff) return 0; colors[i] = rgb(palette[i]); }
    GhosttyColorRgb f = rgb(fg), b = rgb(bg), c = rgb(cursor);
    if (ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, colors) != GHOSTTY_SUCCESS ||
        ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &f) != GHOSTTY_SUCCESS ||
        ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &b) != GHOSTTY_SUCCESS ||
        ghostty_terminal_set(t->terminal, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &c) != GHOSTTY_SUCCESS) return 0;
    end_hold(t); t->needs_capture = true; return ++t->event_revision;
}
size_t nova_ghostty_drain_pty(uintptr_t handle, uint8_t *out, size_t capacity) {
    Terminal *t = (Terminal *)handle;
    if (!t || !out) return 0;
    size_t n = t->pty.len < capacity ? t->pty.len : capacity;
    if (n) { memcpy(out, t->pty.data, n); memmove(t->pty.data, t->pty.data + n, t->pty.len - n); t->pty.len -= n; }
    return n;
}
size_t nova_ghostty_take_clipboard(uintptr_t handle, uint8_t *out, size_t capacity) {
    Terminal *t = (Terminal *)handle;
    if (!t) return 0;
    size_t n = t->clipboard.len;
    if (!capacity) return n;
    t->clipboard.len = 0;
    if (!out || n > capacity) return 0;
    if (n) memcpy(out, t->clipboard.data, n);
    return n;
}
int64_t nova_ghostty_hold_remaining_ms(uintptr_t handle) {
    Terminal *t = (Terminal *)handle;
    if (!t || !t->held) return -1;
    uint64_t elapsed = now_ms() - t->hold_started;
    return elapsed >= HOLD_MS ? 0 : (int64_t)(HOLD_MS - elapsed);
}
uint64_t nova_ghostty_tick(uintptr_t handle) {
    Terminal *t = (Terminal *)handle;
    if (!t || nova_ghostty_hold_remaining_ms(handle) != 0) return 0;
    end_hold(t); return ++t->event_revision;
}

static bool encode_row(Terminal *t, Buffer *out, GhosttyRenderStateColors *colors) {
    if (ghostty_render_state_row_get(t->row_iterator, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &t->cells) != GHOSTTY_SUCCESS) return false;
    Buffer text = {0};
    size_t count = 0;
    bool ok = false;
    while (ghostty_render_state_row_cells_next(t->cells)) {
        GhosttyCell raw = 0; GhosttyStyle style = {0}; GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
        uint32_t codepoint = 0;
        GhosttyColorRgb fg = colors->foreground, bg = colors->background;
        if (ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw) != GHOSTTY_SUCCESS ||
            ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style) != GHOSTTY_SUCCESS ||
            ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide) != GHOSTTY_SUCCESS ||
            ghostty_cell_get(raw, GHOSTTY_CELL_DATA_CODEPOINT, &codepoint) != GHOSTTY_SUCCESS) goto done;
        // NO_VALUE doesn't promise to preserve the output argument.
        if (ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &fg) != GHOSTTY_SUCCESS) fg = colors->foreground;
        if (ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &bg) != GHOSTTY_SUCCESS) bg = colors->background;
        bool spacer = wide == GHOSTTY_CELL_WIDE_SPACER_TAIL || wide == GHOSTTY_CELL_WIDE_SPACER_HEAD;
        text.len = 0;
        if (!spacer && codepoint != 0 && codepoint != 0x10eeee) {
            GhosttyBuffer bytes = {.ptr = text.data, .cap = text.cap};
            GhosttyResult result = ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8, &bytes);
            if (result == GHOSTTY_OUT_OF_SPACE) {
                if (!reserve(t, &text, bytes.len)) goto done;
                bytes.ptr = text.data; bytes.cap = text.cap;
                result = ghostty_render_state_row_cells_get(t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8, &bytes);
            }
            if (result != GHOSTTY_SUCCESS) goto done;
            text.len = bytes.len;
        } else if (!spacer && !append(t, &text, " ", 1)) goto done;
        uint32_t attributes = style.flags | (spacer ? 1u << 31 : 0);
        GhosttyColorRgb underline = style.flags & GHOSTTY_STYLE_FLAG_INVERSE ? bg : fg;
        if (style.underline_color.tag == GHOSTTY_STYLE_COLOR_RGB) underline = style.underline_color.value.rgb;
        if (style.underline_color.tag == GHOSTTY_STYLE_COLOR_PALETTE) underline = colors->palette[style.underline_color.value.palette];
        if (!word(t, out, argb(fg)) || !word(t, out, argb(bg)) || !word(t, out, attributes) ||
            !word(t, out, argb(underline)) || !word(t, out, (uint32_t)text.len) || !append(t, out, text.data, text.len)) goto done;
        count++;
    }
    ok = count == t->columns;
done:
    discard(t, &text); return ok;
}

// Image collection uses the public render iterator; the owned pixel copy is
// also held during synchronized output. No borrowed terminal pointer escapes.
static bool capture_images(Terminal *t);

static bool capture(Terminal *t) {
    if (ghostty_render_state_update(t->render, t->terminal) != GHOSTTY_SUCCESS) return false;
    GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
    GhosttyRenderStateCursor cursor = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
    GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FULL;
    if (ghostty_render_state_get(t->render, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors) != GHOSTTY_SUCCESS ||
        ghostty_render_state_get(t->render, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cursor) != GHOSTTY_SUCCESS ||
        ghostty_render_state_get(t->render, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty) != GHOSTTY_SUCCESS ||
        ghostty_render_state_get(t->render, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &t->row_iterator) != GHOSTTY_SUCCESS) return false;
    Row *next = calloc(t->rows, sizeof(*next));
    if (!next) return false;
    size_t y = 0;
    uint64_t revision = t->frame_revision + 1;
    while (ghostty_render_state_row_iterator_next(t->row_iterator)) {
        if (y >= t->rows) goto done;
        bool changed = true;
        if (ghostty_render_state_row_get(t->row_iterator, GHOSTTY_RENDER_STATE_ROW_DATA_ID, &next[y].id) != GHOSTTY_SUCCESS ||
            ghostty_render_state_row_get(t->row_iterator, GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY, &changed) != GHOSTTY_SUCCESS) goto done;
        Row *previous = NULL;
        if (t->frame_columns == t->columns) for (size_t i = 0; i < t->frame_rows; i++) {
            if (same_id(t->frame[i].id, next[y].id)) { previous = &t->frame[i]; break; }
        }
        if (previous && !changed && dirty != GHOSTTY_RENDER_STATE_DIRTY_FULL) next[y] = *previous;
        else {
            if (!encode_row(t, &next[y].cells, &colors)) goto done;
            next[y].changed = revision;
            if (previous && previous->cells.len == next[y].cells.len &&
                memcmp(previous->cells.data, next[y].cells.data, next[y].cells.len) == 0) {
                discard(t, &next[y].cells); next[y] = *previous;
            }
        }
        y++;
    }
    if (y != t->rows || !capture_images(t)) goto done;
    for (size_t i = 0; i < t->frame_rows; i++) {
        bool reused = false;
        for (size_t j = 0; j < t->rows; j++) if (t->frame[i].cells.data == next[j].cells.data) { reused = true; break; }
        if (!reused) discard(t, &t->frame[i].cells);
    }
    free(t->frame); t->frame = next;
    t->frame_rows = t->rows; t->frame_columns = t->columns;
    t->frame_cell_width = t->cell_width; t->frame_cell_height = t->cell_height;
    t->colors = colors; t->cursor = cursor;
    const GhosttyMode modes[] = {GHOSTTY_MODE_MOUSE_EVENT_X10, GHOSTTY_MODE_MOUSE_EVENT_NORMAL,
        GHOSTTY_MODE_MOUSE_EVENT_BUTTON, GHOSTTY_MODE_MOUSE_EVENT_ANY, GHOSTTY_MODE_MOUSE_FORMAT_SGR,
        GHOSTTY_MODE_MOUSE_FORMAT_UTF8, GHOSTTY_MODE_MOUSE_FORMAT_URXVT, GHOSTTY_MODE_MOUSE_FORMAT_SGR_PIXELS,
        GHOSTTY_MODE_BRACKETED_PASTE};
    t->modes = 0;
    for (size_t i = 0; i < sizeof(modes) / sizeof(*modes); i++) if (mode(t, modes[i])) t->modes |= 1u << i;
    uint8_t keyboard = 0;
    ghostty_terminal_get(t->terminal, GHOSTTY_TERMINAL_DATA_KITTY_KEYBOARD_FLAGS, &keyboard);
    t->modes |= (uint32_t)(keyboard & 31) << 9;
    t->previous_frame = t->frame_revision; t->frame_revision = revision;
    t->needs_capture = false; t->capture_failed = false;
    ghostty_render_state_clean(t->render);
    return true;
done:
    for (size_t j = 0; j < t->rows; j++) {
        bool borrowed = false;
        for (size_t i = 0; i < t->frame_rows; i++) if (next[j].cells.data == t->frame[i].cells.data) { borrowed = true; break; }
        if (!borrowed) discard(t, &next[j].cells);
    }
    free(next); return false;
}

// Kept separate for readability, compiled in this translation unit so the
// frame and its resource budget have a single owner.
#include "nova_vt_images.inc"

const uint8_t *nova_ghostty_frame(uintptr_t handle, uint64_t known, uint64_t known_images, size_t *length) {
    Terminal *t = (Terminal *)handle;
    if (!t || !length) return NULL;
    *length = 0;
    (void)nova_ghostty_tick(handle);
    if ((t->held && t->capture_failed) || (!t->held && (t->needs_capture || t->capture_failed) && !capture(t))) return NULL;
    uint64_t base = known >= t->previous_frame && known <= t->frame_revision ? known : 0;
    Buffer *out = &t->packet; out->len = 0;
    if (!reserve(t, out, FRAME_HEADER)) return NULL;
    memset(out->data, 0, FRAME_HEADER); out->len = FRAME_HEADER;
    put32(out->data, FRAME_MAGIC); put32(out->data + 4, 2);
    put64(out->data + 8, t->frame_revision); put64(out->data + 16, base);
    put32(out->data + 24, t->frame_columns); put32(out->data + 28, t->frame_rows);
    bool visible = t->cursor.visible && t->cursor.viewport_has_value;
    put32(out->data + 32, visible ? t->cursor.viewport_x : UINT32_MAX);
    put32(out->data + 36, visible ? t->cursor.viewport_y : UINT32_MAX);
    put32(out->data + 40, (visible ? 1u : 0) | (t->cursor.blinking ? 2u : 0) | ((uint32_t)t->cursor.visual_style << 8));
    put32(out->data + 44, argb(t->colors.foreground)); put32(out->data + 48, argb(t->colors.background));
    put32(out->data + 52, argb(t->colors.cursor_has_value ? t->colors.cursor : t->colors.foreground));
    put32(out->data + 56, t->modes);
    put32(out->data + 72, t->frame_cell_width); put32(out->data + 76, t->frame_cell_height);
    for (size_t i = 0; i < t->frame_rows; i++) {
        Row *row = &t->frame[i];
        if (!wide_word(t, out, row->id.bits[0]) || !wide_word(t, out, row->id.bits[1]) || !wide_word(t, out, row->changed)) return NULL;
    }
    uint32_t count = 0;
    for (size_t i = 0; i < t->frame_rows; i++) if (!base || t->frame[i].changed > base) {
        if (!word(t, out, (uint32_t)i) || !word(t, out, (uint32_t)t->frame[i].cells.len) ||
            !append(t, out, t->frame[i].cells.data, t->frame[i].cells.len)) return NULL;
        count++;
    }
    put32(out->data + 60, count); put32(out->data + 64, (uint32_t)out->len);
    bool pixels = known_images != t->image_revision;
    if (!word(t, out, 0x5846474e) || !word(t, out, 1) || !wide_word(t, out, t->image_revision) ||
        !word(t, out, pixels ? (uint32_t)t->asset_count : 0) || !word(t, out, (uint32_t)t->placement_count) ||
        (pixels && !append(t, out, t->asset_bytes.data, t->asset_bytes.len)) ||
        !append(t, out, t->placements.data, t->placements.len)) return NULL;
    *length = out->len; return out->data;
}
const uint8_t *nova_ghostty_snapshot(uintptr_t handle, uint64_t images, size_t *length) {
    return nova_ghostty_frame(handle, 0, images, length);
}
