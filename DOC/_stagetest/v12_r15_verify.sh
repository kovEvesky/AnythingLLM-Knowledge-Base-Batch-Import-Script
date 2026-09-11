#!/bin/bash
# v1.2 R15 闭环: 服务器验证 wsl 工作区最新文档
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt | tr -d "\r\n")
echo "== wsl workspace documents =="
curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/workspace/wsl" | python3 -c "
import json,sys
d=json.load(sys.stdin)
docs=d.get('workspace',{}).get('documents',[])
for doc in docs:
    print('-', doc.get('title'), '|', doc.get('docpath'))
"
echo "== custom-documents folder top =="
curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/documents/custom-documents" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('localFiles',{}).get('items',[])
urls=[i for i in items if 'url-' in (i.get('name') or '')]
print(len(urls), 'url-* files; latest:')
for u in urls[-5:]:
    print('-', u.get('name'))
"
