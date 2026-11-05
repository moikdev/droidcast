from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys
base=Path('artifacts/compatibility-view');b=base/'ux-repair-v6'
spec=importlib.util.spec_from_file_location('preservation',base/'preservation.py');p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
name,layout,font,classes,expected=sys.argv[1:6];old=len(sys.argv)==7 and sys.argv[6]=='ux-old'
out=b/name;assert not out.exists(),'Inspect existing output before any retry'
identity=json.loads((b/'candidate/identity.json').read_text())
assert all(hashlib.sha256(Path(path).read_bytes()).hexdigest()==digest for path,digest in identity['sources'].items())
archives=Path('<home>/.t3/scratch/droidcast-command-catalog-27caf3/artifacts/extension-catalog/representative-001-bundles-v1/archives')
app=base/'repair-v2/candidate/app-debug.apk' if old else b/'candidate/app-debug.apk'
before=p.verify()
cmd=[sys.executable,'scripts/test-reviewed-text-input.py','--serial','emulator-5580','--avd','Tinycast_Tablet_API_36','--layout',layout,'--font-scale',font,'--output',str(out),'--apk',str(app),'--test-apk',str(b/'candidate/app-debug-androidTest.apk'),'--json-archive',str(archives/'223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip'),'--recorded-archives',str(archives),'--classes',classes,'--expected',expected]
with (b/(name+'.log')).open('x') as log:result=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,ANDROID_HOME='<home>/Library/Android/sdk'))
assert (out/'record.json').exists(),'Inspect preflight before repeating mutation'
r=json.loads((out/'record.json').read_text());assert r['cleanup'] and all(x['passed'] for x in r['cleanup'].values()),'Cleanup failed; retain recovery owners'
after=p.verify();assert before==after
proof=dict(before=before,after=after,passed=True);(out/'preservation.json').write_text(json.dumps(proof,indent=2)+'\n')
log=(out/'tests.log').read_text()
if old:
 assert result.returncode!=0 and r['passes']==0 and 'Clear search must draw a close glyph' in log and 'No installed commands match this search.' in log and 'NoClassDefFoundError' not in log,'Not both intended UX controls'
 assert len(list((out/'captures').glob('*-clear-search-control.png')))==1 and len(list((out/'captures').glob('*-other-source-empty-message.png')))==1
 pixel=json.loads((out/'captures'/'recorded-clearsearchshowscloseandrestoresresults-record.json').read_text())['clear_glyph_pixels']
 assert pixel['diagonal_ink']==[0,0,0,0] and all(x>0 for x in pixel['axis_ink']),'Uncalibrated old glyph control'
else:assert result.returncode==0 and r['completed'] and r['passes']==int(expected),'Positive cohort failed; inspect retained receipts'
print(json.dumps(dict(name=name,passes=r['passes'],completed=r['completed'],intended_negative=old,cleanup_checks=len(r['cleanup']),preservation=proof)),flush=True)
