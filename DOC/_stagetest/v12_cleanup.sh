#!/bin/bash
# v1.2 全面验证回归数据清理:
# 1) update-embeddings deletes 解关联(wsl 中的回归文档)
# 2) remove-documents 物理删除本轮回归产物(url-* 6 个 + v12_test.txt-b6b5c72c)
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt | tr -d "\r\n")
BASE=http://localhost:3001

declare -a DOCS=(
  "custom-documents/url-example.com_docs_a-18f4194c-8a24-436f-8d37-cc438deace94.json"
  "custom-documents/url-example.com_docs_a-d012f6d5-da26-40a9-ae8c-dc95599feaf4.json"
  "custom-documents/url-example.com_docs_a-f7a1df89-f6a8-46b9-bd42-3e6fae1c9e20.json"
  "custom-documents/url-openai.com_-0312c3f5-192b-4621-9699-8052fc7916b8.json"
  "custom-documents/url-openai.com_research-6b6aa1aa-b151-4f19-b95b-41eda42e4c43.json"
  "custom-documents/url-openai.com_research-883ed278-141a-4036-adf1-6ac3c8a37941.json"
  "custom-documents/v12_test.txt-b6b5c72c-f754-473f-913a-150222844592.json"
)

echo "== step1: update-embeddings deletes (wsl) =="
PATHS=$(printf '"%s",' "${DOCS[@]}")
PATHS="[${PATHS%,}]"
curl -s -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d "{\"adds\":[],\"deletes\":$PATHS}" \
  "$BASE/api/v1/system/update-embeddings" -w "\nHTTP %{http_code}\n" | head -c 300
echo

echo "== step2: remove-documents =="
curl -s -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d "{\"paths\":$PATHS}" \
  "$BASE/api/v1/document/remove" -w "\nHTTP %{http_code}\n" | head -c 300
echo

echo "== verify remaining url-* in custom-documents =="
curl -s -H "Authorization: Bearer $KEY" "$BASE/api/v1/documents" | python3 -c "
import json,sys
d=json.load(sys.stdin)
def walk(node, path):
    for item in node.get('items', []):
        if item.get('type')=='folder':
            yield from walk(item, path+[item.get('name')])
        else:
            yield path, item.get('name')
n=0
for p,name in walk(d.get('localFiles',{}),[]):
    if p and p[-1]=='custom-documents' and name and ('url-' in name or 'v12_test' in name):
        print('LEFTOVER:', name); n+=1
print('leftover count:', n)
"
