#!/usr/bin/env bash
set -euo pipefail
OUT="activity_archive_diagnostics"
mkdir -p "$OUT"
echo "Checking connected Android device..."
adb devices
adb shell dumpsys usagestats > "$OUT/usagestats.txt"
adb logcat -b events -d -v epoch > "$OUT/event-log-buffer.txt"
adb shell dumpsys power > "$OUT/power-state.txt"
adb shell getprop ro.boot.bootreason > "$OUT/boot-reason.txt"
adb shell getprop sys.boot_completed >> "$OUT/boot-reason.txt"
echo "Diagnostics saved in $OUT."
echo "These are best-effort diagnostic snapshots, not a guaranteed complete historical log."
