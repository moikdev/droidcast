from pathlib import Path
import hashlib,json,os,subprocess,sys,time
b=Path('artifacts/touch-clear');h=b/'final-harness-v7';identity=json.loads((h/'identity.json').read_text());app=b/'candidate-v3/app-debug.apk';test=h/'app-debug-androidTest.apk'
methods=['launcherTouchClearPreservesFocusCategoryAndKeyboard','emojiTouchClearPreservesCategoryGeometryAndKeyboard','pointerClearRemovesControlledCompositionAndWhitespace','retainedClearCallbacksRejectReplacedSurfacesAndMenus','currentClearUsesLiveQueryAndRecreationRejectsOldCallback'];regression=['committedEmojiNavigationAndCaretAreExact','activeMultilingualCompositionKeepsResultSelection']
old=Path('<user-home>/.t3/scratch/droidcast-api26-input-27caf3/artifacts/native-input/owned-v5/app-debug.apk');spacing=b/'candidate-v2/app-debug.apk'
plan=[]
# Complete actual Clear flows in the previously constrained conditions first.
for layout,font in [('phone',2)]:plan.append((f'verified-{layout}{font}00-after-launcher-anr',app,layout,font,methods[:2],'touch-search',False,False))
plan += [('verified-guards-tablet100',app,'tablet',1,methods[2:],'touch-search',False,False),('verified-api26-phone100',app,'phone',1,methods,'touch-search',True,False)]
for layout,legacy in [('tablet',False),('phone',False),('phone',True)]:plan.append((f'verified-regression-{"api26" if legacy else layout}100',app,layout,1,regression,'native-input',legacy,False))
for layout in ('tablet','phone'):
 for i,method in enumerate(methods[:2]):plan.append((f'final-old-{layout}100-{i+1}',old,layout,1,[method],'touch-search',False,True))
plan.append(('final-spacing-control-tablet200',spacing,'tablet',2,[methods[1]],'touch-search',False,True))
prior=json.loads((b/'native-final-v7.json').read_text());assert not prior['completed'];assert prior['cohorts'][-1]['name']=='verified-phone200' and prior['cohorts'][-1]['failures']==2
assert json.loads((b/'launcher-anr-recovery.json').read_text())['completed']
retained=prior['cohorts'][:-1];assert len(retained)==5 and all(x['completed'] and x['failures']==0 for x in retained)
state=b/'native-final-v8.json';assert not state.exists()
r={'app_source':identity['app_source'],'test_source':identity['test_source'],'controller_source':identity['controller_source'],'app_sha256':identity['app_sha256'],'test_apk_sha256':identity['test_apk_sha256'],'completed':False,'retained_from':'native-final-v7 first five whole unchanged-APK cohorts','excluded_cohort':'verified-phone200: external Pixel Launcher ANR, 0/2 remains failed','cohorts':[dict(x,retained_from='native-final-v7') for x in retained]}
def save():state.write_text(json.dumps(r,indent=2)+'\n')
save()
for name,binary,layout,font,cases,suite,legacy,negative in plan:
 out=b/name;assert not out.exists(),name
 command=[sys.executable,'scripts/test-webview-engine.py','--serial','emulator-5598' if legacy else 'emulator-5580','--avd','Droidcast_WebView_API26_27caf3' if legacy else 'Tinycast_Tablet_API_36','--apk',str(binary),'--test-apk',str(test),'--output',str(out),'--suite',suite,'--cases',','.join(cases),'--layout',layout,'--font-scale',str(font)]
 if suite=='native-input':command+=['--input-mode','owned']
 row={'name':name,'cases':cases,'expected_control':negative,'state':'running','started':time.time()};r['cohorts'].append(row);save()
 with (b/(name+'-outer.log')).open('xb') as log:result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,ANDROID_HOME='<android-sdk>'))
 receipt=json.loads((out/'record.json').read_text()) if (out/'record.json').is_file() else {}
 row.update(exit_code=result.returncode,state='finished',finished=time.time());row.update({k:receipt.get(k) for k in ('passes','failures','completed','cleanup')});save();print(json.dumps({k:row[k] for k in ('name','exit_code','passes','failures','completed')}),flush=True)
 if negative:
  if result.returncode==0 or receipt.get('passes')!=0 or receipt.get('failures')!=1 or not receipt.get('cleanup',{}).get('release-owner-last',{}).get('passed') or not all(v.get('passed') for v in receipt.get('cleanup',{}).values()):raise SystemExit('Control did not complete its intended isolated failure; inspect before continuing')
 elif result.returncode or not receipt.get('completed'):raise SystemExit('Current cohort failed; retain and inspect before continuing')
r['completed']=True;save()
