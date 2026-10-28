// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: GPL-3.0-only
// Viewport/pin and Unicode placement integration follows Chuchu's MIT reference.
// See third_party/chuchu/README.md. Ghostty retains all protocol/state ownership.
const std = @import("std");
const ghostty = @import("ghostty-vt");
const alloc = std.heap.c_allocator;
pub const max_image_bytes = 8 * 1024 * 1024;
const max_assets = 128;
const max_placements = 512;

fn reply(owner: anytype, response: ghostty.kitty.graphics.Response) void {
    var bytes: [256]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&bytes);
    response.encode(&writer) catch return;
    owner.pty_writes.appendSlice(alloc, writer.buffered()) catch {};
}

/// Apply app limits before calling Ghostty, without reparsing escape sequences.
pub fn finishCommand(owner: anytype, handler: *ghostty.TerminalStream.Handler) void {
    const gpa = owner.memory.allocator();
    var command = handler.apc_handler.end() orelse return;
    defer command.deinit(gpa);
    const cmd = switch (command) {
        .kitty => |*v| v,
    };
    const storage = &owner.terminal.screens.active.kitty_images;
    var invalid = false;
    if (cmd.transmission()) |t| {
        const pending = if (storage.loading) |loading| loading.data.items.len else 0;
        invalid = t.width > 4096 or t.height > 4096 or
            @as(u64, t.width) * t.height * 4 > max_image_bytes or
            pending + cmd.data.len > max_image_bytes or
            (storage.images.count() >= max_assets and storage.imageById(t.image_id) == null);
    }
    if (cmd.display()) |d| {
        invalid = invalid or d.columns > 10000 or d.rows > 10000 or
            d.x > 4096 or d.y > 4096 or d.width > 4096 or d.height > 4096 or
            d.x_offset > 10000 or d.y_offset > 10000 or
            (storage.placements.count() >= max_placements);
    }
    if (invalid) {
        const previous_id: u32 = if (storage.loading) |loading| loading.image.id else 0;
        const quiet = if (storage.loading) |loading| loading.quiet else cmd.quiet;
        if (storage.loading) |loading| {
            loading.destroy(gpa);
            storage.loading = null;
        }
        if (quiet != .failures) {
            const t = cmd.transmission();
            const d = cmd.display();
            const id = if (t) |v| v.image_id else if (d) |v| v.image_id else 0;
            reply(owner, .{ .id = if (id != 0) id else previous_id, .message = "ENOSPC: NovaScale image limit" });
        }
        return;
    }
    if (owner.terminal.kittyGraphics(gpa, cmd)) |response| reply(owner, response);
}

const Placement = struct {
    image: u32,
    z: i32,
    left: f32,
    top: f32,
    width: f32,
    height: f32,
    x: u32,
    y: u32,
    source_width: u32,
    source_height: u32,
    id: u32,
    internal: u32,
};

fn word(list: *std.ArrayListUnmanaged(u8), comptime T: type, value: T) !void {
    const little = std.mem.nativeToLittle(T, value);
    try list.appendSlice(alloc, std.mem.asBytes(&little));
}
fn float(list: *std.ArrayListUnmanaged(u8), value: f32) !void {
    try word(list, u32, @bitCast(value));
}

pub fn appendSnapshot(owner: anytype, known_revision: u64) !void {
    const storage = &owner.terminal.screens.active.kitty_images;
    var ids: [max_assets]u32 = undefined;
    var count: usize = 0;
    var images = storage.images.iterator();
    while (images.next()) |entry| {
        if (count == max_assets) break;
        ids[count] = entry.key_ptr.*;
        count += 1;
    }
    std.mem.sort(u32, ids[0..count], {}, std.sort.asc(u32));
    var hash = std.hash.Wyhash.init(0);
    for (ids[0..count]) |id| {
        const image = storage.imageById(id).?;
        std.hash.autoHash(&hash, id);
        std.hash.autoHash(&hash, image.transmit_time);
    }
    const image_hash = hash.final();
    if (owner.image_hash != image_hash) {
        owner.image_revision +%= 1;
        owner.image_hash = image_hash;
    }

    var placements: [max_placements]Placement = undefined;
    var n: usize = 0;
    const pages = &owner.terminal.screens.active.pages;
    const top = pages.pointFromPin(.screen, pages.getTopLeft(.viewport)).?;
    const cw: f32 = @floatFromInt(owner.cell_width);
    const ch: f32 = @floatFromInt(owner.cell_height);
    var it = storage.placements.iterator();
    while (it.next()) |entry| {
        if (n == max_placements) break;
        const p = entry.value_ptr.*;
        const pin = switch (p.location) {
            .pin => |v| v.*,
            .virtual => continue,
        };
        const point = pages.pointFromPin(.screen, pin) orelse continue;
        const image = storage.imageById(entry.key_ptr.image_id) orelse continue;
        const size = p.pixelSize(image, &owner.terminal);
        const x = @min(p.source_x, image.width);
        const y = @min(p.source_y, image.height);
        const sw = @min(if (p.source_width == 0) image.width else p.source_width, image.width - x);
        const sh = @min(if (p.source_height == 0) image.height else p.source_height, image.height - y);
        const left = @as(f32, @floatFromInt(point.screen.x)) + @as(f32, @floatFromInt(p.x_offset)) / cw;
        const row = @as(i64, @intCast(point.screen.y)) - @as(i64, @intCast(top.screen.y));
        const py = @as(f32, @floatFromInt(row)) + @as(f32, @floatFromInt(p.y_offset)) / ch;
        const width = @as(f32, @floatFromInt(size.width)) / cw;
        const height = @as(f32, @floatFromInt(size.height)) / ch;
        if (sw == 0 or sh == 0 or width == 0 or height == 0 or py + height <= 0 or py >= @as(f32, @floatFromInt(owner.rows))) continue;
        placements[n] = .{ .image = image.id, .z = p.z, .left = left, .top = py, .width = width, .height = height, .x = x, .y = y, .source_width = sw, .source_height = sh, .id = entry.key_ptr.placement_id.id, .internal = if (entry.key_ptr.placement_id.tag == .internal) 1 else 0 };
        n += 1;
    }
    // Unicode placeholders are rendered from Ghostty's grapheme-aware iterator.
    const bot = pages.getBottomRight(.viewport) orelse pages.getTopLeft(.viewport);
    var virtual = ghostty.kitty.graphics.unicode.placementIterator(pages.getTopLeft(.viewport), bot);
    while (virtual.next()) |p| {
        if (n == max_placements) break;
        const image = storage.imageById(p.image_id) orelse continue;
        const r = p.renderPlacement(storage, &image, owner.cell_width, owner.cell_height) catch continue;
        const point = pages.pointFromPin(.viewport, r.top_left) orelse continue;
        if (r.source_width == 0 or r.source_height == 0 or r.dest_width == 0 or r.dest_height == 0) continue;
        var z: i32 = 0;
        var known = storage.placements.iterator();
        while (known.next()) |entry| {
            if (entry.key_ptr.image_id == image.id and entry.value_ptr.location == .virtual and
                (p.placement_id == 0 or (entry.key_ptr.placement_id.tag == .external and entry.key_ptr.placement_id.id == p.placement_id)))
            {
                z = entry.value_ptr.z;
                break;
            }
        }
        placements[n] = .{ .image = image.id, .z = z, .left = @as(f32, @floatFromInt(point.viewport.x)) + @as(f32, @floatFromInt(r.offset_x)) / cw, .top = @as(f32, @floatFromInt(point.viewport.y)) + @as(f32, @floatFromInt(r.offset_y)) / ch, .width = @as(f32, @floatFromInt(r.dest_width)) / cw, .height = @as(f32, @floatFromInt(r.dest_height)) / ch, .x = r.source_x, .y = r.source_y, .source_width = r.source_width, .source_height = r.source_height, .id = p.placement_id, .internal = 0 };
        n += 1;
    }
    const list = &owner.snapshot_buffer;
    try word(list, u32, 0x5846474e); // NGFX
    try word(list, u32, 1);
    try word(list, u64, owner.image_revision);
    try word(list, u32, @intCast(if (known_revision != owner.image_revision) count else 0));
    try word(list, u32, @intCast(n));
    if (known_revision != owner.image_revision) for (ids[0..count]) |id| {
        const image = storage.imageById(id).?;
        const len: usize = @as(usize, image.width) * image.height * 4;
        if (len > max_image_bytes) return error.InvalidData;
        try word(list, u32, id);
        try word(list, u32, image.width);
        try word(list, u32, image.height);
        try word(list, u32, @intCast(len));
        if (image.format == .rgba) try list.appendSlice(alloc, image.data) else {
            if (image.format != .rgb) return error.InvalidData;
            var i: usize = 0;
            while (i < image.data.len) : (i += 3) {
                try list.appendSlice(alloc, image.data[i .. i + 3]);
                try list.append(alloc, 255);
            }
        }
    };
    for (placements[0..n]) |p| {
        try word(list, u32, p.image);
        try word(list, i32, p.z);
        try float(list, p.left);
        try float(list, p.top);
        try float(list, p.width);
        try float(list, p.height);
        try word(list, u32, p.x);
        try word(list, u32, p.y);
        try word(list, u32, p.source_width);
        try word(list, u32, p.source_height);
        try word(list, u32, p.id);
        try word(list, u32, p.internal);
    }
}
