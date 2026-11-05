from pathlib import Path
import json,os,subprocess,sys,time
b=Path('artifacts/empty-feedback');candidate=b/'candidate-v3';identity=json.loads((candidate/'identity.json').read_text());app=candidate/'app-debug.apk';test=candidate/'app-debug-androidTest.apk'
old=Path('<user-home>/.t3/scratch/droidcast-touch-clear-27caf3/artifacts/touch-clear/candidate-v3/app-debug.apk')
feedback=['launcherEmptyFeedbackIsReadableAboveImeAndControlsRemainReachable','emojiEmptyFeedbackIsReadableAboveImeAndControlsRemainReachable'];touch=['launcherTouchClearPreservesFocusCategoryAndKeyboard','emojiTouchClearPreservesCategoryGeometryAndKeyboard'];guards=['pointerClearRemovesControlledCompositionAndWhitespace','retainedClearCallbacksRejectReplacedSurfacesAndMenus','currentClearUsesLiveQueryAndRecreationRejectsOldCallback'];reg=['committedEmojiNavigationAndCaretAreExact','activeMultilingualCompositionKeepsResultSelection']
plan=[('final-landscape200',app,'landscape',2,feedback,'touch-search',False,False),('final-landscape100',app,'landscape',1,feedback,'touch-search',False,False)]
for font in (1,2):plan.append((f'control-old-landscape{font}00',old,'landscape',font,feedback,'touch-search',False,True))
plan.append(('control-first-landscape200',b/'candidate-v1/app-debug.apk','landscape',2,feedback,'touch-search',False,True))
plan.append(('control-secondary-landscape100',b/'candidate-v2/app-debug.apk','landscape',1,feedback,'touch-search',False,True))
for layout,font in [('tablet',1),('tablet',2),('phone',1),('phone',2)]:plan.append((f'final-{layout}{font}00',app,layout,font,feedback,'touch-search',False,False))
plan.append(('final-guards-tablet100',app,'tablet',1,guards,'touch-search',False,False))
for layout in ('tablet','phone'):plan.append((f'final-clear-{layout}200',app,layout,2,touch,'touch-search',False,False))
for layout,legacy in [('phone',False),('phone',True)]:plan.append((f'final-input-{"api26" if legacy else layout}100',app,layout,1,reg,'native-input',legacy,False))
plan.append(('final-api26-phone100',app,'phone',1,feedback,'touch-search',True,False))
state=b/'native-v3.json';assert not state.exists();r={'completed':False,'source':identity['source_commit'],'apk_sha256':identity['apk_sha256'],'cohorts':[]}
def save():state.write_text(json.dumps(r,indent=2)+'\n')
save()
for name,binary,layout,font,cases,suite,legacy,negative in plan:
 out=b/name;assert not out.exists(),name
 command=[sys.executable,'scripts/test-webview-engine.py','--serial','emulator-5598' if legacy else 'emulator-5580','--avd','Droidcast_WebView_API26_27caf3' if legacy else 'Tinycast_Tablet_API_36','--apk',str(binary),'--test-apk',str(test),'--output',str(out),'--suite',suite,'--cases',','.join(cases),'--layout',layout,'--font-scale',str(font)]
 if suite=='native-input':command+=['--input-mode','owned']
 row={'name':name,'cases':cases,'expected_control':negative,'state':'running','started':time.time()};r['cohorts'].append(row);save()
 with (b/(name+'-outer.log')).open('xb') as log:result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,ANDROID_HOME='<android-sdk>'))
 receipt=json.loads((out/'record.json').read_text()) if (out/'record.json').is_file() else {}
 row.update(state='finished',finished=time.time(),exit_code=result.returncode);row.update({k:receipt.get(k) for k in ('passes','failures','completed','cleanup')});save();print(json.dumps({k:row[k] for k in ('name','exit_code','passes','failures','completed')}),flush=True)
 if negative:
  log=(out/'tests.log').read_text() if (out/'tests.log').exists() else ''
  expected = ('assertDoesNotExist' in log and 'emoji-count' in log and 'Try another name or category.' in log) if name.startswith('control-secondary-') else log.count('Primary empty feedback line 0 is fully visible')>=2
  if not(result.returncode!=0 and receipt.get('passes')==0 and receipt.get('failures')==2 and expected and receipt.get('cleanup') and all(v['passed'] for v in receipt['cleanup'].values())):raise SystemExit('Control did not complete the intended isolated failure; inspect before continuing')
 elif result.returncode or not receipt.get('completed'):raise SystemExit('Current cohort failed; retain and inspect before continuing')
r['completed']=True;save()
