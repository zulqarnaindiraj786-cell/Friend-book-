#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# friendbook — local signed release APK builder (NO GitHub Actions, NO billing)
#
# Produces: app/build/outputs/apk/release/app-release.apk
#           -> copied to ./friendbook-2.8.92.apk
#
# Requirements: JDK 17 + Android SDK (platform 36, build-tools 36.0.0)
# Usage:
#   ./build-apk.sh                 # uses $ANDROID_HOME / $ANDROID_SDK_ROOT
#   ./build-apk.sh /path/to/sdk    # explicit SDK path
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")"

RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; OFF=$'\033[0m'
ok()   { echo "${GRN}✓${OFF} $*"; }
warn() { echo "${YEL}!${OFF} $*"; }
die()  { echo "${RED}✗ $*${OFF}" >&2; exit 1; }

echo "── friendbook local build ───────────────────────────────────────────"

# 1. JDK 17 ---------------------------------------------------------------
if ! command -v java >/dev/null 2>&1; then
  die "Java not found. Install JDK 17 (Android Studio bundles one: 'Embedded JDK')."
fi
JVER_FULL=$(java -version 2>&1 | head -1)
JMAJOR=$(echo "$JVER_FULL" | sed -E 's/.*version "([0-9]+).*/\1/')
[ "$JMAJOR" = "1" ] && JMAJOR=$(echo "$JVER_FULL" | sed -E 's/.*version "1\.([0-9]+).*/\1/')
if [ "${JMAJOR:-0}" -lt 17 ]; then
  die "JDK 17+ required, found: $JVER_FULL"
fi
ok "JDK $JMAJOR — $JVER_FULL"

# 2. Android SDK ----------------------------------------------------------
SDK="${1:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
if [ -z "$SDK" ]; then
  for c in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "/usr/lib/android-sdk"; do
    [ -d "$c" ] && SDK="$c" && break
  done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || die "Android SDK not found.
  Set ANDROID_HOME, or pass the path:  ./build-apk.sh /path/to/Android/Sdk
  Easiest: install Android Studio (free) — it installs the SDK for you."
ok "Android SDK — $SDK"

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
echo "sdk.dir=$SDK" > local.properties

# 3. SDK components -------------------------------------------------------
if [ ! -d "$SDK/platforms/android-36" ]; then
  warn "Android platform 36 not installed — installing (needs internet, one time)…"
  SDKM=$(command -v sdkmanager || echo "$SDK/cmdline-tools/latest/bin/sdkmanager")
  if [ -x "$SDKM" ]; then
    yes | "$SDKM" --licenses >/dev/null 2>&1 || true
    "$SDKM" "platforms;android-36" "build-tools;36.0.0" "platform-tools"
  else
    die "sdkmanager not found. Open Android Studio → SDK Manager → install
  'Android 16 (API 36)' + 'Android SDK Build-Tools 36' + 'Android SDK Platform-Tools'."
  fi
fi
ok "platform android-36 present"

# 4. Signing keystore -----------------------------------------------------
# NOTE: keystore file name/passwords live in app/build.gradle. Keep it, otherwise
# the APK is unsigned and Android will refuse to install it as an update.
[ -f app/friendbook-release.p12 ] || die "app/friendbook-release.p12 missing — APK would be unsigned."
ok "release keystore present"

# 5. Build ----------------------------------------------------------------
APPID=$(grep -oE "applicationId '[^']+'" app/build.gradle | head -1 | cut -d"'" -f2)
VCODE=$(grep -oE "versionCode [0-9]+" app/build.gradle | head -1 | awk '{print $2}')
VNAME=$(grep -oE "versionName '[^']+'" app/build.gradle | head -1 | cut -d"'" -f2)
echo
echo "Building  package=$APPID  version=$VNAME ($VCODE)"
echo "First run downloads Gradle + dependencies (a few minutes)."
echo

# assembleRelease only — skips the CI-only unit tests / lint gates.
./gradlew :app:assembleRelease --no-daemon --stacktrace

APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || die "Build finished but $APK not found."

OUT="friendbook-${VNAME}.apk"
cp "$APK" "$OUT"
echo
ok "APK ready: $(pwd)/$OUT"
ls -lh "$OUT"
echo
echo "Install over the OLD app (do NOT uninstall first):"
echo "  1. copy $OUT to your phone (USB / Google Drive / Telegram)"
echo "  2. tap it -> Update / Install"
echo
echo "Check it is the right package:"
echo "  \$SDK/build-tools/36.0.0/aapt2 dump badging \"$OUT\" | head -1"
