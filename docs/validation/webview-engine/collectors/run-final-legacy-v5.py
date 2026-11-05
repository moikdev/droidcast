from pathlib import Path
import json,os,subprocess,sys
base=Path('artifacts/webview-compatibility');ident=json.loads((base/'candidate-v5/identity.json').read_text());root=base/'final-v5';root.mkdir(exist_ok=True)
core='incompatibleEngineShowsNativeRecoveryWithoutDispatch,incompatibleEngineOpensOnlyActualProviderSettingsAndReturns,actualProviderParsesProbeWithoutRunningItsBodyAndRejectsSyntax,closingBeforePreflightFinishesRejectsLatePublication,bridgeCannotPublishOrReadHostApisBeforeApproval,replacedRecoveryRejectsRetainedSettingsAndReturnCallbacks,equivalentFailureGetsFreshRecoveryActionsBeforeRecomposition'
old=Path('<host-home>/.t3/scratch/droidcast-compatibility-view-27caf3/artifacts/compatibility-view/ux-repair-v6/candidate/app-debug.apk')
for name,cases,font,apk,negative in [('legacy100',core,1,base/'candidate-v5/app-debug.apk',False),('legacy200',','.join(core.split(',')[:2]),2,base/'candidate-v5/app-debug.apk',False),('legacy-old-control','incompatibleEngineShowsNativeRecoveryWithoutDispatch',1,old,True)]:
 command=[sys.executable,'scripts/test-webview-engine.py','--serial','emulator-5598','--avd','Droidcast_WebView_API26_27caf3','--apk',str(apk),'--test-apk',str(base/'candidate-v5/app-debug-androidTest.apk'),'--output',str(root/name),'--cases',cases,'--font-scale',str(font),'--layout','phone']+(['--expected-negative'] if negative else [])
 with (root/(name+'-host.log')).open('xb') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,ANDROID_HOME='<host-home>/Library/Android/sdk'))
 if r.returncode:raise SystemExit('Inspect failed cohort '+name)
 print(name,'completed',flush=True)
