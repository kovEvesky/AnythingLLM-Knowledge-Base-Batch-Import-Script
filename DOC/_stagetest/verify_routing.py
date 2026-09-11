import json, urllib.request

KEY = "2JP5NKF-SFF4KRE-K896D1J-2C06W84"

def get(url):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {KEY}"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)

tree = get("http://localhost:3001/api/v1/documents")
print("=== 文档树(本测试相关) ===")
def walk(node, folder=""):
    for it in node.get("items", []):
        if it["type"] == "folder":
            walk(it, it["name"])
        else:
            name = it.get("title") or it["name"]
            if any(k in name for k in ["my'file", "特殊", "验收文件五", "验收文件三", "需求文档", "大文件"]):
                print(f"  {folder}/{it['name']}  <- {name}")
walk(tree["localFiles"])

print("\n=== 工作区归属 ===")
for slug in ["1", "2", "wsl", "5", "ee"]:
    d = get(f"http://localhost:3001/api/v1/workspace/{slug}")
    docs = d["workspace"][0].get("documents", [])
    hits = [x["docpath"] for x in docs if any(k in x["docpath"] for k in ["u56db", "u4e09", "u9700", "u65ad", "u5927", "u4e94", "ud83c", "my"])]
    if hits:
        print(f"  ws={slug}:")
        for h in hits:
            print(f"    {h}")
