#!/bin/bash
# v1.2 R12 回归: 文件分享(content URI + grant) → 收集箱 FILE 条目
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
PKG=com.anythingllm.importer
URI="content://media/external/file/46"

CMD="am start -a android.intent.action.SEND -t text/plain --eu android.intent.extra.STREAM '$URI' --grant-read-uri-permission -n $PKG/.ShareReceiver"
echo "== $CMD =="
"$ADB" shell "$CMD"
sleep 6
echo "== entries.json =="
"$ADB" shell "run-as $PKG cat files/collect/entries.json"
echo ""
echo "== collect files tree =="
"$ADB" shell "run-as $PKG find files/collect -type f"
