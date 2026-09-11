#!/bin/bash
# v1.2 全面验证: wsl 工作区文档清单(找 url-example / openai 落盘)
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt | tr -d "\r\n")
curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/workspace/wsl" > /tmp/wsl_ws.json
python3 <<'PY'
import json
d = json.load(open('/tmp/wsl_ws.json'))
ws = d['workspace'][0] if isinstance(d.get('workspace'), list) else d['workspace']
print('workspace:', ws.get('name'), 'id:', ws.get('id'))
docs = ws.get('documents', [])
print('documents:', len(docs))
for x in docs:
    n = x.get('name') or x.get('docpath') or ''
    print('-', str(n)[:120])
PY
