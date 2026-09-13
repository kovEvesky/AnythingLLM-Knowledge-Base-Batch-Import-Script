#!/bin/bash
# 复现"测试连接"闪退并抓崩溃日志
adb -s 8BRX1EA7Z logcat -c
adb -s 8BRX1EA7Z shell input tap 110 813
sleep 5
echo "== top activity =="
adb -s 8BRX1EA7Z shell "dumpsys activity activities | grep -E 'topResumedActivity'" 2>/dev/null | head -1
echo "== crash =="
adb -s 8BRX1EA7Z logcat -d -t 400 2>/dev/null | grep -A 40 -E "FATAL EXCEPTION|AndroidRuntime.*Process.*com.anythingllm.importer" | head -60
echo "== end =="
