import subprocess,json,pathlib,time,sys,re
name=sys.argv[1];assert re.fullmatch('[a-zA-Z0-9_-]+',name)
p='com.aistudio.videoknowledgesorter.fkvbwy.qa.preview';b=['adb','-s','emulator-5554','shell'];assert subprocess.check_output(b+['getprop','ro.kernel.qemu'],text=True).strip()=='1'
def pid():return subprocess.run(b+['pidof',p],capture_output=True,text=True).stdout.strip()
old=pid();assert old,'Host force-stop requires actual existing QA process'
cmd=b+['am','force-stop',p];start=time.time();r=subprocess.run(cmd,capture_output=True,text=True);end=time.time();after=pid();out={'package':p,'serial':'emulator-5554','argv':cmd,'exit_code':r.returncode,'started':start,'ended':end,'pid_before':old,'pid_after':after};assert r.returncode==0 and not after
(pathlib.Path(__file__).resolve().parents[1]/'runs'/(name+'.json')).write_text(json.dumps(out,indent=2));print('QA process force-stopped; receipt',name)
