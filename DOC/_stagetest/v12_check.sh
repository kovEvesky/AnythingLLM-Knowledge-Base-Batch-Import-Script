#!/bin/bash
# 精确确认: fresh 拉取, 输出 url-* / v12_test 存在性
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt | tr -d "\r\n")
curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/documents" -o /tmp/fresh.json
python3 - <<'PY'
import json
with open('/tmp/fresh.json') as f:
    d = json.load(f)
root = d.get('localFiles', {})
def walk(node, path):
    for item in node.get('items', []):
        if item.get('type')=='folder':
            yield from walk(item, path+[item.get('name')])
        else:
            yield path, item.get('name')
hits = []
for p, name in walk(root, []):
    n = name or ''
    if 'url-' in n or 'v12_test' in n:
        hits.append('/'.join(p + [n]))
print('url-/v12 files:', len(hits))
for h in sorted(hits):
    print('-', h)
PY
