#!/bin/bash
# 原始响应查看
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt | tr -d "\r\n")
curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/workspace/wsl" -o /tmp/wsl.json
wc -c /tmp/wsl.json
head -c 600 /tmp/wsl.json
echo
curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/documents/custom-documents" -o /tmp/docs.json
wc -c /tmp/docs.json
head -c 400 /tmp/docs.json
