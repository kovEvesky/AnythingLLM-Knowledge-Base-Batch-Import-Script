#!/bin/bash
# 测试B: 真实系统 chooser 分享 SEND_MULTIPLE(单Uri, 模拟文件管理器多选)
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
"$ADB" logcat -c
"$ADB" shell "am start -a android.intent.action.SEND_MULTIPLE -t text/plain --eu android.intent.extra.STREAM content://media/external/file/45 --eu android.intent.extra.STREAM content://media/external/file/46"
sleep 4
"$ADB" shell uiautomator dump /sdcard/ui_chooser.xml >/dev/null 2>&1
