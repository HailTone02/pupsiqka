#!/usr/bin/env bash
set -euo pipefail

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.android-sdk}}"
sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
command_line_tools_revision="16111833"

mkdir -p "$sdk_root/cmdline-tools"

if [[ ! -x "$sdkmanager" ]]; then
  temporary_directory="$(mktemp -d)"
  trap 'rm -rf "$temporary_directory"' EXIT
  archive="commandlinetools-linux-${command_line_tools_revision}_latest.zip"

  curl -fsSL "https://dl.google.com/android/repository/$archive" -o "$temporary_directory/$archive"
  unzip -q "$temporary_directory/$archive" -d "$temporary_directory"
  mv "$temporary_directory/cmdline-tools" "$sdk_root/cmdline-tools/latest"
fi

set +o pipefail
yes | "$sdkmanager" --sdk_root="$sdk_root" --licenses >/dev/null
set -o pipefail

"$sdkmanager" --sdk_root="$sdk_root" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0"