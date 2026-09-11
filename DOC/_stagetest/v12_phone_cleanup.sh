#!/bin/bash
# 清理本次真机测试产生的服务器文档
KEY=$(grep -v '^$' /mnt/d/WSL/object/004AnythingLLM-Android/apikey.txt | head -1)
curl -s -X DELETE "http://localhost:3001/api/v1/system/remove-documents" \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"names":["url-example.com_phone-test-v12-9c981268-3343-4bb0-be1c-4e84d1aaa860.json","url-openai.com_research_phone-v12-b-eb64c7f1-b087-495e-8a6c-d0bdcf20cc12.json","v12_phone_a.txt-b134cc08-9bda-45a6-9af7-9ad19ca573ca.json"]}'
echo
