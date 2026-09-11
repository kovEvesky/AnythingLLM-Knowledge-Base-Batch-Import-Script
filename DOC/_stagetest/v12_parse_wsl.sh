#!/bin/bash
# wsl 工作区文档解析(workspace 为数组)
python3 - <<'PY'
import json
with open('/tmp/wsl.json') as f:
    d = json.load(f)
ws = d.get('workspace', [])
if not ws:
    print('EMPTY workspace list')
else:
    w = ws[0]
    docs = w.get('documents', [])
    print('workspace:', w.get('slug'), '| documents:', len(docs))
    for doc in docs:
        print('-', doc.get('title'), '|', doc.get('docpath'))
PY
