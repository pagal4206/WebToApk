#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -f "$PROJECT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env"
  set +a
fi

DEFAULT_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$DEFAULT_SDK_ROOT" ]]; then
  if [[ -d "/opt/android-sdk" ]]; then
    DEFAULT_SDK_ROOT="/opt/android-sdk"
  else
    DEFAULT_SDK_ROOT="$HOME/android-sdk"
  fi
fi
if [[ ! -d "$DEFAULT_SDK_ROOT" ]]; then
  echo "Android SDK not found at: $DEFAULT_SDK_ROOT"
  echo "Set ANDROID_SDK_ROOT in .env or export it before running this script."
  exit 1
fi

export ANDROID_SDK_ROOT="$DEFAULT_SDK_ROOT"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
export BUILDER_TEMPLATE_DIR="${BUILDER_TEMPLATE_DIR:-$PROJECT_DIR/template}"
export BUILDER_DATA_DIR="${BUILDER_DATA_DIR:-$PROJECT_DIR/builder-data}"
export PORT="${PORT:-8080}"
export BUILDER_PORT="${BUILDER_PORT:-$PORT}"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

cd "$PROJECT_DIR"
chmod +x ./gradlew
./gradlew installDist
exec ./build/install/buildersrc/bin/buildersrc
