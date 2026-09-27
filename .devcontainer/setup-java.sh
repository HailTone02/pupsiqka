#!/usr/bin/env bash
set -euo pipefail

sdkman_dir="${SDKMAN_DIR:-/usr/local/sdkman}"
java_candidates="$sdkman_dir/candidates/java"
java_21_version="$(find "$java_candidates" -mindepth 1 -maxdepth 1 -type d -name '21*' -printf '%f\n' | sort -V | tail -n 1)"

if [[ -z "$java_21_version" ]]; then
  echo "No Java 21 installation found under $java_candidates" >&2
  exit 1
fi

set +u
source "$sdkman_dir/bin/sdkman-init.sh"
set -u
sdk default java "$java_21_version"