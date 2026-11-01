#!/usr/bin/env bash
# CI-only x86_64 emulator. Never contacts a printer or uses the Preview signing key.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
: "${ANDROID_HOME:?Android SDK is required}"
test -r /dev/kvm && test -w /dev/kvm
report_dir="$project_dir/build/emulator"
mkdir -p "$report_dir"
export ANDROID_USER_HOME="${RUNNER_TEMP:-$project_dir/build}/brotherfastprint-android-user"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME"
adb_bin="$ANDROID_HOME/platform-tools/adb"
emulator_bin="$ANDROID_HOME/emulator/emulator"
avd_name="brotherfastprint-api36"
avdmanager create avd --force --name "$avd_name" \
    --path "$ANDROID_AVD_HOME/$avd_name.avd" \
    --package 'system-images;android-36;google_apis;x86_64' --device pixel_2 <<<'no'
test -s "$ANDROID_AVD_HOME/$avd_name.ini"
"$emulator_bin" -list-avds >"$report_dir/avds.txt"
python3 - "$report_dir/avds.txt" "$avd_name" <<'PY'
import pathlib, sys
assert sys.argv[2] in pathlib.Path(sys.argv[1]).read_text().splitlines(), 'Created AVD is not visible to emulator'
PY
"$adb_bin" start-server
"$emulator_bin" -avd "$avd_name" -port 5554 -no-window -no-audio -no-boot-anim \
    -no-snapshot -gpu swiftshader -memory 3072 -cores 2 \
    >"$report_dir/emulator.log" 2>&1 &
emulator_pid=$!
cleanup() {
    for sample in full stripes; do
        if timeout 5 "$adb_bin" -s emulator-5554 shell run-as ru.brotherfastprint.preview test -s "cache/ci-render-$sample.png" >/dev/null 2>&1; then
            timeout 8 "$adb_bin" -s emulator-5554 exec-out run-as ru.brotherfastprint.preview cat "cache/ci-render-$sample.png" >"$report_dir/ci-render-$sample.png" 2>/dev/null || true
        fi
    done
    timeout 8 "$adb_bin" -s emulator-5554 logcat -d -t 10000 >"$report_dir/logcat.txt" 2>&1 || true
    timeout 5 "$adb_bin" -s emulator-5554 emu kill >/dev/null 2>&1 || true
    kill "$emulator_pid" 2>/dev/null || true
    for ((retry=0; retry<20; retry++)); do
        kill -0 "$emulator_pid" 2>/dev/null || break
        sleep 0.25
    done
    kill -KILL "$emulator_pid" 2>/dev/null || true
    wait "$emulator_pid" 2>/dev/null || true
}
trap cleanup EXIT
ready=false
boot_deadline=$((SECONDS + 240))
while (( SECONDS < boot_deadline )); do
    if ! kill -0 "$emulator_pid" 2>/dev/null; then
        tail -n 80 "$report_dir/emulator.log"
        exit 1
    fi
    if [[ "$(timeout 5 "$adb_bin" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
        ready=true
        break
    fi
    sleep 2
done
if [[ "$ready" != true ]]; then
    tail -n 80 "$report_dir/emulator.log"
    exit 1
fi
export ANDROID_SERIAL=emulator-5554
"$adb_bin" shell input keyevent 82
"$adb_bin" shell settings put global window_animation_scale 0
"$adb_bin" shell settings put global transition_animation_scale 0
"$adb_bin" shell settings put global animator_duration_scale 0
"$adb_bin" shell getprop ro.build.version.sdk
./gradlew --no-daemon -PtestAbi=x86_64 :app:connectedDebugAndroidTest
