#!/usr/bin/env python3
"""补齐 PerItemCard 内 DropdownField label 末尾引号。"""
import io

P = "/mnt/d/WSL/object/AnythingLLM-Android/app/src/main/java/com/anythingllm/importer/ui/import/TargetScreen.kt"
t = io.open(P, encoding="utf-8").read()
fixes = [
    ('label = "文件夹,', 'label = "文件夹",'),
    ('label = "工作区,', 'label = "工作区",'),
]
for bad, good in fixes:
    if bad in t:
        t = t.replace(bad, good)
        print("FIXED:", repr(bad))
    else:
        print("NOT_FOUND:", repr(bad))
io.open(P, "w", encoding="utf-8", newline="").write(t)
print("UFFFD=", t.count("\ufffd"))
