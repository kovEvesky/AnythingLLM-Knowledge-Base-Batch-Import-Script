#!/bin/bash
# 真机: 从 chooser dump 中定位 AnythingLLM 项并点击
ADB=/mnt/d/WSL/SDK/Android/platform-tools/adb.exe
DUMP=/sdcard/ui_ch.xml
"$ADB" shell uiautomator dump $DUMP >/dev/null 2>&1
"$ADB" shell cat $DUMP > /tmp/ui_ch.xml 2>/dev/null
# 用 python 解析 bounds(取 text 含 AnythingLLM 的节点)
python3 - <<'EOF'
import re
xml = open('/tmp/ui_ch.xml', encoding='utf-8', errors='ignore').read()
# 找 text="AnythingLLM 批量导入" 的节点
for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*>', xml):
    t = m.group(1)
    if 'AnythingLLM' in t:
        x1,y1,x2,y2 = map(int, m.groups()[1:])
        print(f"text={t} bounds=[{x1},{y1}]-[{x2},{y2}] center={ (x1+x2)//2 },{ (y1+y2)//2 }")
        break
EOF
