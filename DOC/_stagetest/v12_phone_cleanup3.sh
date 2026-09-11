#!/bin/bash
# 用 docpath 解关联 + remove
KEY=$(grep -v '^$' /mnt/d/WSL/object/004AnythingLLM-Android/apikey.txt | head -1)
echo "== unlink wsl (docpath) =="
curl -s -X POST "http://localhost:3001/api/v1/workspace/wsl/update-embeddings" \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"deletes":["custom-documents/url-openai.com_research_phone-v12-b-eb64c7f1-b087-495e-8a6c-d0bdcf20cc12.json"]}' \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('docs_left=', len(d.get('workspace',{}).get('documents',[])))"
echo "== unlink ws1 (docpath) =="
curl -s -X POST "http://localhost:3001/api/v1/workspace/1/update-embeddings" \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"deletes":["custom-documents/v12_phone_a.txt-b134cc08-9bda-45a6-9af7-9ad19ca573ca.json"]}' \
  | python3 -c "import sys,json; d=json.load(sys.stdin); docs=d.get('workspace',{}).get('documents',[]); print('has_v12=', any('v12_phone_a' in str(x) for x in docs))"
echo "== remove docs =="
curl -s -X DELETE "http://localhost:3001/api/v1/system/remove-documents" \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"names":["url-openai.com_research_phone-v12-b-eb64c7f1-b087-495e-8a6c-d0bdcf20cc12.json","v12_phone_a.txt-b134cc08-9bda-45a6-9af7-9ad19ca573ca.json"]}'
echo
echo "== verify filesystem =="
docker exec anythingllm sh -c 'ls /app/server/storage/documents/custom-documents/ 2>/dev/null | grep -E "phone-v12|v12_phone_a"' || echo "LEFT=0"
