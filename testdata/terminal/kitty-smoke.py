#!/usr/bin/env python3
# Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
"""Run inside a NovaScale SSH session to exercise direct Kitty image uploads."""
import argparse
import base64
import struct
import sys
import zlib


def png(width, height, rgba):
    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    rows = b"".join(b"\0" + rgba[y * width * 4:(y + 1) * width * 4] for y in range(height))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--format", choices=("png", "rgba", "rgb"), default="png")
    parser.add_argument("--zlib", action="store_true", help="also exercise Kitty o=z compression")
    args = parser.parse_args()
    if not sys.stdout.isatty():
        parser.error("run this helper inside the interactive SSH terminal")
    width, height = 128, 64
    rgba = bytes(value for y in range(height) for x in range(width)
                 for value in (x * 2, y * 4, 255 - x * 2, 0 if x > 96 else 255))
    if args.format == "png":
        data, fmt = png(width, height, rgba), 100
    elif args.format == "rgba":
        data, fmt = rgba, 32
    else:
        data, fmt = bytes(rgba[i] for i in range(len(rgba)) if i % 4 != 3), 24
    control = f"a=T,f={fmt},s={width},v={height},i=42,c=40,r=8,q=2"
    if args.zlib:
        data = zlib.compress(data)
        control += ",o=z"
    encoded = base64.b64encode(data).decode("ascii")
    print("NovaScale Kitty smoke: gradient, transparency, direct chunked upload")
    for offset in range(0, len(encoded), 4096):
        more = int(offset + 4096 < len(encoded))
        metadata = control if offset == 0 else "q=2"
        sys.stdout.write(f"\x1b_G{metadata},m={more};{encoded[offset:offset + 4096]}\x1b\\")
    print("\nImage should be above. Test clear, Vim, scrollback, resize and font changes.", flush=True)


if __name__ == "__main__":
    main()
