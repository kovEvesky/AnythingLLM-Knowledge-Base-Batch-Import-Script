#!/bin/bash
# AnythingLLM 容器日志: 嵌入相关
docker logs --tail 150 anythingllm 2>&1 | grep -iE "embed|v12|error|fail|update" | tail -40
echo "==== last 30 raw ===="
docker logs --tail 30 anythingllm 2>&1 | tail -30
