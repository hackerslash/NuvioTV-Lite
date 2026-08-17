#!/usr/bin/env bash
# Force ART to AOT-compile an installed build the way Play does at install time.
#
# A sideloaded APK sits at compilation filter "verify" (no AOT) until Android's
# background dexopt runs, which waits for idle + charging and can take a day. Until
# then cold start is 2-3x slower than the same code installed from Play. This runs
# that compilation now so a local build can be measured against a Play one fairly.
#
# Usage: scripts/adb-optimize.sh [package]   (default: com.nuvio.tv.lite)

set -euo pipefail

PKG="${1:-com.nuvio.tv.lite}"
ADB_PORT=5555
RUNS=3

command -v adb >/dev/null || { echo "adb not found. brew install --cask android-platform-tools" >&2; exit 1; }

online_devices() { adb devices | awk 'NR>1 && $2=="device" {print $1}'; }

# Android TV boxes commonly bind adbd to 5555 whenever USB debugging is on, with no
# separate wireless-debugging toggle, so a plain subnet sweep usually finds them.
scan_lan() {
    local self base
    self="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
    [ -n "$self" ] || return 0
    base="${self%.*}"
    echo "No device attached. Scanning ${base}.0/24 for adb on ${ADB_PORT}..." >&2
    for i in $(seq 1 254); do
        # -G bounds the connect itself; -w alone leaves unreachable hosts hanging.
        ( nc -z -G1 -w1 "${base}.${i}" "$ADB_PORT" 2>/dev/null && echo "${base}.${i}" ) &
    done
    wait
}

# read_devices instead of mapfile: macOS ships bash 3.2, which has no mapfile.
read_devices() {
    DEVICES=()
    while IFS= read -r line; do
        [ -n "$line" ] && DEVICES+=("$line")
    done < <(online_devices)
}

read_devices

if [ "${#DEVICES[@]}" -eq 0 ]; then
    while read -r host; do
        [ -n "$host" ] || continue
        echo "Found ${host}:${ADB_PORT}, connecting..." >&2
        adb connect "${host}:${ADB_PORT}" >/dev/null 2>&1 || true
    done < <(scan_lan)
    sleep 2
    read_devices
fi

if [ "${#DEVICES[@]}" -eq 0 ]; then
    if adb devices | awk 'NR>1 && $2=="unauthorized"' | grep -q .; then
        echo "Device found but unauthorized. Accept the debugging prompt on the TV" >&2
        echo "(tick 'Always allow from this computer'), then re-run." >&2
    else
        echo "No adb device found on USB or the local subnet." >&2
        echo "Enable ADB/USB debugging in Developer options and make sure the TV is on this WiFi." >&2
    fi
    exit 1
fi

if [ "${#DEVICES[@]}" -eq 1 ]; then
    DEV="${DEVICES[0]}"
else
    echo "Multiple devices:" >&2
    for i in "${!DEVICES[@]}"; do
        model="$(adb -s "${DEVICES[$i]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
        printf '  [%d] %s  %s\n' "$((i + 1))" "${DEVICES[$i]}" "$model" >&2
    done
    read -r -p "Choose [1-${#DEVICES[@]}]: " pick < /dev/tty
    [[ "$pick" =~ ^[0-9]+$ ]] && [ "$pick" -ge 1 ] && [ "$pick" -le "${#DEVICES[@]}" ] \
        || { echo "Invalid selection." >&2; exit 1; }
    DEV="${DEVICES[$((pick - 1))]}"
fi

adb -s "$DEV" shell pm path "$PKG" >/dev/null 2>&1 \
    || { echo "$PKG is not installed on $DEV." >&2; exit 1; }

ACTIVITY="$(adb -s "$DEV" shell cmd package resolve-activity --brief "$PKG" 2>/dev/null | tail -1 | tr -d '\r')"
[ -n "$ACTIVITY" ] || { echo "Could not resolve a launcher activity for $PKG." >&2; exit 1; }

echo "Device   : $DEV ($(adb -s "$DEV" shell getprop ro.product.model 2>/dev/null | tr -d '\r'))"
echo "Package  : $PKG"
echo "Activity : $ACTIVITY"

# Median of RUNS cold starts. force-stop leaves the page cache warm, so this measures
# process start to first frame, not a true power-on cold start -- consistent enough to
# compare before against after.
measure() {
    local times=()
    for _ in $(seq 1 "$RUNS"); do
        adb -s "$DEV" shell am force-stop "$PKG"
        sleep 3
        t="$(adb -s "$DEV" shell am start -W -n "$ACTIVITY" 2>/dev/null | awk -F': *' '/^TotalTime/{print $2}' | tr -d '\r')"
        [ -n "$t" ] && [ "$t" -gt 0 ] 2>/dev/null && times+=("$t")
        sleep 1
    done
    [ "${#times[@]}" -gt 0 ] || { echo ""; return; }
    printf '%s\n' "${times[@]}" | sort -n | awk '{v[NR]=$1} END{print v[int((NR+1)/2)]}'
}

dexopt_state() {
    adb -s "$DEV" shell dumpsys package dexopt 2>/dev/null \
        | grep -A3 "\[$PKG\]" | grep -m1 'status=' | tr -d '\r'
}

STATE="$(dexopt_state)"
echo "Dexopt   : $(printf '%s' "${STATE:-unknown}" | sed -E 's/.*status=([^]]*)\].*reason=([^]]*)\].*/\1 (reason: \2)/')"
echo

# reason=install means the app was compiled from the bundled baseline profile only.
# Anything else (bg-dexopt, or a previous run of this script) means it has already been
# compiled against real usage data, so recompiling buys nothing and the before/after
# below would just be measurement noise.
if [ "${FORCE:-0}" != "1" ] && ! printf '%s' "$STATE" | grep -q 'reason=install'; then
    echo "Already compiled beyond install state -- nothing to do."
    echo "Measuring current cold start only."
    echo "  median: $(measure) ms"
    echo
    echo "Re-run anyway with: FORCE=1 $0 $PKG"
    echo "Start over with:    adb -s $DEV uninstall $PKG && adb -s $DEV install <apk>"
    exit 0
fi

echo "Measuring before..."
BEFORE="$(measure)"
echo "  median: ${BEFORE:-n/a} ms"

echo "Compiling (speed-profile, may take a minute)..."
adb -s "$DEV" shell cmd package compile -m speed-profile -f "$PKG"

echo "Measuring after..."
AFTER="$(measure)"
echo "  median: ${AFTER:-n/a} ms"

echo
if [ -n "$BEFORE" ] && [ -n "$AFTER" ] && [ "$AFTER" -gt 0 ]; then
    awk -v b="$BEFORE" -v a="$AFTER" 'BEGIN{printf "%d ms -> %d ms  (%.2fx)\n", b, a, b/a}'
fi
echo "Undo with: adb -s $DEV shell cmd package compile --reset $PKG"
