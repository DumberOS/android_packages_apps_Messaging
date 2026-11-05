#!/usr/bin/env bash
set -eo pipefail
app_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bash "$app_dir/tools/prepare-dumber-chat.sh"
cd "$app_dir/../../.."
source build/envsetup.sh
lunch "${1:-lineage_vanilla31-ap2a-userdebug}"
m messaging messagingtests
