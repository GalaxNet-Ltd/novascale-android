#!/usr/bin/env bash
set -euo pipefail

android_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="0.17.0"
archive_name="zig-x86_64-linux-${version}.tar.xz"
expected_sha="1cbe9df9f27e6b78d14ccbca43b6703a404ef79ef1c463de901d7f088d4e2026"
tools_dir="$android_root/native/nova-ghostty-zig/.tools"
install_dir="$tools_dir/zig-${version}"
archive="$tools_dir/$archive_name"

if [[ ! -x "$install_dir/zig" ]]; then
    mkdir -p "$tools_dir"
    if [[ ! -f "$archive" ]]; then
        curl --fail --location --output "$archive" \
            "https://ziglang.org/download/${version}/${archive_name}"
    fi
    actual_sha="$(sha256sum "$archive" | awk '{print $1}')"
    if [[ "$actual_sha" != "$expected_sha" ]]; then
        echo "Zig archive checksum mismatch: $actual_sha" >&2
        exit 1
    fi
    tar -xf "$archive" -C "$tools_dir"
    mv "$tools_dir/zig-x86_64-linux-${version}" "$install_dir"
fi

printf '%s\n' "$install_dir/zig"
