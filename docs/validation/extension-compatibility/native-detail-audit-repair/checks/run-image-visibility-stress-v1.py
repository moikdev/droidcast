from pathlib import Path
import hashlib,json,os,subprocess
B=Path('artifacts/markdown-detail');S=B/'image-visibility-stress-v1-state.json';assert not S.exists()
app=json.loads((B/'image-state-diagnostic-build-v1.json').read_text());test=json.loads((B/'image-visibility-stress-build-v2.json').read_text());assert app['completed'] and test['completed']
s=dict(completed=False,pid=os.getpid(),production_source_commit=app['implementation_commit'],test_source_commit=test['implementation_commit'],app_sha256=app['app_sha256'],test_apk_sha256=test['test_apk_sha256'],cohorts=[],scope='Six release/return transitions per case; semantic positioning is explicit setup. Unchanged diagnostic runtime, not a production repair claim.')
def save():S.write_text(json.dumps(s,indent=2)+'\n')
save()
for layout,font in [('landscape',1),('phone',2),('tablet',1)]:
 out=B/f'image-visibility-stress-v1-{layout}{font*100}';assert not out.exists()
 c=dict(layout=layout,font_scale=font,output=str(out),completed=False);s['cohorts'].append(c);save()
 command=['python3','scripts/test-reviewed-text-input.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--layout',layout,'--font-scale',str(font),'--output',str(out),'--apk',app['candidate_directory']+'/app.apk','--test-apk',test['candidate_directory']+'/test.apk','--json-archive','<checkout>/artifacts/extension-catalog/representative-001-bundles-v1/archives/223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip','--classes','dev.tinycast.android.ExtensionDetailDeviceTest#cachedBlockImageReturnsItsPixelsAfterRepeatedVisibilityChanges','--expected','1']
 with (B/(out.name+'-host.log')).open('x') as log:r=subprocess.run(command,env=dict(os.environ,ANDROID_HOME='<android-sdk>'),stdout=log,stderr=subprocess.STDOUT)
 c['exit_code']=r.returncode;save();assert (out/'record.json').exists(),'Inspect preflight before repeating mutation'
 h=json.loads((out/'record.json').read_text());c.update(completed=h['completed'],passes=h['passes'],cleanup_all_passed=bool(h['cleanup']) and all(x['passed'] for x in h['cleanup'].values()),cleanup_checks=len(h['cleanup']));save()
 print(json.dumps(c),flush=True)
 assert r.returncode==0 and c['completed'] and c['passes']==1 and c['cleanup_all_passed']
s['completed']=True;save()
