#!/bin/bash
# v1.2 阶段0:upload-link T4(addToWorkspaces) + 清理 T3 残留 + 工作区回查
BASE="http://localhost:3001"
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt)
AUTH="Authorization: Bearer $KEY"
CT="Content-Type: application/json"

echo "=== 清理 T3 两个文档 ==="
curl -s -m 20 -X DELETE "$BASE/api/v1/system/remove-documents" -H "$AUTH" -H "$CT" -d '{"names":["custom-documents/url-example.com_-04c23899-f093-40d8-8f80-72f8489ac218.json","custom-documents/url-example.org_-435fedbe-4639-4979-9500-e92b9254874f.json"]}'
echo ""

echo "=== T4 upload-link + addToWorkspaces=wsl ==="
RESP=$(curl -s -m 90 -X POST "$BASE/api/v1/document/upload-link" -H "$AUTH" -H "$CT" -d '{"link":"https://example.org","addToWorkspaces":"wsl"}')
echo "$RESP" | head -c 1200
LOC=$(echo "$RESP" | grep -o '"location":"[^"]*"' | head -1 | sed 's/"location":"//;s/"//')
echo ""
echo "LOC=$LOC"
echo ""

echo "=== 回查 workspace/wsl documents(确认 addToWorkspaces 生效) ==="
curl -s -m 30 "$BASE/api/v1/workspace/wsl" -H "$AUTH" | python3 -c "
import sys,json
d=json.load(sys.stdin)
ws=d.get('workspace',[])
if isinstance(ws,list) and ws:
    docs=ws[0].get('documents',[])
    for doc in docs[-8:]:
        print('docpath:',doc.get('docpath'),'| status:',doc.get('status'),'| pinned:',doc.get('pinCount'))
" 2>&1 | tail -10

echo "=== 清理 T4(先解关联再删物理) ==="
curl -s -m 30 -X POST "$BASE/api/v1/workspace/wsl/update-embeddings" -H "$AUTH" -H "$CT" -d "{\"adds\":[],\"deletes\":[\"$LOC\"]}"
echo ""
curl -s -m 20 -X DELETE "$BASE/api/v1/system/remove-documents" -H "$AUTH" -H "$CT" -d "{\"names\":[\"$LOC\"]}"
echo ""
