#!/usr/bin/env python3
"""补齐 CreateFolderDialog title 末尾引号。"""
import io

P = "/mnt/d/WSL/object/AnythingLLM-Android/app/src/main/java/com/anythingllm/importer/ui/import/TargetScreen.kt"
t = io.open(P, encoding="utf-8").read()
bad = 'title = { Text("新建文档文件夹) },'
good = 'title = { Text("新建文档文件夹") },'
assert bad in t, "pattern missing"
t = t.replace(bad, good)
io.open(P, "w", encoding="utf-8", newline="").write(t)
print("FIXED")
