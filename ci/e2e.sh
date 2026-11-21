#!/usr/bin/env bash
# Runs the end-to-end tests on the (already booted) emulator. Called by .github/workflows/build.yml.
#   core    - EndToEndTest: must pass
#   youtube - YouTubeTest: only warns on failure (YouTube often blocks CI's data-center IPs)
# Everything lands in ci-out/ (uploaded as the "e2e-results" artifact).
set -uo pipefail
mode="${1:-core}"
if [ "$mode" = youtube ]; then
  filter="-Pandroid.testInstrumentationRunnerArguments.class=com.example.vidgrab.YouTubeTest"
else
  filter="-Pandroid.testInstrumentationRunnerArguments.notClass=com.example.vidgrab.YouTubeTest"
fi
mkdir -p ci-out
rm -rf app/build/outputs/androidTest-results app/build/reports/androidTests
adb logcat -c || true
adb logcat -v time > "ci-out/logcat-$mode.txt" 2>&1 &
logcat_pid=$!

gradle connectedDebugAndroidTest -Pvidgrab.abis=x86_64 $filter --no-daemon --stacktrace 2>&1 | tee "ci-out/gradle-$mode.log"
code=${PIPESTATUS[0]}

kill "$logcat_pid" 2>/dev/null || true
mkdir -p "ci-out/results-$mode"
find app/build/outputs/androidTest-results -name '*.xml' -exec cp {} "ci-out/results-$mode/" \; 2>/dev/null || true
cp -r app/build/reports/androidTests "ci-out/report-$mode" 2>/dev/null || true
grep -E "VidGrabE2E|AndroidRuntime|youtubedl|TestRunner" "ci-out/logcat-$mode.txt" > "ci-out/logcat-$mode-short.txt" 2>/dev/null || true
python3 ci/annotate.py "$mode" "ci-out/gradle-$mode.log" "ci-out/results-$mode"

[ "$mode" = youtube ] && exit 0
exit "$code"
