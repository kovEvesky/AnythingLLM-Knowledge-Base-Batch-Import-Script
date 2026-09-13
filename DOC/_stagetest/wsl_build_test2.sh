#!/bin/bash
# WSL 渠道编译尝试 v2:修正 JAVA_HOME 结构 + SDK 环境变量 + local.properties 安全备份
set -e
JAVA_LINK_DIR="/home/admin/jdk-bin"
mkdir -p "$JAVA_LINK_DIR/bin"
ln -sf "/mnt/d/WSL/SDK/jdk-17.0.20.1+1/bin/java.exe" "$JAVA_LINK_DIR/bin/java"
ln -sf "/mnt/d/WSL/SDK/jdk-17.0.20.1+1/bin/javac.exe" "$JAVA_LINK_DIR/bin/javac"
export JAVA_HOME="$JAVA_LINK_DIR"
export PATH="$JAVA_HOME/bin:/mnt/d/WSL/SDK/jdk-17.0.20.1+1/bin:$PATH"
export ANDROID_SDK_ROOT="/mnt/d/WSL/SDK/Android"
export ANDROID_HOME="/mnt/d/WSL/SDK/Android"
cd /mnt/d/WSL/object/004AnythingLLM-Android
echo "=== java check ==="
java -version 2>&1 | head -1
echo "=== backup local.properties (trap restore) ==="
cp local.properties /tmp/local.properties.bak
trap 'cp /tmp/local.properties.bak /mnt/d/WSL/object/004AnythingLLM-Android/local.properties; rm -f /mnt/d/WSL/object/004AnythingLLM-Android/local.properties' EXIT
rm -f local.properties
echo "=== assembleDebug ==="
timeout 540 ./gradlew :app:assembleDebug --console=plain 2>&1 | tail -15
echo "=== local.properties restored? ==="
ls -la local.properties 2>&1
cat local.properties 2>&1