from pathlib import Path
import json,os,subprocess,sys
b=Path('artifacts/compatibility-view/ux-repair-v6');owner='dev.tinycast.android.ExtensionRecordedTestsDeviceTest'
critical=','.join(owner+'#'+name for name in ['criticalTextAndTouchTargetsAreReachable','searchAndKeyboardRemainReachableAfterScrolling','weatherHistoricalIdentityMatchesTheRecordedApk','clearSearchShowsCloseAndRestoresResults','assetOnlyEditStaysUnknownAndOtherVersionsStayHistorical'])
controls=','.join(owner+'#'+name for name in ['clearSearchShowsCloseAndRestoresResults','assetOnlyEditStaysUnknownAndOtherVersionsStayHistorical'])
rows=[('ux-old-tablet100','tablet','1',controls,'2','ux-old'),('ux-old-phone100','phone','1',controls,'2','ux-old'),('native-tablet100','tablet','1',owner,'12',None),('native-phone100','phone','1',owner,'12',None),('critical-landscape100','landscape','1',critical,'5',None),('critical-tablet200','tablet','2',critical,'5',None),('critical-phone200','phone','2',critical,'5',None),('critical-landscape200','landscape','2',critical,'5',None)]
state={'pid':os.getpid(),'completed':False,'cohorts':[]};path=b/'native-pipeline-state.json';assert not path.exists()
def save():path.write_text(json.dumps(state,indent=2)+'\n')
save()
for name,layout,font,classes,expected,mode in rows:
 state['active']=name;save()
 args=[sys.executable,str(b/'run-cohort.py'),name,layout,font,classes,expected]+([mode] if mode else [])
 with (b/(name+'-pipeline.log')).open('x') as log:result=subprocess.run(args,stdout=log,stderr=subprocess.STDOUT)
 row={'name':name,'exit_code':result.returncode,'verified':result.returncode==0};state['cohorts'].append(row);save()
 if result.returncode:raise SystemExit('Stopped after '+name+'; inspect receipts before any dependent device action')
 print(name+' verified',flush=True)
state['completed']=True;state['active']=None;save()
