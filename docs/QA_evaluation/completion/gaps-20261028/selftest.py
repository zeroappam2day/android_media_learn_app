#!/usr/bin/env python3
"""Synthetic negative fixtures only; these results establish no Android scenario pass."""
import copy,hashlib,json,tempfile,unittest
from pathlib import Path
from verify import validate,Rejected,digest,validate_preservation,validate_check,validate_expected_interruption
REQ=Path(__file__).with_name('requirements.json')
class NegativeGate(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
  self.req=json.loads(REQ.read_text());self.package='com.aistudio.videoknowledgesorter.fkvbwy.qa.preview';self.serial='emulator-5554'
  self.apk=self.put('app.apk',b'SYNTHETIC SELFTEST APK; NEVER DEPLOY');self.final=self.apk['sha256']
  install=self.put('install.json',{'package':self.package,'serial':self.serial,'api':36,'built_app_sha256':self.final,'installed_app_sha256':self.final})
  self.report={'schema_version':3,'requirements_sha256':digest(REQ),'candidate':{'package':self.package,'serial':self.serial,'api':36,'app_sha256':self.final,'app_apk':self.apk,'final_install':install},'scenarios':[{'id':r['id'],'status':r['exception'] or 'Passed','proofs':[] if r['exception'] else ['one']} for r in self.req['scenarios']],'runs':{}}
  m=self.req['scenarios'][0]['baseline'][0]['method'];cls,test=m.rsplit('.',1)
  log=self.put('one.log',f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={test}\nINSTRUMENTATION_STATUS_CODE: 0\n'.encode())
  self.meta={'argv':['adb','-s',self.serial,'shell','am','instrument','-e','rea_runtime','emulator',self.package+'.test/androidx.test.runner.AndroidJUnitRunner'],'hashes':{'app/build/outputs/apk/debug/app-debug.apk':self.final,'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk':'a'*64},'started':1,'ended':2,'exit_code':0,'log_sha256':log['sha256']}
  self.report['runs']['one']={'meta':self.put('one.meta.json',self.meta),'log':log}
 def tearDown(self):self.temp.cleanup()
 def put(self,name,data):
  p=self.root/name
  if isinstance(data,dict):p.write_text(json.dumps(data))
  else:p.write_bytes(data)
  return {'path':name,'sha256':digest(p)}
 def deny(self,pattern):
  p=self.root/'final-report.json';p.write_text(json.dumps(self.report))
  with self.assertRaisesRegex(Rejected,pattern):validate(p,REQ)
 def test_missing_proof_denied(self):
  self.report['scenarios'][0]['proofs']=[];self.deny('missing runtime proof')
 def test_tampered_log_denied(self):
  (self.root/'one.log').write_text('TAMPERED');self.deny('tampered artifact')
 def test_out_of_scope_old_apk_reuse_denied(self):
  self.meta['hashes']['app/build/outputs/apk/debug/app-debug.apk']='b'*64
  self.report['runs']['one']['meta']=self.put('one.meta.json',self.meta)
  self.report['reuse_waivers']=[{'run':'one','unaffected_scenario_ids':['T002'],'old_app_sha256':'b'*64,'final_app_sha256':self.final,'reviewed':True,'rationale':'Synthetic unrelated scope'}]
  self.deny('out-of-scope or unreviewed old-APK reuse')
 def test_wrong_target_denied(self):
  self.report['candidate']['serial']='emulator-9999';self.deny('exact approved')
 def test_crashed_batch_denied(self):
  ref=self.report['runs']['one']['log'];f=self.root/ref['path'];f.write_text(f.read_text()+'Process crashed.\n');ref['sha256']=digest(f);self.meta['log_sha256']=digest(f);self.report['runs']['one']['meta']=self.put('one.meta.json',self.meta);self.deny('crashed/aborted')
 def test_skip_is_not_pass(self):
  ref=self.report['runs']['one']['log'];p=self.root/ref['path'];p.write_text(p.read_text().replace('CODE: 0','CODE: -3'));ref['sha256']=digest(p);self.meta['log_sha256']=digest(p);self.report['runs']['one']['meta']=self.put('one.meta.json',self.meta);self.deny('missing terminal method0')
 def preservation(self):
  original='d805885199b0264a6b8ae8e641182ed4926eb47b60b5e81d0f5c8d61e9bc55be'
  return {'credentials_accessed':False,'original_preview_built_sha256':original,'original_preview_installed_sha256':original,'qa_backup_hashes':{'owned-state':'c'*64},'qa_restored_hashes':{'owned-state':'c'*64},'evidence':[self.apk]}
 def test_qa_restore_mismatch_denied(self):
  proof=self.preservation();proof['qa_restored_hashes']['owned-state']='d'*64
  with self.assertRaisesRegex(Rejected,'backup/restoration'):validate_preservation(self.root,self.put('preservation.json',proof),self.req)
 def test_wrong_original_preview_denied(self):
  proof=self.preservation();proof['original_preview_installed_sha256']='d'*64
  with self.assertRaisesRegex(Rejected,'original preview'):validate_preservation(self.root,self.put('preservation.json',proof),self.req)
 def test_credentials_access_denied(self):
  proof=self.preservation();proof['credentials_accessed']=True
  with self.assertRaisesRegex(Rejected,'credentials preservation'):validate_preservation(self.root,self.put('preservation.json',proof),self.req)
 def test_insufficient_unit_or_host_counts_denied(self):
  for key,count in [('unit',54),('host',16)]:
   proof={'exit_code':0,'candidate_app_sha256':self.final,'failed':0,'passed':count,'log':self.report['runs']['one']['log']}
   with self.assertRaisesRegex(Rejected,'missing final required '+key):validate_check(self.root,self.put(key+'.json',proof),key,self.final,self.req)
 def interruption(self):
  token='SyntheticInterrupt';method='com.example.InterruptedProcessingCompletionTest.interruptedProcessingSeedOrVerify'
  raw=self.put('held.log',b'INSTRUMENTATION_STATUS: class=com.example.InterruptedProcessingCompletionTest\nINSTRUMENTATION_STATUS: test=interruptedProcessingSeedOrVerify\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_RESULT: shortMsg=Process crashed.\n')
  meta=copy.deepcopy(self.meta);meta['argv']=meta['argv'][:-1]+['-e','qa_phase','interrupt_seed','-e','qa_token',token,meta['argv'][-1]];meta['log_sha256']=raw['sha256']
  fixture='/data/user/0/'+self.package+'/cache/qa-interrupt-'+token+'.png'
  ready=self.put('held.json',{'schema':1,'token':token,'package':self.package,'phase':'held','seedRequests':1,'id':1,'credentials':'qa_interrupt_empty_'+token,'fixture':fixture,'uri':'file://'+fixture,'sha256':'c'*64})
  prefs=self.put('held-prefs.xml',('<map><string name="token">'+token+'</string><string name="phase">held</string><long name="id" value="1" /></map>').encode())
  observed=self.put('ready-observed.json',{'token':token,'package':self.package,'serial':self.serial,'pid':'555','observed_at':1.2,'ready_sha256':ready['sha256'],'prefs_sha256':prefs['sha256']})
  stop=self.put('stop.json',{'package':self.package,'serial':self.serial,'argv':['adb','-s',self.serial,'shell','am','force-stop',self.package],'exit_code':0,'pid_before':'555','pid_after':'','started':1.5,'ended':1.7})
  entry={'seed_meta':self.put('held.meta.json',meta),'seed_log':raw,'ready':ready,'receipt_preferences':prefs,'readiness_observation':observed,'host_stop':stop}
  run={'terminals':{method:0},'args':{'qa_phase':'interrupt_verify','qa_token':token},'app':self.final,'meta':{'started':3}}
  return entry,run
 def test_expected_interruption_helper_positive_synthetic_only(self):
  entry,run=self.interruption();self.assertTrue(validate_expected_interruption(self.root,entry,run,self.final,self.package,self.serial))
 def test_interruption_token_mismatch_denied(self):
  entry,run=self.interruption();run['args']['qa_token']='DifferentToken'
  with self.assertRaisesRegex(Rejected,'phase/token'):validate_expected_interruption(self.root,entry,run,self.final,self.package,self.serial)
 def test_interruption_ready_not_held_denied(self):
  entry,run=self.interruption();ready=json.loads((self.root/'held.json').read_text());ready['phase']='prepared';entry['ready']=self.put('held.json',ready)
  with self.assertRaisesRegex(Rejected,'held request'):validate_expected_interruption(self.root,entry,run,self.final,self.package,self.serial)
 def test_interruption_seed_method0_denied(self):
  entry,run=self.interruption();path=self.root/'held.log';path.write_text(path.read_text()+'INSTRUMENTATION_STATUS: class=com.example.InterruptedProcessingCompletionTest\nINSTRUMENTATION_STATUS: test=interruptedProcessingSeedOrVerify\nINSTRUMENTATION_STATUS_CODE: 0\n');entry['seed_log']=self.put('held.log',path.read_bytes());meta=json.loads((self.root/'held.meta.json').read_text());meta['log_sha256']=entry['seed_log']['sha256'];entry['seed_meta']=self.put('held.meta.json',meta)
  with self.assertRaisesRegex(Rejected,'masquerade'):validate_expected_interruption(self.root,entry,run,self.final,self.package,self.serial)
 def test_interruption_stop_wrong_pid_denied(self):
  entry,run=self.interruption();proof=json.loads((self.root/'stop.json').read_text());proof['pid_before']='999';entry['host_stop']=self.put('stop.json',proof)
  with self.assertRaisesRegex(Rejected,'stop/PID'):validate_expected_interruption(self.root,entry,run,self.final,self.package,self.serial)
if __name__=='__main__':unittest.main()
