#!/bin/bash
# v1.2 全面验证: 读取新落盘的 url-example 文档内容(chunkSource/docpath/wordCount)
KEY=$(cat /mnt/d/WSL/object/AnythingLLM-Android/apikey.txt | tr -d "\r\n")
DOC="custom-documents/url-example.com_docs_a-d012f6d5-da26-40a9-ae8c-dc95599feaf4.json"
curl -s -H "Authorization: Bearer $KEY" "http://localhost:3001/api/v1/document/$DOC" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('docpath:', d.get('docpath'))
print('chunkSource:', d.get('chunkSource'))
print('title:', d.get('title'))
print('wordCount:', d.get('wordCount'))
print('published:', d.get('published'))
"
