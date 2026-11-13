// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
// Public C API image access. This is included only by nova_vt.c.
typedef struct {
    uint32_t id, offset_x, offset_y, width, height, source_x, source_y, source_width, source_height, placement_id;
    int32_t x, y, z;
    uint64_t generation;
} Placement;

static int compare_assets(const void *left, const void *right) {
    uint32_t a = ((const AssetKey *)left)->id, b = ((const AssetKey *)right)->id;
    return (a > b) - (a < b);
}
static bool image_metadata(GhosttyKittyGraphics storage, uint32_t id, uint32_t *width,
                           uint32_t *height, GhosttyKittyImageFormat *format, GhosttyString *pixels) {
    GhosttyKittyGraphicsImage image = ghostty_kitty_graphics_image(storage, id);
    if (!image) return false;
    if (ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_WIDTH, width) != GHOSTTY_SUCCESS ||
        ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_HEIGHT, height) != GHOSTTY_SUCCESS ||
        ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_FORMAT, format) != GHOSTTY_SUCCESS ||
        ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_PIXELS, pixels) != GHOSTTY_SUCCESS) return false;
    size_t channels = *format == GHOSTTY_KITTY_IMAGE_FORMAT_RGB ? 3 : *format == GHOSTTY_KITTY_IMAGE_FORMAT_RGBA ? 4 : 0;
    return channels && *width && *width <= 4096 && *height && *height <= 4096 &&
        (uint64_t)*width * *height * 4 <= IMAGE_LIMIT && pixels->len == (size_t)*width * *height * channels;
}
static bool capture_images(Terminal *t) {
    GhosttyKittyGraphics storage = NULL;
    if (ghostty_terminal_get(t->terminal, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS, &storage) != GHOSTTY_SUCCESS ||
        ghostty_render_state_get(t->render, GHOSTTY_RENDER_STATE_DATA_IMAGE_ITERATOR, &t->image_iterator) != GHOSTTY_SUCCESS) return false;
    AssetKey assets[IMAGE_COUNT] = {0};
    size_t asset_count = 0, placement_count = 0, pixel_bytes = 0;
    Buffer placements = {0}, encoded = {0};
    bool success = false;
    while (ghostty_render_state_image_iterator_next(t->image_iterator)) {
        if (placement_count == PLACEMENT_COUNT) break;
        Placement p = {0};
        const GhosttyRenderStateImageData keys[] = {
            GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_ID, GHOSTTY_RENDER_STATE_IMAGE_DATA_IMAGE_GENERATION,
            GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_COL, GHOSTTY_RENDER_STATE_IMAGE_DATA_VIEWPORT_ROW,
            GHOSTTY_RENDER_STATE_IMAGE_DATA_Z, GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_X,
            GHOSTTY_RENDER_STATE_IMAGE_DATA_CELL_OFFSET_Y, GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_WIDTH,
            GHOSTTY_RENDER_STATE_IMAGE_DATA_PIXEL_HEIGHT, GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_X,
            GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_Y, GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_WIDTH,
            GHOSTTY_RENDER_STATE_IMAGE_DATA_SOURCE_HEIGHT, GHOSTTY_RENDER_STATE_IMAGE_DATA_KITTY_PLACEMENT_ID};
        void *values[] = {&p.id, &p.generation, &p.x, &p.y, &p.z, &p.offset_x, &p.offset_y,
            &p.width, &p.height, &p.source_x, &p.source_y, &p.source_width, &p.source_height, &p.placement_id};
        if (ghostty_render_state_image_get_multi(t->image_iterator, sizeof(keys) / sizeof(*keys), keys, values, NULL) != GHOSTTY_SUCCESS) goto done;
        uint32_t width = 0, height = 0;
        GhosttyKittyImageFormat format;
        GhosttyString pixels;
        if (!image_metadata(storage, p.id, &width, &height, &format, &pixels) || !p.width || !p.height ||
            !p.source_width || !p.source_height || (uint64_t)p.source_x + p.source_width > width ||
            (uint64_t)p.source_y + p.source_height > height) continue;
        // Enforce the renderer's destination limits after upstream resolution.
        // One excessive placement must not invalidate the entire text frame.
        float left = (float)p.x + (float)p.offset_x / t->cell_width;
        float top = (float)p.y + (float)p.offset_y / t->cell_height;
        float draw_width = (float)p.width / t->cell_width;
        float draw_height = (float)p.height / t->cell_height;
        if (left < -1000000.f || left > 1000000.f || top < -1000000.f || top > 1000000.f ||
            draw_width > 1000000.f || draw_height > 1000000.f) continue;
        bool found = false;
        for (size_t i = 0; i < asset_count; i++) if (assets[i].id == p.id) { found = true; break; }
        if (!found) {
            size_t bytes = (size_t)width * height * 4;
            if (asset_count == IMAGE_COUNT || bytes > 12u * 1024u * 1024u - pixel_bytes) continue;
            pixel_bytes += bytes;
            assets[asset_count++] = (AssetKey){p.id, p.generation};
        }
        if (!word(t, &placements, p.id) || !word(t, &placements, (uint32_t)p.z) ||
            !real(t, &placements, left) || !real(t, &placements, top) ||
            !real(t, &placements, draw_width) || !real(t, &placements, draw_height) ||
            !word(t, &placements, p.source_x) || !word(t, &placements, p.source_y) ||
            !word(t, &placements, p.source_width) || !word(t, &placements, p.source_height) ||
            !word(t, &placements, p.placement_id) || !word(t, &placements, p.placement_id == 0 ? 1 : 0)) goto done;
        placement_count++;
    }
    qsort(assets, asset_count, sizeof(*assets), compare_assets);
    bool changed = t->image_revision == 0 || asset_count != t->asset_count;
    for (size_t i = 0; i < asset_count && !changed; i++) {
        changed = assets[i].id != t->assets[i].id || assets[i].generation != t->assets[i].generation;
    }
    if (changed) for (size_t i = 0; i < asset_count; i++) {
        uint32_t width = 0, height = 0;
        GhosttyKittyImageFormat format;
        GhosttyString pixels;
        if (!image_metadata(storage, assets[i].id, &width, &height, &format, &pixels)) goto done;
        if (!word(t, &encoded, assets[i].id) || !word(t, &encoded, width) || !word(t, &encoded, height) ||
            !word(t, &encoded, width * height * 4)) goto done;
        if (format == GHOSTTY_KITTY_IMAGE_FORMAT_RGBA) {
            if (!append(t, &encoded, pixels.ptr, pixels.len)) goto done;
        } else {
            size_t bytes = (size_t)width * height * 4;
            if (!reserve(t, &encoded, encoded.len + bytes)) goto done;
            for (size_t src = 0, dest = encoded.len; src < pixels.len; src += 3, dest += 4) {
                memcpy(encoded.data + dest, pixels.ptr + src, 3); encoded.data[dest + 3] = 255;
            }
            encoded.len += bytes;
        }
    }
    if (changed) {
        discard(t, &t->asset_bytes); t->asset_bytes = encoded; encoded = (Buffer){0};
        memcpy(t->assets, assets, asset_count * sizeof(*assets));
        t->asset_count = asset_count; t->image_revision++;
    }
    discard(t, &t->placements); t->placements = placements; placements = (Buffer){0};
    t->placement_count = placement_count;
    success = true;
done:
    discard(t, &encoded); discard(t, &placements); return success;
}
