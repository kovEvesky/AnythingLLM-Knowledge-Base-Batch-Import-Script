#!/bin/bash
# 强制回前台并确认知识库页
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
PKG=com.anythingllm.importer

"$ADB" shell am force-stop $PKG
sleep 2
"$ADB" shell am start -n $PKG/.MainActivity
sleep 5
echo "== top =="
"$ADB" shell dumpsys activity activities | grep topResumedActivity
echo "== texts =="
"$ADB" shell uiautomator dump /sdcard/ui_kb.xml
"$ADB" shell cat /sdcard/ui_kb.xml | grep -o 'text="[^"]*"' | grep -v 'text=""' | head -25
