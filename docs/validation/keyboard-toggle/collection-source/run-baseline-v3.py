from pathlib import Path
import hashlib,json,os,subprocess,sys,time
b=Path('artifacts/keyboard-toggle');identity=json.loads((b/'baseline-harness-v3/identity.json').read_text());source=identity['test_source'];assert subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()==source;assert not subprocess.check_output(['git','status','--porcelain'])
app=Path('<previous-checkout>/artifacts/empty-feedback/candidate-v3/app-debug.apk');test=b/'baseline-harness-v3/app-debug-androidTest.apk';sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest();assert sha(app)==identity['old_app_sha256'] and sha(test)==identity['test_apk_sha256']
methods=['launcherKeyboardToggleRevealsResultsAndPreservesSearch','emojiKeyboardToggleRevealsResultsAndPreservesSearch'];out=b/'baseline-controls-v3.json';assert not out.exists();state={'completed':False,'test_source':source,'app_sha256':sha(app),'test_apk_sha256':sha(test),'cohorts':[]}
def save():out.write_text(json.dumps(state,indent=2)+'\n')
save()
for font in (2,1):
 name=f'baseline-v3-old-landscape{font}00';folder=b/name;assert not folder.exists();row={'name':name,'state':'running','started':time.time()};state['cohorts'].append(row);save()
 command=[sys.executable,'scripts/test-webview-engine.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--apk',str(app),'--test-apk',str(test),'--output',str(folder),'--suite','touch-search','--cases',','.join(methods),'--layout','landscape','--font-scale',str(font)]
 with (b/(name+'-outer.log')).open('xb') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,ANDROID_HOME='<android-sdk>'))
 record=json.loads((folder/'record.json').read_text()) if (folder/'record.json').exists() else {};row.update(state='finished',exit_code=r.returncode,finished=time.time());row.update({k:record.get(k) for k in ('passes','failures','completed','cleanup')});save()
 log=(folder/'tests.log').read_text() if (folder/'tests.log').exists() else '';valid=r.returncode!=0 and record.get('passes')==0 and record.get('failures')==2 and log.count('Header keyboard action must hide the visible IME')>=2 and record.get('cleanup') and all(v['passed'] for v in record['cleanup'].values())
 if valid:
  for method in methods:
   case=json.loads((folder/'captures'/(method.lower()+'-case.json')).read_text());actions=[e for e in case['events'] if e['kind']=='keyboard-pointer-action'];valid=valid and len(actions)==2 and actions[0]['expected_visible'] and actions[0]['reached_expected_state'] and actions[1]['label_before']=='Show on-screen keyboard' and actions[1]['ime_before'] and actions[1]['ime_after'] and not actions[1]['expected_visible'] and not actions[1]['reached_expected_state']
 print(json.dumps({'name':name,'passes':record.get('passes'),'failures':record.get('failures'),'intended_functional_failure':bool(valid),'cleanup_checks':len(record.get('cleanup',{}))}),flush=True)
 if not valid:raise SystemExit('Baseline did not establish the intended functional failure; retain and inspect')
state['completed']=True;save()
