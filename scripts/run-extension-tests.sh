#!/usr/bin/env bash
# Builds everything the extension tests need, then runs them on a running emulator.
#
#   ./scripts/run-extension-tests.sh
#
# Requires a booted emulator or device (`adb devices` should list one).
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> Building app + mock extension APKs"
./gradlew :mock-extension:assembleDebug :app:assembleDebug :app:assembleDebugAndroidTest

echo "==> Checking for a device"
if ! adb devices | grep -qE "device$"; then
    echo "No device/emulator attached. Start one first, e.g.:"
    echo "  emulator -avd <name> -no-window -no-audio -gpu swiftshader_indirect"
    exit 1
fi

echo "==> Running extension tests on $(adb shell getprop ro.product.model | tr -d '\r')"
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.auris.app.source.ExtensionLoaderTest,com.auris.app.source.SourceAdapterTest,com.auris.app.source.DexFileReaderTest \
    "$@"