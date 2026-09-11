#!/bin/bash
# 服务器嵌入队列/状态
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt | tr -d "\r\n")
echo "== embedded-files =="
curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/system/embedded-files" | head -c 400
echo
echo "== workspace-embeddings check (wsl json search) =="
curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/workspace/wsl" | python3 -c "
import json,sys
d=json.load(sys.stdin)
ws=d.get('workspace',[{}])[0]
for doc in ws.get('documents',[]):
    print(doc.get('docpath'))
"
