#!/usr/bin/env bash
# Cross-compile the Bridge for the four Android ABIs.
# Pure-Go static binaries (CGO_ENABLED=0), no NDK required.
set -euo pipefail
cd "$(dirname "$0")"

GO_BIN="${GO_BIN:-go}"
OUT="${OUT:-dist}"
mkdir -p "$OUT"

build() {
  local goarch="$1" goarm="$2" name="$3"
  echo "building $name (linux/$goarch${goarm:+/armv$goarm})"
  GOOS=linux GOARCH="$goarch" GOARM="$goarm" CGO_ENABLED=0 \
    "$GO_BIN" build -trimpath -ldflags "-s -w" -o "$OUT/$name" .
}

build arm64 ""  bridge-arm64-v8a
build arm   7   bridge-armeabi-v7a
build 386   ""  bridge-x86
build amd64 ""  bridge-x86_64

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$OUT" && sha256sum bridge-* > SHA256SUMS)
fi
ls -lh "$OUT"