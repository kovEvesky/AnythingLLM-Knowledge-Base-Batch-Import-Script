#!/bin/bash
# v1.2 R15 准备: 检查各工作区文档量(决定删哪个做失效测试)
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt | tr -d "\r\n")
for slug in ewewew ee 3333 5 2 1 wsl; do
  CNT=$(curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/workspace/$slug" | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    ws=d['workspace'][0] if isinstance(d.get('workspace'),list) else d.get('workspace',{})
    docs=ws.get('documents',[]) if isinstance(ws,dict) else []
    print(len(docs))
except: print('ERR')
")
  echo "$slug: $CNT docs"
done
