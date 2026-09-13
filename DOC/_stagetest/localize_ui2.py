#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""UI-13 补替换:多行 Text(\n    "literal", 形式的纯字面量"""
import os, re, io, xml.etree.ElementTree as ET

STRINGS = "/mnt/d/WSL/object/004AnythingLLM-Android/app/src/main/res/values/strings.xml"
ROOT = "/mnt/d/WSL/object/004AnythingLLM-Android/app/src/main/java/com/anythingllm/importer/ui"

tree = ET.parse(STRINGS)
mapping = {}
for s in tree.getroot().findall("string"):
    name = s.get("name")
    val = s.text or ""
    if "%" in val:
        continue
    mapping[val.replace("\\n", "\n")] = name

# 多行模式:Text(\n    "literal",
pat = re.compile(r'(\bText\(\n\s*)"((?:[^"\\]|\\.)*)"')

total = 0
for dirpath, _, files in os.walk(ROOT):
    for fn in sorted(files):
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fn)
        with io.open(path, encoding="utf-8") as f:
            content = f.read()
        def repl(m):
            global total
            key = m.group(2).replace("\\n", "\n")
            if key in mapping:
                total += 1
                return m.group(1) + "stringResource(R.string.%s)" % mapping[key]
            return m.group(0)
        new = pat.sub(repl, content)
        if new != content:
            with io.open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(new)
            print("MODIFIED:", fn)
print("TOTAL:", total)