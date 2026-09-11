#!/bin/bash
# v1.2 R13 回归: 链接分享 → 收集箱条目 (直接启动 ShareReceiver, run-as 校验)
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
PKG=com.anythingllm.importer

"$ADB" shell am start -n $PKG/.MainActivity
sleep 3
"$ADB" shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT '看这个 https://example.com/docs/a?x=1 还有 https://openai.com 参考一下' \
  -n $PKG/.ShareReceiver
sleep 5
echo "=== entries.json ==="
"$ADB" shell run-as $PKG sh -c "cat files/collect/entries.json" 2>/dev/null
echo ""
echo "=== files tree ==="
"$ADB" shell run-as $PKG sh -c "find files/collect -type f" 2>/dev/null
