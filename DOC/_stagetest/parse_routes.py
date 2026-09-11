import re, subprocess

# 从容器拷出 workspace 端点源码(用 WSL 的 docker)
subprocess.run(["docker", "cp", "anythingllm:/app/server/endpoints/api/workspace/index.js", "/tmp/ws_index.js"], check=True)
src = open("/tmp/ws_index.js", encoding="utf-8").read()
print("len:", len(src))
print("=== 路由注册(app.xxx) ===")
for m in re.finditer(r'app\.(get|post|delete|put|patch)\(["\']([^"\']+)["\']', src):
    print(m.group(1).upper(), m.group(2))
