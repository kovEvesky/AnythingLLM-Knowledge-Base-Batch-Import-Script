#!/bin/bash
# v1.2 R15: 服务器删除 ee 工作区(1 测试文档) → app 应显示目标失效
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt | tr -d "\r\n")
echo "== delete workspace ee =="
curl -s -X DELETE -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/workspace/ee" -w "\nHTTP %{http_code}\n" | head -c 300
echo
echo "== remaining workspaces =="
curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/workspaces" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print([w.get('slug') for w in d.get('workspaces',[])])
"
