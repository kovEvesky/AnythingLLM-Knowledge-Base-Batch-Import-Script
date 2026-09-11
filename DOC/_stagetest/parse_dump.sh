#!/bin/bash
# 解析 uiautomator dump: 输出非空 text + bounds
DUMP="$1"
if [ -z "$DUMP" ]; then DUMP="/sdcard/ui_v2_m2.xml"; fi
grep -oE 'text="[^"]*"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' "$DUMP" 2>/dev/null \
  | grep -v 'text=""' | sed -E 's/text="([^"]*)"[^>]*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 @ \2,\3/' | head -25
