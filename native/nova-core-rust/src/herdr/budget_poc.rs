// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
//
// Test-only feasibility experiment. This scanner is NOT wired into the client
// and its logical accounting is NOT a process RSS or cumulative-session bound.
use super::*;
use serde::de::{DeserializeSeed, MapAccess, SeqAccess, Visitor};
use std::fmt;
use std::mem::size_of;

const CELLS: usize = 500 * 300;
const ITEMS: usize = 4096;
const LIMIT: usize = 64 * 1024 * 1024;

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
        self.budget.charge(s.len())?;
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
    fn cells(&mut self) -> Result<(), &'static str> {
        let n = self.count::<CellData>(CELLS)?;
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
                self.val::<u32>()?;
            }
        }
        Ok(())
    }
    fn frame(&mut self) -> Result<(), &'static str> {
        self.cells()?;
        self.val::<u16>()?;
        self.val::<u16>()?;
        self.cursor()?;
        let n = self.count::<String>(CELLS)?;
        for _ in 0..n {
            self.text()?;
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
            self.cells()?;
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
            13 => self.surface()?,
            19 => self.patch()?,
            20 => self.projection()?,
            _ => return Err("unimplemented frame variant"),
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

fn scan_message(msg: &ServerMessage, limit: usize) -> Result<usize, &'static str> {
    let payload = bincode::serde::encode_to_vec(msg, bincode::config::standard())
        .map_err(|_| "encode failed")?;
    Scan::new(&payload, limit)?.scan()
}

#[test]
fn scans_frozen_surface_patch_and_projection_shapes() {
    let cell = CellData {
        symbol: "✓".into(),
        fg: 0,
        bg: 0,
        modifier: 0,
        skip: false,
        hyperlink: None,
    };
    let frame = FrameData {
        cells: vec![cell.clone()],
        width: 1,
        height: 1,
        cursor: None,
        hyperlinks: vec![],
        graphics: vec![],
    };
    let surface = PaneSurfaceFrame {
        boot_id: "boot".into(),
        projection_revision: 1,
        surface_revision: 1,
        frame,
        panes: vec![],
        splits: vec![],
        popup: None,
        graphics: SurfaceGraphicsScene::default(),
    };
    assert!(scan_message(&ServerMessage::PaneSurface(surface), LIMIT).is_ok());
    let patch = PaneSurfacePatch {
        boot_id: "boot".into(),
        projection_revision: 1,
        base_surface_revision: 1,
        surface_revision: 2,
        rows: vec![PaneSurfacePatchRow {
            x: 0,
            y: 0,
            cells: vec![cell],
        }],
        panes: vec![],
        cursor: None,
    };
    assert!(scan_message(&ServerMessage::PaneSurfacePatch(patch), LIMIT).is_ok());
    let projection = ServerMessage::EndpointControl {
        kind: ENDPOINT_SNAPSHOT_KIND.into(),
        data: r#"{"boot_id":"boot","agents":[{"id":"one"}]}"#.into(),
    };
    assert!(scan_message(&projection, LIMIT).is_ok());
    assert!(scan_message(&projection, 128).is_err());
}

#[test]
fn rejects_malicious_nested_counts_before_allocation() {
    let config = bincode::config::standard();
    let mut payload = bincode::serde::encode_to_vec(13u32, config).unwrap();
    payload.extend(bincode::serde::encode_to_vec("boot", config).unwrap());
    payload.extend(bincode::serde::encode_to_vec(1u64, config).unwrap());
    payload.extend(bincode::serde::encode_to_vec(1u64, config).unwrap());
    payload.extend(bincode::serde::encode_to_vec(u64::MAX, config).unwrap());
    assert!(Scan::new(&payload, LIMIT).unwrap().scan().is_err());
    assert!(Scan::new(&payload, 1).is_err());
}

#[test]
fn accepts_maximum_dimensions_with_separate_heap_budget() {
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
    let msg = ServerMessage::PaneSurface(PaneSurfaceFrame {
        boot_id: "boot".into(),
        projection_revision: 1,
        surface_revision: 1,
        frame,
        panes: vec![],
        splits: vec![],
        popup: None,
        graphics: SurfaceGraphicsScene::default(),
    });
    let used = scan_message(&msg, LIMIT).unwrap();
    println!("maximum-dimension scanner estimate: {used} bytes");
    assert!(used > 2 * 1024 * 1024 && used < LIMIT);
    assert!(scan_message(&msg, 2 * 1024 * 1024).is_err());
}

#[test]
fn scans_optional_popup_pane_scroll_split_and_json_nesting() {
    let rect = SurfaceRect {
        x: 0,
        y: 0,
        width: 1,
        height: 1,
    };
    let cell = CellData {
        symbol: "A".into(),
        fg: 0,
        bg: 0,
        modifier: 0,
        skip: false,
        hyperlink: Some(0),
    };
    let frame = FrameData {
        cells: vec![cell.clone()],
        width: 1,
        height: 1,
        cursor: Some(CursorState {
            x: 0,
            y: 0,
            visible: true,
            shape: 1,
        }),
        hyperlinks: vec!["https://example.test".into()],
        graphics: vec![],
    };
    let pane = PaneSurfacePane {
        pane_id: "pane".into(),
        content_revision: 9,
        rect,
        inner_rect: rect,
        scrollbar_rect: Some(rect),
        scroll: Some(PaneSurfaceScrollMetrics {
            offset_from_bottom: 1,
            max_offset_from_bottom: 2,
            viewport_rows: 3,
        }),
        focused: true,
        mouse_reporting: true,
        sgr_pixel_mouse: false,
        alternate_screen_active: false,
        pixel_width: 8,
        pixel_height: 16,
    };
    let split = PaneSurfaceSplit {
        direction: PaneSurfaceSplitDirection::Horizontal,
        pos: 0,
        area: rect,
        hit_rect: rect,
        path: vec![true, false],
    };
    let popup = ClientShellPopupSurface {
        terminal_id: "popup".into(),
        title: "title".into(),
        width: Some(ClientShellPopupSize::Cells(1)),
        height: Some(ClientShellPopupSize::Percent(90)),
        frame: frame.clone(),
        mouse_reporting: false,
        sgr_pixel_mouse: false,
        pixel_width: 8,
        pixel_height: 16,
    };
    let surface = PaneSurfaceFrame {
        boot_id: "boot".into(),
        projection_revision: 1,
        surface_revision: 1,
        frame,
        panes: vec![pane.clone()],
        splits: vec![split],
        popup: Some(Box::new(popup)),
        graphics: SurfaceGraphicsScene::default(),
    };
    let msg = ServerMessage::PaneSurface(surface);
    assert!(scan_message(&msg, LIMIT).is_ok());
    let bytes = bincode::serde::encode_to_vec(&msg, bincode::config::standard()).unwrap();
    let decoded: (ServerMessage, usize) =
        bincode::serde::decode_from_slice(&bytes, bincode::config::standard()).unwrap();
    assert_eq!(decoded.1, bytes.len());
    let patch = PaneSurfacePatch {
        boot_id: "boot".into(),
        projection_revision: 1,
        base_surface_revision: 1,
        surface_revision: 2,
        rows: vec![PaneSurfacePatchRow {
            x: 0,
            y: 0,
            cells: vec![cell],
        }],
        panes: vec![pane],
        cursor: Some(CursorState {
            x: 0,
            y: 0,
            visible: true,
            shape: 1,
        }),
    };
    assert!(scan_message(&ServerMessage::PaneSurfacePatch(patch), LIMIT).is_ok());
    let projection = ServerMessage::EndpointControl {
        kind: ENDPOINT_SNAPSHOT_KIND.into(),
        data: r#"{"nested":[1,true,null,{"escaped":"\u0061"}]}"#.into(),
    };
    assert!(scan_message(&projection, LIMIT).is_ok());
}
