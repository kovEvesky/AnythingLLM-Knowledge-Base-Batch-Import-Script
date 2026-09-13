#!/bin/bash
# WSL 渠道编译尝试:java 软链接 + SDK 环境变量
set -e
export JAVA_LINK_DIR="/home/admin/jdk-bin"
mkdir -p "$JAVA_LINK_DIR"
ln -sf "/mnt/d/WSL/SDK/jdk-17.0.20.1+1/bin/java.exe" "$JAVA_LINK_DIR/java"
ln -sf "/mnt/d/WSL/SDK/jdk-17.0.20.1+1/bin/javac.exe" "$JAVA_LINK_DIR/javac"
export PATH="$JAVA_LINK_DIR:/mnt/d/WSL/SDK/jdk-17.0.20.1+1/bin:$PATH"
export JAVA_HOME="/mnt/d/WSL/SDK/jdk-17.0.20.1+1"
export ANDROID_SDK_ROOT="/mnt/d/WSL/SDK/Android"
export ANDROID_HOME="/mnt/d/WSL/SDK/Android"
cd /mnt/d/WSL/object/004AnythingLLM-Android
echo "=== java check ==="
java -version 2>&1 | head -1
echo "=== try assembleDebug (up-to-date check only) ==="
timeout 540 ./gradlew :app:assembleDebug --console=plain -Pandroid.sdk.dir=/mnt/d/WSL/SDK/Android 2>&1 | tail -20