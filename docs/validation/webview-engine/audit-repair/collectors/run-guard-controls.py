from pathlib import Path
import json,os,re,subprocess,sys,hashlib
B=Path('artifacts/webview-compatibility/audit-repair-v1');C=B/'guard-control';identity=json.loads((C/'identity.json').read_text())
assert identity['completed'] and identity['source_restored_exactly']
assert hashlib.sha256((C/'app-debug.apk').read_bytes()).hexdigest()==identity['apk_sha256']
P=Path('app/src/main/java/dev/tinycast/android/extensions/ExtensionRuntime.kt');assert hashlib.sha256(P.read_bytes()).hexdigest()==identity['single_source_mutation']['before_sha256']
result={'completed':False,'scope':'Counterfactual sync-guard regression only; no candidate/runtime passing credit','app_sha256':identity['apk_sha256'],'collector_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'cohorts':{}}
def save():(B/'guard-controls.json').write_text(json.dumps(result,indent=2)+'\n')
save()
for name,harness,insensitive in [('old-insensitive',Path('artifacts/webview-compatibility/candidate-v6/app-debug-androidTest.apk'),True),('new-sensitive',B/'app-debug-androidTest.apk',False)]:
 out=C/name
 command=[sys.executable,'scripts/test-webview-engine.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--apk',str(C/'app-debug.apk'),'--test-apk',str(harness),'--output',str(out),'--cases','bridgeCannotPublishOrReadHostApisBeforeApproval','--font-scale','1','--layout','tablet']
 with (C/(name+'-host.log')).open('xb') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,ANDROID_HOME='<host-home>/Library/Android/sdk'),timeout=180)
 record=json.loads((out/'record.json').read_text());log=(out/'tests.log').read_text();case=json.loads((out/'captures/case-bridge-cannot-publish-or-read-host-apis-before-approval.json').read_text())
 row={'runner_exit_code':r.returncode,'runner_completed':record['completed'],'passes':record['passes'],'failures':record['failures'],'cleanup_checks':len(record['cleanup']),'cleanup_passed':all(v['passed'] for v in record['cleanup'].values()),'test_apk_sha256':record['test_apk_sha256'],'initialization_error':record.get('initialization_error',False),'preserved_files':record['preserved_file_count']}
 result['cohorts'][name]=row;save()
 assert row['cleanup_passed'] and row['preserved_files']==573 and not row['initialization_error']
 assert record['apk_sha256']==identity['apk_sha256']
 if insensitive:
  assert r.returncode==0 and record['completed'] and record['passes']==1 and record['failures']==0 and case['passed']
  row['insensitive_pass_on_regressed_guard']=True
 else:
  assert r.returncode!=0 and not record['completed'] and record['passes']==0 and record['failures']==1
  assert 'Supported fs.exists must remain blocked before approval' in log and 'NoClassDefFoundError' not in log
  assert case['supported_probe_operation']=='fs.exists' and case['owned_probe_file_exists'] and case['preflight_response_ok'] is True
  row['intended_supported_probe_assertion_failed']=True
 save()
result['completed']=True;save();print(json.dumps(result))
