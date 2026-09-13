#!/bin/bash
export JAVA_HOME="/mnt/d/WSL/SDK/jdk-17.0.20.1+1"
export PATH="$JAVA_HOME/bin:$PATH"
echo "=== java via interop ==="
java.exe -version 2>&1 | head -2
echo "=== which java ==="
ls "$JAVA_HOME/bin/" | head
echo "=== local.properties ==="
cat /mnt/d/WSL/object/004AnythingLLM-Android/local.properties
echo "=== gradle wrapper ==="
ls -la /mnt/d/WSL/object/004AnythingLLM-Android/gradlew