from pathlib import Path
import hashlib,json,os,re,subprocess,xml.etree.ElementTree as ET,zipfile
b=Path('artifacts/keyboard-toggle');sha=lambda x:hashlib.sha256(x).hexdigest();load=lambda p:json.loads(p.read_text());out=b/'frozen-host-verification.json';assert not out.exists()
identities={v:load(b/v/'identity.json') for v in ['candidate-v1','candidate-v2','candidate-v3']}
for name,j in identities.items():
 for p,h in j['source_sha256'].items():assert sha(subprocess.check_output(['git','show',j['source_commit']+':'+p]))==h,(name,p)
 for f,h in j['apk_sha256'].items():
  p=b/name/f;assert sha(p.read_bytes())==h
  with zipfile.ZipFile(p) as z:assert {n:sha(z.read(n)) for n in z.namelist() if not n.endswith('/')}==j['zip_payload_sha256'][f]
for name in ['baseline-harness','baseline-harness-v3']:
 j=load(b/name/'identity.json')
 for p,h in j['source_sha256'].items():assert sha(subprocess.check_output(['git','show',j['test_source']+':'+p]))==h,(name,p)
 p=b/name/'app-debug-androidTest.apk';assert sha(p.read_bytes())==j['test_apk_sha256']
 with zipfile.ZipFile(p) as z:assert {n:sha(z.read(n)) for n in z.namelist() if not n.endswith('/')}==j['test_zip_payload_sha256']
 # Both baseline harnesses compile against production/build source equal to the old reviewed app source.
 for p,h in j['source_sha256'].items():
  if p.startswith(('app/src/main/','gradle/')) or p in ('app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew'):assert sha(subprocess.check_output(['git','show','6c5825bf0da60ad9b452ec4550aa49d60704e677:'+p]))==h,p
final=identities['candidate-v3'];assert final['source_commit']=='f30bebd6c414307a5b5fe690d80dfffe5fc58ccc'
for p,h in final['source_sha256'].items():assert sha(Path(p).read_bytes())==h,p
prod=[p for p in final['source_sha256'] if p.startswith(('app/src/main/','gradle/')) or p in ('app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew')]
for p in prod:assert final['source_sha256'][p]==identities['candidate-v2']['source_sha256'][p],p
python=[p for p in final['source_sha256'] if p.startswith('scripts/') and p.endswith('.py')]
for p in python:assert len({j['source_sha256'][p] for j in identities.values()})==1,p
assets=final['unchanged_original_assets'];assert len(assets)==11
for j in identities.values():assert j['unchanged_original_assets']==assets
analyzer='<android-sdk>/cmdline-tools/latest/bin/apkanalyzer';env=dict(os.environ,JAVA_HOME='<java-home>')
for name,j in identities.items():
 for f,label in [('app-debug.apk','app-manifest.xml'),('app-debug-androidTest.apk','test-manifest.xml')]:
  p=b/name/label;assert not p.exists();data=subprocess.check_output([analyzer,'manifest','print',str(b/name/f)],env=env,timeout=45);root=ET.fromstring(data);stamps=[e.attrib.get('{http://schemas.android.com/apk/res/android}value') for e in root.iter('meta-data') if 'commit' in e.attrib.get('{http://schemas.android.com/apk/res/android}name','').lower()]
  assert (stamps==[j['source_commit']]) if f=='app-debug.apk' else not stamps
  p.write_bytes(data)
for name in ['baseline-harness','baseline-harness-v3']:
 p=b/name/'test-manifest.xml';assert not p.exists();data=subprocess.check_output([analyzer,'manifest','print',str(b/name/'app-debug-androidTest.apk')],env=env,timeout=45);root=ET.fromstring(data);assert not [e for e in root.iter('meta-data') if 'commit' in e.attrib.get('{http://schemas.android.com/apk/res/android}name','').lower()];p.write_bytes(data)
reports=sorted(Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'));jvm={k:0 for k in ['tests','failures','errors','skipped']}
for p in reports:
 r=ET.fromstring(p.read_bytes())
 for k in jvm:jvm[k]+=int(r.attrib.get(k,0))
assert jvm=={'tests':343,'failures':0,'errors':0,'skipped':0},jvm
lint={}
for variant in ['debug','benchmark']:
 r=ET.fromstring(Path(f'app/build/reports/lint-results-{variant}.xml').read_bytes());counts={s:sum(e.attrib.get('severity')==s for e in r.iter('issue')) for s in ['Error','Fatal','Warning']};assert counts['Error']==counts['Fatal']==0;lint[variant]=counts
log=(b/'host-v3.log').read_text();assert log.count('BUILD SUCCESSFUL')==2;task=[s for s in log.splitlines() if s.startswith('> Task :app:testDebugUnitTest')];assert len(task)==1
py={}
for mode in ['normal','optimized']:
 p=b/f'python-v1-{mode}.log';s=p.read_text();matches=re.findall(r'Ran (\d+) tests? in ([0-9.]+)s',s);assert matches and matches[-1][0]=='135' and re.search(r'^OK$',s,re.M);py[mode]={'cases':135,'seconds':float(matches[-1][1]),'source_commit':'1355f84cb4f3cc93ae732a5ae9028c42747f0f8e','log_sha256':sha(p.read_bytes())}
differences=[]
for earlier,later in [('candidate-v1','candidate-v2'),('candidate-v2','candidate-v3')]:
 for kind in ['app-debug.apk','app-debug-androidTest.apk']:
  a=identities[earlier]['zip_payload_sha256'][kind];c=identities[later]['zip_payload_sha256'][kind];differences.append({'from':earlier,'to':later,'apk':kind,'changed_payloads':[p for p in sorted(set(a)|set(c)) if a.get(p)!=c.get(p)]})
r={'app_build_source':final['source_commit'],'source_count':len(final['source_sha256']),'historical_vectors_and_apk_payloads_match':True,'production_build_sources_equal':'411b7f73ffea57b15ffa1c5ff01a468506495467','production_build_input_count':len(prod),'unchanged_python_source_count':len(python),'python_reuse':py,'unchanged_assets':assets,'manifest_stamps_match':True,'test_manifests_have_no_independent_source_stamp':True,'jvm':dict(jvm,task_line=task[0],report_files=len(reports)),'lint':lint,'build_successes':2,'zip_differences':differences,'scope':'Source/Git/ZIP/manifest inspection and actual host reports. Candidate app binaries remain distinct; no stamp-only reconstruction/equivalence is inferred. Python receipts reuse exact unchanged inputs.'}
out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps({k:r[k] for k in ['source_count','production_build_input_count','unchanged_python_source_count','jvm','lint','zip_differences']},indent=2))
