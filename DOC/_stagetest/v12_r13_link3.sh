#!/bin/bash
# v1.2 R13 回归: 链接分享 → 收集箱条目 (整条命令单参数传给 adb shell)
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
PKG=com.anythingllm.importer

CMD="am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '看这个 https://example.com/docs/a?x=1 还有 https://openai.com 参考一下' -n $PKG/.ShareReceiver"
echo "== shell cmd: $CMD =="
"$ADB" shell "$CMD"
sleep 5
echo "== entries.json =="
"$ADB" shell "run-as $PKG cat files/collect/entries.json"
