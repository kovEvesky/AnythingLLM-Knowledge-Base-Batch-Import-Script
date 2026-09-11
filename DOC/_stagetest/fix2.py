#!/usr/bin/env python3
"""修复 TargetScreen.kt 218 行残留损坏。"""
import io

P = "/mnt/d/WSL/object/AnythingLLM-Android/app/src/main/java/com/anythingllm/importer/ui/import/TargetScreen.kt"
t = io.open(P, encoding="utf-8").read()
before = t.count("\ufffd")
t = t.replace('Text("\ufffd?新建文件夹…, color', 'Text("＋ 新建文件夹…", color')
io.open(P, "w", encoding="utf-8", newline="").write(t)
after = t.count("\ufffd")
print("UFFFD before=%d after=%d" % (before, after))
assert after == 0, "still corrupted"
print("FIX_OK")
