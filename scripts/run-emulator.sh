#!/usr/bin/env bash
set -Eeuo pipefail

# Persistent NovaScale Android owner-test emulator.
#
# The AVD userdata is intentionally reused. APK updates use `adb install -r`,
# which preserves application data, including the embedded Tailscale node state.

android_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

: "${ANDROID_SDK_ROOT:=${ANDROID_HOME:-$HOME/Android/Sdk}}"
: "${NOVASCALE_AVD_NAME:=NovaScale_API_33}"
: "${NOVASCALE_SYSTEM_IMAGE:=system-images;android-33;google_apis_playstore;x86_64}"
: "${NOVASCALE_AVD_DEVICE:=pixel_8}"
: "${NOVASCALE_EMULATOR_PORT:=5556}"
: "${NOVASCALE_BOOT_TIMEOUT:=240}"

export ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"

emulator="$ANDROID_SDK_ROOT/emulator/emulator"
adb="$ANDROID_SDK_ROOT/platform-tools/adb"
avdmanager="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
: "${NOVASCALE_BUILD_FLAVOR:=community}"
case "$NOVASCALE_BUILD_FLAVOR" in
    community) package_name="cc.galaxnet.novascale.community.debug" ;;
    play) package_name="cc.galaxnet.novascale.debug" ;;
    *) echo "NOVASCALE_BUILD_FLAVOR must be community or play" >&2; exit 1 ;;
esac
apk="$android_root/app/build/outputs/apk/$NOVASCALE_BUILD_FLAVOR/debug/app-$NOVASCALE_BUILD_FLAVOR-debug.apk"
activity_name="$package_name/cc.galaxnet.novascale.MainActivity"
avd_home="${ANDROID_AVD_HOME:-${ANDROID_USER_HOME:-$HOME/.android}/avd}"

command_name="run"
build_apk=true
headless=false
cold_boot=false
launch_app=true
serial=""
log_file=""

usage() {
    cat <<'EOF'
Usage: ./scripts/run-emulator.sh [command] [options]

Commands:
  run       Create/start the persistent AVD, build, install, and launch (default)
  start     Create/start the persistent AVD without building or installing
  install   Start if needed, build, install, and launch NovaScale
  stop      Shut down the AVD cleanly; its userdata is preserved
  status    Show the AVD location and running device serial

Options:
  --no-build             Install the existing APK for the selected flavor
  --no-launch            Install without opening NovaScale
  --headless             Launch without an emulator window
  --cold-boot            Ignore Quick Boot state once; userdata is still preserved
  --avd NAME             Override the persistent AVD name
  --port EVEN_PORT       Override the emulator console port (default: 5556)
  --system-image ID      Override the avdmanager system-image package
  --device ID            Override the avdmanager hardware device (default: pixel_8)
  -h, --help             Show this help

Environment equivalents:
  ANDROID_SDK_ROOT, NOVASCALE_AVD_NAME, NOVASCALE_SYSTEM_IMAGE,
  NOVASCALE_AVD_DEVICE, NOVASCALE_EMULATOR_PORT, NOVASCALE_BOOT_TIMEOUT,
  NOVASCALE_BUILD_FLAVOR (community by default, or play)

Persistence contract:
  This script never uses -wipe-data, adb uninstall, pm clear, or avdmanager
  delete. Repeated installs use `adb install -r`, so NovaScale app data and the
  embedded Tailscale identity remain on the named AVD. Authentication can still
  expire or be revoked by the control server.
EOF
}

die() {
    echo "error: $*" >&2
    exit 1
}

note() {
    echo "==> $*"
}

if (($# > 0)); then
    case "$1" in
        run|start|install|stop|status)
            command_name="$1"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
    esac
fi

while (($# > 0)); do
    case "$1" in
        --no-build)
            build_apk=false
            shift
            ;;
        --no-launch)
            launch_app=false
            shift
            ;;
        --headless)
            headless=true
            shift
            ;;
        --cold-boot)
            cold_boot=true
            shift
            ;;
        --avd)
            (($# >= 2)) || die "--avd requires a name"
            NOVASCALE_AVD_NAME="$2"
            shift 2
            ;;
        --port)
            (($# >= 2)) || die "--port requires a value"
            NOVASCALE_EMULATOR_PORT="$2"
            shift 2
            ;;
        --system-image)
            (($# >= 2)) || die "--system-image requires a package ID"
            NOVASCALE_SYSTEM_IMAGE="$2"
            shift 2
            ;;
        --device)
            (($# >= 2)) || die "--device requires an avdmanager device ID"
            NOVASCALE_AVD_DEVICE="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown argument: $1 (use --help)"
            ;;
    esac
done

[[ "$NOVASCALE_AVD_NAME" =~ ^[A-Za-z0-9._-]+$ ]] ||
    die "AVD name may contain only letters, numbers, dot, underscore, and dash"
[[ "$NOVASCALE_EMULATOR_PORT" =~ ^[0-9]+$ ]] || die "emulator port must be numeric"
((NOVASCALE_EMULATOR_PORT >= 5554 && NOVASCALE_EMULATOR_PORT <= 5682)) ||
    die "emulator port must be between 5554 and 5682"
((NOVASCALE_EMULATOR_PORT % 2 == 0)) || die "emulator port must be even"
[[ "$NOVASCALE_BOOT_TIMEOUT" =~ ^[0-9]+$ ]] || die "boot timeout must be numeric"

serial="emulator-$NOVASCALE_EMULATOR_PORT"
log_file="${TMPDIR:-/tmp}/novascale-emulator-${UID}-${NOVASCALE_AVD_NAME}.log"

require_sdk_tools() {
    [[ -x "$emulator" ]] || die "Android emulator not found at $emulator"
    [[ -x "$adb" ]] || die "adb not found at $adb"
    [[ -x "$avdmanager" ]] || die "avdmanager not found at $avdmanager"
}

avd_exists() {
    "$emulator" -list-avds 2>/dev/null | grep -Fxq "$NOVASCALE_AVD_NAME"
}

create_avd_if_needed() {
    if avd_exists; then
        note "Reusing persistent AVD $NOVASCALE_AVD_NAME"
        return
    fi

    local image_path="$ANDROID_SDK_ROOT/${NOVASCALE_SYSTEM_IMAGE//;/\/}"
    [[ -d "$image_path" ]] || die \
        "system image is not installed: $NOVASCALE_SYSTEM_IMAGE
Install it with:
  \"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager\" \"$NOVASCALE_SYSTEM_IMAGE\""

    note "Creating persistent AVD $NOVASCALE_AVD_NAME"
    printf 'no\n' | "$avdmanager" create avd \
        --name "$NOVASCALE_AVD_NAME" \
        --package "$NOVASCALE_SYSTEM_IMAGE" \
        --device "$NOVASCALE_AVD_DEVICE"
    note "AVD userdata created under $avd_home and will be reused"
}

running_avd_name() {
    local device_serial="$1"
    "$adb" -s "$device_serial" emu avd name 2>/dev/null |
        tr -d '\r' |
        awk 'NF && $0 != "OK" { print; exit }'
}

find_running_serial() {
    local device_serial state avd_name
    while read -r device_serial state _; do
        [[ "$device_serial" == emulator-* ]] || continue
        [[ "$state" == "device" || "$state" == "offline" ]] || continue
        avd_name="$(running_avd_name "$device_serial")"
        if [[ "$avd_name" == "$NOVASCALE_AVD_NAME" ]]; then
            printf '%s\n' "$device_serial"
            return 0
        fi
    done < <("$adb" devices 2>/dev/null | tail -n +2)
    return 1
}

device_state() {
    local requested_serial="$1"
    "$adb" devices 2>/dev/null |
        awk -v serial="$requested_serial" '$1 == serial { print $2; exit }'
}

wait_for_boot() {
    local started_at=$SECONDS state boot_completed
    note "Waiting up to ${NOVASCALE_BOOT_TIMEOUT}s for Android to boot ($serial)"
    while ((SECONDS - started_at < NOVASCALE_BOOT_TIMEOUT)); do
        state="$("$adb" -s "$serial" get-state 2>/dev/null || true)"
        if [[ "$state" == "device" ]]; then
            boot_completed="$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
            if [[ "$boot_completed" == "1" ]]; then
                "$adb" -s "$serial" shell input keyevent 82 >/dev/null 2>&1 || true
                note "Android is ready on $serial"
                return
            fi
        fi
        sleep 2
    done
    tail -n 80 "$log_file" >&2 2>/dev/null || true
    die "emulator did not finish booting; log: $log_file"
}

start_emulator() {
    local found_serial current_name current_state emulator_pid unit_name
    if found_serial="$(find_running_serial)"; then
        serial="$found_serial"
        note "Persistent AVD is already running on $serial"
        wait_for_boot
        return
    fi

    current_state="$(device_state "$serial")"
    if [[ -n "$current_state" ]]; then
        current_name="$(running_avd_name "$serial")"
        die "$serial is already used by AVD ${current_name:-unknown} ($current_state); choose another --port"
    fi

    local -a emulator_args=(
        -avd "$NOVASCALE_AVD_NAME"
        -port "$NOVASCALE_EMULATOR_PORT"
        -netdelay none
        -netspeed full
        -no-boot-anim
    )
    $headless && emulator_args+=(-no-window -no-audio)
    $cold_boot && emulator_args+=(-no-snapshot-load)

    note "Launching $NOVASCALE_AVD_NAME on $serial"
    note "Emulator log: $log_file"
    : >"$log_file"

    # A user service survives the terminal or launcher shell that started this
    # script. Fall back to a detached process session on hosts without systemd.
    if command -v systemd-run >/dev/null 2>&1 &&
        command -v systemd-escape >/dev/null 2>&1 &&
        systemctl --user show-environment >/dev/null 2>&1; then
        unit_name="$(systemd-escape --template=novascale-emulator@.service "$NOVASCALE_AVD_NAME")"
        systemctl --user reset-failed "$unit_name" >/dev/null 2>&1 || true
        systemd-run --user --quiet --collect \
            --unit="$unit_name" \
            --property=Type=exec \
            --property="StandardOutput=append:$log_file" \
            --property="StandardError=append:$log_file" \
            --setenv="ANDROID_HOME=$ANDROID_SDK_ROOT" \
            --setenv="ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT" \
            --setenv="ANDROID_AVD_HOME=$avd_home" \
            --setenv="DISPLAY=${DISPLAY:-}" \
            "$emulator" "${emulator_args[@]}"
        note "Emulator is managed by the user service $unit_name"
    else
        if command -v setsid >/dev/null 2>&1; then
            nohup setsid --fork "$emulator" "${emulator_args[@]}" \
                >"$log_file" 2>&1 </dev/null &
        else
            nohup "$emulator" "${emulator_args[@]}" \
                >"$log_file" 2>&1 </dev/null &
        fi
        emulator_pid=$!
        disown "$emulator_pid" 2>/dev/null || true
    fi
    wait_for_boot
}

build_and_install() {
    if $build_apk; then
        note "Building the latest NovaScale debug APK"
        (cd "$android_root" && ./gradlew ":app:assemble${NOVASCALE_BUILD_FLAVOR^}Debug")
    fi
    [[ -f "$apk" ]] || die "APK not found: $apk (remove --no-build or build it first)"

    note "Verifying APK native packaging"
    "$android_root/scripts/verify-debug-apk.sh" "$apk"

    note "Installing with replacement mode; existing NovaScale data is preserved"
    local install_output
    if ! install_output="$("$adb" -s "$serial" install -r -t "$apk" 2>&1)"; then
        echo "$install_output" >&2
        die "APK installation failed. This script will not uninstall the app or clear its data."
    fi
    echo "$install_output"

    if $launch_app; then
        note "Launching NovaScale"
        "$adb" -s "$serial" shell am start -S -n "$activity_name"
    fi
}

stop_emulator() {
    local found_serial started_at
    if ! found_serial="$(find_running_serial)"; then
        note "$NOVASCALE_AVD_NAME is not running; userdata remains at $avd_home"
        return
    fi
    serial="$found_serial"
    note "Stopping $NOVASCALE_AVD_NAME on $serial without wiping userdata"
    "$adb" -s "$serial" emu kill >/dev/null
    started_at=$SECONDS
    while [[ -n "$(device_state "$serial")" ]] && ((SECONDS - started_at < 30)); do
        sleep 1
    done
    note "The next run will reuse the same authenticated emulator data"
}

show_status() {
    local found_serial=""
    echo "AVD:         $NOVASCALE_AVD_NAME"
    echo "AVD home:    $avd_home"
    echo "APK:         $apk"
    echo "System image: $NOVASCALE_SYSTEM_IMAGE"
    if avd_exists; then
        echo "Created:     yes"
    else
        echo "Created:     no (it will be created by run/start/install)"
    fi
    if found_serial="$(find_running_serial)"; then
        echo "Running:     yes ($found_serial)"
    else
        echo "Running:     no"
    fi
}

require_sdk_tools
case "$command_name" in
    run|install)
        create_avd_if_needed
        "$adb" start-server >/dev/null
        start_emulator
        build_and_install
        note "Ready. Close the emulator window or run '$0 stop' when finished."
        ;;
    start)
        create_avd_if_needed
        "$adb" start-server >/dev/null
        start_emulator
        ;;
    stop)
        "$adb" start-server >/dev/null
        stop_emulator
        ;;
    status)
        "$adb" start-server >/dev/null 2>&1 || true
        show_status
        ;;
esac
