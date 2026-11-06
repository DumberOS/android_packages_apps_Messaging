#!/usr/bin/env bash
set -eo pipefail
app_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$app_dir/tools/verify-dumber-chat-prebuilts.py"
cd "$app_dir/../../.."
source build/envsetup.sh
lunch "${1:-lineage_vanilla31-ap2a-userdebug}"
m messaging messagingtests
