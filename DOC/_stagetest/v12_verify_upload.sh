#!/bin/bash
# v1.2 全面验证: 服务器侧验证 upload-link 落盘(wsl 工作区 + custom-documents)
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt | tr -d "\r\n")
echo "== wsl workspace documents =="
curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/workspace/wsl" | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    docs=d.get('documents',d if isinstance(d,list) else [])
    if isinstance(d,dict) and 'workspace' in d: docs=d['workspace'].get('documents',[])
    print('document count:', len(docs) if isinstance(docs,list) else 'n/a')
    for x in (docs if isinstance(docs,list) else [])[:8]:
        if isinstance(x,dict):
            print('-', x.get('name') or x.get('title') or x.get('docpath') or str(x)[:80])
except Exception as e:
    print('parse error:', e)
"
echo
echo "== custom-documents recent =="
curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/documents" | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    print(json.dumps(d, ensure_ascii=False)[:600])
except Exception as e:
    print('parse error:', e)
"
