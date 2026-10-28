import json,subprocess,sys,pathlib
r=subprocess.run([sys.executable,"docs/QA_evaluation/completion/gaps-20261028/verify.py","docs/QA_evaluation/completion/gaps-20261028/final-report.json","--requirements","docs/QA_evaluation/completion/gaps-20261028/requirements.json"],capture_output=True,text=True)
r.check_returncode();x=json.loads(r.stdout);assert x["ok"] is True
x["evidence"]=["docs/QA_evaluation/completion/gaps-20261028/final-report.json","docs/QA_evaluation/completion/gaps-20261028/acceptance.json"]
print(json.dumps(x))
