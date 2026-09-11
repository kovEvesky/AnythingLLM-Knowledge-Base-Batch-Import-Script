#!/bin/bash
# 真机: 查 MediaStore 测试文件 id
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
"$ADB" shell "content query --uri content://media/external/file --projection _id:_display_name --where \"_display_name='v12_phone_a.txt'\""
"$ADB" shell "content query --uri content://media/external/file --projection _id:_display_name --where \"_display_name='v12_phone_b.txt'\""
