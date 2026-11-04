from pathlib import Path
import hashlib, json, subprocess, re, os, tarfile, io

B=Path('artifacts/menu-readability')
read=lambda p:json.loads(Path(p).read_text())
def require(value,message):
    if not value:raise RuntimeError(message)
matrix=read(B/'matrix-current-v18/state.json')
build=read(B/'build-harness-v18/state.json')
require(matrix['completed'] and matrix['same_boot'] and matrix['durable_data_unchanged'],'Current matrix incomplete')
require(all(hashlib.sha256(Path(p).read_bytes()).hexdigest()==h for p,h in build['source_hashes'].items()),'Frozen source changed')
root=B/'final-common-controls-v19';root.mkdir(mode=0o700,exist_ok=False)
collector_sha=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
state=dict(completed=False,pid=os.getpid(),collector_sha256=collector_sha,retained_prior_collector_sha256=hashlib.sha256((B/'final-common-controls-v18/state.json').read_bytes()).hexdigest(),retained_prior_collector_scope='Earlier classifier stopped after the intended zero-height failure; known two completed native executions are validated and reused, not repeated. The v18 source was edited near process startup, so no exact executed private-collector source identity is claimed for that failed classification.',common_test_apk_sha256=build['test_apk_sha256'],cohorts=[],
    scope='Three common final-harness controls. Revised simultaneous category containment should pass old runtime; old Actions empty and intermediate caller-range loss should fail. Failed native/cleanup receipts remain failed; recovery is recorded separately. A separate guarded current-app smoke provides the matched positive and final redeployment.')
def save():(root/'state.json').write_text(json.dumps(state,indent=2)+'\n')
save()
for candidate,case,layout,font,passes in [('baseline','legacy-category','phone',2,1),('baseline','legacy-actions','landscape',1,0),('pre-repair','touch-actions','landscape',1,0),('baseline','caller-diagnostics','landscape',1,3),('current','legacy-actions','landscape',1,1)]:
    out=B/f'candidate-{candidate}-{case}-{layout}{font*100}-current-v18'
    reuse=out.exists()
    require(not reuse or (candidate=='baseline' and case in ['legacy-category','legacy-actions']), 'Unexpected existing control; do not repeat native mutations')
    item=dict(candidate=candidate,case=case,layout=layout,font_scale=font,expected_passes=passes,completed=False)
    state['cohorts'].append(item);save()
    args=['python3',str(B/'run-candidate-probe-v18.py'),'--candidate',candidate,'--case',case,'--layout',layout,'--font-scale',str(font)]
    print(json.dumps({'control':out.name}),flush=True)
    if reuse:
        old=read(out/'probe.json')
        require((case=='legacy-category' and old['completed'] and old['preservation_owner_released']) or (case=='legacy-actions' and old.get('junit_passes')==0 and old.get('restoration_pending')), 'Existing execution does not match the retained collector boundary')
        item['exit_code']=0 if case=='legacy-category' else 1;item['reused_completed_native_execution']=True
    else:
        with (root/(out.name+'.log')).open('x') as log:item['exit_code']=subprocess.run(args,stdout=log,stderr=subprocess.STDOUT).returncode
    require((out/'cohort/record.json').exists(),'No control receipt; inspect preflight')
    host=read(out/'cohort/record.json');outer=read(out/'probe.json');log=(out/'cohort/tests.log').read_text()
    codes=[int(v) for v in re.findall(r'^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)$',log,re.M)]
    require(host['passes']==passes and codes.count(0)==passes and host['installed_apks_match'] and host['test_apk_sha256']==build['test_apk_sha256'],'Unexpected control result/identity')
    failed=[k for k,v in host['cleanup'].items() if not v['passed']]
    item.update(junit_passes=host['passes'],failed_cleanup=failed,cleanup_checks=len(host['cleanup']))
    save()
    if passes:
        require(item['exit_code']==0 and host['completed'] and not failed,'Positive control failed')
    else:
        require(item['exit_code']!=0 and codes.count(-2)==1 and failed==['legacy-fixtures-completed','release-lease'],'Unexpected negative/cleanup boundary')
        if candidate=='baseline':
            lines=Path('app/src/androidTest/java/dev/tinycast/android/InlinePaletteDeviceTest.kt').read_text().splitlines()
            start=next(i for i,line in enumerate(lines) if '@Test fun actionCapsuleAndSearchableActionsPreserveTheSelectedCommand(' in line)
            end=next((i for i in range(start+1,len(lines)) if '@Test fun ' in lines[i]),len(lines))
            empty_lines=[i+1 for i in range(start,end) if 'inline-menu-empty' in lines[i] and 'assertIsDisplayed' in lines[i]]
            require(len(empty_lines)==1 and f'InlinePaletteDeviceTest.kt:{empty_lines[0]})' in log and 'not displayed' in log.lower(), 'Old Actions failed at a different assertion')
            frame=read(out/'cohort/extension-grid-evidence/menu-actions-no-match-legacy-frame.json')['before_native_capture']
            choices=[n for n in frame['nodes'] if n.get('tag')=='inline-menu-choices']
            require(len(choices)==1 and choices[0]['layout_size'][1]==0, 'Old Actions did not retain the expected zero-height viewport')
            item['expected_zero_height_empty_viewport_failure_observed']=True
        else:require('TextSelectionRange' in log and ('2, 6' in log or '2,6' in log) and ('6, 6' in log or '6,6' in log),'Intermediate app failed for a different reason')
        recovery='<user-home>/.t3/scratch/droidcast-markdown-detail-27caf3/artifacts/markdown-detail/recover-legacy-cohort-v1.py'
        with (root/(out.name+'-recovery.log')).open('x') as recovery_log:
            result=subprocess.run(['python3',recovery,'--output',str(out/'cohort'),'--receipt',str(out/'recovery')],stdout=recovery_log,stderr=subprocess.STDOUT)
        require(result.returncode==0,'Separate recovery failed; retain ownership')
        receipt=read(out/'recovery/public-recovery.json')
        require(receipt['completed'] and receipt['application_lease_released_last'] and receipt['clipboard_restored_and_verified_independently'],'Independent recovery incomplete')
        with (root/(out.name+'-outer-restoration.log')).open('x') as outer_log:
            result=subprocess.run(args+['--restore-only'],stdout=outer_log,stderr=subprocess.STDOUT)
        require(result.returncode==0,'Outer restoration failed')
        item['separate_recovery_verified']=True
    outer=read(out/'probe.json')
    require(outer['original_evidence_restored_and_verified'] and outer['preservation_owner_released'],'Original evidence not restored')
    item['original_evidence_restored']=True;item['completed']=True;save()
    print(json.dumps(item),flush=True)
adb=['<android-sdk>/platform-tools/adb','-s','emulator-5580']
def raw(*args):return subprocess.check_output(adb+list(args),stderr=subprocess.STDOUT,timeout=60)
require(raw('shell','id').decode().startswith('uid=2000('),'Privileged adbd')
require('ActiveInstrumentation{' not in raw('shell','dumpsys','activity','processes').decode(),'Live instrumentation')
for path in ['files/compact-menu-evidence-preservation-v1','files/reviewed-text-fixture-lease','files/empty-view-fixture-lease','files/compatibility-batch-fixtures']:
    result=subprocess.run(adb+['shell','run-as','dev.tinycast.android','test','-e',path],capture_output=True,timeout=30)
    require(result.returncode==1 and not result.stdout and not result.stderr,'Recovery ownership remains')
apk=B/'build-harness-v18/candidate/app.apk'
require(hashlib.sha256(apk.read_bytes()).hexdigest()==build['app_sha256'],'Frozen final app changed')
final_smoke=read(B/'candidate-current-legacy-actions-landscape100-current-v18/cohort/record.json')
require(final_smoke['completed'] and final_smoke['passes']==1 and final_smoke['installed_apks_match'] and
        final_smoke['apk_sha256']==build['app_sha256'] and all(v['passed'] for v in final_smoke['cleanup'].values()),
        'Final current APK must be installed by the guarded smoke cohort')
path=raw('shell','pm','path','dev.tinycast.android').decode().strip()
require(path.startswith('package:') and '\n' not in path,'Unexpected installed package layout')
actual=raw('exec-out','cat',path.removeprefix('package:'))
require(hashlib.sha256(actual).hexdigest()==build['app_sha256'],'Installed final app differs')
before=read(B/'matrix-current-v18/private-durable-after.json')
data=raw('exec-out','run-as','dev.tinycast.android','tar','-cf','-',*before['roots'])
hashes={}
with tarfile.open(fileobj=io.BytesIO(data),mode='r:') as archive:
    for member in archive:
        if member.isdir():continue
        require(member.isfile() and any(member.name.startswith(root+'/') for root in before['roots']),'Unexpected durable member')
        require(member.name not in hashes,'Duplicate durable member')
        hashes[member.name]=hashlib.sha256(archive.extractfile(member).read()).hexdigest()
state['same_boot']=raw('shell','cat','/proc/sys/kernel/random/boot_id').decode().strip()==before['boot_id']
state['durable_data_unchanged']=hashes==before['hashes']
state['durable_file_count']=len(hashes)
state['final_current_app_installed_and_verified']=True
state['final_current_app_sha256']=build['app_sha256']
save();require(state['same_boot'] and state['durable_data_unchanged'],'Post-control data preservation unestablished')
state['old_caller_reveal_passes']=3
state['final_current_smoke_passes']=1
state['collector_source_unchanged']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()==collector_sha
require(state['collector_source_unchanged'],'Collector source changed during execution')
state['completed']=True;save();print('Three main controls, a three-case old caller reveal, and guarded final current smoke completed; separate recovery and final APK/data verification recorded',flush=True)
