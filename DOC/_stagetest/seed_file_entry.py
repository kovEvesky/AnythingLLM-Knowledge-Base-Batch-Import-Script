# -*- coding: utf-8 -*-
"""追加 FILE 条目到收集箱,保持与设备端一致的 UTF-8 BOM 编码。"""
import json

src = r"D:\WSL\object\004AnythingLLM-Android\DOC\_stagetest\uf_entries_now.json"
out = r"D:\WSL\object\004AnythingLLM-Android\DOC\_stagetest\uf_entries_seed.json"

with open(src, encoding="utf-8-sig") as f:
    d = json.load(f)
entries = d["entries"]
entries.append({
    "id": "uf-file-pdf-0001",
    "type": "FILE",
    "source": "SHARE_FILE",
    "fileName": "v13_userflow.pdf",
    "localPath": "/data/data/com.anythingllm.importer/files/collect/files/20260911/uf_v13_userflow.pdf",
    "sizeBytes": 608,
    "url": None,
    "title": "v13_userflow.pdf",
    "collectedAt": "2026-09-11T11:10:00Z",
    "status": "PENDING",
    "markFolder": None,
    "markWorkspace": None,
    "serverTitle": None,
    "serverLocation": None,
    "error": None,
})
with open(out, "w", encoding="utf-8-sig") as f:
    json.dump(d, f, ensure_ascii=False)
print("entries:", len(entries), "written:", out)
