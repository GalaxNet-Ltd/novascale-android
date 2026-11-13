// Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0
use super::*;
use crossterm::event::{MouseEvent, MouseEventKind};
fn contains(rect: SurfaceRect, mouse: MouseEvent) -> bool {
    mouse.column >= rect.x
        && mouse.row >= rect.y
        && mouse.column < rect.x.saturating_add(rect.width)
        && mouse.row < rect.y.saturating_add(rect.height)
}

impl Client {
    pub(super) fn scroll_mouse(
        &mut self,
        mouse: MouseEvent,
        out: &mut Output,
    ) -> Result<(), String> {
        let kind = match mouse.kind {
            MouseEventKind::ScrollUp => ClientMouseKind::ScrollUp,
            MouseEventKind::ScrollDown => ClientMouseKind::ScrollDown,
            _ => return Ok(()),
        };
        if self.current_target().is_none() {
            return Ok(());
        }
        let Some((_, surface)) = self.coherent() else {
            return Ok(());
        };
        let event = |rect: SurfaceRect, width_px, height_px| ClientPaneInputEvent::Mouse {
            kind,
            position: ClientMousePosition::Cell {
                column: mouse.column - rect.x,
                row: mouse.row - rect.y,
            },
            geometry: Some(ClientMouseGeometry {
                cols: rect.width,
                rows: rect.height,
                width_px,
                height_px,
            }),
            modifiers: mouse.modifiers.bits(),
            lines: 1,
        };
        // A popup obscures its underlying pane. Never scroll through it.
        if let Some(popup) = &surface.popup {
            let rect = SurfaceRect {
                x: surface.frame.width.saturating_sub(popup.frame.width) / 2,
                y: surface.frame.height.saturating_sub(popup.frame.height) / 2,
                width: popup.frame.width,
                height: popup.frame.height,
            };
            if popup.mouse_reporting && contains(rect, mouse) {
                send(
                    out,
                    ClientMessage::ClientShellPopupInput {
                        terminal_id: popup.terminal_id.clone(),
                        events: vec![event(rect, popup.pixel_width, popup.pixel_height)],
                    },
                )?;
            }
            return Ok(());
        }
        let Some(pane) = surface.panes.iter().find(|p| contains(p.inner_rect, mouse)) else {
            return Ok(());
        };
        // Send relative input against the server's current viewport. Computing
        // absolute offsets from a displayed surface races newer output/scrolls,
        // and waiting for each endpoint response adds a round trip to a swipe.
        // The negotiated input codec handles both application mouse reporting
        // and host history; the server clamps at the history boundaries.
        if !pane.mouse_reporting && (pane.alternate_screen_active || pane.scroll.is_none()) {
            return Ok(());
        }
        send(
            out,
            ClientMessage::ClientShellPaneInput {
                pane_id: pane.pane_id.clone(),
                events: vec![event(pane.inner_rect, pane.pixel_width, pane.pixel_height)],
            },
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::KeyModifiers;

    fn client() -> Client {
        let mut c = navigation::tests::client();
        c.welcome
            .as_mut()
            .unwrap()
            .methods
            .push("pane.scroll".into());
        c.surface.as_mut().unwrap().panes[0].scroll = Some(PaneSurfaceScrollMetrics {
            offset_from_bottom: 0,
            max_offset_from_bottom: 2000,
            viewport_rows: 1,
        });
        c
    }
    fn wheel(c: &mut Client, up: bool) -> Output {
        c.input(if up {
            b"\x1b[<64;1;1M"
        } else {
            b"\x1b[<65;1;1M"
        })
        .unwrap()
    }
    fn message(out: &Output) -> ClientMessage {
        let (m, used) =
            bincode::serde::decode_from_slice(&out.outbound[4..], bincode::config::standard())
                .unwrap();
        assert_eq!(used + 4, out.outbound.len());
        m
    }
    fn direction(out: &Output) -> ClientMouseKind {
        let ClientMessage::ClientShellPaneInput { events, .. } = message(out) else {
            panic!("not pane input");
        };
        let [ClientPaneInputEvent::Mouse { kind, lines: 1, .. }] = events.as_slice() else {
            panic!("not relative scroll");
        };
        *kind
    }
    fn surface(c: &mut Client, offset: u64) -> Output {
        let mut s = c.surface.clone().unwrap();
        s.surface_revision += 1;
        s.panes[0].scroll.as_mut().unwrap().offset_from_bottom = offset;
        let mut wire = Vec::new();
        protocol::write_message(&mut wire, &ServerMessage::PaneSurface(s)).unwrap();
        c.receive(&wire).unwrap()
    }

    #[test]
    fn rapid_gestures_and_direction_reversals_do_not_wait_for_surfaces_or_acks() {
        let mut c = client();
        for _ in 0..1100 {
            assert_eq!(direction(&wheel(&mut c, true)), ClientMouseKind::ScrollUp);
        }
        // An unrelated newer frame still carries an older scroll position.
        // It must never reset the accumulated gesture or send an absolute jump.
        assert!(surface(&mut c, 0).outbound.is_empty());
        assert_eq!(direction(&wheel(&mut c, true)), ClientMouseKind::ScrollUp);
        for _ in 0..30 {
            assert_eq!(
                direction(&wheel(&mut c, false)),
                ClientMouseKind::ScrollDown
            );
        }
        assert!(surface(&mut c, 1101).outbound.is_empty());
        assert_eq!(
            direction(&wheel(&mut c, false)),
            ClientMouseKind::ScrollDown
        );
        assert_eq!(c.target_epoch(), Some(1));
        assert!(!c.action_pending(), "scroll input needs no response timer");
    }

    #[test]
    fn server_clamps_relative_scroll_and_stale_geometry_blocks_input() {
        let mut c = client();
        // Even a surface reporting the bottom may lag queued upward input.
        assert_eq!(
            direction(&wheel(&mut c, false)),
            ClientMouseKind::ScrollDown
        );
        // Relative wheel input is part of the input codec, not pane.scroll RPC.
        c.welcome.as_mut().unwrap().methods.clear();
        assert_eq!(direction(&wheel(&mut c, true)), ClientMouseKind::ScrollUp);
        for case in 0..3 {
            let mut c = client();
            match case {
                0 => c.surface.as_mut().unwrap().panes[0].alternate_screen_active = true,
                1 => c.surface.as_mut().unwrap().panes[0].scroll = None,
                _ => c.surface.as_mut().unwrap().projection_revision += 1,
            }
            assert!(wheel(&mut c, true).outbound.is_empty());
        }
    }

    #[test]
    fn wheel_targets_the_pane_under_the_pointer_and_routes_application_mouse() {
        let mut c = client();
        c.cols = 6;
        c.rows = 4;
        let s = c.surface.as_mut().unwrap();
        s.frame.width = 6;
        s.frame.height = 4;
        s.frame.cells = vec![s.frame.cells[0].clone(); 24];
        let mut right = s.panes[0].clone();
        right.pane_id = "right".into();
        right.focused = false;
        right.rect = SurfaceRect {
            x: 3,
            y: 0,
            width: 3,
            height: 4,
        };
        right.inner_rect = SurfaceRect {
            x: 4,
            y: 1,
            width: 2,
            height: 3,
        };
        s.panes.push(right);
        let out = c.input(b"\x1b[<64;5;2M").unwrap();
        assert!(
            matches!(message(&out), ClientMessage::ClientShellPaneInput { pane_id, .. } if pane_id == "right")
        );
        assert_eq!(
            c.current_target().unwrap().1,
            "p",
            "scroll must not change keyboard focus"
        );
        c.surface.as_mut().unwrap().panes[1].mouse_reporting = true;
        c.surface.as_mut().unwrap().panes[1].alternate_screen_active = true;
        let out = c.input(b"\x1b[<69;6;3M").unwrap(); // Shift + wheel down
        let ClientMessage::ClientShellPaneInput { pane_id, events } = message(&out) else {
            panic!("not semantic input");
        };
        assert_eq!(pane_id, "right");
        assert_eq!(
            events,
            vec![ClientPaneInputEvent::Mouse {
                kind: ClientMouseKind::ScrollDown,
                position: ClientMousePosition::Cell { column: 1, row: 1 },
                geometry: Some(ClientMouseGeometry {
                    cols: 2,
                    rows: 3,
                    width_px: 0,
                    height_px: 0
                }),
                modifiers: KeyModifiers::SHIFT.bits(),
                lines: 1
            }]
        );
        assert!(
            c.input(b"\x1b[<64;4;1M").unwrap().outbound.is_empty(),
            "pane border is not content"
        );
    }

    #[test]
    fn popup_scroll_never_falls_through_to_underlying_history() {
        let mut c = client();
        let s = c.surface.as_mut().unwrap();
        s.popup = Some(Box::new(ClientShellPopupSurface {
            terminal_id: "popup".into(),
            title: "test".into(),
            width: None,
            height: None,
            frame: s.frame.clone(),
            mouse_reporting: false,
            sgr_pixel_mouse: false,
            pixel_width: 0,
            pixel_height: 0,
        }));
        assert!(wheel(&mut c, true).outbound.is_empty());
        c.surface
            .as_mut()
            .unwrap()
            .popup
            .as_mut()
            .unwrap()
            .mouse_reporting = true;
        assert!(
            matches!(message(&wheel(&mut c, true)), ClientMessage::ClientShellPopupInput {terminal_id,..} if terminal_id == "popup")
        );
    }

    #[test]
    fn mixed_input_preserves_wire_order_and_leaves_no_delayed_scroll() {
        let mut c = client();
        let out = c.input(b"\x1b[<64;1;1M\x1b[<65;1;1Mx").unwrap();
        let mut wire = &out.outbound[..];
        for kind in [ClientMouseKind::ScrollUp, ClientMouseKind::ScrollDown] {
            let message: ClientMessage = protocol::read_message(&mut wire, MAX_FRAME_SIZE).unwrap();
            assert!(
                matches!(message, ClientMessage::ClientShellPaneInput { events, .. }
                if matches!(events.as_slice(), [ClientPaneInputEvent::Mouse { kind: k, lines: 1, .. }] if *k == kind))
            );
        }
        let message: ClientMessage = protocol::read_message(&mut wire, MAX_FRAME_SIZE).unwrap();
        assert!(matches!(
            message,
            ClientMessage::ClientShellPaneInput { .. }
        ));
        assert!(wire.is_empty());
        c.resize(2, 1).unwrap();
        assert!(c.flush().unwrap().outbound.is_empty());
    }
}
