#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
gradle_version=8.9
gradle_sha=d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab
tool_cache="${ENGAGE_TOOLS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/engage-sdk-tools}"
gradle_bin="$tool_cache/gradle-$gradle_version/bin/gradle"
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -z "${ANDROID_HOME:-}" && -d /opt/homebrew/share/android-commandlinetools/platforms ]]; then
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
fi
if [[ ! -x "$gradle_bin" ]]; then
  mkdir -p "$tool_cache"
  archive="$tool_cache/gradle-$gradle_version-bin.zip"
  curl --fail --location --silent --show-error "https://services.gradle.org/distributions/gradle-$gradle_version-bin.zip" -o "$archive"
  if command -v sha256sum >/dev/null; then
    actual_sha="$(sha256sum "$archive" | cut -d ' ' -f 1)"
  else
    actual_sha="$(shasum -a 256 "$archive" | cut -d ' ' -f 1)"
  fi
  [[ "$actual_sha" == "$gradle_sha" ]] || { echo 'Gradle checksum mismatch' >&2; exit 1; }
  unzip -q -o "$archive" -d "$tool_cache"
fi
for argument in "$@"; do
  case "$argument" in
    -p|--project-dir|--project-dir=*) exec "$gradle_bin" "$@" ;;
  esac
done
exec "$gradle_bin" -p "$repo_dir/android" "$@"
