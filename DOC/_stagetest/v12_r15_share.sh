#!/bin/bash
# v1.2 R15 重测: 分享链接 → 收集箱
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
PKG=com.anythingllm.importer
"$ADB" shell am start -n $PKG/.MainActivity
sleep 3
CMD="am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'r15测试 https://openai.com/research 参考' -n $PKG/.ShareReceiver"
"$ADB" shell "$CMD"
sleep 5
"$ADB" shell am start -n $PKG/.MainActivity
sleep 4
"$ADB" shell uiautomator dump /sdcard/ui_r15_new.xml >/dev/null 2>&1
