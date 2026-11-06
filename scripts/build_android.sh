#!/usr/bin/env bash
# 构建 Android debug APK
# 用法:./scripts/build_android.sh [--release]
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR/android"

# 若本机默认 JDK 不是 17+,设置 JAVA_HOME(按需修改)
if [ -d "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home" ]; then
    export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
fi

if [ "${1:-}" = "--release" ]; then
    ./gradlew :app:assembleRelease
    echo ""
    echo "APK → $DIR/android/app/build/outputs/apk/release/"
else
    ./gradlew :app:assembleDebug
    echo ""
    echo "APK → $DIR/android/app/build/outputs/apk/debug/app-debug.apk"
    echo "安装:adb install -r $DIR/android/app/build/outputs/apk/debug/app-debug.apk"
fi
