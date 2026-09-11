#!/bin/bash
# 真机: 单选文件分享 → 系统 chooser → 选 AnythingLLM → 验证
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
PKG=com.anythingllm.importer
URI="content://media/external/file/1000000110"
"$ADB" logcat -c
"$ADB" shell "am start -a android.intent.action.SEND -t text/plain --eu android.intent.extra.STREAM '$URI'"
sleep 5
"$ADB" shell uiautomator dump /sdcard/ui_ch.xml
"$ADB" shell cat /sdcard/ui_ch.xml | grep -o 'text="[^"]*"' | grep -v 'text=""' | head -12
