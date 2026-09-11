#!/bin/bash
# 测试A2: SEND_MULTIPLE + EXTRA_STREAM(单Uri,模拟部分管理器) + grant 授权
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
PKG=com.anythingllm.importer
"$ADB" logcat -c
"$ADB" shell "am start --grant-read-uri-permission -a android.intent.action.SEND_MULTIPLE -t text/plain --eu android.intent.extra.STREAM content://media/external/file/45 -n $PKG/.ShareReceiver"
sleep 5
echo "== entries =="
"$ADB" shell "run-as $PKG cat files/collect/entries.json" | head -c 600
echo
echo "== top activity =="
"$ADB" shell "dumpsys activity activities | grep topResumedActivity" | head -1
