// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: GPL-3.0-only
//
// Terminal setup and snapshot serialization are adapted from Chuchu's MIT
// chuchu_snapshot.zig. See third_party/chuchu/README.md for the exact pin.

const std = @import("std");
const ghostty = @import("ghostty-vt");

const allocator = std.heap.c_allocator;
const graphics = @import("kitty_snapshot.zig");
const Budget = @import("budget_allocator.zig").Budget;
var decoder_once = std.once(installDecoder);

fn installDecoder() void {
    ghostty.sys.decode_png = decodePng;
}
const snapshot_header_i32_count = 14;
const snapshot_cell_size = 11;
const cell_flag_grapheme: u8 = 1 << 6;
const cell_flag_spacer: u8 = 1 << 7;
const kitty_placeholder: u32 = 0x10EEEE;
const osc_payload_capacity = 32;

const OscQueryState = enum {
    ground,
    escape,
    osc,
    osc_escape,
};

const OscQueryScanner = struct {
    state: OscQueryState = .ground,
    payload: [osc_payload_capacity]u8 = undefined,
    len: u8 = 0,
    overflowed: bool = false,
};

const DeviceAttributes = blk: {
    const optional = @FieldType(ghostty.TerminalStream.Handler.Effects, "device_attributes");
    const function = @typeInfo(optional).optional.child;
    break :blk @typeInfo(@typeInfo(function).pointer.child).@"fn".return_type.?;
};

const Stream = ghostty.Stream(*StreamHandler);

const NovaTerminal = struct {
    memory: Budget = .{},
    image_hash: u64 = 0,
    image_revision: u64 = 1,
    terminal: ghostty.Terminal,
    render_state: ghostty.RenderState = .empty,
    stream_handler: StreamHandler = undefined,
    stream: Stream = undefined,
    columns: u16,
    rows: u16,
    cell_width: u32 = 8,
    cell_height: u32 = 16,
    revision: u64 = 0,
    snapshot_buffer: std.ArrayListUnmanaged(u8) = .empty,
    snapshot_extras: std.ArrayListUnmanaged(u32) = .empty,
    pty_writes: std.ArrayListUnmanaged(u8) = .empty,
    clipboard_write: std.ArrayListUnmanaged(u8) = .empty,
    osc_query_scanner: OscQueryScanner = .{},
};

const StreamHandler = struct {
    owner: *NovaTerminal,
    inner: ghostty.TerminalStream.Handler,

    pub fn deinit(self: *StreamHandler) void {
        self.inner.deinit();
    }

    pub fn vt(
        self: *StreamHandler,
        comptime action: Stream.Action.Tag,
        value: Stream.Action.Value(action),
    ) void {
        if (action == .clipboard_contents) {
            // OSC 52 writes only. Never answer clipboard read requests.
            if ((value.kind == 'c' or value.kind == 'p' or value.kind == 0) and
                value.data.len > 0 and value.data.len <= 1_398_104 and !std.mem.eql(u8, value.data, "?"))
            {
                self.owner.clipboard_write.clearRetainingCapacity();
                self.owner.clipboard_write.appendSlice(allocator, value.data) catch {};
            }
        }
        if (action == .apc_end) {
            graphics.finishCommand(self.owner, &self.inner);
            return;
        }
        self.inner.vt(action, value);
    }
};

fn fromHandle(handle: usize) ?*NovaTerminal {
    if (handle == 0) return null;
    return @ptrFromInt(handle);
}

fn terminalFromHandler(handler: *ghostty.TerminalStream.Handler) *NovaTerminal {
    return @fieldParentPtr("terminal", handler.terminal);
}

fn writePty(handler: *ghostty.TerminalStream.Handler, data: [:0]const u8) void {
    terminalFromHandler(handler).pty_writes.appendSlice(allocator, data) catch {};
}

fn deviceAttributes(_: *ghostty.TerminalStream.Handler) DeviceAttributes {
    return .{};
}

fn terminalSize(handler: *ghostty.TerminalStream.Handler) ?ghostty.size_report.Size {
    const terminal = terminalFromHandler(handler);
    return .{
        .rows = terminal.rows,
        .columns = terminal.columns,
        .cell_width = terminal.cell_width,
        .cell_height = terminal.cell_height,
    };
}

fn xtversion(_: *ghostty.TerminalStream.Handler) []const u8 {
    return "NovaScale/libghostty-vt";
}

fn decodePng(alloc: std.mem.Allocator, data: []const u8) ghostty.sys.DecodeError!ghostty.sys.Image {
    // Reject oversized IHDR dimensions before Wuffs allocates its output buffer.
    if (data.len < 24 or !std.mem.eql(u8, data[0..8], "\x89PNG\r\n\x1a\n")) return error.InvalidData;
    const width = std.mem.readInt(u32, data[16..20], .big);
    const height = std.mem.readInt(u32, data[20..24], .big);
    if (width == 0 or height == 0 or width > 4096 or height > 4096 or
        @as(u64, width) * height * 4 > graphics.max_image_bytes) return error.InvalidData;
    const result = @import("wuffs").png.decode(alloc, data) catch |err| switch (err) {
        error.OutOfMemory => return error.OutOfMemory,
        else => return error.InvalidData,
    };
    return .{ .width = result.width, .height = result.height, .data = result.data };
}

fn beginOscQuery(scanner: *OscQueryScanner) void {
    scanner.state = .osc;
    scanner.len = 0;
    scanner.overflowed = false;
}

fn appendOscQueryByte(scanner: *OscQueryScanner, byte: u8) void {
    if (scanner.overflowed) return;
    if (scanner.len == scanner.payload.len) {
        scanner.overflowed = true;
        return;
    }
    scanner.payload[scanner.len] = byte;
    scanner.len += 1;
}

fn colorForOscQuery(terminal: *NovaTerminal, payload: []const u8) ?struct {
    code: []const u8,
    color: ghostty.color.RGB,
} {
    if (std.mem.eql(u8, payload, "10;?")) {
        return .{
            .code = "10",
            .color = terminal.terminal.colors.foreground.get() orelse .{
                .r = 0xF2,
                .g = 0xF2,
                .b = 0xF2,
            },
        };
    }
    if (std.mem.eql(u8, payload, "11;?")) {
        return .{
            .code = "11",
            .color = terminal.terminal.colors.background.get() orelse .{},
        };
    }
    if (std.mem.eql(u8, payload, "12;?")) {
        return .{
            .code = "12",
            .color = terminal.terminal.colors.cursor.get() orelse .{
                .r = 0xF2,
                .g = 0xF2,
                .b = 0xF2,
            },
        };
    }
    return null;
}

fn finishOscQuery(terminal: *NovaTerminal) void {
    const scanner = &terminal.osc_query_scanner;
    defer {
        scanner.state = .ground;
        scanner.len = 0;
        scanner.overflowed = false;
    }
    if (scanner.overflowed) return;
    const result = colorForOscQuery(terminal, scanner.payload[0..scanner.len]) orelse return;
    const red: u16 = @as(u16, result.color.r) * 0x101;
    const green: u16 = @as(u16, result.color.g) * 0x101;
    const blue: u16 = @as(u16, result.color.b) * 0x101;
    var response_buffer: [64]u8 = undefined;
    const response = std.fmt.bufPrint(
        &response_buffer,
        "\x1b]{s};rgb:{x:0>4}/{x:0>4}/{x:0>4}\x1b\\",
        .{ result.code, red, green, blue },
    ) catch return;
    terminal.pty_writes.appendSlice(allocator, response) catch {};
}

/// The pinned libghostty-vt TerminalStream applies OSC color changes but does
/// not answer OSC 10/11/12 queries. Full-screen TUIs such as Codex use those
/// replies to derive contrast-safe surfaces, so the NovaScale adapter supplies
/// the active theme colors. The scanner is incremental because an escape
/// sequence may be split across SSH channel reads.
fn inspectOscColorQueries(terminal: *NovaTerminal, data: []const u8) void {
    const scanner = &terminal.osc_query_scanner;
    for (data) |byte| {
        switch (scanner.state) {
            .ground => switch (byte) {
                0x1b => scanner.state = .escape,
                0x9d => beginOscQuery(scanner),
                else => {},
            },
            .escape => switch (byte) {
                ']' => beginOscQuery(scanner),
                0x1b => {},
                else => scanner.state = .ground,
            },
            .osc => switch (byte) {
                0x07, 0x9c => finishOscQuery(terminal),
                0x1b => scanner.state = .osc_escape,
                else => appendOscQueryByte(scanner, byte),
            },
            .osc_escape => switch (byte) {
                '\\' => finishOscQuery(terminal),
                else => {
                    appendOscQueryByte(scanner, 0x1b);
                    appendOscQueryByte(scanner, byte);
                    scanner.state = .osc;
                },
            },
        }
    }
}

fn updateRenderState(terminal: *NovaTerminal) bool {
    terminal.render_state.update(terminal.memory.allocator(), &terminal.terminal) catch return false;
    return true;
}

export fn nova_ghostty_create(columns: u32, rows: u32, max_scrollback: u32) callconv(.c) usize {
    if (columns == 0 or columns > std.math.maxInt(u16) or rows == 0 or rows > std.math.maxInt(u16)) return 0;
    decoder_once.call();

    const result = allocator.create(NovaTerminal) catch return 0;
    var success = false;
    defer if (!success) allocator.destroy(result);
    result.* = .{ .terminal = undefined, .columns = @intCast(columns), .rows = @intCast(rows) };
    const alloc = result.memory.allocator();
    const inner = ghostty.Terminal.init(alloc, .{
        .cols = @intCast(columns),
        .rows = @intCast(rows),
        .max_scrollback = @intCast(@min(max_scrollback, 100_000)),
        .kitty_image_storage_limit = graphics.max_image_bytes,
        .kitty_image_loading_limits = .{
            .file = false,
            .temporary_file = false,
            .shared_memory = false,
        },
    }) catch return 0;
    result.terminal = inner;
    result.terminal.width_px = columns * 8;
    result.terminal.height_px = rows * 16;
    var handler = result.terminal.vtHandler();
    handler.effects = .{
        .write_pty = writePty,
        .bell = null,
        .color_scheme = null,
        .device_attributes = deviceAttributes,
        .enquiry = null,
        .size = terminalSize,
        .title_changed = null,
        .xtversion = xtversion,
    };
    result.stream_handler = .{ .owner = result, .inner = handler };
    result.stream = Stream.initAlloc(alloc, &result.stream_handler);
    result.stream.nextSlice("\x1b[?2027h");
    if (!updateRenderState(result)) {
        result.stream.deinit();
        result.terminal.deinit(alloc);
        return 0;
    }
    success = true;
    return @intFromPtr(result);
}

export fn nova_ghostty_destroy(handle: usize) callconv(.c) void {
    const terminal = fromHandle(handle) orelse return;
    terminal.snapshot_buffer.deinit(allocator);
    terminal.snapshot_extras.deinit(allocator);
    terminal.pty_writes.deinit(allocator);
    terminal.clipboard_write.deinit(allocator);
    terminal.render_state.deinit(terminal.memory.allocator());
    terminal.stream.deinit();
    terminal.terminal.deinit(terminal.memory.allocator());
    allocator.destroy(terminal);
}

export fn nova_ghostty_write(handle: usize, data_ptr: ?[*]const u8, data_len: usize) callconv(.c) u64 {
    const terminal = fromHandle(handle) orelse return 0;
    const pointer = data_ptr orelse return terminal.revision;
    if (data_len == 0) return terminal.revision;
    const data = pointer[0..data_len];
    inspectOscColorQueries(terminal, data);
    terminal.stream.nextSlice(data);
    _ = updateRenderState(terminal);
    terminal.revision +%= 1;
    return terminal.revision;
}

export fn nova_ghostty_resize(
    handle: usize,
    columns: u32,
    rows: u32,
    cell_width: u32,
    cell_height: u32,
) callconv(.c) u64 {
    const terminal = fromHandle(handle) orelse return 0;
    if (columns == 0 or columns > std.math.maxInt(u16) or rows == 0 or rows > std.math.maxInt(u16)) return terminal.revision;
    terminal.terminal.resize(terminal.memory.allocator(), @intCast(columns), @intCast(rows)) catch return terminal.revision;
    terminal.columns = @intCast(columns);
    terminal.rows = @intCast(rows);
    terminal.cell_width = @max(cell_width, 1);
    terminal.cell_height = @max(cell_height, 1);
    terminal.terminal.width_px = columns *| terminal.cell_width;
    terminal.terminal.height_px = rows *| terminal.cell_height;
    terminal.terminal.modes.set(.synchronized_output, false);
    _ = updateRenderState(terminal);
    terminal.revision +%= 1;
    return terminal.revision;
}

/// Moves Ghostty's render viewport without modifying the terminal contents.
/// Negative rows reveal older scrollback; positive rows return toward the
/// active screen. Ghostty clamps both ends of the retained history.
export fn nova_ghostty_scroll(handle: usize, rows: i32) callconv(.c) u64 {
    const terminal = fromHandle(handle) orelse return 0;
    if (rows == 0) return terminal.revision;
    terminal.terminal.scrollViewport(.{ .delta = @intCast(rows) });
    _ = updateRenderState(terminal);
    terminal.revision +%= 1;
    return terminal.revision;
}

/// Returns zero when the viewport was already at the active screen. A
/// non-zero revision means the viewport moved and a new snapshot is needed.
export fn nova_ghostty_scroll_to_bottom(handle: usize) callconv(.c) u64 {
    const terminal = fromHandle(handle) orelse return 0;
    if (terminal.terminal.screens.active.viewportIsBottom()) return 0;
    terminal.terminal.scrollViewport(.bottom);
    _ = updateRenderState(terminal);
    terminal.revision +%= 1;
    return terminal.revision;
}

fn rgb(value: u32) ghostty.color.RGB {
    return .{
        .r = @truncate(value >> 16),
        .g = @truncate(value >> 8),
        .b = @truncate(value),
    };
}

export fn nova_ghostty_set_theme(
    handle: usize,
    foreground: u32,
    background: u32,
    cursor: u32,
    ansi_ptr: ?[*]const u32,
    ansi_len: usize,
) callconv(.c) u64 {
    const terminal = fromHandle(handle) orelse return 0;
    const ansi = ansi_ptr orelse return terminal.revision;
    if (ansi_len != 16 or foreground > 0xFFFFFF or background > 0xFFFFFF or cursor > 0xFFFFFF) {
        return terminal.revision;
    }
    var palette = terminal.terminal.colors.palette.original;
    for (ansi[0..16], 0..) |value, index| {
        if (value > 0xFFFFFF) return terminal.revision;
        palette[index] = rgb(value);
    }
    terminal.terminal.colors.foreground.default = rgb(foreground);
    terminal.terminal.colors.background.default = rgb(background);
    terminal.terminal.colors.cursor.default = rgb(cursor);
    terminal.terminal.colors.palette.changeDefault(palette);
    terminal.terminal.flags.dirty.palette = true;
    _ = updateRenderState(terminal);
    terminal.revision +%= 1;
    return terminal.revision;
}

export fn nova_ghostty_drain_pty(handle: usize, output: ?[*]u8, capacity: usize) callconv(.c) usize {
    const terminal = fromHandle(handle) orelse return 0;
    const destination = output orelse return 0;
    const count = @min(capacity, terminal.pty_writes.items.len);
    if (count == 0) return 0;
    @memcpy(destination[0..count], terminal.pty_writes.items[0..count]);
    const remaining = terminal.pty_writes.items.len - count;
    if (remaining > 0) {
        std.mem.copyForwards(u8, terminal.pty_writes.items[0..remaining], terminal.pty_writes.items[count..]);
    }
    terminal.pty_writes.shrinkRetainingCapacity(remaining);
    return count;
}

fn ensureBytes(list: *std.ArrayListUnmanaged(u8), size: usize) ?[]u8 {
    list.ensureTotalCapacityPrecise(allocator, size) catch return null;
    list.items.len = size;
    return list.items;
}

fn writeLittle(comptime T: type, buffer: []u8, offset: usize, value: T) void {
    const little = std.mem.nativeToLittle(T, value);
    @memcpy(buffer[offset .. offset + @sizeOf(T)], std.mem.asBytes(&little));
}

fn resolvedStyle(cell: ghostty.RenderState.Cell) ghostty.Style {
    return if (cell.raw.style_id != 0) cell.style else .{};
}

fn resolvedCodepoint(cell: ghostty.RenderState.Cell) u32 {
    if (cell.raw.wide == .spacer_tail or cell.raw.wide == .spacer_head) return 32;
    const codepoint = cell.raw.codepoint();
    if (codepoint == 0 or codepoint == kitty_placeholder) return 32;
    return codepoint;
}

fn hasGraphemeExtras(cell: ghostty.RenderState.Cell) bool {
    if (cell.raw.wide == .spacer_tail or cell.raw.wide == .spacer_head) return false;
    if (cell.raw.codepoint() == kitty_placeholder) return false;
    return cell.raw.content_tag == .codepoint_grapheme and cell.grapheme.len > 0;
}

fn cursorShapeCode(style: ghostty.CursorStyle) i32 {
    return switch (style) {
        .block, .block_hollow => 0,
        .bar => 1,
        .underline => 2,
    };
}

export fn nova_ghostty_snapshot(handle: usize, known_image_revision: u64, out_size: ?*usize) callconv(.c) ?[*]const u8 {
    const terminal = fromHandle(handle) orelse return null;
    const size_result = out_size orelse return null;
    if (!updateRenderState(terminal)) return null;

    const columns: usize = terminal.render_state.cols;
    const rows: usize = terminal.render_state.rows;
    const header_size = snapshot_header_i32_count * @sizeOf(i32);
    const base_size = header_size + columns * rows * snapshot_cell_size;
    var buffer = ensureBytes(&terminal.snapshot_buffer, base_size) orelse return null;

    writeLittle(i32, buffer, 0, @intCast(columns));
    writeLittle(i32, buffer, 4, @intCast(rows));
    writeLittle(i32, buffer, 8, if (terminal.render_state.cursor.viewport) |cursor| cursor.x else -1);
    writeLittle(i32, buffer, 12, if (terminal.render_state.cursor.viewport) |cursor| cursor.y else -1);
    writeLittle(i32, buffer, 16, if (terminal.render_state.cursor.visible and terminal.render_state.cursor.viewport != null) 1 else 0);
    writeLittle(i32, buffer, 20, terminal.render_state.colors.background.r);
    writeLittle(i32, buffer, 24, terminal.render_state.colors.background.g);
    writeLittle(i32, buffer, 28, terminal.render_state.colors.background.b);
    writeLittle(i32, buffer, 32, terminal.render_state.colors.foreground.r);
    writeLittle(i32, buffer, 36, terminal.render_state.colors.foreground.g);
    writeLittle(i32, buffer, 40, terminal.render_state.colors.foreground.b);
    writeLittle(i32, buffer, 44, 0);
    writeLittle(i32, buffer, 48, cursorShapeCode(terminal.render_state.cursor.visual_style));
    // Snapshot mouse ABI: tracking bits 0..3, encoding bits 4..7.
    var mouse_flags: i32 = 0;
    inline for (.{ .mouse_event_x10, .mouse_event_normal, .mouse_event_button, .mouse_event_any, .mouse_format_sgr, .mouse_format_utf8, .mouse_format_urxvt, .mouse_format_sgr_pixels }, 0..) |mode, bit| {
        if (terminal.terminal.modes.get(mode)) mouse_flags |= @as(i32, 1) << @intCast(bit);
    }
    const keyboard_flags: i32 = terminal.terminal.screens.active.kitty_keyboard.current().int();
    writeLittle(i32, buffer, 52, mouse_flags |
        (if (terminal.terminal.modes.get(.bracketed_paste)) @as(i32, 256) else 0) |
        (keyboard_flags << 9));

    terminal.snapshot_extras.clearRetainingCapacity();
    var extras_count: usize = 0;
    var cell_index: usize = 0;
    const row_slice = terminal.render_state.row_data.slice();
    const row_cells = row_slice.items(.cells);
    for (row_cells[0..rows]) |cells| {
        const cell_slice = cells.slice();
        const raw_cells = cell_slice.items(.raw);
        const graphemes = cell_slice.items(.grapheme);
        const styles = cell_slice.items(.style);
        for (0..columns) |column| {
            const offset = header_size + cell_index * snapshot_cell_size;
            const render_cell: ghostty.RenderState.Cell = .{
                .raw = raw_cells[column],
                .grapheme = graphemes[column],
                .style = styles[column],
            };
            const style = resolvedStyle(render_cell);
            const foreground = style.fg(.{
                .default = terminal.render_state.colors.foreground,
                .palette = &terminal.render_state.colors.palette,
            });
            const background = style.bg(&render_cell.raw, &terminal.render_state.colors.palette) orelse terminal.render_state.colors.background;
            var flags: u8 = 0;
            if (style.flags.bold) flags |= 1 << 0;
            if (style.flags.italic) flags |= 1 << 1;
            if (style.flags.underline != .none) flags |= 1 << 2;
            if (style.flags.inverse) flags |= 1 << 3;
            if (style.flags.blink) flags |= 1 << 4;
            if (style.flags.faint) flags |= 1 << 5;
            if (render_cell.raw.wide == .spacer_tail or render_cell.raw.wide == .spacer_head) flags |= cell_flag_spacer;
            if (hasGraphemeExtras(render_cell)) {
                flags |= cell_flag_grapheme;
                terminal.snapshot_extras.append(allocator, @intCast(cell_index)) catch return null;
                terminal.snapshot_extras.append(allocator, @intCast(render_cell.grapheme.len)) catch return null;
                for (render_cell.grapheme) |codepoint| {
                    terminal.snapshot_extras.append(allocator, @intCast(codepoint)) catch return null;
                }
                extras_count += 1;
            }
            writeLittle(i32, buffer, offset, @intCast(resolvedCodepoint(render_cell)));
            buffer[offset + 4] = foreground.r;
            buffer[offset + 5] = foreground.g;
            buffer[offset + 6] = foreground.b;
            buffer[offset + 7] = background.r;
            buffer[offset + 8] = background.g;
            buffer[offset + 9] = background.b;
            buffer[offset + 10] = flags;
            cell_index += 1;
        }
    }

    const extras_offset = base_size;
    const total_size = base_size + @sizeOf(u32) + terminal.snapshot_extras.items.len * @sizeOf(u32);
    buffer = ensureBytes(&terminal.snapshot_buffer, total_size) orelse return null;
    writeLittle(i32, buffer, 44, @intCast(extras_offset));
    writeLittle(u32, buffer, extras_offset, @intCast(extras_count));
    var offset = extras_offset + @sizeOf(u32);
    for (terminal.snapshot_extras.items) |word| {
        writeLittle(u32, buffer, offset, word);
        offset += @sizeOf(u32);
    }
    // Always append a versioned graphics trailer after the grapheme records.
    graphics.appendSnapshot(terminal, known_image_revision) catch return null;
    writeLittle(i32, terminal.snapshot_buffer.items, 52, mouse_flags |
        (if (terminal.terminal.modes.get(.bracketed_paste)) @as(i32, 256) else 0) |
        (keyboard_flags << 9) | (1 << 14));
    if (terminal.snapshot_buffer.capacity > @max(1024 * 1024, terminal.snapshot_buffer.items.len * 2)) {
        terminal.snapshot_buffer.shrinkAndFree(allocator, terminal.snapshot_buffer.items.len);
    }
    size_result.* = terminal.snapshot_buffer.items.len;
    return terminal.snapshot_buffer.items.ptr;
}

// A one-shot bounded control event, not a network data-plane transport.
export fn nova_ghostty_take_clipboard(handle: usize, output: [*]u8, capacity: usize) callconv(.c) usize {
    const terminal = fromHandle(handle) orelse return 0;
    const count = terminal.clipboard_write.items.len;
    if (capacity == 0) return count;
    defer terminal.clipboard_write.clearRetainingCapacity();
    if (count > capacity) return 0;
    @memcpy(output[0..count], terminal.clipboard_write.items);
    return count;
}
