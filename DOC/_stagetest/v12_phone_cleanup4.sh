#!/bin/bash
# 解关联完成后物理删除测试文档
docker exec anythingllm sh -c 'rm -f "/app/server/storage/documents/custom-documents/url-openai.com_research_phone-v12-b-eb64c7f1-b087-495e-8a6c-d0bdcf20cc12.json" "/app/server/storage/documents/custom-documents/v12_phone_a.txt-b134cc08-9bda-45a6-9af7-9ad19ca573ca.json"'
echo "== verify =="
docker exec anythingllm sh -c 'ls /app/server/storage/documents/custom-documents/ 2>/dev/null | grep -E "phone-v12|v12_phone_a"' || echo "LEFT=0"
