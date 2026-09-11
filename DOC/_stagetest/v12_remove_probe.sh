#!/bin/bash
# 试 AnythingLLM remove 端点形式
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt | tr -d "\r\n")
BASE=http://localhost:3001
DOC="url-example.com_docs_a-18f4194c-8a24-436f-8d37-cc438deace94.json"
echo "== try DELETE /api/v1/document/remove/custom-documents/$DOC =="
curl -s -X DELETE -H "Authorization: Bearer $KEY" "$BASE/api/v1/document/remove/custom-documents/$DOC" -w "\nHTTP %{http_code}\n" | head -c 300
echo
echo "== try POST /api/v1/document/remove {folder,name} =="
curl -s -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d "{\"folder\":\"custom-documents\",\"name\":\"$DOC\"}" \
  "$BASE/api/v1/document/remove" -w "\nHTTP %{http_code}\n" | head -c 300
echo
