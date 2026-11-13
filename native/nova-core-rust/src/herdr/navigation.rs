// Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0
use super::*;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::time::{Duration, Instant};

pub(super) const RESPONSE_LIMIT: usize = 64 * 1024;

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct NativeAction {
    pub method: String,
    pub target_id: String,
    #[serde(default)]
    pub label: Option<String>,
}
impl NativeAction {
    pub fn valid(&self) -> bool {
        matches!(
            self.method.as_str(),
            "workspace.focus"
                | "workspace.create"
                | "tab.focus"
                | "tab.create"
                | "tab.rename"
                | "tab.close"
                | "pane.focus"
        ) && !self.target_id.is_empty()
            && self.target_id.len() <= 256
            && !self.target_id.chars().any(char::is_control)
            && if self.method == "tab.rename" {
                self.label.as_ref().is_some_and(|s| {
                    !s.trim().is_empty()
                        && s.len() <= 1024
                        && s.chars().count() <= 256
                        && !s.chars().any(char::is_control)
                })
            } else {
                self.label.is_none()
            }
    }
}

#[derive(Serialize)]
pub struct ActionResult {
    pub request_id: u64,
    pub code: &'static str,
    pub message: Option<String>,
}
pub(super) struct PendingAction {
    id: u64,
    wire_id: String,
    started: Instant,
    bytes: Vec<u8>,
    acknowledged: bool,
}

#[derive(Deserialize)]
struct Response {
    #[serde(default)]
    error: Option<ResponseError>,
}
#[derive(Deserialize)]
struct ResponseError {
    #[serde(default)]
    message: Option<String>,
}

impl Client {
    pub(super) fn navigation_ready(&self) -> bool {
        self.pending_action.is_none() && self.navigation_revision.is_none()
    }

    pub fn action(&mut self, id: u64, boot: &str, action: NativeAction) -> Result<Output, String> {
        let mut out = Output::default();
        let rejected = |code| Output {
            action_result: Some(ActionResult {
                request_id: id,
                code,
                message: None,
            }),
            ..Output::default()
        };
        if !action.valid() || id == 0 {
            return Ok(rejected("invalid"));
        }
        if !self.navigation_ready() || !self.viewport.ready() {
            return Ok(rejected("busy"));
        }
        let Some((snapshot, _)) = self.coherent() else {
            return Ok(rejected("stale"));
        };
        if snapshot.boot_id != boot {
            return Ok(rejected("stale"));
        }
        if !self
            .welcome
            .as_ref()
            .is_some_and(|w| w.methods.contains(&action.method))
        {
            return Ok(rejected("unsupported"));
        }
        let target = &action.target_id;
        let (params, unchanged) = match action.method.as_str() {
            "workspace.focus" | "workspace.create" | "tab.create" => {
                let Some(item) = snapshot
                    .workspaces
                    .iter()
                    .find(|w| &w.workspace_id == target)
                else {
                    return Ok(rejected("stale"));
                };
                match action.method.as_str() {
                    "workspace.focus" => (json!({"workspace_id": target}), item.focused),
                    "workspace.create" => {
                        (json!({"source_workspace_id": target, "focus": true}), false)
                    }
                    _ => (json!({"workspace_id": target, "focus": true}), false),
                }
            }
            "tab.focus" | "tab.rename" | "tab.close" => {
                let Some(item) = snapshot.tabs.iter().find(|t| &t.tab_id == target) else {
                    return Ok(rejected("stale"));
                };
                if action.method == "tab.rename" {
                    (
                        json!({"tab_id": target, "label": action.label}),
                        action.label.as_deref() == Some(item.label.as_str()),
                    )
                } else {
                    (
                        json!({"tab_id": target}),
                        action.method == "tab.focus" && item.focused,
                    )
                }
            }
            "pane.focus" => {
                if !snapshot.panes.iter().any(|p| &p.pane_id == target)
                    && !snapshot.agents.iter().any(|p| &p.pane_id == target)
                {
                    return Ok(rejected("stale"));
                }
                (
                    json!({"pane_id": target}),
                    snapshot.focused_pane_id.as_ref() == Some(target),
                )
            }
            _ => return Ok(rejected("unsupported")),
        };
        if unchanged {
            return Ok(rejected("ok"));
        }
        let wire_id = format!("nova-android-{id}");
        let message = ClientMessage::ClientShellEndpointRequest {
            boot_id: boot.to_owned(),
            request: json!({"id": wire_id, "method": action.method, "params": params}).to_string(),
        };
        self.navigation_revision = Some(snapshot.revision);
        self.pending_action = Some(PendingAction {
            id,
            wire_id,
            started: Instant::now(),
            bytes: Vec::new(),
            acknowledged: false,
        });
        // Never carry partial keys or displayed-frame input through an endpoint action.
        self.input_parser = RawInputFramer::default();
        self.input_target = None;
        self.target_epoch = self
            .target_epoch
            .checked_add(1)
            .ok_or("Herdr target epoch exhausted")?;
        self.repaint_pending = true;
        send(&mut out, message)?;
        self.publish(&mut out)?;
        Ok(out)
    }

    pub(super) fn response(
        &mut self,
        boot: &str,
        request: &str,
        final_chunk: bool,
        bytes: &[u8],
        out: &mut Output,
    ) -> Result<(), String> {
        if self.snapshot.as_ref().is_none_or(|s| s.boot_id != boot) {
            return Ok(());
        }
        if self.viewport_response(request, final_chunk, bytes)? {
            self.render(out)?;
            self.publish(out)?;
            return Ok(());
        }
        let Some(pending) = self
            .pending_action
            .as_mut()
            .filter(|p| p.wire_id == request)
        else {
            return Ok(());
        };
        if pending.acknowledged {
            return Ok(());
        }
        if pending.bytes.len().saturating_add(bytes.len()) > RESPONSE_LIMIT {
            return Err("Herdr action response exceeds limit".into());
        }
        pending.bytes.extend_from_slice(bytes);
        if !final_chunk {
            return Ok(());
        }
        // Only deserialize the error envelope; arbitrary result objects are skipped.
        let response = serde_json::from_slice::<Response>(&pending.bytes);
        pending.bytes.clear();
        match response {
            Ok(Response { error: None }) => pending.acknowledged = true,
            response => {
                let pending = self.pending_action.take().unwrap();
                self.navigation_revision = None;
                let (code, message) = match response {
                    Ok(Response { error: Some(error) }) => (
                        "refused",
                        error
                            .message
                            .map(|s| s.chars().filter(|c| !c.is_control()).take(512).collect()),
                    ),
                    _ => ("invalid_response", None),
                };
                out.action_result = Some(ActionResult {
                    request_id: pending.id,
                    code,
                    message,
                });
                self.repaint_pending = true;
                self.render(out)?;
                self.publish(out)?;
            }
        }
        Ok(())
    }

    pub(super) fn complete_navigation(&mut self, out: &mut Output) -> Result<(), String> {
        let progressed = self
            .coherent()
            .is_some_and(|(s, _)| self.navigation_revision.is_some_and(|r| s.revision > r));
        if progressed && self.pending_action.as_ref().is_some_and(|p| p.acknowledged) {
            let pending = self.pending_action.take().unwrap();
            self.navigation_revision = None;
            out.action_result = Some(ActionResult {
                request_id: pending.id,
                code: "ok",
                message: None,
            });
            self.repaint_pending = true;
            self.render(out)?;
            self.publish(out)?;
        }
        Ok(())
    }

    pub fn action_pending(&self) -> bool {
        self.pending_action.is_some() || self.viewport.in_flight()
    }
    pub fn expire_action(&mut self) -> Result<Output, String> {
        self.expire_viewport()?;
        let mut out = Output::default();
        if self
            .pending_action
            .as_ref()
            .is_some_and(|p| p.started.elapsed() >= Duration::from_secs(15))
        {
            let pending = self.pending_action.take().unwrap();
            out.action_result = Some(ActionResult {
                request_id: pending.id,
                code: "timeout",
                message: None,
            });
            // Outcome is unknown. Require a reconnect rather than replaying a mutation
            // or sending keys against the old visible target.
            self.publish(&mut out)?;
        }
        Ok(out)
    }
}

#[cfg(test)]
pub(super) mod tests {
    use super::*;

    pub(in crate::herdr) fn client() -> Client {
        let mut c = Client::new(1, 1);
        c.welcome = Some(
            serde_json::from_value(json!({"generation":1,"server_version":"test",
            "snapshot_codec":SNAPSHOT_CODEC_V1,"surface_codec":SURFACE_CODEC_V1,
            "input_codec":INPUT_CODEC_V1,"blob_codec":BLOB_CODEC_V1,
            "methods":["tab.create","tab.rename","tab.close","tab.focus"],"capabilities":[]}))
            .unwrap(),
        );
        c.snapshot = Some(serde_json::from_value(json!({
            "boot_id":"boot","revision":1,"config_diagnostic":null,"product_announcement":null,
            "update_available":null,"update_install_command":"","server_keybindings_toml":null,
            "latest_release_notes_available":false,"integration_updates_available":false,
            "worktree_directory":"","release_notes":null,"focused_workspace_id":"w",
            "focused_tab_id":"t","focused_pane_id":"p","tab_bar_right":[],"tab_bar_right_separator":"",
            "agent_view_label":null,"agent_order":[],"panes":[],"agents":[],"commands":[],
            "workspaces":[{"workspace_id":"w","active_tab_id":"t","new_workspace_cwd":"/tmp","number":1,
                "label":"Fixture","custom_label":false,"branch":null,"git_ahead_behind":null,"tokens":[],
                "worktree":null,"focused":true,"agent_status":"idle"}],
            "tabs":[{"tab_id":"t","workspace_id":"w","number":1,"label":"Shell","custom_label":false,
                "zoomed":false,"focused":true,"agent_status":"idle"}]
        })).unwrap());
        let rect = SurfaceRect {
            x: 0,
            y: 0,
            width: 1,
            height: 1,
        };
        c.surface = Some(PaneSurfaceFrame {
            boot_id: "boot".into(),
            projection_revision: 1,
            surface_revision: 1,
            frame: FrameData {
                width: 1,
                height: 1,
                cells: vec![CellData {
                    symbol: " ".into(),
                    fg: 0,
                    bg: 0,
                    modifier: 0,
                    skip: false,
                    hyperlink: None,
                }],
                cursor: None,
                hyperlinks: vec![],
                graphics: vec![],
            },
            panes: vec![PaneSurfacePane {
                pane_id: "p".into(),
                content_revision: 1,
                rect,
                inner_rect: rect,
                scrollbar_rect: None,
                scroll: None,
                focused: true,
                mouse_reporting: false,
                sgr_pixel_mouse: false,
                alternate_screen_active: false,
                pixel_width: 0,
                pixel_height: 0,
            }],
            splits: vec![],
            popup: None,
            graphics: SurfaceGraphicsScene::default(),
        });
        c.target_epoch = 1;
        c
    }
    fn action(method: &str, target: &str) -> NativeAction {
        NativeAction {
            method: method.into(),
            target_id: target.into(),
            label: None,
        }
    }
    fn receive(c: &mut Client, message: ServerMessage) -> Result<Output, String> {
        let mut bytes = Vec::new();
        protocol::write_message(&mut bytes, &message).unwrap();
        c.receive(&bytes)
    }
    fn response(c: &mut Client, id: &str, bytes: &[u8], final_chunk: bool) -> Output {
        receive(
            c,
            ServerMessage::ClientShellEndpointResponseChunk {
                boot_id: "boot".into(),
                request_id: id.into(),
                final_chunk,
                data: bytes.to_vec(),
            },
        )
        .unwrap()
    }

    #[test]
    fn navigation_requires_advertised_method_current_boot_and_existing_target() {
        let mut c = client();
        for (boot, a, code) in [
            ("old", action("tab.close", "t"), "stale"),
            ("boot", action("tab.close", "missing"), "stale"),
            ("boot", action("workspace.create", "w"), "unsupported"),
            ("boot", action("workspace.close", "w"), "invalid"),
        ] {
            let out = c.action(1, boot, a).unwrap();
            assert!(out.outbound.is_empty());
            assert_eq!(out.action_result.unwrap().code, code);
            assert_eq!(c.target_epoch(), Some(1));
        }
        assert_eq!(
            c.action(2, "boot", action("tab.focus", "t"))
                .unwrap()
                .action_result
                .unwrap()
                .code,
            "ok"
        );
        assert!(!c.action_pending());
    }

    #[test]
    fn action_acknowledgement_waits_for_matching_new_projection_and_surface() {
        let mut c = client();
        c.input(b"\x1b[").unwrap();
        let out = c.action(7, "boot", action("tab.create", "w")).unwrap();
        let (message, _): (ClientMessage, usize) =
            bincode::serde::decode_from_slice(&out.outbound[4..], bincode::config::standard())
                .unwrap();
        match message {
            ClientMessage::ClientShellEndpointRequest { boot_id, request } => {
                assert_eq!(boot_id, "boot");
                let request: serde_json::Value = serde_json::from_str(&request).unwrap();
                assert_eq!(request["id"], "nova-android-7");
                assert_eq!(request["params"], json!({"workspace_id":"w","focus":true}));
            }
            _ => panic!("not an endpoint request"),
        }
        assert_eq!(c.target_epoch(), None);
        assert_eq!(c.input_parser.pending_len(), 0);
        assert!(c.input(b"do not retarget").unwrap().outbound.is_empty());
        assert!(
            response(&mut c, "wrong", b"{}", true)
                .action_result
                .is_none()
        );
        assert!(
            response(&mut c, "nova-android-7", b"{\"error\":", false)
                .action_result
                .is_none()
        );
        assert!(
            response(&mut c, "nova-android-7", b"null}", true)
                .action_result
                .is_none()
        );
        let mut snapshot = c.snapshot.clone().unwrap();
        snapshot.revision = 2;
        let out = receive(
            &mut c,
            ServerMessage::EndpointControl {
                kind: ENDPOINT_SNAPSHOT_KIND.into(),
                data: serde_json::to_string(&snapshot).unwrap(),
            },
        )
        .unwrap();
        assert!(out.action_result.is_none());
        assert_eq!(c.target_epoch(), None);
        let mut surface = c.surface.clone().unwrap();
        surface.projection_revision = 2;
        surface.surface_revision = 2;
        let out = receive(&mut c, ServerMessage::PaneSurface(surface)).unwrap();
        assert_eq!(out.action_result.unwrap().code, "ok");
        assert_eq!(out.ready, Some(true));
        assert!(c.target_epoch().unwrap() > 1);
        assert!(!out.ansi.is_empty());
        assert!(!c.action_pending());
    }

    #[test]
    fn refusal_keeps_connection_usable_and_timeout_never_replays_a_mutation() {
        let mut c = client();
        c.action(1, "boot", action("tab.close", "t")).unwrap();
        let out = response(
            &mut c,
            "nova-android-1",
            b"{\"error\":{\"message\":\"Last tab cannot close\"}}",
            true,
        );
        assert_eq!(out.action_result.unwrap().code, "refused");
        assert!(c.target_epoch().is_some());
        assert!(!c.failed);
        c.action(2, "boot", action("tab.close", "t")).unwrap();
        c.pending_action.as_mut().unwrap().started -= Duration::from_secs(16);
        let out = c.expire_action().unwrap();
        assert_eq!(out.action_result.unwrap().code, "timeout");
        assert!(out.outbound.is_empty());
        assert_eq!(c.target_epoch(), None);
        assert_eq!(
            c.action(3, "boot", action("tab.close", "t"))
                .unwrap()
                .action_result
                .unwrap()
                .code,
            "busy"
        );
        assert!(
            response(&mut c, "nova-android-2", b"{}", true)
                .action_result
                .is_none()
        );
    }

    #[test]
    fn response_chunks_and_aggregate_are_bounded() {
        let mut c = client();
        c.action(1, "boot", action("tab.create", "w")).unwrap();
        assert!(
            receive(
                &mut c,
                ServerMessage::ClientShellEndpointResponseChunk {
                    boot_id: "boot".into(),
                    request_id: "nova-android-1".into(),
                    final_chunk: false,
                    data: vec![b' '; RESPONSE_LIMIT + 1]
                }
            )
            .is_err()
        );
        let mut c = client();
        c.action(1, "boot", action("tab.create", "w")).unwrap();
        response(&mut c, "nova-android-1", &vec![b' '; RESPONSE_LIMIT], false);
        assert!(
            receive(
                &mut c,
                ServerMessage::ClientShellEndpointResponseChunk {
                    boot_id: "boot".into(),
                    request_id: "nova-android-1".into(),
                    final_chunk: true,
                    data: vec![b' ']
                }
            )
            .is_err()
        );
    }
}
