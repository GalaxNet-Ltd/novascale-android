// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: GPL-3.0-only

fn main() {
    println!("cargo:rerun-if-env-changed=NOVA_GHOSTTY_LIB_DIR");
    if std::env::var_os("CARGO_FEATURE_GHOSTTY").is_none() {
        return;
    }
    let directory = std::env::var("NOVA_GHOSTTY_LIB_DIR")
        .expect("NOVA_GHOSTTY_LIB_DIR is required by the ghostty feature");
    // Zig changes must invalidate Cargo's link step even when the directory is unchanged.
    println!("cargo:rerun-if-changed={directory}/libnova_ghostty.a");
    println!("cargo:rustc-link-search=native={directory}");
    println!("cargo:rustc-link-lib=static=nova_ghostty");
}
