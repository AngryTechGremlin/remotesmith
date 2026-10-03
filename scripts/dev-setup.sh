#!/usr/bin/env bash
# Install what this repo needs to build the app and talk to a device: adb, a
# JDK and the Android SDK. No sudo: everything goes under $PREFIX (default
# ~/.local/opt). Safe to run again; it only fetches what is missing.
#
# Linux x86_64 only. On another system, install the same three things your
# usual way (Android Studio brings all of them) and set JAVA_HOME and
# ANDROID_HOME; the Gradle wrapper does the rest.
set -euo pipefail

PREFIX=${PREFIX:-$HOME/.local/opt}
JDK=$PREFIX/jdk-21
SDK=$PREFIX/android-sdk
# The SDK platform the app compiles against (compileSdk in app/build.gradle.kts).
PLATFORM="platforms;android-36"
# A known-good build of Google's command-line tools; newer ones work too.
CMDLINE_TOOLS=commandlinetools-linux-9862592_latest.zip

die() { printf 'dev-setup: %s\n' "$1" >&2; exit 2; }

[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || die "this script only knows Linux x86_64; see the comment at its top"
for tool in curl unzip tar; do
  command -v "$tool" >/dev/null 2>&1 || die "needs $tool"
done

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$PREFIX"

if [[ ! -x $PREFIX/platform-tools/adb ]]; then
  echo "fetching adb"
  curl -fsSL -o "$tmp/platform-tools.zip" https://dl.google.com/android/repository/platform-tools-latest-linux.zip
  unzip -q "$tmp/platform-tools.zip" -d "$PREFIX"
fi

if [[ ! -x $JDK/bin/java ]]; then
  echo "fetching a JDK (Temurin 21)"
  curl -fsSL -o "$tmp/jdk.tgz" https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse
  mkdir -p "$JDK"
  tar -xzf "$tmp/jdk.tgz" -C "$JDK" --strip-components=1
fi

if [[ ! -x $SDK/cmdline-tools/latest/bin/sdkmanager ]]; then
  echo "fetching the Android SDK command-line tools"
  curl -fsSL -o "$tmp/cmdline-tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/sdk"
  mkdir -p "$SDK/cmdline-tools"
  mv "$tmp/sdk/cmdline-tools" "$SDK/cmdline-tools/latest"
fi

export JAVA_HOME=$JDK ANDROID_HOME=$SDK
sdkmanager=$SDK/cmdline-tools/latest/bin/sdkmanager
if [[ ! -d $SDK/platforms/${PLATFORM#*;} ]]; then
  echo "fetching the Android platform (this accepts the SDK licences on your behalf)"
  # `yes` is cut off when sdkmanager stops reading, which pipefail would report as a failure.
  yes 2>/dev/null | "$sdkmanager" --licenses >/dev/null || true
  "$sdkmanager" "$PLATFORM" >/dev/null
fi

# Tell Gradle where the SDK is, and switch on the pre-push scan for personal data.
[[ -f $root/local.properties ]] || printf 'sdk.dir=%s\n' "$SDK" > "$root/local.properties"
if git -C "$root" rev-parse --git-dir >/dev/null 2>&1; then
  git -C "$root" config core.hooksPath .githooks
fi

cat <<EOF

Ready. Put these in your shell profile, or export them in each session:

  export JAVA_HOME=$JDK
  export ANDROID_HOME=$SDK
  export PATH=$PREFIX/platform-tools:\$PATH

Then:  ./gradlew assembleDebug testDebugUnitTest lintDebug
EOF
