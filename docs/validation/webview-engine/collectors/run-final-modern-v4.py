from pathlib import Path
import os,subprocess,sys
base=Path('artifacts/webview-compatibility');root=base/'final-v4';root.mkdir(exist_ok=True)
core='modernEnginePublishesListAndRunsLiveAction,modernEnginePublishesGridAndRunsLiveAction,actualProviderParsesProbeWithoutRunningItsBodyAndRejectsSyntax,closingBeforePreflightFinishesRejectsLatePublication,bridgeCannotPublishOrReadHostApisBeforeApproval'
for name,cases,font,layout in [('modern-tablet100',core,1,'tablet'),('modern-phone200',','.join(core.split(',')[:2]),2,'phone'),('modern-landscape200',','.join(core.split(',')[:2]),2,'landscape')]:
 command=[sys.executable,'scripts/test-webview-engine.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--apk',str(base/'candidate-v4/app-debug.apk'),'--test-apk',str(base/'candidate-v4/app-debug-androidTest.apk'),'--output',str(root/name),'--cases',cases,'--font-scale',str(font),'--layout',layout]
 with (root/(name+'-host.log')).open('xb') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,ANDROID_HOME='<host-home>/Library/Android/sdk'))
 if r.returncode:raise SystemExit('Inspect failed cohort '+name)
 print(name,'completed',flush=True)
with (base/'shared-regressions-v2-host.log').open('xb') as log:r=subprocess.run([sys.executable,str(base/'run-shared-regressions-v2.py')],stdout=log,stderr=subprocess.STDOUT)
if r.returncode:raise SystemExit('Inspect shared regression failure')
print('shared regressions completed',flush=True)
