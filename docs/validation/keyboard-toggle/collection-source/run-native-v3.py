from pathlib import Path
import hashlib,json,os,subprocess,sys,time
b=Path('artifacts/keyboard-toggle');candidate=b/'candidate-v3';identity=json.loads((candidate/'identity.json').read_text());app=candidate/'app-debug.apk';test=candidate/'app-debug-androidTest.apk';old=Path('<previous-checkout>/artifacts/empty-feedback/candidate-v3/app-debug.apk');sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
assert sha(app)==identity['apk_sha256']['app-debug.apk'] and sha(test)==identity['apk_sha256']['app-debug-androidTest.apk'] and sha(old)==identity['old_app']['sha256'];assert hashlib.sha256(Path('scripts/test-webview-engine.py').read_bytes()).hexdigest()==identity['source_sha256']['scripts/test-webview-engine.py']
toggle=['launcherKeyboardToggleRevealsResultsAndPreservesSearch','emojiKeyboardToggleRevealsResultsAndPreservesSearch'];guards=['launcherKeyboardCallbacksRejectObsoleteModesOwnersAndMenus','emojiKeyboardCallbacksRejectObsoleteModesOwnersAndMenus'];feedback=['launcherEmptyFeedbackIsReadableAboveImeAndControlsRemainReachable','emojiEmptyFeedbackIsReadableAboveImeAndControlsRemainReachable'];clear=['launcherTouchClearPreservesFocusCategoryAndKeyboard','emojiTouchClearPreservesCategoryGeometryAndKeyboard'];core=['pointerClearRemovesControlledCompositionAndWhitespace','retainedClearCallbacksRejectReplacedSurfacesAndMenus','currentClearUsesLiveQueryAndRecreationRejectsOldCallback'];inputs=['committedEmojiNavigationAndCaretAreExact','activeMultilingualCompositionKeepsResultSelection']
intermediate=b/'candidate-v1/app-debug.apk'
plan=[('candidate-v3-guards-tablet100',app,'tablet',1,guards,'touch-search',False,False),('candidate-v3-landscape200',app,'landscape',2,toggle,'touch-search',False,False),('control-proxy-v3-tablet100',intermediate,'tablet',1,guards,'touch-search',False,True)]
for font in (2,1):plan.append((f'control-old-v3-landscape{font}00',old,'landscape',font,toggle,'touch-search',False,True))
for layout,font in [('landscape',1),('tablet',1),('tablet',2)]:plan.append((f'candidate-v3-{layout}{font}00',app,layout,font,toggle,'touch-search',False,False))
for font in (1,2):plan.append((f'candidate-v3-feedback-landscape{font}00',app,'landscape',font,feedback,'touch-search',False,False))
plan += [('candidate-v3-clear-phone200',app,'phone',2,clear,'touch-search',False,False),('candidate-v3-core-guards-tablet100',app,'tablet',1,core,'touch-search',False,False),('candidate-v3-input-phone100',app,'phone',1,inputs,'native-input',False,False),('candidate-v3-input-api26100',app,'phone',1,inputs,'native-input',True,False)]
state=b/'native-v3.json';assert not state.exists();r={'completed':False,'source':identity['source_commit'],'apk_sha256':identity['apk_sha256'],'cohorts':[]}
def save():state.write_text(json.dumps(r,indent=2)+'\n')
save()
for name,binary,layout,font,methods,suite,legacy,negative in plan:
 folder=b/name;assert not folder.exists(),name
 command=[sys.executable,'scripts/test-webview-engine.py','--serial','emulator-5598' if legacy else 'emulator-5580','--avd','Droidcast_WebView_API26_27caf3' if legacy else 'Tinycast_Tablet_API_36','--apk',str(binary),'--test-apk',str(test),'--output',str(folder),'--suite',suite,'--cases',','.join(methods),'--layout',layout,'--font-scale',str(font)]
 if suite=='native-input':command+=['--input-mode','owned']
 row={'name':name,'methods':methods,'expected_control':negative,'state':'running','started':time.time()};r['cohorts'].append(row);save()
 with (b/(name+'-outer.log')).open('xb') as log:result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,ANDROID_HOME='<android-sdk>'))
 record=json.loads((folder/'record.json').read_text()) if (folder/'record.json').exists() else {};row.update(state='finished',finished=time.time(),exit_code=result.returncode);row.update({k:record.get(k) for k in ('passes','failures','completed','cleanup')});save()
 valid=bool(record.get('cleanup')) and all(v['passed'] for v in record['cleanup'].values())
 if negative:
  log=(folder/'tests.log').read_text() if (folder/'tests.log').exists() else ''
  proxy=name.startswith('control-proxy-')
  phrase='Obsolete or obstructed keyboard callback must not change visibility: keyboard-guard-obsolete-mode' if proxy else 'Header keyboard action must hide the visible IME'
  valid=valid and result.returncode!=0 and record.get('passes')==0 and record.get('failures')==2 and log.count(phrase)>=2
  if valid:
   for method in methods:
    case=json.loads((folder/'captures'/(method.lower()+'-case.json')).read_text());actions=[e for e in case['events'] if e['kind']=='keyboard-pointer-action']
    if proxy:
     frame=json.loads((folder/'captures'/(method.lower()+'-keyboard-guard-obsolete-mode-frame.json')).read_text());valid=valid and len(actions)==3 and all(e['reached_expected_state'] for e in actions) and not frame['ime_visible'] and not any(e['kind']=='keyboard-callback-rejected' for e in case['events'])
    else:valid=valid and len(actions)==2 and actions[0]['expected_visible'] and actions[0]['reached_expected_state'] and actions[1]['label_before']=='Show on-screen keyboard' and actions[1]['ime_before'] and actions[1]['ime_after'] and not actions[1]['expected_visible'] and not actions[1]['reached_expected_state']

 else:valid=valid and result.returncode==0 and record.get('completed') and record.get('passes')==len(methods) and record.get('failures')==0
 print(json.dumps({'name':name,'passes':record.get('passes'),'failures':record.get('failures'),'accepted_protocol':bool(valid),'checks':len(record.get('cleanup',{}))}),flush=True)
 if not valid:raise SystemExit('Native cohort failed its exact contract; retain and inspect before continuing')
r['completed']=True;save()
