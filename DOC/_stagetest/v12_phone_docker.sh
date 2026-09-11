#!/bin/bash
# 查服务器物理文档残留
docker exec anythingllm sh -c 'ls /app/server/storage/documents/custom-documents/ 2>/dev/null | grep -E "phone-v12|v12_phone_a"'
echo "--- all folders ---"
docker exec anythingllm sh -c 'ls /app/server/storage/documents/ 2>/dev/null'
