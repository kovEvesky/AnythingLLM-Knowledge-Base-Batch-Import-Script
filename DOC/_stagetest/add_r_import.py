#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查 ui 下使用 R.string 但缺 import R 的文件,自动补 import"""
import os, io
ROOT = "/mnt/d/WSL/object/004AnythingLLM-Android/app/src/main/java/com/anythingllm/importer/ui"
for dirpath, _, files in os.walk(ROOT):
    for fn in sorted(files):
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fn)
        with io.open(path, encoding="utf-8") as f:
            lines = f.readlines()
        uses_r = any("R.string." in l for l in lines)
        has_import = any("import com.anythingllm.importer.R" in l for l in lines)
        if uses_r and not has_import:
            # 插到 package 行之后
            insert = 1
            for i, l in enumerate(lines):
                if l.startswith("package "):
                    insert = i + 1
                    break
            lines.insert(insert, "import com.anythingllm.importer.R\n")
            with io.open(path, "w", encoding="utf-8", newline="\n") as f:
                f.writelines(lines)
            print("ADDED R import:", fn)