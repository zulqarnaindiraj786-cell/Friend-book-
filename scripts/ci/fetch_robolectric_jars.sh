#!/usr/bin/env bash
# Verify each Android runtime before Robolectric's JVM requests it.
set -euo pipefail
for version in 15-robolectric-12650502-i7 9-robolectric-4913185-2-i7; do
  dir="$HOME/.m2/repository/org/robolectric/android-all-instrumented/$version"
  jar="$dir/android-all-instrumented-$version.jar"
  url="https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented/$version/android-all-instrumented-$version.jar"
  mkdir -p "$dir"
  curl -fsSL --connect-timeout 15 --max-time 120 --retry 3 --retry-max-time 180 --retry-delay 3 --retry-all-errors "$url.sha512" -o "$jar.sha512.tmp"
  hash="$(grep -Eo '[a-fA-F0-9]{128}' "$jar.sha512.tmp" | head -1 || true)"
  [[ "$hash" =~ ^[a-fA-F0-9]{128}$ ]] || { echo "::error::Invalid SHA-512 metadata for $version"; exit 1; }
  if [ ! -f "$jar" ] || ! printf '%s  %s\n' "$hash" "$jar" | sha512sum --check --status; then
    verified=false
    for attempt in 1 2 3; do
      if curl -fsSL --connect-timeout 15 --max-time 120 --retry 3 --retry-max-time 180 --retry-delay 3 --retry-all-errors "$url" -o "$jar.tmp" &&
          printf '%s  %s\n' "$hash" "$jar.tmp" | sha512sum --check --status; then
        verified=true; break
      fi
      echo "::warning::Android jar download/checksum attempt $attempt failed for $version"
      rm -f "$jar.tmp"
    done
    [ "$verified" = true ] || { echo "::error::Could not obtain a verified Robolectric jar for $version"; exit 1; }
    mv "$jar.tmp" "$jar"
  fi
  rm -f "$jar.sha512.tmp"
done
