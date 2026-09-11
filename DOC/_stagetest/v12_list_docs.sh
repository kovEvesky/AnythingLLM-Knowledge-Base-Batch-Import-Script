#!/bin/bash
# 查 custom-documents 下所有文件(含 v12_test.txt)
python3 - <<'PY'
import json
with open('/tmp/all_docs.json') as f:
    d = json.load(f)
def walk(node, path):
    for item in node.get('items', []):
        if item.get('type') == 'folder':
            yield from walk(item, path + [item.get('name')])
        else:
            yield path, item.get('name')
found = False
for p, name in walk(d.get('localFiles', {}), []):
    if p and p[-1] == 'custom-documents':
        print('/'.join(p + [name or '']))
        if name and 'v12' in name:
            found = True
print('v12 found in cached tree:', found)
PY
