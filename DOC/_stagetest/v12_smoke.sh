#!/bin/bash
# v1.2 阶段0 冒烟:AnythingLLM 连通性 + Key 有效性(WSL 执行,避免 PowerShell 转译)
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt)
echo "--- resolv nameserver ---"
grep nameserver /etc/resolv.conf
echo "--- ping localhost:3001 ---"
curl -s -m 6 -o /dev/null -w "HTTP:%{http_code} ERR:%{errormsg}\n" http://localhost:3001/api/ping 2>&1
HOSTIP=$(grep nameserver /etc/resolv.conf | awk '{print $2}')
echo "HOSTIP=$HOSTIP"
echo "--- ping hostip:3001 ---"
curl -s -m 6 -o /dev/null -w "HTTP:%{http_code} ERR:%{errormsg}\n" http://$HOSTIP:3001/api/ping 2>&1
echo "--- auth localhost:3001 ---"
curl -s -m 6 -o /dev/null -w "HTTP:%{http_code}\n" http://localhost:3001/api/v1/auth -H "Authorization: Bearer $KEY" 2>&1
