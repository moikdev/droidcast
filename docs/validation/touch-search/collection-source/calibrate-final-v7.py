from pathlib import Path
import json,hashlib,re,xml.etree.ElementTree as ET
b=Path('artifacts/touch-clear');load=lambda p:json.loads(p.read_text());j=load(b/'native-final-v9.json');assert j['completed']
controls=[]
for row in j['cohorts']:
 if not row['expected_control']:continue
 name=row['name'];folder=b/name;r=load(folder/'record.json');log=(folder/'tests.log').read_text()
 assert r['passes']==0 and r['failures']==1 and not r['completed'] and r['test_apk_sha256']==j['test_apk_sha256']
 assert r['cleanup'] and all(v['passed'] for v in r['cleanup'].values())
 if name.startswith('final-old-'):
  tag='search-clear' if name.endswith('-1') else 'emoji-search-clear'
  assert 'TouchSearchDeviceTest.geometry' in log and 'TouchSearchDeviceTest.kt:107' in log
  xmls=list((folder/'captures').glob('*.xml'));assert xmls
  for p in xmls:assert not any(n.get('resource-id')==tag for n in ET.parse(p).getroot().iter('node'))
  assertion='Missing Clear display at geometry line107; ordinary pre-action app frame, no app error banner'
 else:
  assert 'Returning retains the search baseline' in log and '249.0' in log and '243.0' in log
  assertion='Six-pixel Emoji/launcher return-origin mismatch after successful pointer Clear, 200%'
 controls.append({'cohort':name,'passes':0,'intended_failures':1,'test_apk_sha256':r['test_apk_sha256'],'app_sha256':r['apk_sha256'],'test_log_sha256':hashlib.sha256((folder/'tests.log').read_bytes()).hexdigest(),'cleanup_checks':len(r['cleanup']),'assertion':assertion})
assert len(controls)==5
out=b/'final-controls-calibration.json';assert not out.exists()
out.write_text(json.dumps({'all_completed':True,'controls':controls,'scope':'Same final54a instrumentation on negative and repaired apps. Positive-only runner receipts remain failed; no generic failure classification earns control credit.'},indent=2)+'\n');print(json.dumps(controls,indent=2))
