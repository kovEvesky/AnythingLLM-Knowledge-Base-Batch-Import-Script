#!/bin/bash
# v1.2 全面验证: 服务器侧 API key 有效性 + 工作区/文件夹清单
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt | tr -d "\r\n")
echo "key=$KEY"
echo "== workspaces =="
curl -s -w "\nHTTP %{http_code}\n" -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/workspaces" | head -c 1200
echo
echo "== custom-documents list =="
curl -s -w "\nHTTP %{http_code}\n" -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/documents/custom-documents" | head -c 800
