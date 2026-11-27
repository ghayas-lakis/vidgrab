#!/usr/bin/env bash
# Builds the APK. Called by .github/workflows/build.yml.
# With the VIDGRAB_KEYSTORE secret (base64 of the signing key file) the APK is signed with VidGrab's
# fixed key, so it installs over the previous build; without it Android's temporary debug key is used.
set -uo pipefail
if [ -n "${VIDGRAB_KEYSTORE_B64:-}" ]; then
  key="${RUNNER_TEMP:-/tmp}/vidgrab-signing.p12"
  echo "$VIDGRAB_KEYSTORE_B64" | base64 -d > "$key"
  export VIDGRAB_KEYSTORE_FILE="$key"
  echo "Signing with the fixed VidGrab key."
else
  echo "::warning title=Temporary signing key::The VIDGRAB_KEYSTORE secret is not set, so this build uses a temporary debug key and will not install over a previous build. See README > Installing."
fi
gradle assembleDebug testDebugUnitTest --no-daemon 2>&1 | tee build.log
code=${PIPESTATUS[0]}
[ -n "${VIDGRAB_KEYSTORE_FILE:-}" ] && rm -f "$VIDGRAB_KEYSTORE_FILE"
if [ "$code" != 0 ]; then python3 ci/annotate.py build build.log; fi
ls -la app/build/outputs/apk/debug/ 2>/dev/null
exit "$code"
