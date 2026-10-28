#!/usr/bin/env python3
"""Version3 acceptance verifier. Reads proof only; never executes device/build commands."""
import argparse, hashlib, json, re, sys
from pathlib import Path

class Rejected(ValueError): pass
def need(condition, message):
    if not condition: raise Rejected(message)
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def read_artifact(root, ref):
    need(isinstance(ref,dict) and set(ref)>={'path','sha256'}, 'missing artifact reference')
    path=(root/ref['path']).resolve()
    need(path.is_relative_to(root.resolve()) and path.is_file(), 'artifact outside report directory or missing: '+ref['path'])
    need(re.fullmatch('[0-9a-f]{64}',ref['sha256']) is not None and digest(path)==ref['sha256'], 'tampered artifact: '+ref['path'])
    return path

def arguments(argv):
    result={}
    for i,v in enumerate(argv):
        if v=='-e':
            need(i+2<len(argv),'truncated instrumentation argument')
            need(argv[i+1] not in result,'duplicate instrumentation arg')
            result[argv[i+1]]=argv[i+2]
    return result

def terminal_methods(text):
    block={}; terminals={}
    for line in text.splitlines():
        m=re.match(r'INSTRUMENTATION_STATUS: (class|test)=(.*)',line)
        if m:block[m[1]]=m[2]
        m=re.match(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$',line)
        if m:
            if int(m[1])!=1 and set(block)>={'class','test'}:
                terminals[block['class']+'.'+block['test']]=int(m[1])
            block={}
    return terminals

def matched(run, requirement):
    if run['terminals'].get(requirement['method'])!=0:return False
    if any(run['args'].get(k)!=v for k,v in requirement.get('args',{}).items()):return False
    # Direct Keep retained journey cannot reuse the separate Bin fixture route.
    if requirement.get('args',{}).get('qa71_permission_phase','').startswith('retained_'):
        if 'qa71_bin_fixture' not in requirement['args'] and run['args'].get('qa71_bin_fixture')=='true':return False
    return True

def validate_preservation(root, reference, requirements):
    preservation=json.loads(read_artifact(root,reference).read_text())
    need(preservation.get('credentials_accessed') is False,'credentials preservation missing')
    original_hash='d805885199b0264a6b8ae8e641182ed4926eb47b60b5e81d0f5c8d61e9bc55be'
    need(requirements.get('original_preview_sha256')==original_hash,'frozen original preview identity changed')
    need(preservation.get('original_preview_built_sha256')==original_hash==preservation.get('original_preview_installed_sha256'),'original preview built/installed identity not preserved')
    backup=preservation.get('qa_backup_hashes',{});restored=preservation.get('qa_restored_hashes',{})
    need(isinstance(backup,dict) and bool(backup) and backup==restored and all(isinstance(v,str) and re.fullmatch('[0-9a-f]{64}',v) for v in backup.values()),'QA backup/restoration hashes missing or unequal')
    need(bool(preservation.get('evidence')),'missing raw preservation evidence')
    for artifact in preservation['evidence']:read_artifact(root,artifact)

def validate_check(root, reference, key, final_hash, requirements):
    need(requirements.get('required_check_counts')=={'unit':55,'host':17},'frozen required check counts changed')
    receipt=json.loads(read_artifact(root,reference).read_text())
    need(receipt.get('exit_code')==0 and receipt.get('candidate_app_sha256')==final_hash and receipt.get('failed')==0 and receipt.get('passed')==requirements['required_check_counts'][key],'missing final required '+key+' check')
    read_artifact(root,receipt['log'])

def validate_expected_interruption(root, entry, verify_run, final_hash, package, serial):
    method='com.example.InterruptedProcessingCompletionTest.interruptedProcessingSeedOrVerify'
    need(verify_run['terminals'].get(method)==0 and verify_run['args'].get('qa_phase')=='interrupt_verify','interruption needs actual verify method0')
    token=verify_run['args'].get('qa_token','')
    need(re.fullmatch(r'[A-Za-z0-9_-]{8,80}',token) is not None,'interruption verify token missing/invalid')
    seed=json.loads(read_artifact(root,entry['seed_meta']).read_text())
    seed_log_path=read_artifact(root,entry['seed_log']);seed_text=seed_log_path.read_text()
    need(seed.get('log_sha256')==digest(seed_log_path),'held seed log hash mismatch')
    args=arguments(seed.get('argv',[]));argv=seed.get('argv',[])
    need(args.get('qa_phase')=='interrupt_seed' and args.get('qa_token')==token and args.get('rea_runtime')=='emulator','held seed phase/token mismatch')
    need('-s' in argv and argv[argv.index('-s')+1]==serial and argv[-1].split('/')[0]==package+'.test','held seed target mismatch')
    apps=[v for k,v in seed.get('hashes',{}).items() if k.endswith('.apk') and 'androidTest' not in k]
    need(apps==[final_hash] and verify_run['app']==final_hash,'interruption seed/verify must use final product APK')
    if 'installed_apk_hashes' in seed:need(seed['installed_apk_hashes'].get(package)==final_hash,'held seed installed APK mismatch')
    need('INSTRUMENTATION_STATUS: class=com.example.InterruptedProcessingCompletionTest' in seed_text and 'INSTRUMENTATION_STATUS: test=interruptedProcessingSeedOrVerify' in seed_text and re.search(r'INSTRUMENTATION_STATUS_CODE: 1\s*$',seed_text,re.M) is not None,'held seed method start missing')
    need(terminal_methods(seed_text).get(method)!=0,'held seed must not masquerade as completed method0')
    need(re.search(r'Process\s+crashed|INSTRUMENTATION_ABORTED',seed_text,re.I) is not None and 'Host did not force-stop QA within ten minutes' not in seed_text,'held seed lacks expected host interruption or timed out')
    ready=json.loads(read_artifact(root,entry['ready']).read_text())
    need(ready.get('schema')==1 and ready.get('token')==token and ready.get('package')==package and ready.get('phase')=='held' and ready.get('seedRequests',0)>=1,'real held request receipt missing')
    need(isinstance(ready.get('id'),int) and ready['id']>0 and ready.get('credentials')=='qa_interrupt_empty_'+token and str(ready.get('fixture','')).endswith('/'+package+'/cache/qa-interrupt-'+token+'.png'),'held fixture identity/ownership mismatch')
    need(ready.get('uri')=='file://'+ready['fixture'] and re.fullmatch('[0-9a-f]{64}',ready.get('sha256','')) is not None,'held fixture URI/hash missing')
    import xml.etree.ElementTree as ET
    prefs_xml=ET.parse(read_artifact(root,entry['receipt_preferences'])).getroot()
    prefs={node.attrib.get('name'):node.text if node.tag=='string' else node.attrib.get('value') for node in prefs_xml}
    need(prefs.get('token')==token and prefs.get('phase')=='held' and str(prefs.get('id'))==str(ready['id']),'held file/preferences receipts disagree')
    observed=json.loads(read_artifact(root,entry['readiness_observation']).read_text())
    need(observed.get('token')==token and observed.get('package')==package and observed.get('serial')==serial and str(observed.get('pid','')).isdigit() and observed.get('ready_sha256')==entry['ready']['sha256'] and observed.get('prefs_sha256')==entry['receipt_preferences']['sha256'],'host held-readiness/PID binding missing')
    stop=json.loads(read_artifact(root,entry['host_stop']).read_text())
    need(stop.get('package')==package and stop.get('serial')==serial and stop.get('argv')==['adb','-s',serial,'shell','am','force-stop',package] and stop.get('exit_code')==0 and str(stop.get('pid_before'))==str(observed['pid']) and not stop.get('pid_after'),'real host stop/PID mismatch')
    need(isinstance(seed.get('started'),(int,float)) and isinstance(seed.get('ended'),(int,float)) and seed['started']<=observed.get('observed_at',-1)<=stop.get('started',-1)<=seed['ended']<=verify_run['meta']['started'] and stop['started']<=stop.get('ended',-1)<=verify_run['meta']['started'],'stop must interrupt actual held seed before verify')
    return True

def validate(report_path, requirements_path):
    root=report_path.parent.resolve(); report=json.loads(report_path.read_text()); requirements=json.loads(requirements_path.read_text())
    need(report.get('schema_version')==3,'report must use schema_version3')
    need(report.get('requirements_sha256')==digest(requirements_path),'requirements hash mismatch')
    ids={f'T{i:03d}' for i in range(1,238)}
    need(len(requirements['scenarios'])==237 and {r['id'] for r in requirements['scenarios']}==ids,'requirements must cover all237 exact IDs')
    expected_exceptions={'T096':'Retired','T114':'Retired','T185':'Not applicable on API36','T231':'Unavailable legacy/debug surface','T232':'Unavailable legacy/debug surface','T113':'User descoped','T179':'User descoped'}
    need(requirements['exceptions']==expected_exceptions,'exceptions changed')
    need(len(requirements['original_gap_ids'])==69 and len(set(requirements['original_gap_ids']))==69,'need69 unique in-scope original gaps')
    rows=report.get('scenarios',[])
    need(len(rows)==237 and {r['id'] for r in rows}==ids,'report must contain all237 exact IDs once')
    candidate=report['candidate']; final_hash=candidate['app_sha256']; package=candidate['package']; serial=candidate['serial']
    need(requirements.get('target')=={'serial':'emulator-5554','package':'com.aistudio.videoknowledgesorter.fkvbwy.qa.preview','api':36},'frozen target changed')
    need(package==requirements['target']['package'] and serial==requirements['target']['serial'] and candidate['api']==36,'candidate must be exact approved API36 QA emulator target')
    need(read_artifact(root,candidate['app_apk']) and candidate['app_apk']['sha256']==final_hash,'candidate APK identity mismatch')
    installed=json.loads(read_artifact(root,candidate['final_install']).read_text())
    need(installed.get('package')==package and installed.get('serial')==serial and installed.get('api')==36,'final installed target mismatch')
    need(installed.get('built_app_sha256')==final_hash==installed.get('installed_app_sha256'),'final installed/built hash mismatch')
    runs={}
    for name,entry in report.get('runs',{}).items():
        meta=json.loads(read_artifact(root,entry['meta']).read_text());logpath=read_artifact(root,entry['log']);text=logpath.read_text()
        need(meta.get('log_sha256')==digest(logpath),'meta log hash mismatch: '+name)
        need(re.search(r'Process\s+crashed|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED',text,re.IGNORECASE) is None,'crashed/aborted instrumentation proof: '+name)
        need(meta.get('exit_code')==0 and isinstance(meta.get('started'),(int,float)) and meta.get('ended',0)>=meta['started'],'nonterminal/failed run: '+name)
        argv=meta.get('argv',[]); need('-s' in argv and argv[argv.index('-s')+1]==serial and 'instrument' in argv,'wrong device/command: '+name)
        need(argv[-1].split('/')[0]==package+'.test','wrong instrumentation package: '+name)
        hashes=meta.get('hashes',{});apps=[v for k,v in hashes.items() if k.endswith('.apk') and 'androidTest' not in k];tests=[v for k,v in hashes.items() if k.endswith('.apk') and 'androidTest' in k]
        need(len(apps)==1 and len(tests)==1 and all(re.fullmatch('[0-9a-f]{64}',v) for v in apps+tests),'missing real APK hashes: '+name)
        if 'installed_apk_hashes' in meta:
            need(meta['installed_apk_hashes'].get(package)==apps[0] and meta['installed_apk_hashes'].get(package+'.test')==tests[0],'run installed hash mismatch: '+name)
        runs[name]={'entry':entry,'meta':meta,'app':apps[0],'args':arguments(argv),'terminals':terminal_methods(text),'text':text}
        need(runs[name]['args'].get('rea_runtime')=='emulator','run lacks emulator guard: '+name)
    def identity_ok(run_name, scenario):
        run=runs[run_name]
        if run['app']==final_hash:return
        waivers=report.get('reuse_waivers',[])
        valid=[w for w in waivers if w.get('run')==run_name and scenario in w.get('unaffected_scenario_ids',[]) and w.get('old_app_sha256')==run['app'] and w.get('final_app_sha256')==final_hash and w.get('reviewed') is True and bool(w.get('rationale','').strip())]
        need(len(valid)==1,'out-of-scope or unreviewed old-APK reuse: '+scenario+'/'+run_name)
        review=json.loads(read_artifact(root,valid[0]['review_artifact']).read_text())
        need(review.get('reviewed') is True and scenario in review.get('unaffected_scenario_ids',[]) and review.get('old_app_sha256')==run['app'] and review.get('final_app_sha256')==final_hash,'reuse review does not bind scope/app identity')
    def host_ok(run_name,requirement,scenario):
        spec=requirement.get('host_before')
        if not spec:return
        edges=[e for e in report.get('host_proofs',[]) if e.get('to_run')==run_name and e.get('kind')==spec['kind'] and (spec['kind']!='native_app_info' or e.get('setting')==spec['setting'])]
        valid=False
        for edge in edges:
            previous=runs.get(edge.get('from_run'));current=runs[run_name]
            if not previous or not matched(previous,{'method':requirement['method'],'args':spec['previous_args']}):continue
            if previous['args'].get('qa71_bin_fixture')!=current['args'].get('qa71_bin_fixture'):continue
            if previous['meta']['ended']>current['meta']['started']:continue
            identity_ok(edge['from_run'],scenario)
            path=read_artifact(root,edge['artifact']);proof=json.loads(path.read_text())
            if spec['kind']=='force_stop':
                valid=proof.get('package')==package and proof.get('serial')==serial and proof.get('exit_code')==0 and proof.get('argv')==['adb','-s',serial,'shell','am','force-stop',package] and proof.get('pid_before') and not proof.get('pid_after') and previous['meta']['ended']<=proof.get('started',-1)<=proof.get('ended',-1)<=current['meta']['started']
            else:
                grants=proof.get('grants',{});setting=spec['setting'];actions=proof.get('actions',[])
                labels=[a.get('click') for a in actions if 'click' in a]
                valid=proof.get('package')==package and proof.get('serial')==serial and proof.get('api')==36 and proof.get('native_setting')==setting and proof.get('package_manager_mutations') is False and 'Permissions' in labels and any(v in labels for v in ['Photos and videos','Photos & videos'])
                valid=valid and ((not any(grants.values()) and any(v in labels for v in ["Don't allow",'Don’t allow'])) if setting=='denied' else (grants.get('READ_MEDIA_IMAGES') is True and grants.get('READ_MEDIA_VIDEO') is True and any(v in labels for v in ['Allow all','Always allow all','Allow all photos and videos'])))
                for index,event in enumerate(actions):
                    if 'screen_sha256' in event:
                        screen=path.parent/f'{index:02d}.xml';need(screen.is_file() and digest(screen)==event['screen_sha256'],'native screen missing/tampered')
                valid=valid and any('screen_sha256' in a for a in actions)
            if valid:break
        need(valid,'missing exact native/force-stop phase proof: '+scenario+'/'+run_name)
    req_by_id={r['id']:r for r in requirements['scenarios']}
    for row in rows:
        sid=row['id'];req=req_by_id[sid]
        if sid in expected_exceptions:
            need(row.get('status')==expected_exceptions[sid] and not row.get('proofs'),'exception changed or falsely promoted: '+sid);continue
        need(row.get('status')=='Passed','no Partial/untested status allowed: '+sid)
        proof_names=row.get('proofs',[]);need(proof_names and all(n in runs for n in proof_names),'missing runtime proof: '+sid)
        for requirement in req['baseline']+req['gap_requirements']:
            candidates=[n for n in proof_names if matched(runs[n],requirement)]
            need(candidates,'missing terminal method0/exact phase args: '+sid+'/'+requirement['method']+'/'+str(requirement.get('args')))
            accepted=False;failures=[]
            for n in candidates:
                try:identity_ok(n,sid);host_ok(n,requirement,sid);accepted=True;break
                except Rejected as error:failures.append(str(error))
            need(accepted,'; '.join(failures))
        if req['manual_visual_review']:
            visual=json.loads(read_artifact(root,row.get('visual_review')).read_text())
            need(visual.get('reviewed') is True and visual.get('readability')=='passed' and sid in visual.get('scenario_ids',[]) and visual.get('candidate_app_sha256')==final_hash,'missing manual final-APK visual review: '+sid)
            need(visual.get('flag_secure_preserved') is True and visual.get('protected_surfaces_reviewed') is True and visual.get('protected_evidence_kind')=='semantics_geometry_contrast','protected screenshots cannot establish readable Settings')
            read_artifact(root,visual['protected_evidence'])
            need(bool(visual.get('screens')),'missing actual nonsecure rendered screens')
            from PIL import Image,ImageStat
            for screen in visual['screens']:
                image=Image.open(read_artifact(root,screen)).convert('RGB')
                need(max(ImageStat.Stat(image).var)>1,'blank/black screenshot cannot establish readable UI')
    baseline=report.get('regression',{});names=baseline.get('runs',[])
    need(names and all(n in runs and runs[n]['app']==final_hash for n in names),'full regression must run final product APK')
    passed={m for n in names for m,c in runs[n]['terminals'].items() if c==0}
    need(len(requirements['baseline_methods'])==191 and len(set(requirements['baseline_methods']))==191 and set(requirements['baseline_methods'])<=passed,'missing frozen exact191-method baseline regression')
    for requirement in requirements['baseline_requirements']:
        candidates=[n for n in names if matched(runs[n],requirement)]
        need(candidates,'missing exact regression opt-in args/method0: '+requirement['method'])
        if requirement.get('host_before'):
            validated=False;errors=[]
            for name in candidates:
                try:host_ok(name,requirement,'__baseline__');validated=True;break
                except Rejected as error:errors.append(str(error))
            need(validated,'; '.join(errors))
        if requirement.get('expected_interruption'):
            entries=[e for e in report.get('expected_interruptions',[]) if e.get('verify_run') in candidates]
            validated=False;errors=[]
            for entry in entries:
                try:validate_expected_interruption(root,entry,runs[entry['verify_run']],final_hash,package,serial);validated=True;break
                except Rejected as error:errors.append(str(error))
            need(validated,'missing valid held-seed/host-stop/verify proof: '+'; '.join(errors))
    for key in ['unit','host']:validate_check(root,report['checks'][key],key,final_hash,requirements)
    validate_preservation(root,report['preservation'],requirements)
    need(report.get('deployment_verified') is True and report.get('phone_deployment') is False,'final emulator deployment proof required')
    return {'ok':True,'version':3,'scenarios':237,'Passed':230,'exceptions':7,'original_gaps_closed':69,'candidate_app_sha256':final_hash}

def main():
    p=argparse.ArgumentParser();p.add_argument('report',type=Path);p.add_argument('--requirements',type=Path,default=Path(__file__).with_name('requirements.json'));a=p.parse_args()
    try: print(json.dumps(validate(a.report,a.requirements),sort_keys=True))
    except (Rejected,KeyError,TypeError,ValueError,OSError,ImportError) as error:
        print(json.dumps({'ok':False,'error':str(error)}));return 1
    return 0
if __name__=='__main__':sys.exit(main())
