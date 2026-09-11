#!/bin/bash
# 诊断: 关闭 chooser 后直接 -n 启动 ShareReceiver
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
PKG=com.anythingllm.importer

"$ADB" shell input keyevent 4
sleep 2
echo "== top activity =="
"$ADB" shell dumpsys activity activities | grep -i "topResumedActivity"
echo "== start ShareReceiver explicit =="
"$ADB" shell am start -n $PKG/.ShareReceiver
sleep 3
echo "== top activity after =="
"$ADB" shell dumpsys activity activities | grep -i "topResumedActivity"
