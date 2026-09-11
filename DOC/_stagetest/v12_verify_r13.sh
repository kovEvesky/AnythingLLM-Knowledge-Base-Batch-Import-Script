#!/bin/bash
# v1.2 全面验证 R13: 链接分享 → 收集箱(直接启动 ShareReceiver, run-as 校验)
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
PKG=com.anythingllm.importer

"$ADB" shell am start -n $PKG/.MainActivity
sleep 3
CMD="am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '全面验证 https://example.com/docs/a?x=1 和 https://openai.com 两个链接' -n $PKG/.ShareReceiver"
echo "== $CMD =="
"$ADB" shell "$CMD"
sleep 5
echo "== entries.json =="
"$ADB" shell "run-as $PKG cat files/collect/entries.json"
