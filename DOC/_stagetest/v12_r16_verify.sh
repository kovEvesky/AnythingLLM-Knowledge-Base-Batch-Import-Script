#!/bin/bash
# v1.2 R16 同步模式纯上传验证: 文档存在于 custom-documents, 但不属于任何工作区
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt | tr -d "\r\n")
curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/documents" -o /tmp/all_docs.json
echo "== custom-documents url-* files (latest 5) =="
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
urls = []
for p, name in walk(d.get('localFiles', {}), []):
    if p and p[-1] == 'custom-documents' and name and name.startswith('url-'):
        urls.append(name)
print(len(urls), 'url-* files in custom-documents')
for u in urls[-5:]:
    print('-', u)
PY
echo "== all workspaces document refs (check research not embedded) =="
curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/workspaces" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for w in d.get('workspaces',[]):
    print(w.get('slug'))
"
