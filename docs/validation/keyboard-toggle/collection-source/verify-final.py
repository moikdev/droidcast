from pathlib import Path
import hashlib,json,os,re,subprocess,xml.etree.ElementTree as ET,zipfile
b=Path('artifacts/keyboard-toggle');load=lambda p:json.loads(p.read_text());sha=lambda x:hashlib.sha256(x).hexdigest();out=b/'final-verification.json';assert not out.exists();j=load(b/'final-harness/identity.json');head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip();assert head==j['source_commit']=='69bd578d528c42296ef4a10fa24d675a8f635408';old=load(b/'candidate-v3/identity.json')
for p,h in j['source_sha256'].items():assert sha(Path(p).read_bytes())==h and sha(subprocess.check_output(['git','show',head+':'+p]))==h,p
changed={p for p,h in j['source_sha256'].items() if old['source_sha256'].get(p)!=h};assert changed==set(j['changed_inputs_since_app_source'])=={'app/src/androidTest/java/dev/tinycast/android/TouchSearchDeviceTest.kt','scripts/test-webview-engine.py'}
for label,h in j['apk_sha256'].items():
 p=b/'final-harness'/label;assert sha(p.read_bytes())==h
 with zipfile.ZipFile(p) as z:assert {n:sha(z.read(n)) for n in z.namelist() if not n.endswith('/')}==j['zip_payload_sha256'][label]
assert (b/'final-harness/app-debug.apk').read_bytes()==(b/'candidate-v3/app-debug.apk').read_bytes()
analyzer='<android-sdk>/cmdline-tools/latest/bin/apkanalyzer';env=dict(os.environ,JAVA_HOME='<java-home>')
for name,label in [('app-debug.apk','app-manifest.xml'),('app-debug-androidTest.apk','test-manifest.xml')]:
 p=b/'final-harness'/label;assert not p.exists();data=subprocess.check_output([analyzer,'manifest','print',str(b/'final-harness'/name)],env=env,timeout=45);root=ET.fromstring(data);stamps=[e.attrib.get('{http://schemas.android.com/apk/res/android}value') for e in root.iter('meta-data') if 'commit' in e.attrib.get('{http://schemas.android.com/apk/res/android}name','').lower()];assert (stamps==[j['app_source']]) if name=='app-debug.apk' else not stamps;p.write_bytes(data)
# Retained JVM343 binds exact unchanged production, build and JVM-test sources.
report=load(b/'frozen-host-verification.json');assert report['jvm']['tests']==343 and all(report['jvm'][k]==0 for k in ['failures','errors','skipped'])
for p,h in j['source_sha256'].items():
 if p not in changed:assert h==old['source_sha256'][p]
assert (b/'host-final-harness.log').read_text().count('BUILD SUCCESSFUL')==2
lint={}
for variant in ['debug','benchmark']:
 p=Path(f'app/build/reports/lint-results-{variant}.xml');root=ET.fromstring(p.read_bytes());counts={s:sum(e.attrib.get('severity')==s for e in root.iter('issue')) for s in ['Error','Fatal','Warning']};assert counts['Error']==counts['Fatal']==0;lint[variant]=counts
python={}
for mode in ['normal','optimized']:
 p=b/f'python-final-{mode}.log';s=p.read_text();counts=re.findall(r'Ran (\d+) tests? in ([0-9.]+)s',s);assert counts and counts[-1][0]=='135' and re.search(r'^OK$',s,re.M);python[mode]={'cases':135,'seconds':float(counts[-1][1]),'log_sha256':sha(p.read_bytes()),'source_commit':head}
last=old['zip_payload_sha256']['app-debug-androidTest.apk'];current=j['zip_payload_sha256']['app-debug-androidTest.apk'];r={'current_source_commit':head,'source_hash_count':len(j['source_sha256']),'executed_app_source':j['app_source'],'executed_app_sha256':j['apk_sha256']['app-debug.apk'],'test_source':j['test_source'],'test_apk_sha256':j['apk_sha256']['app-debug-androidTest.apk'],'app_bytes_equal_frozen_f30':True,'only_changed_inputs':sorted(changed),'jvm_reuse':report['jvm'],'fresh_fixture_build_successes':2,'fresh_lint':lint,'fresh_python':python,'test_payload_changes':[p for p in sorted(set(last)|set(current)) if last.get(p)!=current.get(p)],'scope':'Current code/input hashes and final APKs verified. App is the exact archived f30 binary; only the fixture/controller change afterward. JVM343 is retained with production/build/JVM inputs unchanged, not rerun by this fixture-only build. No stamp-only equivalence is assumed.'};out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps({k:r[k] for k in ['source_hash_count','only_changed_inputs','fresh_lint','test_payload_changes']},indent=2))
