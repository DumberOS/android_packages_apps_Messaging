#!/usr/bin/env bash
set -euo pipefail
app_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
rom_dir="$(cd "$app_dir/../../.." && pwd)"
: "${KOTLINC:=$rom_dir/external/kotlinc/bin/kotlinc}"
: "${KOTLIN:=$rom_dir/external/kotlinc/bin/kotlin}"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
"$KOTLINC" "$app_dir"/prebuilts/dumber-chat/kotlin/src/main/kotlin/chat/dumber/sdk/{Models,ChatApi}.kt \
    "$app_dir"/src/com/android/messaging/dumber/{ChatModels,ChatDelivery,ChatActivation,ChatGroupActions}.kt \
    "$app_dir/tests/dumber-chat/DeliveryTest.kt" "$app_dir/tests/dumber-chat/ActivationTest.kt" "$app_dir/tests/dumber-chat/GroupTest.kt" -d "$test_dir/tests.jar"
"$KOTLIN" -classpath "$test_dir/tests.jar" com.android.messaging.dumber.DeliveryTestKt
"$KOTLIN" -classpath "$test_dir/tests.jar" com.android.messaging.dumber.ActivationTestKt
"$KOTLIN" -classpath "$test_dir/tests.jar" com.android.messaging.dumber.GroupTestKt
