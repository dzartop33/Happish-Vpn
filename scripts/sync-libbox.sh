#!/usr/bin/env bash
set -euo pipefail

# Builds sing-box libbox.aar and copies it into app/libs/libbox.aar.
# Requirements: git, Go, Android SDK, Android NDK, Java, and network access.
# Usage:
#   ./scripts/sync-libbox.sh                # latest stable tag from GitHub
#   ./scripts/sync-libbox.sh v1.12.0        # fixed tag

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK_DIR="$ROOT_DIR/.kernel-sync-local"
UPSTREAM_DIR="$WORK_DIR/sing-box"
TARGET_TAG="${1:-}"

mkdir -p "$WORK_DIR" "$ROOT_DIR/app/libs"

if [[ -z "$TARGET_TAG" ]]; then
  TARGET_TAG="$({
    git ls-remote --tags https://github.com/SagerNet/sing-box.git \
      | awk -F/ '/refs\/tags\/v[0-9]+\.[0-9]+\.[0-9]+$/ {print $NF}' \
      | grep -Ev 'alpha|beta|rc' \
      | sort -V \
      | tail -1
  })"
fi

if [[ -z "$TARGET_TAG" ]]; then
  echo "Cannot resolve sing-box tag" >&2
  exit 1
fi

echo "==> Using sing-box tag: $TARGET_TAG"

if [[ ! -d "$UPSTREAM_DIR/.git" ]]; then
  git clone https://github.com/SagerNet/sing-box.git "$UPSTREAM_DIR"
fi

cd "$UPSTREAM_DIR"
git fetch --tags --force
git checkout "$TARGET_TAG"
git reset --hard "$TARGET_TAG"

export PATH="$(go env GOPATH)/bin:$PATH"
export GO111MODULE=on
export GOPROXY="https://proxy.golang.org,direct"

echo "==> Installing gomobile"
go install github.com/sagernet/gomobile/cmd/gomobile@latest
gomobile init

# The command is used by sing-box's Android build flow. Exact flags may differ by tag.
echo "==> Building libbox for Android"
go run ./cmd/internal/build_libbox -target android

FOUND_AAR="$(find . -name 'libbox.aar' -type f | head -1 || true)"
if [[ -z "$FOUND_AAR" ]]; then
  echo "libbox.aar was not found after build" >&2
  exit 1
fi

cp "$FOUND_AAR" "$ROOT_DIR/app/libs/libbox.aar"
echo "==> Copied: $ROOT_DIR/app/libs/libbox.aar"
echo "==> Now rebuild the Android app."
