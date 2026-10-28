import json
from pathlib import Path
p=Path(__file__).parent
r=json.loads((p/'session-20261027/results.json').read_text())
assert r['serial']=='emulator-5554' and r['phone_deployment'] is False
assert r['coverage_complete'] is True
assert r['fixes'] and all(x['status']=='verified' for x in r['fixes'])
for x in r['fixes']:
    assert x['before'] and x['after']
    for f in x['before']+x['after']: assert (p/f).is_file()
assert not r['unresolved_required_checks']
print(json.dumps({'ok':True,'evidence':[str(p/'session-20261027/results.json'),str(p/'QA.md')]}))
