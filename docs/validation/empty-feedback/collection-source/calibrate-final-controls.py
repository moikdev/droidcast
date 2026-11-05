from pathlib import Path
import hashlib,json
b=Path('artifacts/empty-feedback');load=lambda p:json.loads(p.read_text());j=load(b/'native-v3.json');assert j['completed'];out=b/'final-controls-calibration.json';assert not out.exists();controls=[]
for row in j['cohorts']:
 if not row['expected_control']:continue
 name=row['name'];folder=b/name;r=load(folder/'record.json');log=(folder/'tests.log').read_text();assert r['passes']==0 and r['failures']==2 and not r['completed'] and all(x['passed'] for x in r['cleanup'].values());assert r['test_apk_sha256']==j['apk_sha256']['app-debug-androidTest.apk']
 expected='compact-metadata-absence' if 'secondary' in name else 'complete-primary-line'
 witnesses=[]
 for method in r['methods']:
  case=load(folder/'captures'/(method.lower()+'-case.json'));shown=[e for e in case['events'] if e.get('kind')=='natural-feedback-line' and e['phase']=='initial-shown'];assert shown
  if expected=='complete-primary-line':assert any(not e['complete'] for e in shown)
  else:assert all(e['complete'] for e in shown) and not any(e.get('kind')=='compact-primary-status-only' for e in case['events'])
  witnesses.append({'case':method,'shown_primary_lines':shown,'case_receipt_sha256':hashlib.sha256((folder/'captures'/(method.lower()+'-case.json')).read_bytes()).hexdigest()})
 if expected=='complete-primary-line':assert log.count('Primary empty feedback line 0 is fully visible')>=2
 else:assert 'assertDoesNotExist' in log and 'emoji-count' in log and 'Try another name or category.' in log
 controls.append({'cohort':name,'passes':0,'intended_failures':2,'expected_assertion':expected,'app_sha256':r['apk_sha256'],'test_apk_sha256':r['test_apk_sha256'],'log_sha256':hashlib.sha256((folder/'tests.log').read_bytes()).hexdigest(),'restoration_checks':len(r['cleanup']),'witnesses':witnesses})
assert len(controls)==4
out.write_text(json.dumps({'all_completed':True,'controls':controls,'scope':'Common final test APK, failed positive-only runner receipts and exact intended assertions. Ordinary app/clipping frames, no app error banner; no generic failure is accepted as a control.'},indent=2)+'\n');print('Calibrated4 cohorts /8 intended failures /zero passes')
