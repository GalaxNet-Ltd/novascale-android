// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
// Wuffs remains the checksum-pinned PNG decoder. This shim uses Ghostty's
// public allocator and callback ABI, never Ghostty's Zig terminal internals.
const std = @import("std");
// These three frozen declarations mirror ghostty/vt/{allocator,sys}.h.
const Allocator = opaque {};
const Image = extern struct { width: u32, height: u32, data: ?[*]u8, data_len: usize };
extern fn ghostty_alloc(allocator: ?*const Allocator, len: usize) ?[*]u8;
extern fn ghostty_free(allocator: ?*const Allocator, data: [*]u8, len: usize) void;
pub const std_options_debug_io: std.Io = .failing;

const PngAllocator = struct {
    handle: ?*const Allocator,
    fn alloc(ctx: *anyopaque, len: usize, alignment: std.mem.Alignment, _: usize) ?[*]u8 {
        if (alignment.toByteUnits() > 64) return null;
        const self: *PngAllocator = @ptrCast(@alignCast(ctx));
        return ghostty_alloc(self.handle, len);
    }
    fn free(ctx: *anyopaque, memory: []u8, _: std.mem.Alignment, _: usize) void {
        const self: *PngAllocator = @ptrCast(@alignCast(ctx));
        ghostty_free(self.handle, memory.ptr, memory.len);
    }
    const vtable: std.mem.Allocator.VTable = .{
        .alloc = alloc,
        .resize = std.mem.Allocator.noResize,
        .remap = std.mem.Allocator.noRemap,
        .free = free,
    };
};

export fn nova_vt_decode_png(_: ?*anyopaque, allocator: ?*const Allocator, data: [*c]const u8, length: usize, out: ?*Image) callconv(.c) bool {
    if (data == null or out == null or length < 24) return false;
    const input = data[0..length];
    if (!std.mem.eql(u8, input[0..8], "\x89PNG\r\n\x1a\n")) return false;
    const width = std.mem.readInt(u32, input[16..20], .big);
    const height = std.mem.readInt(u32, input[20..24], .big);
    if (width == 0 or height == 0 or width > 4096 or height > 4096 or
        @as(u64, width) * height * 4 > 8 * 1024 * 1024) return false;
    var context: PngAllocator = .{ .handle = allocator };
    const result = @import("wuffs").png.decode(.{ .ptr = &context, .vtable = &PngAllocator.vtable }, input) catch return false;
    out.?.* = .{ .width = result.width, .height = result.height, .data = result.data.ptr, .data_len = result.data.len };
    return true;
}
