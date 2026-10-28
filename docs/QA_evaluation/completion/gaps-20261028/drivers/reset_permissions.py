import subprocess,json,pathlib,time,sys,re
name=sys.argv[1]; assert re.fullmatch('[a-zA-Z0-9_-]+',name)
p='com.aistudio.videoknowledgesorter.fkvbwy.qa.preview';base=['adb','-s','emulator-5554','shell'];assert subprocess.check_output(base+['getprop','ro.kernel.qemu'],text=True).strip()=='1';events=[]
for perm in ['READ_MEDIA_IMAGES','READ_MEDIA_VIDEO','READ_MEDIA_VISUAL_USER_SELECTED']:
 for args in [['pm','revoke',p,'android.permission.'+perm],['pm','clear-permission-flags',p,'android.permission.'+perm,'user-set','user-fixed']]:
  r=subprocess.run(base+args,capture_output=True,text=True);events.append({'argv':base+args,'code':r.returncode,'output':r.stdout+r.stderr});assert r.returncode==0,r.stderr
(pathlib.Path(__file__).resolve().parents[1]/'runs'/(name+'-setup.json')).write_text(json.dumps({'setup_only':True,'not_native_ui_proof':True,'events':events,'time':time.time()},indent=2));print('QA-only initial permission state reset; native UI journey must establish actual grant.')
