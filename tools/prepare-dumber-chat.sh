#!/usr/bin/env bash
# Compatibility entry point: validate shipped prebuilts, never compile or download.
set -euo pipefail
app_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$app_dir/tools/verify-dumber-chat-prebuilts.py" "$@"
