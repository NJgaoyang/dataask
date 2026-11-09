#!/usr/bin/env bash
# Convenience entry point: one complete artifact containing PC, miniapp H5 and API.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$ROOT/scripts/package.sh" "$@"
