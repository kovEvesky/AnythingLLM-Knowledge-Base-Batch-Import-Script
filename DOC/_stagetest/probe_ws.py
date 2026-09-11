import json, urllib.request, urllib.error

KEY = "2JP5NKF-SFF4KRE-K896D1J-2C06W84"

def get(url):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {KEY}", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")

for slug in ["1", "2", "wsl", "5", "ee"]:
    code, body = get(f"http://localhost:3001/api/v1/workspace/{slug}")
    snippet = body[:80].replace("\n", " ")
    print(f"workspace/{slug} -> HTTP {code} : {snippet}")

code, body = get("http://localhost:3001/api/v1/workspaces")
print(f"list -> HTTP {code}")
if code == 200:
    d = json.loads(body)
    print("workspaces:", [w["slug"] for w in d["workspaces"]])
