// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
#include "nova_vt.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static uint32_t u32(const uint8_t *p) { return (uint32_t)p[0] | (uint32_t)p[1]<<8 | (uint32_t)p[2]<<16 | (uint32_t)p[3]<<24; }
static uint64_t u64(const uint8_t *p) { return u32(p) | (uint64_t)u32(p+4)<<32; }
static void feed(uintptr_t t, const char *s) { nova_ghostty_write(t, (const uint8_t *)s, strlen(s)); }
static uint8_t *frame(uintptr_t t, uint64_t known, size_t *length) {
    const uint8_t *borrowed = nova_ghostty_frame(t, known, 0, length);
    assert(borrowed && *length >= 104 && u32(borrowed) == 0x4654564e && u32(borrowed+4) == 2);
    uint8_t *copy = malloc(*length); assert(copy); memcpy(copy, borrowed, *length); return copy;
}
static void first_row(uintptr_t t, char *out, size_t capacity) {
    size_t length; uint8_t *p = frame(t, 0, &length);
    size_t offset = 80 + u32(p+28)*24;
    assert(u32(p+offset) == 0); offset += 8;
    size_t used = 0;
    for (uint32_t col = 0; col < u32(p+24); col++) {
        assert(offset+20 <= length); size_t count = u32(p+offset+16); offset += 20;
        assert(offset+count <= length);
        if (count && count < capacity-used) { memcpy(out+used, p+offset, count); used += count; }
        offset += count;
    }
    out[used] = 0; free(p);
}
int main(void) {
    uintptr_t t = nova_ghostty_create(20, 4, 10000); assert(t);
    feed(t, "READY");
    size_t length; uint8_t *full = frame(t, 0, &length);
    uint64_t revision = u64(full+8);
    assert(u32(full+60) == 4);
    uint8_t *clean = frame(t, revision, &length);
    assert(u32(clean+60) == 0 && u64(clean+16) == revision);
    free(clean); free(full);
    feed(t, "\x1b[2;1Hchanged");
    uint8_t *delta = frame(t, revision, &length);
    assert(u32(delta+60) == 1 && u64(delta+16) == revision);
    free(delta);
    char text[128];
    feed(t, "\x1b[1;1H\x1b[?2026h\r\x1b[2KHALF");
    first_row(t, text, sizeof(text)); assert(strncmp(text, "READY", 5) == 0);
    feed(t, "\r\x1b[2KCOMPLETE\x1b[?2026l\x1b[?2026h\r\x1b[2KINCOMPLETE");
    first_row(t, text, sizeof(text)); assert(strncmp(text, "COMPLETE", 8) == 0);
    assert(nova_ghostty_hold_remaining_ms(t) >= 0);
    struct timespec wait = {.tv_sec=1,.tv_nsec=100000000}; nanosleep(&wait, NULL);
    assert(nova_ghostty_tick(t) != 0 && nova_ghostty_hold_remaining_ms(t) == -1);
    first_row(t, text, sizeof(text)); assert(strncmp(text, "INCOMPLETE", 10) == 0);
    feed(t, "\x1b[?2026hnew");
    nova_ghostty_resize(t, 21, 4, 8, 16);
    assert(nova_ghostty_hold_remaining_ms(t) == -1);
    nova_ghostty_destroy(t);

    t = nova_ghostty_create(20, 4, 10000); assert(t);
    feed(t, "a\r\nb\r\nc\r\nd");
    full = frame(t, 0, &length); size_t full_length = length;
    revision = u64(full+8);
    feed(t, "\r\ne");
    delta = frame(t, revision, &length);
    assert(u32(delta+60) == 1 && u64(delta+16) == revision);
    for (unsigned row = 0; row < 3; row++) assert(memcmp(full+80+(row+1)*24, delta+80+row*24, 24) == 0);
    printf("Scrolling fixture packet: full=%zu bytes, delta=%zu bytes\n", full_length, length);
    free(full); free(delta);
    nova_ghostty_destroy(t);

    t = nova_ghostty_create(20, 4, 0); assert(t);
    feed(t, "\x1b_Ga=T,f=32,s=1,v=1,i=5,c=1,r=1,C=1;/wAA/w==\x1b\\");
    feed(t, "\x1b[?2026h\x1b_Ga=T,f=32,s=1,v=1,i=5,c=1,r=1,C=1;AP8A/w==\x1b\\");
    full = frame(t, 0, &length);
    size_t image = u32(full+64);
    assert(u32(full+image+16) == 1 && full[image+40] == 255 && full[image+41] == 0);
    free(full);
    feed(t, "\x1b[?2026l");
    full = frame(t, 0, &length); image = u32(full+64);
    assert(u32(full+image+16) == 1 && full[image+40] == 0 && full[image+41] == 255);
    free(full); nova_ghostty_destroy(t);

    t = nova_ghostty_create(20, 4, 0); assert(t);
    feed(t, "\x1b[4;7;31;44mX\x1b[58;2;1;2;3mY");
    full = frame(t, 0, &length);
    size_t cell = 80 + 4*24 + 8;
    assert(u32(full+cell+4) == u32(full+cell+12)); // Default underline follows inverse foreground.
    cell += 20 + u32(full+cell+16);
    assert(u32(full+cell+12) == 0xff010203); // Explicit underline color is not inverted.
    free(full); nova_ghostty_destroy(t);
    puts("PASS: C API terminal, full/delta/scrolling rows, synchronized frames and pixels, consecutive holds, idle timeout and resize release");
    return 0;
}
