#!/bin/bash
# 全树: 文件夹名 + 顶层文件 + 搜索 v12
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt | tr -d "\r\n")
curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/documents" -o /tmp/tree.json
python3 - <<'PY'
import json
with open('/tmp/tree.json') as f:
    d = json.load(f)
root = d.get('localFiles', {})
print('== top-level ==')
for item in root.get('items', []):
    kind = 'folder' if item.get('type') == 'folder' else 'file'
    print('[%s]' % kind, item.get('name'))
def walk(node, path):
    for item in node.get('items', []):
        if item.get('type') == 'folder':
            yield from walk(item, path + [item.get('name')])
        else:
            yield '/'.join(path + [item.get('name') or ''])
print('== v12 search ==')
for full in walk(root, []):
    if 'v12' in full.lower():
        print(full)
PY
