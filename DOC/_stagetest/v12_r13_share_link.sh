#!/bin/bash
# v1.2 R13 回归: 链接分享 → 收集箱 (直接启动 ShareReceiver)
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
"$ADB" shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT '看这个 https://example.com/docs/a?x=1 还有 https://openai.com 参考一下' \
  -n com.anythingllm.importer/.ShareReceiver
sleep 4
"$ADB" shell uiautomator dump /sdcard/ui6.xml
"$ADB" shell cat /sdcard/ui6.xml | grep -o 'text="[^"]*"' | grep -v 'text=""' | head -30
