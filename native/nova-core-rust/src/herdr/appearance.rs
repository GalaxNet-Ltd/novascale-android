// Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0
use super::*;
use crate::model::TerminalThemeConfig;

const DIM: u16 = 2;
const REVERSE_OR_HIDDEN: u16 = (1 << 6) | (1 << 7);
type Rgb = [u8; 3];

/// Presentation state is independent of the retained server surface. A fixed
/// direct-mapped cache bounds memory even with arbitrary truecolor output.
pub(super) struct Appearance {
    theme: Option<TerminalThemeConfig>,
    enabled: bool,
    focused: bool,
    cache: [Option<(u32, bool, u32)>; 256],
}
impl Default for Appearance {
    fn default() -> Self {
        Self {
            theme: None,
            enabled: true,
            focused: true,
            cache: [None; 256],
        }
    }
}

fn rgb(value: u32) -> Rgb {
    [(value >> 16) as u8, (value >> 8) as u8, value as u8]
}
fn packed([r, g, b]: Rgb) -> u32 {
    0x02000000 | (u32::from(r) << 16) | (u32::from(g) << 8) | u32::from(b)
}
fn palette(theme: &TerminalThemeConfig, index: usize) -> Rgb {
    match index {
        0..=15 => rgb(theme.ansi[index]),
        16..=231 => {
            let cube = index - 16;
            [cube / 36, cube / 6 % 6, cube % 6]
                .map(|v| if v == 0 { 0 } else { (55 + 40 * v) as u8 })
        }
        _ => [((index - 232) * 10 + 8) as u8; 3],
    }
}
fn luminance(color: Rgb) -> f64 {
    // sRGB relative luminance. Cached once, including quantization to the same
    // 8-bit channels used by Canvas, rather than powf per cell per frame.
    static LINEAR: std::sync::OnceLock<[f64; 256]> = std::sync::OnceLock::new();
    let table = LINEAR.get_or_init(|| {
        std::array::from_fn(|i| {
            let c = i as f64 / 255.0;
            if c <= 0.04045 {
                c / 12.92
            } else {
                ((c + 0.055) / 1.055).powf(2.4)
            }
        })
    });
    table[color[0] as usize] * 0.2126
        + table[color[1] as usize] * 0.7152
        + table[color[2] as usize] * 0.0722
}
fn ratio(fg: Rgb, bg: Rgb, dim: bool) -> f64 {
    // TerminalCanvasView dims each channel to floor(channel * 2 / 3).
    // This is intentionally different from a blend toward the background.
    let fg = if dim {
        fg.map(|c| (u16::from(c) * 2 / 3) as u8)
    } else {
        fg
    };
    let a = luminance(fg);
    let b = luminance(bg);
    (a.max(b) + 0.05) / (a.min(b) + 0.05)
}
fn readable_foreground(fg: Rgb, bg: Rgb, dim: bool) -> u32 {
    let minimum = if dim { 3.0 } else { 4.5 };
    let original = ratio(fg, bg, dim);
    if original >= minimum {
        return 0;
    }
    let end = if ratio([255; 3], bg, dim) > ratio([0; 3], bg, dim) {
        [255; 3]
    } else {
        [0; 3]
    };
    if ratio(end, bg, dim) <= original {
        return 0;
    }
    if ratio(end, bg, dim) < minimum {
        return packed(end);
    }
    // Find the smallest quantized move toward a readable endpoint. Preserve
    // the theme's hue as far as the required contrast allows.
    let mix = |amount: u32| {
        std::array::from_fn(|i| {
            ((u32::from(fg[i]) * (255 - amount) + u32::from(end[i]) * amount + 127) / 255) as u8
        })
    };
    let (mut low, mut high) = (0, 255);
    while low < high {
        let mid = (low + high) / 2;
        if ratio(mix(mid), bg, dim) >= minimum {
            high = mid;
        } else {
            low = mid + 1;
        }
    }
    packed(mix(high))
}

impl Appearance {
    pub(super) fn apply(&mut self, frame: &mut FrameData) {
        let Some(theme) = self.theme.as_ref().filter(|_| self.enabled) else {
            return;
        };
        for cell in &mut frame.cells {
            if cell.fg != 0 || cell.bg == 0 || cell.skip || cell.modifier & REVERSE_OR_HIDDEN != 0 {
                continue;
            }
            let dim = cell.modifier & DIM != 0;
            let slot = ((cell.bg ^ (cell.bg >> 16)) as usize + usize::from(dim)) % self.cache.len();
            if let Some((_, _, fg)) =
                self.cache[slot].filter(|(bg, faint, _)| *bg == cell.bg && *faint == dim)
            {
                cell.fg = fg;
                continue;
            }
            let bg = match cell.bg >> 24 {
                0 if (1..=16).contains(&cell.bg) => palette(theme, cell.bg as usize - 1),
                1 => palette(theme, (cell.bg & 255) as usize),
                2 => rgb(cell.bg),
                _ => continue,
            };
            let fg = readable_foreground(rgb(theme.foreground), bg, dim);
            self.cache[slot] = Some((cell.bg, dim, fg));
            cell.fg = fg;
        }
    }
}

impl Client {
    pub fn set_theme(&mut self, theme: TerminalThemeConfig) -> Result<Output, String> {
        if !theme.is_valid() {
            return Err("Invalid Herdr host theme".into());
        }
        self.appearance.theme = Some(theme);
        self.appearance.cache.fill(None);
        self.repaint_pending = true;
        let mut out = Output::default();
        self.render_frame(&mut out)?;
        if self.welcome.is_some() {
            self.send_host_theme(&mut out)?;
        }
        Ok(out)
    }

    pub fn set_display(
        &mut self,
        improve_text_contrast: bool,
        focused: bool,
    ) -> Result<Output, String> {
        let mut out = Output::default();
        if self.appearance.enabled != improve_text_contrast {
            self.appearance.enabled = improve_text_contrast;
            self.repaint_pending = true;
            self.render_frame(&mut out)?;
        }
        if self.appearance.focused != focused {
            self.appearance.focused = focused;
            if self.welcome.is_some() {
                send(&mut out, ClientMessage::ClientShellFocus { focused })?;
            }
        }
        Ok(out)
    }

    pub(super) fn send_host_theme(&self, out: &mut Output) -> Result<(), String> {
        if let Some(theme) = &self.appearance.theme {
            let color = |[r, g, b]: Rgb| ClientHostColor { r, g, b };
            for (kind, value) in [
                (ClientHostDefaultColorKind::Foreground, theme.foreground),
                (ClientHostDefaultColorKind::Background, theme.background),
            ] {
                send(
                    out,
                    ClientMessage::ClientShellHostTheme {
                        update: ClientHostThemeUpdate::DefaultColor {
                            kind,
                            color: color(rgb(value)),
                        },
                    },
                )?;
            }
            send(
                out,
                ClientMessage::ClientShellHostTheme {
                    update: ClientHostThemeUpdate::PaletteColors(
                        (0..256)
                            .map(|i| (i as u8, color(palette(theme, i))))
                            .collect(),
                    ),
                },
            )?;
            let dark = ratio([255; 3], rgb(theme.background), false)
                > ratio([0; 3], rgb(theme.background), false);
            send(
                out,
                ClientMessage::ClientShellHostTheme {
                    update: ClientHostThemeUpdate::Appearance(if dark {
                        ClientHostAppearance::Dark
                    } else {
                        ClientHostAppearance::Light
                    }),
                },
            )?;
        }
        // Promote updated colors only for the displayed attachment. Hidden
        // retained sessions must not take theme ownership from another client.
        if self.appearance.focused {
            send(out, ClientMessage::ClientShellFocus { focused: false })?;
        }
        send(
            out,
            ClientMessage::ClientShellFocus {
                focused: self.appearance.focused,
            },
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn theme(fg: u32, bg: u32) -> TerminalThemeConfig {
        TerminalThemeConfig {
            foreground: fg,
            background: bg,
            cursor: fg,
            ansi: vec![0x303959; 16],
        }
    }
    fn client() -> Client {
        let mut c = navigation::tests::client();
        let cell = &mut c.surface.as_mut().unwrap().frame.cells[0];
        cell.symbol = "X".into();
        cell.bg = 0x02303959;
        c
    }
    fn messages(out: &Output) -> Vec<ClientMessage> {
        let mut bytes = out.outbound.as_slice();
        let mut result = Vec::new();
        while !bytes.is_empty() {
            let length = u32::from_le_bytes(bytes[..4].try_into().unwrap()) as usize;
            let (msg, used) = bincode::serde::decode_from_slice(
                &bytes[4..4 + length],
                bincode::config::standard(),
            )
            .unwrap();
            assert_eq!(length, used);
            result.push(msg);
            bytes = &bytes[4 + length..];
        }
        result
    }

    #[test]
    fn stale_prompt_text_uses_android_faint_math_and_preserves_other_styles() {
        let mut c = client();
        c.set_theme(theme(0, 0xffffff)).unwrap();
        for modifiers in [0, DIM, DIM | 1 | (1 << 2) | (1 << 3)] {
            let mut frame = c.surface.as_ref().unwrap().frame.clone();
            frame.cells[0].modifier = modifiers;
            c.appearance.apply(&mut frame);
            let cell = &frame.cells[0];
            assert_eq!(cell.bg, 0x02303959);
            assert_eq!(cell.modifier, modifiers);
            assert_eq!(cell.fg >> 24, 2);
            assert!(
                ratio(rgb(cell.fg), rgb(cell.bg), modifiers & DIM != 0)
                    >= if modifiers & DIM != 0 { 3.0 } else { 4.5 }
            );
        }
        // Midtone backgrounds still preserve faint instead of silently
        // dropping the application's emphasis to improve readability.
        let fg = readable_foreground([100; 3], [90; 3], true);
        assert!(ratio(rgb(fg), [90; 3], true) >= 3.0);
    }

    #[test]
    fn explicit_colors_hidden_reverse_continuations_and_readable_text_are_exact() {
        let mut c = client();
        c.set_theme(theme(0, 0xffffff)).unwrap();
        for variant in 0..7 {
            let mut frame = c.surface.as_ref().unwrap().frame.clone();
            let cell = &mut frame.cells[0];
            match variant {
                0 => cell.fg = 0x02303959,
                1 => cell.fg = 1,
                2 => cell.fg = 0x010000f0,
                3 => cell.bg = 0,
                4 => cell.modifier = 1 << 6,
                5 => cell.modifier = 1 << 7,
                _ => cell.skip = true,
            }
            let raw = frame.clone();
            c.appearance.apply(&mut frame);
            assert_eq!(frame, raw);
        }
        for (fg, bg) in [(0xffffff, 0x303959), (0, 0xf4f4f4)] {
            c.set_theme(theme(fg, bg)).unwrap();
            for dim in [0, DIM] {
                let mut frame = c.surface.as_ref().unwrap().frame.clone();
                frame.cells[0].bg = 0x02000000 | bg;
                frame.cells[0].modifier = dim;
                let raw = frame.clone();
                c.appearance.apply(&mut frame);
                assert_eq!(frame, raw);
            }
        }
    }

    #[test]
    fn palette_and_theme_changes_invalidate_bounded_color_cache() {
        let mut c = client();
        c.set_theme(theme(0, 0xffffff)).unwrap();
        for bg in [1, 0x01000000, 0x01000011, 0x02303959] {
            let mut frame = c.surface.as_ref().unwrap().frame.clone();
            frame.cells[0].bg = bg;
            c.appearance.apply(&mut frame);
            assert_ne!(frame.cells[0].fg, 0);
        }
        c.set_theme(theme(0xffffff, 0)).unwrap();
        let mut frame = c.surface.as_ref().unwrap().frame.clone();
        c.appearance.apply(&mut frame);
        assert_eq!(frame.cells[0].fg, 0);
        for bg in 0..5000 {
            frame.cells[0].fg = 0;
            frame.cells[0].bg = 0x02000000 | bg;
            c.appearance.apply(&mut frame);
        }
        assert_eq!(c.appearance.cache.len(), 256);
    }

    #[test]
    fn toggling_repaints_raw_frame_and_popup_without_remote_bytes_or_target_change() {
        let mut c = client();
        let raw = c.surface.as_ref().unwrap().frame.clone();
        c.surface.as_mut().unwrap().popup = Some(Box::new(ClientShellPopupSurface {
            terminal_id: "popup".into(),
            title: "contrast".into(),
            width: None,
            height: None,
            frame: raw.clone(),
            mouse_reporting: false,
            sgr_pixel_mouse: false,
            pixel_width: 0,
            pixel_height: 0,
        }));
        let before = c.target_epoch();
        assert!(
            String::from_utf8(c.set_theme(theme(0, 0xffffff)).unwrap().ansi)
                .unwrap()
                .contains("38;2;")
        );
        for enabled in [false, true, false] {
            let out = c.set_display(enabled, true).unwrap();
            assert!(out.outbound.is_empty());
            assert_eq!(
                String::from_utf8(out.ansi).unwrap().contains("38;2;"),
                enabled
            );
            assert_eq!(c.target_epoch(), before);
            assert_eq!(c.surface.as_ref().unwrap().frame, raw);
            assert_eq!(
                c.surface.as_ref().unwrap().popup.as_ref().unwrap().frame,
                raw
            );
        }
    }

    #[test]
    fn host_theme_sends_full_physical_palette_and_hidden_clients_do_not_take_focus() {
        let mut c = client();
        let out = c.set_theme(theme(0, 0xffffff)).unwrap();
        let sent = messages(&out);
        assert_eq!(sent.len(), 6);
        let ClientMessage::ClientShellHostTheme {
            update: ClientHostThemeUpdate::PaletteColors(colors),
        } = &sent[2]
        else {
            panic!("palette missing");
        };
        assert_eq!(colors.len(), 256);
        for (index, expected) in [
            (0, [48, 57, 89]),
            (16, [0; 3]),
            (17, [0, 0, 95]),
            (231, [255; 3]),
            (255, [238; 3]),
        ] {
            let (i, color) = colors[index];
            assert_eq!(i as usize, index);
            assert_eq!([color.r, color.g, color.b], expected);
        }
        assert!(matches!(
            sent[3],
            ClientMessage::ClientShellHostTheme {
                update: ClientHostThemeUpdate::Appearance(ClientHostAppearance::Light)
            }
        ));
        assert!(matches!(
            sent[4],
            ClientMessage::ClientShellFocus { focused: false }
        ));
        assert!(matches!(
            sent[5],
            ClientMessage::ClientShellFocus { focused: true }
        ));
        c.set_display(true, false).unwrap();
        let hidden = messages(&c.set_theme(theme(0xffffff, 0)).unwrap());
        assert_eq!(hidden.len(), 5);
        assert!(matches!(
            hidden[4],
            ClientMessage::ClientShellFocus { focused: false }
        ));
        assert!(c.set_display(false, false).unwrap().outbound.is_empty());
        assert!(matches!(
            messages(&c.set_display(false, true).unwrap())[0],
            ClientMessage::ClientShellFocus { focused: true }
        ));
    }

    #[test]
    fn server_patches_keep_raw_colors_and_presentation_changes_send_no_scroll() {
        let mut c = client();
        c.set_theme(theme(0, 0xffffff)).unwrap();
        let updated = CellData {
            symbol: "中".into(),
            fg: 0,
            bg: 0x02303030,
            modifier: DIM,
            skip: false,
            hyperlink: None,
        };
        let patch = PaneSurfacePatch {
            boot_id: "boot".into(),
            projection_revision: 1,
            base_surface_revision: 1,
            surface_revision: 2,
            rows: vec![PaneSurfacePatchRow {
                x: 0,
                y: 0,
                cells: vec![updated.clone()],
            }],
            panes: vec![],
            cursor: None,
        };
        let mut wire = Vec::new();
        protocol::write_message(&mut wire, &ServerMessage::PaneSurfacePatch(patch)).unwrap();
        let out = c.receive(&wire).unwrap();
        assert!(out.outbound.is_empty());
        assert!(String::from_utf8(out.ansi).unwrap().contains("38;2;"));
        assert_eq!(c.surface.as_ref().unwrap().frame.cells[0], updated);
        c.welcome
            .as_mut()
            .unwrap()
            .methods
            .push("pane.scroll".into());
        c.surface.as_mut().unwrap().panes[0].scroll = Some(PaneSurfaceScrollMetrics {
            offset_from_bottom: 0,
            max_offset_from_bottom: 100,
            viewport_rows: 1,
        });
        c.input(b"\x1b[<64;1;1M\x1b[<64;1;1M").unwrap();
        assert!(
            !c.action_pending(),
            "relative scroll needs no endpoint acknowledgement"
        );
        assert!(c.set_display(false, true).unwrap().outbound.is_empty());
        assert_eq!(c.surface.as_ref().unwrap().frame.cells[0], updated);
    }
}
