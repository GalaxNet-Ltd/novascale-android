// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: GPL-3.0-only
const std = @import("std");

/// Caps all Ghostty allocations, including pending uploads and decompression.
/// A terminal is serialized by its Rust mutex, so this allocator is not shared concurrently.
pub const Budget = struct {
    used: usize = 0,
    limit: usize = 64 * 1024 * 1024,

    pub fn allocator(self: *Budget) std.mem.Allocator {
        return .{ .ptr = self, .vtable = &.{ .alloc = alloc, .resize = resize, .remap = remap, .free = free } };
    }
    fn allowed(self: *Budget, old: usize, new: usize) bool {
        return new <= old or new - old <= self.limit -| self.used;
    }
    fn alloc(ctx: *anyopaque, len: usize, alignment: std.mem.Alignment, ra: usize) ?[*]u8 {
        const self: *Budget = @ptrCast(@alignCast(ctx));
        if (!self.allowed(0, len)) return null;
        const ptr = std.heap.c_allocator.rawAlloc(len, alignment, ra) orelse return null;
        self.used += len;
        return ptr;
    }
    fn resize(ctx: *anyopaque, memory: []u8, alignment: std.mem.Alignment, len: usize, ra: usize) bool {
        const self: *Budget = @ptrCast(@alignCast(ctx));
        if (!self.allowed(memory.len, len)) return false;
        if (!std.heap.c_allocator.rawResize(memory, alignment, len, ra)) return false;
        self.used = self.used - memory.len + len;
        return true;
    }
    fn remap(ctx: *anyopaque, memory: []u8, alignment: std.mem.Alignment, len: usize, ra: usize) ?[*]u8 {
        const self: *Budget = @ptrCast(@alignCast(ctx));
        if (!self.allowed(memory.len, len)) return null;
        const ptr = std.heap.c_allocator.rawRemap(memory, alignment, len, ra) orelse return null;
        self.used = self.used - memory.len + len;
        return ptr;
    }
    fn free(ctx: *anyopaque, memory: []u8, alignment: std.mem.Alignment, ra: usize) void {
        const self: *Budget = @ptrCast(@alignCast(ctx));
        std.heap.c_allocator.rawFree(memory, alignment, ra);
        self.used -= memory.len;
    }
};
