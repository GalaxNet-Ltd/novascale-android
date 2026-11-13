// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: Apache-2.0

fn main() {
    println!("cargo:rerun-if-env-changed=NOVA_GHOSTTY_LIB_DIR");
    if std::env::var_os("CARGO_FEATURE_GHOSTTY").is_none() {
        return;
    }
    let directory = std::env::var("NOVA_GHOSTTY_LIB_DIR")
        .expect("NOVA_GHOSTTY_LIB_DIR is required by the ghostty feature");
    println!("cargo:rustc-link-search=native={directory}");
    // Link the adapter and its SIMD dependencies. Static archives do not carry
    // transitive libraries; changes to any archive must invalidate Cargo's link.
    for library in ["nova_ghostty", "ghostty-vt-static", "simdutf", "highway"] {
        println!("cargo:rerun-if-changed={directory}/lib{library}.a");
        println!("cargo:rustc-link-lib=static={library}");
    }
}
