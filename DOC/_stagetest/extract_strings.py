#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""提取 ui/**/*.kt 中含中文的字符串字面量清单(资源化准备)"""
import os, re, io

ROOT = "/mnt/d/WSL/object/004AnythingLLM-Android/app/src/main/java/com/anythingllm/importer/ui"
pat = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"')
for dirpath, _, files in os.walk(ROOT):
    for fn in sorted(files):
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fn)
        with io.open(path, encoding="utf-8") as f:
            lines = f.readlines()
        hits = []
        for i, line in enumerate(lines, 1):
            for m in pat.finditer(line):
                s = m.group(1)
                if re.search(r"[\u4e00-\u9fff]", s):
                    hits.append((i, s))
        if hits:
            print("###", fn)
            for i, s in hits:
                print(f"  L{i}: {s}")