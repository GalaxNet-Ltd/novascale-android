// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
#ifndef NOVA_VT_H
#define NOVA_VT_H
#include <stddef.h>
#include <stdint.h>

// All calls for a handle are serialized by its Rust owner. Returned buffers
// belong to the handle and must be copied before another call mutates it.
uintptr_t nova_ghostty_create(uint32_t columns, uint32_t rows, uint32_t history);
uintptr_t nova_ghostty_create_bounded(uint32_t columns, uint32_t rows, uint32_t history);
void nova_ghostty_destroy(uintptr_t handle);
uint64_t nova_ghostty_write(uintptr_t handle, const uint8_t *bytes, size_t length);
uint64_t nova_ghostty_resize(uintptr_t handle, uint32_t columns, uint32_t rows,
                           uint32_t cell_width, uint32_t cell_height);
uint64_t nova_ghostty_scroll(uintptr_t handle, int32_t rows);
uint64_t nova_ghostty_scroll_to_bottom(uintptr_t handle);
uint64_t nova_ghostty_set_theme(uintptr_t handle, uint32_t foreground,
                              uint32_t background, uint32_t cursor,
                              const uint32_t *palette, size_t count);
size_t nova_ghostty_drain_pty(uintptr_t handle, uint8_t *out, size_t capacity);
// Clipboard writes are already decoded UTF-8 by the public VT API.
size_t nova_ghostty_take_clipboard(uintptr_t handle, uint8_t *out, size_t capacity);
const uint8_t *nova_ghostty_snapshot(uintptr_t handle, uint64_t images, size_t *length);
const uint8_t *nova_ghostty_frame(uintptr_t handle, uint64_t frame, uint64_t images, size_t *length);
// -1 means no hold. The runtime sleeps only while a hold has a deadline.
int64_t nova_ghostty_hold_remaining_ms(uintptr_t handle);
uint64_t nova_ghostty_tick(uintptr_t handle);
#endif
