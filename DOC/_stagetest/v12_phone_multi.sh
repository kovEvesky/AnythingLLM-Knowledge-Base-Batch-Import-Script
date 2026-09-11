#!/bin/bash
# 真机: SEND_MULTIPLE(单Uri形态,部分管理器) → chooser → AnythingLLM → 验证不崩溃/入库
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
PKG=com.anythingllm.importer
"$ADB" logcat -c
"$ADB" shell "am start -a android.intent.action.SEND_MULTIPLE -t text/plain --eu android.intent.extra.STREAM content://media/external/file/1000000111"
sleep 5
"$ADB" shell uiautomator dump /sdcard/ui_ms.xml >/dev/null 2>&1
"$ADB" shell cat /sdcard/ui_ms.xml > /tmp/ui_ms.xml 2>/dev/null
python3 - <<'EOF'
import re
xml = open('/tmp/ui_ms.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*>', xml):
    t = m.group(1)
    if 'AnythingLLM' in t:
        x1,y1,x2,y2 = map(int, m.groups()[1:])
        print(f"CENTER {(x1+x2)//2},{(y1+y2)//2}")
        break
EOF
