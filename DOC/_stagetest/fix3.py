#!/usr/bin/env python3
"""补齐 TargetScreen.kt 字符串末尾丢失的引号。"""
import io

P = "/mnt/d/WSL/object/AnythingLLM-Android/app/src/main/java/com/anythingllm/importer/ui/import/TargetScreen.kt"
t = io.open(P, encoding="utf-8").read()

fixes = [
    ('"全部 ${passedFiles.size} 个文件 → 同一文件夹 + 同一工作区', '"全部 ${passedFiles.size} 个文件 → 同一文件夹 + 同一工作区"'),
    ('"每个文件单独选择文件夹与工作区', '"每个文件单独选择文件夹与工作区"'),
    ('label = "文档文件夹,', 'label = "文档文件夹",'),
    ('label = "目标工作区,', 'label = "目标工作区",'),
    ('开始导入 ${passedFiles.size} 个文件)', '开始导入 ${passedFiles.size} 个文件")'),
]
for bad, good in fixes:
    if bad in t:
        t = t.replace(bad, good)
    else:
        print("NOT_FOUND:", repr(bad))

io.open(P, "w", encoding="utf-8", newline="").write(t)
print("DONE ufffd=", t.count("\ufffd"))
