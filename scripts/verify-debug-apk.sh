#!/usr/bin/env bash
set -euo pipefail

android_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk="${1:-$android_root/app/build/outputs/apk/community/debug/app-community-debug.apk}"
: "${ANDROID_SDK_ROOT:=${ANDROID_HOME:-$HOME/Android/Sdk}}"

if [[ ! -f "$apk" ]]; then
    echo "APK not found: $apk" >&2
    exit 1
fi

zipalign="$(find "$ANDROID_SDK_ROOT/build-tools" -type f -name zipalign -print | sort -V | tail -n 1)"
if [[ -z "$zipalign" ]]; then
    echo "zipalign was not found under $ANDROID_SDK_ROOT/build-tools" >&2
    exit 1
fi
command -v unzip >/dev/null
command -v readelf >/dev/null

"$zipalign" -c -P 16 -v 4 "$apk" >/dev/null

entries="$(unzip -Z1 "$apk")"
for required in \
    lib/arm64-v8a/libgojni.so \
    lib/arm64-v8a/libnova_core.so \
    lib/x86_64/libgojni.so \
    lib/x86_64/libnova_core.so; do
    if ! grep -Fxq "$required" <<<"$entries"; then
        echo "Required native library is missing: $required" >&2
        exit 1
    fi
done

unexpected_abi="$(awk -F/ '/^lib\/[^/]+\/.*\.so$/ && $2 != "arm64-v8a" && $2 != "x86_64" {print $2}' <<<"$entries" | sort -u)"
if [[ -n "$unexpected_abi" ]]; then
    echo "Unexpected native ABI(s): $unexpected_abi" >&2
    exit 1
fi

temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
(
    cd "$temporary"
    unzip -q "$apk" 'lib/*/libgojni.so' 'lib/*/libnova_core.so'
)

while IFS= read -r library; do
    while IFS= read -r alignment; do
        if (( alignment < 0x4000 )); then
            echo "ELF segment is not 16 KiB aligned: $library ($alignment)" >&2
            exit 1
        fi
    done < <(readelf -lW "$library" | awk '$1 == "LOAD" {print $NF}')
done < <(find "$temporary/lib" -type f -name '*.so' -print | sort)

echo "APK verification passed: $(sha256sum "$apk" | awk '{print $1}')"
echo "ABIs: arm64-v8a, x86_64; archive and ELF LOAD alignment: 16 KiB"
