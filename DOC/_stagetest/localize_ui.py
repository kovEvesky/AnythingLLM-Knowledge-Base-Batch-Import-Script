#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""UI-13 资源化替换:Text("literal") -> Text(stringResource(R.string.x)),仅无格式参数条目;输出未匹配清单。"""
import os, re, io, xml.etree.ElementTree as ET

STRINGS = "/mnt/d/WSL/object/004AnythingLLM-Android/app/src/main/res/values/strings.xml"
ROOT = "/mnt/d/WSL/object/004AnythingLLM-Android/app/src/main/java/com/anythingllm/importer/ui"

tree = ET.parse(STRINGS)
mapping = {}  # text -> resId
for s in tree.getroot().findall("string"):
    name = s.get("name")
    val = s.text or ""
    if "%" in val:
        continue  # 带格式参数,手工处理
    mapping[val] = name

# 转义还原:XML 中 \n 表示换行
mapping = {k.replace("\\n", "\n"): v for k, v in mapping.items()}

text_pat = re.compile(r'Text\("((?:[^"\\]|\\.)*)"\)')

total_replaced = 0
for dirpath, _, files in os.walk(ROOT):
    for fn in sorted(files):
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fn)
        with io.open(path, encoding="utf-8") as f:
            lines = f.readlines()
        changed = False
        import_added = False
        new_lines = []
        for line in lines:
            def repl(m):
                global total_replaced
                lit = m.group(1)
                key = lit.replace("\\n", "\n")
                if key in mapping:
                    total_replaced += 1
                    return 'Text(stringResource(R.string.%s))' % mapping[key]
                return m.group(0)
            nl = text_pat.sub(repl, line)
            if nl != line:
                changed = True
            new_lines.append(nl)
        if changed:
            # 插入 import(放在 androidx.compose.ui.unit.dp 之后,若无则放 import 区首行后)
            if not any("import androidx.compose.ui.res.stringResource" in l for l in new_lines):
                insert_at = None
                for i, l in enumerate(new_lines):
                    if l.startswith("import androidx.compose.ui.unit.dp"):
                        insert_at = i + 1
                        break
                if insert_at is None:
                    for i, l in enumerate(new_lines):
                        if l.startswith("import ") and "ui.unit.dp" not in l:
                            insert_at = i + 1
                            break
                if insert_at is None:
                    insert_at = 1
                new_lines.insert(insert_at, "import androidx.compose.ui.res.stringResource\n")
            with io.open(path, "w", encoding="utf-8", newline="\n") as f:
                f.writelines(new_lines)
            print("MODIFIED:", fn)
print("TOTAL replaced:", total_replaced)