// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: GPL-3.0-only

use serde::{Deserialize, Deserializer, Serialize, de};
use std::os::fd::{FromRawFd, OwnedFd};
use zeroize::{Zeroize, ZeroizeOnDrop};

pub const API_VERSION: u32 = 1;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartRequest {
    pub host: String,
    pub username: String,
    #[serde(default)]
    pub expected_host_key: Option<String>,
    #[serde(default = "default_terminal")]
    pub terminal: String,
    #[serde(default = "default_columns")]
    pub columns: u32,
    #[serde(default = "default_rows")]
    pub rows: u32,
    #[serde(default)]
    pub terminal_theme: Option<TerminalThemeConfig>,
    pub authentication: Authentication,
}

impl StartRequest {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.host.trim().is_empty() {
            return Err("host must not be empty");
        }
        if self.username.trim().is_empty() {
            return Err("username must not be empty");
        }
        if !(20..=500).contains(&self.columns) || !(5..=300).contains(&self.rows) {
            return Err("terminal dimensions are outside the supported range");
        }
        if self
            .terminal_theme
            .as_ref()
            .is_some_and(|theme| !theme.is_valid())
        {
            return Err("terminal theme is invalid");
        }
        if !self
            .expected_host_key
            .as_deref()
            .is_none_or(|fingerprint| fingerprint.starts_with("SHA256:"))
        {
            return Err("host key must be an SHA256 fingerprint");
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TerminalThemeConfig {
    pub foreground: u32,
    pub background: u32,
    pub cursor: u32,
    pub ansi: Vec<u32>,
}

impl TerminalThemeConfig {
    pub fn is_valid(&self) -> bool {
        self.ansi.len() == 16
            && std::iter::once(self.foreground)
                .chain(std::iter::once(self.background))
                .chain(std::iter::once(self.cursor))
                .chain(self.ansi.iter().copied())
                .all(|color| color <= 0x00ff_ffff)
    }
}

#[derive(Debug, Deserialize, Zeroize, ZeroizeOnDrop)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Authentication {
    None,
    Password {
        password: String,
    },
    PrivateKey {
        private_key: String,
        #[serde(default)]
        passphrase: Option<String>,
    },
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Command {
    Input {
        base64: String,
    },
    Resize {
        columns: u32,
        rows: u32,
        #[serde(default = "default_cell_width")]
        cell_width: u32,
        #[serde(default = "default_cell_height")]
        cell_height: u32,
    },
    TerminalScroll {
        rows: i32,
    },
    TerminalTheme {
        foreground: u32,
        background: u32,
        cursor: u32,
        ansi: Vec<u32>,
    },
    Close,
    SftpList {
        request_id: String,
        path: String,
    },
    SftpRead {
        request_id: String,
        path: String,
    },
    SftpWrite {
        request_id: String,
        path: String,
        base64: String,
    },
    SftpCreateDirectory {
        request_id: String,
        path: String,
    },
    SftpCreateFile {
        request_id: String,
        path: String,
    },
    SftpRename {
        request_id: String,
        source_path: String,
        destination_path: String,
    },
    SftpDelete {
        request_id: String,
        path: String,
        directory: bool,
    },
    SftpUpload {
        request_id: String,
        path: String,
        local_fd: TransferredFd,
        #[serde(default)]
        total_bytes: Option<u64>,
    },
    SftpDownload {
        request_id: String,
        path: String,
        local_fd: TransferredFd,
    },
    SftpCancelTransfer {
        request_id: String,
    },
}

#[derive(Debug)]
pub struct TransferredFd(OwnedFd);

impl TransferredFd {
    pub fn into_owned(self) -> OwnedFd {
        self.0
    }
}

impl<'de> Deserialize<'de> for TransferredFd {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let raw_fd = i32::deserialize(deserializer)?;
        if raw_fd < 0 {
            return Err(de::Error::custom(
                "transferred descriptor must be non-negative",
            ));
        }
        // The Kotlin sender has detached this descriptor and relinquished
        // ownership. Deserialization adopts it immediately so a queued or
        // discarded command still closes it exactly once.
        Ok(Self(unsafe { OwnedFd::from_raw_fd(raw_fd) }))
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CoreEvent<'a> {
    pub version: u32,
    #[serde(rename = "type")]
    pub event_type: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub category: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fingerprint: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub algorithm: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expected_fingerprint: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub exit_status: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub revision: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_id: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub payload: Option<&'a serde_json::Value>,
}

impl<'a> CoreEvent<'a> {
    pub fn named(event_type: &'a str) -> Self {
        Self {
            version: API_VERSION,
            event_type,
            category: None,
            message: None,
            fingerprint: None,
            algorithm: None,
            expected_fingerprint: None,
            exit_status: None,
            revision: None,
            request_id: None,
            payload: None,
        }
    }
}

const fn default_columns() -> u32 {
    80
}

const fn default_rows() -> u32 {
    24
}

fn default_terminal() -> String {
    "xterm-256color".to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_host_key_and_dimensions() {
        let request: StartRequest = serde_json::from_str(
            r#"{"host":"server","username":"me","expectedHostKey":"SHA256:test","columns":80,"rows":24,"authentication":{"type":"none"}}"#,
        )
        .unwrap();
        assert!(request.validate().is_ok());
    }

    #[test]
    fn rejects_unscoped_host_key_text() {
        let request: StartRequest = serde_json::from_str(
            r#"{"host":"server","username":"me","expectedHostKey":"trust-all","authentication":{"type":"none"}}"#,
        )
        .unwrap();
        assert!(request.validate().is_err());
    }

    #[test]
    fn validates_terminal_theme_shape_and_rgb_range() {
        let request: StartRequest = serde_json::from_str(
            r#"{"host":"server","username":"me","terminalTheme":{"foreground":16777215,"background":0,"cursor":1122867,"ansi":[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,16777215]},"authentication":{"type":"none"}}"#,
        )
        .unwrap();
        assert!(request.validate().is_ok());

        let invalid: StartRequest = serde_json::from_str(
            r#"{"host":"server","username":"me","terminalTheme":{"foreground":16777216,"background":0,"cursor":0,"ansi":[0,1]},"authentication":{"type":"none"}}"#,
        )
        .unwrap();
        assert_eq!(invalid.validate(), Err("terminal theme is invalid"));
    }

    #[test]
    fn parses_terminal_scroll_command() {
        let command: Command =
            serde_json::from_str(r#"{"type":"terminal_scroll","rows":-4}"#).unwrap();
        assert!(matches!(command, Command::TerminalScroll { rows: -4 }));
    }

    #[test]
    fn rejects_invalid_transferred_descriptor() {
        let result = serde_json::from_str::<Command>(
            r#"{"type":"sftp_download","request_id":"test","path":"/tmp/file","local_fd":-1}"#,
        );
        assert!(result.is_err());
    }
}

fn default_cell_width() -> u32 {
    8
}
fn default_cell_height() -> u32 {
    16
}
