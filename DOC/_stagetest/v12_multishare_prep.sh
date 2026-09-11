#!/bin/bash
# 多选分享测试准备: 查 MediaStore id
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
"$ADB" shell "content query --uri content://media/external/file --projection _id:_display_name | grep -E 'test.txt|断网|需求'" 
