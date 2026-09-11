#!/bin/bash
# v1.2 R12 尝试: 系统 chooser 真实分享(文件) → 选择 app
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
PKG=com.anythingllm.importer
URI="content://media/external/file/46"

# 1. 回主页
"$ADB" shell am start -n $PKG/.MainActivity
sleep 3
# 2. 发起分享(不指定目标,弹出系统 chooser)
CMD="am start -a android.intent.action.SEND -t text/plain --eu android.intent.extra.STREAM '$URI'"
echo "== $CMD =="
"$ADB" shell "$CMD"
sleep 4
# 3. 检查 chooser 是否弹出
"$ADB" shell dumpsys activity activities | grep topResumedActivity
"$ADB" shell uiautomator dump /sdcard/ui_chooser.xml
"$ADB" shell cat /sdcard/ui_chooser.xml | grep -o 'text="[^"]*"' | grep -v 'text=""' | head -12
