import sys, json, urllib.request

KEY = "2JP5NKF-SFF4KRE-K896D1J-2C06W84"

def get(url):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {KEY}"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)

tree = get("http://localhost:3001/api/v1/documents")
print("=== 文档树 ===")
def walk(node, folder=""):
    for it in node.get("items", []):
        if it["type"] == "folder":
            walk(it, it["name"])
        else:
            print(f"{folder}/{it['name']} | {it.get('title','')}")
walk(tree["localFiles"])

print("\n=== 工作区详情(1,2,wsl) ===")
for slug in ["1", "2", "wsl"]:
    d = get(f"http://localhost:3001/api/v1/workspaces/{slug}")
    ws = d["workspace"][0]
    docs = ws.get("documents", [])
    print(f"-- {slug}: {len(docs)} docs")
    for x in docs:
        print(f"   {x['docpath']}")
