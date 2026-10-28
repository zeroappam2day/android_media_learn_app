import sys,subprocess,json,hashlib,pathlib,time,re
name,classes,*pairs=sys.argv[1:]; assert re.fullmatch(r'[a-zA-Z0-9_-]+',name)
p=(pathlib.Path(__file__).resolve().parents[1]/'runs');p.mkdir(exist_ok=True)
files=['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'];hashes={x:hashlib.sha256(pathlib.Path(x).read_bytes()).hexdigest() for x in files}
installed = {}
for package, local in zip(['com.aistudio.videoknowledgesorter.fkvbwy.qa.preview','com.aistudio.videoknowledgesorter.fkvbwy.qa.preview.test'], files):
    apkPath = subprocess.check_output(['adb','-s','emulator-5554','shell','pm','path',package],text=True).strip().split('package:')[-1]
    installed[package] = subprocess.check_output(['adb','-s','emulator-5554','shell','sha256sum',apkPath],text=True).split()[0]
    assert installed[package] == hashes[local], 'Install built APK before running: '+package
cmd=['adb','-s','emulator-5554','shell','am','instrument','-w','-r','-e','rea_runtime','emulator','-e','qa_empty_credentials_confirmed','true','-e','class',classes]
for x in pairs:
 k,v=x.split('=',1);cmd+=['-e',k,v]
cmd+=['com.aistudio.videoknowledgesorter.fkvbwy.qa.preview.test/androidx.test.runner.AndroidJUnitRunner']
meta={'argv':cmd,'hashes':hashes,'installed_apk_hashes':installed,'started':time.time()};out=p/(name+'.log');side=p/(name+'.meta.json');assert not out.exists(),out
side.write_text(json.dumps(meta,indent=2))
with out.open('w') as f:r=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT)
meta.update(ended=time.time(),exit_code=r.returncode,log_sha256=hashlib.sha256(out.read_bytes()).hexdigest());side.write_text(json.dumps(meta,indent=2));s=out.read_text();print(name, re.findall(r'OK \(\d+ tests?\)|Tests run:.*|FAILURES!!!|INSTRUMENTATION_FAILED:.*',s)[-3:]);sys.exit(0 if r.returncode==0 and 'OK (' in s and 'FAILURES!!!' not in s else 1)
