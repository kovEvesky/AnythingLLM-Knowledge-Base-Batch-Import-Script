#!/bin/bash
# 测试A: SEND_MULTIPLE + EXTRA_STREAM 数组(2 文件)
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
PKG=com.anythingllm.importer
"$ADB" logcat -c
"$ADB" shell "am start -a android.intent.action.SEND_MULTIPLE -t text/plain --eu android.intent.extra.STREAM content://media/external/file/45 --eu android.intent.extra.STREAM content://media/external/file/46 -n $PKG/.ShareReceiver"
sleep 5
"$ADB" shell "run-as $PKG cat files/collect/entries.json"
