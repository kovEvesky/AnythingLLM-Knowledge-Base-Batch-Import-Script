#!/bin/bash
# v1.2 R13 回归(纯ASCII): 链接分享 → 收集箱条目
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
PKG=com.anythingllm.importer

echo "== start ShareReceiver with plain URL =="
"$ADB" shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT 'check https://example.com/path?x=1 ok' \
  -n $PKG/.ShareReceiver
sleep 5
echo "== entries.json =="
"$ADB" shell run-as $PKG cat files/collect/entries.json
echo ""
echo "== top activity =="
"$ADB" shell dumpsys activity activities | grep -i resumed | head -2
