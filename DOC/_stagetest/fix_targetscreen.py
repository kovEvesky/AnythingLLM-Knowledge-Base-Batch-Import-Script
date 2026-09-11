#!/usr/bin/env python3
"""修复 TargetScreen.kt 中被 GBK mojibake 破坏的字符串(对照 class 提取的原始串)。"""
import io

P = "/mnt/d/WSL/object/AnythingLLM-Android/app/src/main/java/com/anythingllm/importer/ui/import/TargetScreen.kt"

with io.open(P, "r", encoding="utf-8") as fh:
    t = fh.read()

BAD2GOOD = [
    ("正在加载文件夹与工作区\ufffd?,", "正在加载文件夹与工作区…\","),
    ("全部 ${passedFiles.size} 个文\ufffd?\ufffd?同一文件\ufffd?+ 同一工作\ufffd?", "全部 ${passedFiles.size} 个文件 → 同一文件夹 + 同一工作区"),
    ("每个文件单独选择文件夹与工作\ufffd?", "每个文件单独选择文件夹与工作区"),
    ('label = "文档文件\ufffd?",', 'label = "文档文件夹",'),
    ('label = "目标工作\ufffd?",', 'label = "目标工作区",'),
    ("开始导\ufffd?${passedFiles.size} 个文\ufffd?)", "开始导入 ${passedFiles.size} 个文件)"),
    ('label = "文件\ufffd?",', 'label = "文件夹",'),
    ('label = "工作\ufffd?",', 'label = "工作区",'),
    ('" \ufffd?新建文件夹\ufffd?",', '"＋ 新建文件夹…",'),
    ("新建文档文件\ufffd?)", "新建文档文件夹)"),
]

missing = []
for bad, good in BAD2GOOD:
    if bad in t:
        t = t.replace(bad, good)
    else:
        # 可能是 U+FFFD 显示为单个字符(无 '?'), 尝试宽松匹配
        missing.append(bad)

# 宽松兜底: 对每个未命中的模式, 去掉 '\ufffd?' 字面匹配改用正则
import re
if missing:
    # 通用修复: 替换所有 '<汉字>\ufffd?' 尾部缺字(参照已知真值)
    fixes = {
        "工作区…": ["工作区\ufffd?", "工作区\ufffd"],
        "文档文件夹": ["文档文件\ufffd?", "文档文件\ufffd"],
        "目标工作区": ["目标工作\ufffd?", "目标工作\ufffd"],
        "文件夹": ["文件\ufffd?", "文件\ufffd"],
        "工作区": ["工作\ufffd?", "工作\ufffd"],
        "新建文件夹…": ["新建文件夹\ufffd?", "新建文件夹\ufffd"],
        "新建文档文件夹": ["新建文档文件\ufffd?", "新建文档文件\ufffd"],
        "每个文件单独选择文件夹与工作区": ["每个文件单独选择文件夹与工作\ufffd?", "每个文件单独选择文件夹与工作\ufffd"],
    }
    for good, bads in fixes.items():
        for bad in bads:
            if bad in t:
                t = t.replace(bad, good)

with io.open(P, "w", encoding="utf-8", newline="") as fh:
    fh.write(t)

# 报告残留
leftover = [c for c in set(t) if c == "\ufffd"]
print("REMAINING_UFFFD=", t.count("\ufffd"))
print("HAS_ZHONG=", "\u9fdf" in t)
