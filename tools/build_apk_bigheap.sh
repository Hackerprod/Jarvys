#!/usr/bin/env bash
# Compatibility entrypoint; share the portable, signing-safe build contract.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../app" && pwd)"
exec "$DIR/build_apk.sh" "$@"
