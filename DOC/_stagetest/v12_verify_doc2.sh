#!/bin/bash
# v1.2 全面验证: wsl 工作区文档完整元数据(含 chunkSource)
python3 <<'PY'
import json
d = json.load(open('/tmp/wsl_ws.json'))
ws = d['workspace'][0] if isinstance(d.get('workspace'), list) else d['workspace']
for x in ws.get('documents', []):
    if 'url-example' in str(x.get('name','')) or 'url-example' in str(x.get('docpath','')):
        print(json.dumps(x, ensure_ascii=False, indent=1)[:900])
        print('---')
PY
