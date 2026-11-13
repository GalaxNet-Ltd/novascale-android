// Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0
use super::*;
use std::time::{Duration, Instant};

/// Reopening a retained attachment is distinct from resizing its terminal.
/// Reset only the pane visible at that point; subsequent output and resizes
/// must not repeatedly pull a user (or another attached client) out of history.
#[derive(Default)]
pub(super) struct ViewportReset {
    requested: bool,
    serial: u64,
    pending: Option<PendingReset>,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scrolled() -> Client {
        let mut c = navigation::tests::client();
        c.welcome
            .as_mut()
            .unwrap()
            .methods
            .push("pane.scroll".into());
        c.surface.as_mut().unwrap().panes[0].scroll = Some(PaneSurfaceScrollMetrics {
            offset_from_bottom: 60,
            max_offset_from_bottom: 100,
            viewport_rows: 1,
        });
        c
    }
    fn surface(c: &mut Client, offset: u64) -> Output {
        let mut s = c.surface.clone().unwrap();
        s.surface_revision += 1;
        s.panes[0].scroll.as_mut().unwrap().offset_from_bottom = offset;
        let mut wire = Vec::new();
        protocol::write_message(&mut wire, &ServerMessage::PaneSurface(s)).unwrap();
        c.receive(&wire).unwrap()
    }
    fn ack(c: &mut Client) -> Output {
        let id = c.viewport.pending.as_ref().unwrap().wire_id.clone();
        let mut out = Output::default();
        c.response("boot", &id, true, b"{\"error\":null}", &mut out)
            .unwrap();
        out
    }

    #[test]
    fn reopening_waits_for_ack_and_bottom_surface_in_either_order() {
        for ack_first in [false, true] {
            let mut c = scrolled();
            c.input(b"\x1b[").unwrap();
            let out = c.resume_viewport().unwrap();
            let (message, _): (ClientMessage, usize) =
                bincode::serde::decode_from_slice(&out.outbound[4..], bincode::config::standard())
                    .unwrap();
            let ClientMessage::ClientShellEndpointRequest { boot_id, request } = message else {
                panic!("not an endpoint request");
            };
            assert_eq!(boot_id, "boot");
            let request: serde_json::Value = serde_json::from_str(&request).unwrap();
            assert_eq!(request["method"], "pane.scroll");
            assert_eq!(
                request["params"],
                json!({"pane_id":"p", "offset_from_bottom":0})
            );
            assert_eq!(out.ready, Some(false));
            assert!(out.ansi.is_empty());
            assert_eq!(c.input_parser.pending_len(), 0);
            assert!(c.input(b"stale frame").unwrap().outbound.is_empty());
            assert!(
                c.resume_viewport().unwrap().outbound.is_empty(),
                "coalesce repeat opens while restoring"
            );
            let first = if ack_first {
                ack(&mut c)
            } else {
                surface(&mut c, 0)
            };
            assert_eq!(first.ready, Some(false));
            let last = if ack_first {
                surface(&mut c, 0)
            } else {
                ack(&mut c)
            };
            assert_eq!(last.ready, Some(true));
            assert!(!last.ansi.is_empty());
            assert!(c.target_epoch().unwrap() > 1);
            assert!(!c.action_pending());

            // A later manual scroll and an ordinary keyboard/layout resize
            // remain scrolled, until the screen is explicitly opened again.
            assert!(surface(&mut c, 40).outbound.is_empty());
            let resized = c.resize(1, 1).unwrap();
            let (message, used): (ClientMessage, usize) = bincode::serde::decode_from_slice(
                &resized.outbound[4..],
                bincode::config::standard(),
            )
            .unwrap();
            assert!(matches!(message, ClientMessage::ClientShellResize { .. }));
            assert_eq!(used + 4, resized.outbound.len());
            assert_eq!(
                c.surface.as_ref().unwrap().panes[0]
                    .scroll
                    .unwrap()
                    .offset_from_bottom,
                40
            );
            assert!(!c.resume_viewport().unwrap().outbound.is_empty());
            assert_eq!(
                c.viewport.pending.as_ref().unwrap().wire_id,
                "nova-viewport-2"
            );
        }
    }

    #[test]
    fn reopening_defers_until_resized_surface_matches() {
        let mut c = scrolled();
        c.resize(2, 1).unwrap();
        let out = c.resume_viewport().unwrap();
        assert!(out.outbound.is_empty());
        assert_eq!(out.ready, Some(false));
        let frame = &mut c.surface.as_mut().unwrap().frame;
        frame.width = 2;
        frame.cells.push(frame.cells[0].clone());
        let out = surface(&mut c, 60);
        assert!(!out.outbound.is_empty());
        assert_eq!(out.ready, Some(false));
    }

    #[test]
    fn already_bottom_alternate_screen_and_unsupported_server_are_noops() {
        for case in 0..3 {
            let mut c = scrolled();
            match case {
                0 => {
                    c.surface.as_mut().unwrap().panes[0]
                        .scroll
                        .as_mut()
                        .unwrap()
                        .offset_from_bottom = 0
                }
                1 => c.surface.as_mut().unwrap().panes[0].alternate_screen_active = true,
                _ => c.welcome.as_mut().unwrap().methods.clear(),
            }
            let out = c.resume_viewport().unwrap();
            assert!(out.outbound.is_empty());
            assert_eq!(out.ready, Some(true));
            assert!(!out.ansi.is_empty(), "retained display must still repaint");
        }
    }

    #[test]
    fn viewport_responses_are_scoped_bounded_and_time_out() {
        let mut c = scrolled();
        c.resume_viewport().unwrap();
        let mut out = Output::default();
        c.response("old-boot", "nova-viewport-1", true, b"{}", &mut out)
            .unwrap();
        c.response("boot", "wrong-id", true, b"{}", &mut out)
            .unwrap();
        assert!(!c.viewport.pending.as_ref().unwrap().acknowledged);
        c.viewport_response("nova-viewport-1", false, b"{\"error\":")
            .unwrap();
        assert!(!c.viewport.pending.as_ref().unwrap().acknowledged);
        assert!(
            c.viewport_response("nova-viewport-1", true, b"{\"message\":\"refused\"}}")
                .is_err()
        );
        let mut c = scrolled();
        c.resume_viewport().unwrap();
        assert!(
            c.viewport_response(
                "nova-viewport-1",
                false,
                &vec![0; navigation::RESPONSE_LIMIT + 1]
            )
            .is_err()
        );
        let mut c = scrolled();
        c.resume_viewport().unwrap();
        c.viewport.pending.as_mut().unwrap().started = Instant::now() - Duration::from_secs(16);
        assert!(c.expire_action().is_err());
        assert_eq!(c.target_epoch(), None);
    }
}
struct PendingReset {
    wire_id: String,
    pane_id: String,
    surface_revision: u64,
    started: Instant,
    bytes: Vec<u8>,
    acknowledged: bool,
}
impl ViewportReset {
    pub(super) fn ready(&self) -> bool {
        !self.requested && self.pending.is_none()
    }
    pub(super) fn in_flight(&self) -> bool {
        self.pending.is_some()
    }
}

impl Client {
    pub fn resume_viewport(&mut self) -> Result<Output, String> {
        let mut out = Output::default();
        if self.viewport.ready() {
            self.viewport.requested = true;
            self.target_epoch = self
                .target_epoch
                .checked_add(1)
                .ok_or("Herdr target epoch exhausted")?;
            self.input_parser = RawInputFramer::default();
            self.input_target = None;
            self.repaint_pending = true;
        }
        self.render(&mut out)?;
        self.publish(&mut out)?;
        Ok(out)
    }

    pub(super) fn restore_viewport(&mut self, out: &mut Output) -> Result<bool, String> {
        if self.viewport.ready() {
            return Ok(true);
        }
        if !self.navigation_ready() {
            return Ok(false);
        }
        let Some((snapshot, surface)) = self.coherent() else {
            return Ok(false);
        };
        if let Some(pending) = &self.viewport.pending {
            // An acknowledgement alone does not prove that the display has
            // caught up. Accept the matching newer surface, in either order.
            let current = snapshot.focused_pane_id.as_ref();
            let restored = current != Some(&pending.pane_id)
                || surface.panes.iter().any(|p| {
                    p.pane_id == pending.pane_id
                        && (p.alternate_screen_active
                            || p.scroll.is_some_and(|s| s.offset_from_bottom == 0))
                });
            if !pending.acknowledged
                || surface.surface_revision <= pending.surface_revision
                || !restored
            {
                return Ok(false);
            }
            self.viewport.pending = None;
            return Ok(true);
        }
        let pane = surface
            .panes
            .iter()
            .find(|p| Some(&p.pane_id) == snapshot.focused_pane_id.as_ref());
        let supported = self
            .welcome
            .as_ref()
            .is_some_and(|w| w.methods.iter().any(|m| m == "pane.scroll"));
        let target = pane
            .filter(|p| {
                supported
                    && surface.popup.is_none()
                    && !p.alternate_screen_active
                    && p.scroll.is_some_and(|s| s.offset_from_bottom > 0)
            })
            .map(|p| {
                (
                    snapshot.boot_id.clone(),
                    p.pane_id.clone(),
                    surface.surface_revision,
                )
            });
        self.viewport.requested = false;
        let Some((boot_id, pane_id, surface_revision)) = target else {
            return Ok(true);
        };
        self.viewport.serial = self
            .viewport
            .serial
            .checked_add(1)
            .ok_or("Herdr viewport serial exhausted")?;
        let wire_id = format!("nova-viewport-{}", self.viewport.serial);
        send(
            out,
            ClientMessage::ClientShellEndpointRequest {
                boot_id,
                request: serde_json::json!({"id":wire_id, "method":"pane.scroll",
                "params":{"pane_id":pane_id, "offset_from_bottom":0}})
                .to_string(),
            },
        )?;
        self.viewport.pending = Some(PendingReset {
            wire_id,
            pane_id,
            surface_revision,
            started: Instant::now(),
            bytes: Vec::new(),
            acknowledged: false,
        });
        Ok(false)
    }

    pub(super) fn viewport_response(
        &mut self,
        request: &str,
        final_chunk: bool,
        bytes: &[u8],
    ) -> Result<bool, String> {
        let Some(pending) = self
            .viewport
            .pending
            .as_mut()
            .filter(|p| p.wire_id == request)
        else {
            return Ok(false);
        };
        if pending.acknowledged {
            return Ok(true);
        }
        if pending.bytes.len().saturating_add(bytes.len()) > navigation::RESPONSE_LIMIT {
            return Err("Herdr viewport response exceeds limit".into());
        }
        pending.bytes.extend_from_slice(bytes);
        if final_chunk {
            #[derive(serde::Deserialize)]
            struct Response {
                #[serde(default)]
                error: Option<serde::de::IgnoredAny>,
            }
            let response: Response = serde_json::from_slice(&pending.bytes)
                .map_err(|_| "Invalid Herdr viewport response")?;
            if response.error.is_some() {
                return Err("Herdr could not restore the live viewport".into());
            }
            pending.bytes.clear();
            pending.acknowledged = true;
        }
        Ok(true)
    }

    pub(super) fn expire_viewport(&self) -> Result<(), String> {
        if self
            .viewport
            .pending
            .as_ref()
            .is_some_and(|p| p.started.elapsed() >= Duration::from_secs(15))
        {
            return Err("Herdr viewport restore timed out".into());
        }
        Ok(())
    }
}
