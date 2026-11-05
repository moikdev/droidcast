from pathlib import Path
import hashlib,json
b=Path('artifacts/keyboard-toggle');load=lambda p:json.loads(p.read_text());state=load(b/'native-v4.json');assert state['completed'];out=b/'final-controls-calibration.json';assert not out.exists();controls=[]
for row in state['cohorts']:
 if not row['expected_control']:continue
 name=row['name'];folder=b/name;r=load(folder/'record.json');log=(folder/'tests.log').read_text();assert r['passes']==0 and r['failures']==2 and not r['completed'] and all(v['passed'] for v in r['cleanup'].values());assert r['test_apk_sha256']==state['apk_sha256']['app-debug-androidTest.apk'];proxy='proxy' in name;w=[]
 for method in r['methods']:
  p=folder/'captures'/(method.lower()+'-case.json');case=load(p);actions=[e for e in case['events'] if e['kind']=='keyboard-pointer-action']
  if proxy:
   frame=load(folder/'captures'/(method.lower()+'-keyboard-guard-obsolete-mode-frame.json'));assert len(actions)==3 and all(e['reached_expected_state'] for e in actions) and not frame['ime_visible'];assert not any(e['kind']=='keyboard-callback-rejected' for e in case['events'])
  else:assert len(actions)==2 and actions[0]['expected_visible'] and actions[0]['reached_expected_state'] and actions[1]['label_before']=='Show on-screen keyboard' and actions[1]['ime_before'] and actions[1]['ime_after'] and not actions[1]['expected_visible'] and not actions[1]['reached_expected_state']
  w.append({'method':method,'observed_pointer_actions':actions,'case_sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
 phrase='Obsolete or obstructed keyboard callback must not change visibility: keyboard-guard-obsolete-mode' if proxy else 'Header keyboard action must hide the visible IME';assert log.count(phrase)>=2;controls.append({'cohort':name,'kind':'published-action-proxy' if proxy else 'show-only-button','passes':0,'intended_failures':2,'expected_assertion':phrase,'test_apk_sha256':r['test_apk_sha256'],'app_sha256':r['apk_sha256'],'restoration_checks':len(r['cleanup']),'witnesses':w})
assert len(controls)==3
current=[]
for name in ['final-guards-tablet100','final-landscape100','final-landscape200','final-tablet100','final-tablet200']:
 folder=b/name;r=load(folder/'record.json');assert r['completed']
 for method in r['methods']:
  case=load(folder/'captures'/(method.lower()+'-case.json'));events=case['events'];denials=[e for e in events if e['kind']=='keyboard-callback-rejected'];accepted=[e for e in events if e['kind']=='current-keyboard-accessibility-action'];pointers=[e for e in events if e['kind']=='keyboard-pointer-action']
  if 'CallbacksReject' in method:assert len(denials)==5 and all(e['accepted'] is False for e in denials)
  if method.startswith('currentKeyboardAccessibility'):assert len(accepted)==6 and all(e['accepted'] and e['expected_visible']==e['actual_visible'] for e in accepted)
  assert all(e['reached_expected_state'] and e['expected_visible']==e['ime_after'] for e in pointers)
  current.append({'cohort':name,'method':method,'pointer_transitions':len(pointers),'rejected_owned_actions':len(denials),'accepted_current_accessibility_actions':len(accepted)})
out.write_text(json.dumps({'all_completed':True,'controls':controls,'current_protocol_counts':current,'scope':'Matched final instrumentation, exact functional assertions and actual IME states. Failed positive-only runner receipts remain failed. Accepted current semantics actions separately establish support; no app error banner, TalkBack gesture or physical queued-input claim.'},indent=2)+'\n');print('Calibrated six intended failures and current accepted/rejected action contracts')
