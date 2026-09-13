#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""抽查:列出各文件 stringResource import 位置 + 仍含中文 Text("...") 的行(待手工处理)"""
import os, re, io
ROOT = "/mnt/d/WSL/object/004AnythingLLM-Android/app/src/main/java/com/anythingllm/importer/ui"
for dirpath, _, files in os.walk(ROOT):
    for fn in sorted(files):
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fn)
        with io.open(path, encoding="utf-8") as f:
            lines = f.readlines()
        imp = [i+1 for i, l in enumerate(lines) if "stringResource" in l and l.startswith("import")]
        remain = []
        for i, l in enumerate(lines, 1):
            for m in re.finditer(r'"([^"]*[\u4e00-\u9fff][^"]*)"', l):
                if "stringResource" in l:
                    continue
                remain.append((i, m.group(1)[:40]))
        if imp or remain:
            print("###", fn, "import@", imp)
            for i, s in remain[:12]:
                print(f"   L{i}: {s}")