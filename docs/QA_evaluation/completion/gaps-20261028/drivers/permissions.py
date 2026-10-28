#!/usr/bin/env python3
"""Native Android-16 App-info permission changes; no package-manager mutations."""
import argparse, hashlib, json, pathlib, re, subprocess, time, xml.etree.ElementTree as ET
p=argparse.ArgumentParser(); p.add_argument('--serial',default='emulator-5554'); p.add_argument('--package',required=True); p.add_argument('--set',choices=['denied','full'],required=True); p.add_argument('--out',required=True); a=p.parse_args()
assert re.fullmatch(r'emulator-\d+',a.serial), 'emulator only'
assert re.fullmatch(r'[A-Za-z0-9_.]+\.qa\.preview',a.package), 'QA preview only'
out=pathlib.Path(a.out); out.mkdir(parents=True,exist_ok=True); events=[]
def adb(*args):
 r=subprocess.run(['adb','-s',a.serial,*args],capture_output=True,text=True,timeout=20); r.check_returncode(); return r.stdout
assert adb('shell','getprop','ro.kernel.qemu').strip()=='1'
assert adb('shell','getprop','ro.build.version.sdk').strip()=='36'
def screen():
 path='/sdcard/qa71_permission_native.xml'; adb('shell','uiautomator','dump',path)
 xml=adb('shell','cat',path); root=ET.fromstring(xml[xml.index('<?xml'):]); nodes=list(root.iter('node'))
 assert any(n.get('package') in ('com.android.settings','com.google.android.permissioncontroller','com.android.permissioncontroller') for n in nodes),'Unexpected native package'
 index=len(events); (out/f'{index:02d}.xml').write_text(xml)
 events.append({'screen_sha256':hashlib.sha256(xml.encode()).hexdigest()}); return nodes
def click(labels):
 deadline=time.monotonic()+12
 while time.monotonic()<deadline:
  ns=screen(); hits=[n for n in ns if n.get('text') in labels or n.get('content-desc') in labels]
  if hits:
   n=hits[0]; coords=list(map(int,re.findall(r'\d+',n.get('bounds','')))); assert len(coords)==4 and coords[2]>coords[0] and coords[3]>coords[1]
   events.append({'click':n.get('text') or n.get('content-desc'),'bounds':coords}); adb('shell','input','tap',str((coords[0]+coords[2])//2),str((coords[1]+coords[3])//2)); time.sleep(.6); return
  time.sleep(.3)
 raise RuntimeError('Required observed control missing: '+repr(labels))
adb('shell','am','start','-W','-a','android.settings.APPLICATION_DETAILS_SETTINGS','-d','package:'+a.package)
# App-info intent fixes exact identity; retain screen proof before touching permissions.
# Android may restore the previously open permission child rather than its parent details.
for _ in range(4):
 ns=screen()
 if any(n.get('text')=='Permissions' for n in ns): break
 assert any(n.get('content-desc')=='Navigate up' for n in ns), 'No observed native parent navigation to App-info'
 click({'Navigate up'})
else: raise RuntimeError('App-info Permissions control not observed after bounded native parent navigation')
click({'Permissions'}); click({'Photos and videos','Photos & videos'})
if a.set=='denied':
 click({"Don't allow",'Don’t allow'})
 ns=screen()
 if any(n.get('text') in {"Don't allow anyway",'Don’t allow anyway'} for n in ns): click({"Don't allow anyway",'Don’t allow anyway'})
else: click({'Allow all','Always allow all','Allow all photos and videos'})
time.sleep(.8); screen()
dump=adb('shell','dumpsys','package',a.package)
flags={}
for permission in ['READ_MEDIA_IMAGES','READ_MEDIA_VIDEO','READ_MEDIA_VISUAL_USER_SELECTED']:
 matches=re.findall(r'android\.permission\.'+permission+r': granted=(true|false)',dump)
 assert matches, 'Missing runtime permission evidence '+permission
 flags[permission]=matches[-1]=='true'
assert (not any(flags.values())) if a.set=='denied' else (flags['READ_MEDIA_IMAGES'] and flags['READ_MEDIA_VIDEO']),flags
receipt={'package':a.package,'serial':a.serial,'api':36,'native_setting':a.set,'grants':flags,'actions':events,'package_manager_mutations':False}
(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n'); print(json.dumps({k:v for k,v in receipt.items() if k!='actions'}))
