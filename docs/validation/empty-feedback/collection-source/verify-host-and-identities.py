from pathlib import Path
import hashlib,json,os,re,subprocess,xml.etree.ElementTree as ET,zipfile
b=Path('artifacts/empty-feedback');sha=lambda data:hashlib.sha256(data).hexdigest();load=lambda p:json.loads(p.read_text())
outputs=['host-checks-summary.json','source-and-asset-verification.json','test-payload-differences.json']
assert not any((b/n).exists() for n in outputs),'Inspect existing summaries before repeating'
source='6c5825bf0da60ad9b452ec4550aa49d60704e677';assert subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()==source
identities={v:load(b/v/'identity.json') for v in ('candidate-v1','candidate-v2','candidate-v3')}
for v,j in identities.items():
 for name,wanted in j['source_sha256'].items():
  assert sha(subprocess.check_output(['git','show',j['source_commit']+':'+name]))==wanted,(v,name)
  if v=='candidate-v3':assert sha(Path(name).read_bytes())==wanted,name
 for name,wanted in j['apk_sha256'].items():
  p=b/v/name;assert sha(p.read_bytes())==wanted
  with zipfile.ZipFile(p) as z:assert {n:sha(z.read(n)) for n in z.namelist() if not n.endswith('/')}==j['zip_payload_sha256'][name]
# Retain manifests decoded by the SDK, including each app's real source stamp.
env=dict(os.environ,JAVA_HOME='<user-home>/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home');analyzer='<android-sdk>/cmdline-tools/latest/bin/apkanalyzer'
for v,j in identities.items():
 for name,label in [('app-debug.apk','app-manifest.xml'),('app-debug-androidTest.apk','test-manifest.xml')]:
  dest=b/v/label;data=subprocess.check_output([analyzer,'manifest','print',str(b/v/name)],env=env,timeout=45);root=ET.fromstring(data)
  stamps=[e.attrib.get('{http://schemas.android.com/apk/res/android}value') for e in root.iter('meta-data') if 'commit' in e.attrib.get('{http://schemas.android.com/apk/res/android}name','').lower()]
  if name=='app-debug.apk':assert j['source_commit'] in stamps,(v,stamps)
  else:assert not stamps,(v,stamps)
  if dest.exists():assert dest.read_bytes()==data,(v,label)
  else:dest.write_bytes(data)
old=Path('<user-home>/.t3/scratch/droidcast-touch-clear-27caf3/artifacts/touch-clear/candidate-v3/app-debug.apk');assert sha(old.read_bytes())==identities['candidate-v3']['old_app']['sha256']
def entries(p):
 with zipfile.ZipFile(p) as z:return {n:sha(z.read(n)) for n in z.namelist() if not n.endswith('/')}
old_entries=entries(old);assets={n:h for n,h in old_entries.items() if n.startswith('assets/')};assert len(assets)==11
for v,j in identities.items():assert {n:h for n,h in j['zip_payload_sha256']['app-debug.apk'].items() if n.startswith('assets/')}==assets==j['unchanged_original_assets']
python_paths=[n for n in identities['candidate-v3']['source_sha256'] if n.startswith('scripts/') and n.endswith('.py')]
assert python_paths
for name in python_paths:assert len({j['source_sha256'][name] for j in identities.values()})==1,name
source_summary={'source_commit':source,'input_count':len(identities['candidate-v3']['source_sha256']),'all_current_inputs_match_git_and_disk':True,'all_historical_inputs_match_their_git_commits':True,'all_apk_payload_vectors_match':True,'all_app_manifest_stamps_match':True,'test_apks_have_no_independent_source_stamp':True,'python_source_paths':python_paths,'all_python_bytes_unchanged_since':'15eb95c1c8d91611d5cd099989bf67eaaf5ecaf3','original_asset_count':len(assets),'original_assets_unchanged_in_all_three_candidates':assets,'scope':'Source/Git/ZIP/manifest inspection; no stamp-only binary equivalence inferred. App source stamp is separate from instrumentation compilation/source/hash binding.'}
differences=[]
for earlier,later in [('old','candidate-v1'),('candidate-v1','candidate-v2'),('candidate-v2','candidate-v3')]:
 for kind in ('app-debug.apk','app-debug-androidTest.apk'):
  if earlier=='old' and kind.endswith('androidTest.apk'):continue
  a=old_entries if earlier=='old' else identities[earlier]['zip_payload_sha256'][kind];c=identities[later]['zip_payload_sha256'][kind]
  differences.append({'from':earlier,'to':later,'apk':kind,'changed_payloads':[n for n in sorted(set(a)|set(c)) if a.get(n)!=c.get(n)],'added':[n for n in c if n not in a],'removed':[n for n in a if n not in c]})
payload_summary={'differences':differences,'scope':'Actual uncompressed ZIP payload differences; signed APK bytes and DEX cohorts remain distinct. No stamp-only reconstruction or equivalence is claimed.'}
xmls=sorted(Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'));assert xmls
counts={k:0 for k in ('tests','failures','errors','skipped')}
for p in xmls:
 root=ET.fromstring(p.read_bytes())
 for k in counts:counts[k]+=int(root.attrib.get(k,0))
assert counts=={'tests':343,'failures':0,'errors':0,'skipped':0},counts
lint={}
for variant in ('debug','benchmark'):
 p=Path(f'app/build/reports/lint-results-{variant}.xml');root=ET.fromstring(p.read_bytes());values={s:sum(e.attrib.get('severity')==s for e in root.iter('issue')) for s in ('Error','Fatal','Warning')};assert values=={'Error':0,'Fatal':0,'Warning':56},values;lint[variant]=values
log=(b/'host-v3.log').read_text();assert log.count('BUILD SUCCESSFUL')==2
lines=[line for line in log.splitlines() if line.startswith('> Task :app:testDebugUnitTest')];assert lines==['> Task :app:testDebugUnitTest'],lines
py={}
for mode,name in [('normal','python-v1.log'),('optimized','python-v1-opt.log')]:
 data=(b/name).read_text();matches=re.findall(r'Ran (\d+) tests? in ([0-9.]+)s',data);assert matches and matches[-1][0]=='135' and re.search(r'^OK$',data,re.M),name
 py[mode]={'cases':135,'seconds':float(matches[-1][1]),'result':'passed','source_commit':'15eb95c1c8d91611d5cd099989bf67eaaf5ecaf3','log_sha256':sha((b/name).read_bytes()),'reuse':'All Python inputs byte-identical through final source; no later Python rerun claimed.'}
host={'build_source_commit':source,'debug_and_test_build':'success','benchmark_and_test_build':'success','host_log_sha256':sha((b/'host-v3.log').read_bytes()),'jvm':dict(counts,task_line=lines[0],task_state='executed',report_files=len(xmls)),'lint':lint,'python':py,'scope':'Actual local source-bound checks and retained exact-source Python receipts. Hosted exact-head Android CI and independent audits remain separate gates.'}
for name,j in zip(outputs,[host,source_summary,payload_summary]):(b/name).write_text(json.dumps(j,indent=2)+'\n')
print(json.dumps({'source_inputs':source_summary['input_count'],'python_inputs':len(python_paths),'jvm':counts,'lint':lint,'assets':len(assets),'payload_differences':differences},indent=2))
