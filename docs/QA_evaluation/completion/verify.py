import json,re,hashlib
from pathlib import Path
p=Path(__file__).parent
r=json.loads((p/'final-report.json').read_text())
assert r['serial']=='emulator-5554' and r['phone_deployment'] is False
assert r['target_package'].endswith('.qa.preview')
rows=r['scenarios']
assert {x['id'] for x in rows}=={f'T{i:03}' for i in range(1,238)}
assert all(x['status'] in ['Passed','Retired','Not applicable on API36','Unavailable legacy/debug surface'] for x in rows)
for row in rows:
    assert row['reason'] and row['evidence']
    for evidence in row['evidence']: assert (p/evidence).is_file()
    if row['status']=='Passed':
        assert row['tests']
        for test in row['tests']:
            proof=r['test_results'][test]
            assert proof['passed'] and proof['candidate_apk_sha256']==r['candidate_apk_sha256']
            log=(p/proof['log']).read_text()
            assert 'OK (' in log and not re.search(r'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED',log)
assert not r['unresolved_on_target']
assert r['original_preview_preserved'] and r['credentials_accessed'] is False
print(json.dumps({'ok':True,'evidence':[str(p/'final-report.json'),str(p.parent/'QA.md')]}))
