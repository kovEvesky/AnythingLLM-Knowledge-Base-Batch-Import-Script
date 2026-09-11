#!/bin/bash
# v1.2 阶段0:upload-link T3(URL数组) + 回查 documents + 清理 T1 测试文档
BASE="http://localhost:3001"
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt)
AUTH="Authorization: Bearer $KEY"
CT="Content-Type: application/json"

echo "=== T3 URL数组 [example.com, example.org] ==="
curl -s -m 150 -X POST "$BASE/api/v1/document/upload-link" -H "$AUTH" -H "$CT" -d '{"link":["https://example.com","https://example.org"]}'
echo ""

echo "=== 回查 /api/v1/documents 顶层结构 ==="
curl -s -m 20 "$BASE/api/v1/documents" -H "$AUTH" | head -c 2500
echo ""

echo "=== 清理 T1 文档(带文件夹前缀) ==="
curl -s -m 20 -X DELETE "$BASE/api/v1/system/remove-documents" -H "$AUTH" -H "$CT" -d '{"names":["custom-documents/url-example.com_-a52ebaf2-d870-49a4-a353-5d51e68d379f.json"]}'
echo ""
