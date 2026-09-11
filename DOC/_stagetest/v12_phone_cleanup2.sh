#!/bin/bash
# 完整清理: 先解关联工作区, 再 remove-documents
KEY=$(grep -v '^$' /mnt/d/WSL/object/004AnythingLLM-Android/apikey.txt | head -1)
echo "== unlink wsl =="
curl -s -X POST "http://localhost:3001/api/v1/workspace/wsl/update-embeddings" \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"deletes":["url-openai.com_research_phone-v12-b-eb64c7f1-b087-495e-8a6c-d0bdcf20cc12.json"]}'
echo
echo "== unlink ws1 =="
curl -s -X POST "http://localhost:3001/api/v1/workspace/1/update-embeddings" \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"deletes":["v12_phone_a.txt-b134cc08-9bda-45a6-9af7-9ad19ca573ca.json"]}'
echo
echo "== remove docs =="
curl -s -X DELETE "http://localhost:3001/api/v1/system/remove-documents" \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"names":["url-openai.com_research_phone-v12-b-eb64c7f1-b087-495e-8a6c-d0bdcf20cc12.json","v12_phone_a.txt-b134cc08-9bda-45a6-9af7-9ad19ca573ca.json"]}'
echo
echo "== verify =="
docker exec anythingllm sh -c 'ls /app/server/storage/documents/custom-documents/ 2>/dev/null | grep -E "phone-v12|v12_phone_a"' || echo "LEFT=0"
