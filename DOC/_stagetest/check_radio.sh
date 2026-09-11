#!/bin/bash
# 输出 checked=true 的节点 text + 上下文
grep -oE 'text="[^"]*"[^>]*checked="true"' "$1" | head -8
echo "--- unchecked radios ---"
grep -oE 'text="[^"]*"[^>]*checked="false"' "$1" | head -12
