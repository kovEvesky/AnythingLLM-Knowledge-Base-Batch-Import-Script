#!/bin/bash
# v1.2 阶段0:upload-link 实测 T1(单URL) / T2(无效URL)
BASE="http://localhost:3001"
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt)
AUTH="Authorization: Bearer $KEY"
CT="Content-Type: application/json"

echo "=== T1 单URL https://example.com ==="
curl -s -m 90 -X POST "$BASE/api/v1/document/upload-link" -H "$AUTH" -H "$CT" -d '{"link":"https://example.com"}'
echo ""
echo "=== T2 无效URL(不存在域名) ==="
curl -s -m 30 -X POST "$BASE/api/v1/document/upload-link" -H "$AUTH" -H "$CT" -d '{"link":"https://nonexistent-domain-xyz-2026-0911.com/"}'
echo ""
