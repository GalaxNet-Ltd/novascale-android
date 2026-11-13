// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
//
// Schema-aware preflight for the frozen generation-1 endpoint. It checks
// lengths before Serde creates owned containers. Logical charges are not RSS.
use super::*;
use serde::de::{DeserializeSeed, MapAccess, SeqAccess, Visitor};
use std::fmt;
use std::mem::size_of;

const CELLS: usize = 500 * 300;
const ITEMS: usize = 4096;
pub(super) const FRAME_BUDGET: usize = 24 * 1024 * 1024;
const METADATA_BUDGET: usize = 4 * 1024 * 1024;
const CLIPBOARD_BUDGET: usize = 8 * 1024 * 1024;

#[derive(Default, Debug)]
struct Budget {
    used: usize,
    limit: usize,
}
impl Budget {
    fn new(limit: usize) -> Self {
        Self { used: 0, limit }
    }
    fn charge(&mut self, bytes: usize) -> Result<(), &'static str> {
        let next = self
            .used
            .checked_add(bytes)
            .ok_or("budget arithmetic overflow")?;
        if next > self.limit {
            return Err("budget exceeded");
        }
        self.used = next;
        Ok(())
    }
    fn vec<T>(&mut self, len: usize, max: usize) -> Result<(), &'static str> {
        if len > max {
            return Err("collection too large");
        }
        // Conservative doubling for geometric Vec growth, plus a small initial
        // allocation. This is an estimate, not a guarantee of allocator RSS.
        let capacity = len
            .checked_mul(2)
            .and_then(|n| n.checked_add(4))
            .ok_or("capacity overflow")?;
        self.charge(
            capacity
                .checked_mul(size_of::<T>())
                .and_then(|n| n.checked_add(32))
                .ok_or("allocation overflow")?,
        )
    }
}

struct Scan<'a> {
    rest: &'a [u8],
    budget: Budget,
}
impl<'a> Scan<'a> {
    fn new(payload: &'a [u8], limit: usize) -> Result<Self, &'static str> {
        if payload.len() > MAX_FRAME_SIZE {
            return Err("wire limit");
        }
        // Account for the input frame and a possible JSON parser scratch copy.
        let mut budget = Budget::new(limit);
        budget.charge(payload.len().checked_mul(2).ok_or("input overflow")?)?;
        Ok(Self {
            rest: payload,
            budget,
        })
    }
    fn val<T: bincode::Decode<()>>(&mut self) -> Result<T, &'static str> {
        let (v, n): (T, usize) = bincode::decode_from_slice(self.rest, bincode::config::standard())
            .map_err(|_| "invalid value")?;
        self.rest = &self.rest[n..];
        Ok(v)
    }
    fn text(&mut self) -> Result<&'a str, &'static str> {
        let (s, n): (&str, usize) =
            bincode::borrow_decode_from_slice(self.rest, bincode::config::standard())
                .map_err(|_| "invalid string")?;
        self.rest = &self.rest[n..];
        self.budget.charge(
            s.len()
                .checked_add(32)
                .ok_or("string allocation overflow")?,
        )?;
        Ok(s)
    }
    fn count<T>(&mut self, max: usize) -> Result<usize, &'static str> {
        let n: u64 = self.val()?;
        let n = usize::try_from(n).map_err(|_| "count overflow")?;
        self.budget.vec::<T>(n, max)?;
        // Every item in the scanned wire schema requires at least one byte.
        if n > self.rest.len() {
            return Err("truncated collection");
        }
        Ok(n)
    }
    fn option(&mut self) -> Result<bool, &'static str> {
        match self.val::<u8>()? {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err("invalid option"),
        }
    }
    fn rect(&mut self) -> Result<(), &'static str> {
        for _ in 0..4 {
            self.val::<u16>()?;
        }
        Ok(())
    }
    fn cursor(&mut self) -> Result<(), &'static str> {
        if self.option()? {
            self.val::<u16>()?;
            self.val::<u16>()?;
            self.val::<bool>()?;
            self.val::<u8>()?;
        }
        Ok(())
    }
    fn cells(&mut self, collect_links: bool) -> Result<(usize, Vec<u32>), &'static str> {
        let n = self.count::<CellData>(CELLS)?;
        let mut links = Vec::new();
        if collect_links {
            self.budget.vec::<u32>(n, CELLS)?;
            links
                .try_reserve_exact(n)
                .map_err(|_| "preflight allocation failed")?;
            if links.capacity() > n.saturating_mul(2).saturating_add(4) {
                return Err("preflight capacity exceeded");
            }
        }
        for _ in 0..n {
            let symbol = self.text()?;
            if symbol.len() > 4096 || symbol.chars().any(char::is_control) {
                return Err("invalid cell");
            }
            self.val::<u32>()?;
            self.val::<u32>()?;
            self.val::<u16>()?;
            self.val::<bool>()?;
            if self.option()? {
                let index = self.val::<u32>()?;
                if collect_links {
                    links.push(index);
                }
            }
        }
        Ok((n, links))
    }
    fn frame(&mut self) -> Result<(), &'static str> {
        let (_, links) = self.cells(true)?;
        self.val::<u16>()?;
        self.val::<u16>()?;
        self.cursor()?;
        let n = self.count::<String>(CELLS)?;
        self.budget.vec::<usize>(n, CELLS)?;
        let mut lengths = Vec::new();
        lengths
            .try_reserve_exact(n)
            .map_err(|_| "preflight allocation failed")?;
        if lengths.capacity() > n.saturating_mul(2).saturating_add(4) {
            return Err("preflight capacity exceeded");
        }
        for _ in 0..n {
            let uri = self.text()?;
            if uri.len() > 4096 {
                return Err("hyperlink too large");
            }
            lengths.push(uri.len());
        }
        // The renderer sanitizes a linked URI for each linked cell, even after
        // the writer reports overflow. Charge actual linked-cell URI lengths,
        // rather than pessimistically multiplying every cell by the longest
        // URI, and reject invalid indices before the owned frame is decoded.
        let mut work = 0usize;
        for index in links {
            let len = *lengths
                .get(index as usize)
                .ok_or("invalid hyperlink index")?;
            work = work.checked_add(len).ok_or("hyperlink work overflow")?;
            if work > 64 * 1024 * 1024 {
                return Err("hyperlink rendering work too large");
            }
        }
        let n = self.count::<u8>(MAX_FRAME_SIZE)?;
        if n != 0 {
            return Err("graphics not negotiated");
        }
        Ok(())
    }
    fn panes(&mut self) -> Result<(), &'static str> {
        let n = self.count::<PaneSurfacePane>(ITEMS)?;
        for _ in 0..n {
            self.text()?;
            self.val::<u64>()?;
            self.rect()?;
            self.rect()?;
            if self.option()? {
                self.rect()?;
            }
            if self.option()? {
                for _ in 0..3 {
                    self.val::<u64>()?;
                }
            }
            for _ in 0..4 {
                self.val::<bool>()?;
            }
            self.val::<u32>()?;
            self.val::<u32>()?;
        }
        Ok(())
    }
    fn splits(&mut self) -> Result<(), &'static str> {
        let n = self.count::<PaneSurfaceSplit>(ITEMS)?;
        for _ in 0..n {
            if self.val::<u32>()? > 1 {
                return Err("invalid split direction");
            }
            self.val::<u16>()?;
            self.rect()?;
            self.rect()?;
            let path = self.count::<bool>(ITEMS)?;
            for _ in 0..path {
                self.val::<bool>()?;
            }
        }
        Ok(())
    }
    fn popup_size(&mut self) -> Result<(), &'static str> {
        if self.option()? {
            match self.val::<u32>()? {
                0 => {
                    self.val::<u16>()?;
                }
                1 => {
                    self.val::<u8>()?;
                }
                _ => return Err("invalid popup size"),
            }
        }
        Ok(())
    }
    fn surface(&mut self) -> Result<(), &'static str> {
        self.text()?;
        self.val::<u64>()?;
        self.val::<u64>()?;
        self.frame()?;
        self.panes()?;
        self.splits()?;
        if self.option()? {
            self.budget.charge(size_of::<ClientShellPopupSurface>())?;
            self.text()?;
            self.text()?;
            self.popup_size()?;
            self.popup_size()?;
            self.frame()?;
            self.val::<bool>()?;
            self.val::<bool>()?;
            self.val::<u32>()?;
            self.val::<u32>()?;
        }
        for _ in 0..3 {
            if self.val::<u64>()? != 0 {
                return Err("graphics scene not negotiated");
            }
        }
        Ok(())
    }
    fn patch(&mut self) -> Result<(), &'static str> {
        self.text()?;
        for _ in 0..3 {
            self.val::<u64>()?;
        }
        let rows = self.count::<PaneSurfacePatchRow>(CELLS)?;
        for _ in 0..rows {
            self.val::<u16>()?;
            self.val::<u16>()?;
            self.cells(false)?;
        }
        self.panes()?;
        self.cursor()?;
        Ok(())
    }
    fn projection(&mut self) -> Result<(), &'static str> {
        let kind = self.text()?;
        let data = self.text()?;
        if kind == ENDPOINT_WELCOME_KIND || kind == ENDPOINT_SNAPSHOT_KIND {
            let mut parser = serde_json::Deserializer::from_str(data);
            JsonSeed(&mut self.budget)
                .deserialize(&mut parser)
                .map_err(|_| "invalid or over-budget JSON")?;
            parser.end().map_err(|_| "trailing JSON")?;
        }
        Ok(())
    }
    fn scan(mut self) -> Result<usize, &'static str> {
        match self.val::<u32>()? {
            3 => {
                if self.option()? {
                    self.text()?;
                }
            } // ServerShutdown
            5 => {
                self.text()?;
            } // Clipboard
            9 => {
                self.val::<u16>()?;
            } // TerminalBell
            13 => self.surface()?,
            15 => {
                self.text()?;
            } // ClientShellError
            18 => {
                // Endpoint response: bound the chunk before any owned allocation.
                if self.text()?.len() > 256 || self.text()?.len() > 256 {
                    return Err("invalid response identity");
                }
                self.val::<bool>()?;
                let bytes = self.count::<u8>(navigation::RESPONSE_LIMIT)?;
                self.rest = &self.rest[bytes..];
            }
            19 => self.patch()?,
            20 => self.projection()?,
            _ => return Err("unsupported frame variant"),
        }
        if !self.rest.is_empty() {
            return Err("trailing frame bytes");
        }
        Ok(self.budget.used)
    }
}

// Generic, allocation-free JSON structure walk. The intentionally generous
// charge per node is experimental; it is not yet a proof for typed projections.
struct JsonSeed<'a>(&'a mut Budget);
impl<'de> DeserializeSeed<'de> for JsonSeed<'_> {
    type Value = ();
    fn deserialize<D: serde::Deserializer<'de>>(self, d: D) -> Result<(), D::Error> {
        self.0.charge(1024).map_err(serde::de::Error::custom)?;
        d.deserialize_any(JsonVisitor(self.0))
    }
}
struct JsonVisitor<'a>(&'a mut Budget);
impl<'de> Visitor<'de> for JsonVisitor<'_> {
    type Value = ();
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("bounded JSON")
    }
    fn visit_bool<E: serde::de::Error>(self, _: bool) -> Result<(), E> {
        Ok(())
    }
    fn visit_i64<E: serde::de::Error>(self, _: i64) -> Result<(), E> {
        Ok(())
    }
    fn visit_u64<E: serde::de::Error>(self, _: u64) -> Result<(), E> {
        Ok(())
    }
    fn visit_f64<E: serde::de::Error>(self, _: f64) -> Result<(), E> {
        Ok(())
    }
    fn visit_unit<E: serde::de::Error>(self) -> Result<(), E> {
        Ok(())
    }
    fn visit_str<E: serde::de::Error>(self, s: &str) -> Result<(), E> {
        self.0
            .charge(
                s.len()
                    .checked_mul(2)
                    .ok_or_else(|| E::custom("string overflow"))?,
            )
            .map_err(E::custom)
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut a: A) -> Result<(), A::Error> {
        while a.next_element_seed(JsonSeed(self.0))?.is_some() {}
        Ok(())
    }
    fn visit_map<A: MapAccess<'de>>(self, mut a: A) -> Result<(), A::Error> {
        while a.next_key_seed(JsonSeed(self.0))?.is_some() {
            a.next_value_seed(JsonSeed(self.0))?;
        }
        Ok(())
    }
}

/// Inspect the entire encoded frame without allocating its owned data. The
/// wire limit and structural caps are separate from this logical heap estimate.
/// This does not include retained state, renderer copies or allocator overhead.
pub(super) fn preflight(payload: &[u8], limit: usize) -> Result<usize, &'static str> {
    let (tag, _): (u32, usize) = bincode::decode_from_slice(payload, bincode::config::standard())
        .map_err(|_| "invalid frame tag")?;
    let specific_limit = match tag {
        20 => METADATA_BUDGET,
        5 => CLIPBOARD_BUDGET,
        _ => FRAME_BUDGET,
    };
    Scan::new(payload, limit.min(specific_limit))?.scan()
}

/// These frozen generation-1 tags are not negotiated by this client. Their
/// contents are ignored without ever deserializing attacker-controlled data.
/// The two legacy handshake variants explicitly fail compatibility instead.
pub(super) fn disposition(payload: &[u8]) -> Result<bool, &'static str> {
    let (tag, _): (u32, usize) = bincode::decode_from_slice(payload, bincode::config::standard())
        .map_err(|_| "invalid frame tag")?;
    match tag {
        3 | 5 | 9 | 13 | 15 | 18 | 19 | 20 => Ok(true),
        0 | 12 => Err("incompatible Herdr endpoint"),
        1 | 2 | 4 | 6 | 7 | 8 | 10 | 11 | 14 | 16 | 17 => Ok(false),
        _ => Err("unknown Herdr frame tag"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn payload(message: &ServerMessage) -> Vec<u8> {
        bincode::serde::encode_to_vec(message, bincode::config::standard()).unwrap()
    }

    #[test]
    fn supported_and_unnegotiated_tags_are_handled_without_owned_decode() {
        let bell = payload(&ServerMessage::TerminalBell { count: 1 });
        assert_eq!(disposition(&bell), Ok(true));
        assert!(preflight(&bell, FRAME_BUDGET).is_ok());
        let ignored = payload(&ServerMessage::Graphics {
            bytes: vec![0; 1024],
        });
        assert_eq!(disposition(&ignored), Ok(false));
        let unknown = bincode::encode_to_vec(21u32, bincode::config::standard()).unwrap();
        assert!(disposition(&unknown).is_err());
        assert!(disposition(&[255]).is_err());
    }

    #[test]
    fn oversized_nested_counts_fail_before_deserialization() {
        let config = bincode::config::standard();
        let mut bytes = bincode::serde::encode_to_vec(13u32, config).unwrap();
        bytes.extend(bincode::serde::encode_to_vec("boot", config).unwrap());
        bytes.extend(bincode::serde::encode_to_vec(1u64, config).unwrap());
        bytes.extend(bincode::serde::encode_to_vec(1u64, config).unwrap());
        bytes.extend(bincode::serde::encode_to_vec(u64::MAX, config).unwrap());
        assert!(preflight(&bytes, FRAME_BUDGET).is_err());
        let mut framed = (bytes.len() as u32).to_le_bytes().to_vec();
        framed.extend(bytes);
        assert!(Client::new(80, 24).receive(&framed).is_err());
    }

    #[test]
    fn json_structure_is_charged_before_owned_projection_decode() {
        let data = format!("{{\"items\":[{}]}}", vec!["0"; 66_000].join(","));
        let bytes = payload(&ServerMessage::EndpointControl {
            kind: ENDPOINT_SNAPSHOT_KIND.into(),
            data,
        });
        assert!(bytes.len() < MAX_FRAME_SIZE);
        assert!(preflight(&bytes, FRAME_BUDGET).is_err());
    }

    #[test]
    fn maximum_dimensions_are_accepted_with_the_frame_budget() {
        let cell = CellData {
            symbol: " ".into(),
            fg: 0,
            bg: 0,
            modifier: 0,
            skip: false,
            hyperlink: None,
        };
        let frame = FrameData {
            cells: vec![cell; CELLS],
            width: 500,
            height: 300,
            cursor: None,
            hyperlinks: vec![],
            graphics: vec![],
        };
        let bytes = payload(&ServerMessage::PaneSurface(PaneSurfaceFrame {
            boot_id: "boot".into(),
            projection_revision: 1,
            surface_revision: 1,
            frame,
            panes: vec![],
            splits: vec![],
            popup: None,
            graphics: SurfaceGraphicsScene::default(),
        }));
        assert!(preflight(&bytes, FRAME_BUDGET).is_ok());
        assert!(preflight(&bytes, MAX_FRAME_SIZE).is_err());
    }

    #[test]
    fn hyperlink_work_counts_actual_references_instead_of_every_cell() {
        let cell = CellData {
            symbol: " ".into(),
            fg: 0,
            bg: 0,
            modifier: 0,
            skip: false,
            hyperlink: None,
        };
        let mut cells = vec![cell; CELLS];
        cells[0].hyperlink = Some(0);
        let mut surface = PaneSurfaceFrame {
            boot_id: "boot".into(),
            projection_revision: 1,
            surface_revision: 1,
            frame: FrameData {
                cells,
                width: 500,
                height: 300,
                cursor: None,
                hyperlinks: vec!["x".repeat(500)],
                graphics: vec![],
            },
            panes: vec![],
            splits: vec![],
            popup: None,
            graphics: SurfaceGraphicsScene::default(),
        };
        let bytes = payload(&ServerMessage::PaneSurface(surface.clone()));
        assert!(preflight(&bytes, FRAME_BUDGET).is_ok());
        for cell in &mut surface.frame.cells {
            cell.hyperlink = Some(0);
        }
        let bytes = payload(&ServerMessage::PaneSurface(surface));
        assert!(preflight(&bytes, FRAME_BUDGET).is_err());
    }
}
