from pathlib import Path
import hashlib,json,re,xml.etree.ElementTree as ET
b=Path('artifacts/empty-feedback');d=Path('docs/validation/empty-feedback');images=Path('docs/screenshots/empty-feedback');load=lambda p:json.loads(p.read_text());sha=lambda x:hashlib.sha256(x).hexdigest()
def require(v,msg):
 if not v:raise ValueError(msg)
final=load(b/'native-v3.json');require(final['completed'],'Final pipeline incomplete');require(load(b/'final-preservation.json')['all_passed'],'Final preservation incomplete');require(load(b/'final-controls-calibration.json')['all_completed'],'Controls uncalibrated');require((b/'host-v3.log').read_text().count('BUILD SUCCESSFUL')==2,'Host checks incomplete');require(not d.exists() and not images.exists(),'Publication exists; inspect rather than overwrite');d.mkdir(parents=True);images.mkdir(parents=True)
bindings=[];projections=[];retained=[];selected=[]
def put(source,target,mode='identity'):
 raw=source.read_bytes();out=raw
 if mode!='identity':
  s=raw.decode();xml=mode=='redact-xml'
  def token(x):return '&lt;'+x+'&gt;' if xml else '<'+x+'>'
  for prefix,label in [(str(Path.cwd()),'checkout'),(str(Path.home()/'Library/Android/sdk'),'android-sdk'),(str(Path.home()/'.gradle'),'gradle-cache'),(str(Path.home()),'user-home')]:s=s.replace(prefix,token(label))
  s=re.sub(r'/(?:private/)?var/folders/[^\s\"\x27<>&]+',lambda _:token('temporary-dir'),s);s=re.sub(r'(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])',lambda _:token('owned-id'),s);out=s.encode()
 target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(out)
 if target.suffix=='.xml':ET.fromstring(out)
 bindings.append({'original':str(source),'original_sha256':sha(raw),'public':str(target),'public_sha256':sha(out),'transformation':mode if out!=raw else 'identity'})
fields=('completed','expected_negative','suite','input_mode','runner_sha256','apk_sha256','test_apk_sha256','methods','api','font_scale','layout','cleanup','passes','failures','native_expected_outcome','instrumentation_exit_code','initialization_error','provider_before','original_geometry','original_font_scale','captures','preserved_file_count','ime_restoration_scope','ime_restore_observations')
def project(source,target):
 raw=source.read_bytes();r=json.loads(raw);j={k:r[k] for k in fields if k in r};target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(j,indent=2)+'\n');projections.append({'original':str(source),'original_sha256':sha(raw),'public':str(target),'public_sha256':sha(target.read_bytes()),'allowed_fields':list(j)});return r
hist=[r['name'] for f in ('native-v1.json','native-v2.json') for r in load(b/f)['cohorts']];current=[r['name'] for r in final['cohorts']];names=hist+current;require(len(names)==len(set(names)),'Duplicate cohort')
stop=load(b/'orchestration-stop-v2.json');require(stop['completed'] and all(stop['checks'].values()),'Stopped orchestration not verified');require(load(b/stop['current_cohort']/'record.json')['completed'],'Last paused-registry cohort incomplete')
rows=[]
def choose(name,filename):
 if name in ('current-landscape100','current-landscape200'):return filename.endswith('-feedback-shown-empty.png')
 if name not in current:return False
 if name.startswith('control-'):return filename.endswith('-feedback-shown-empty.png')
 if name in ('final-landscape100','final-landscape200') or '-guards-' in name or '-input-' in name:return True
 if '-clear-' in name:return filename.endswith(('-shown-before.png','-shown-after.png'))
 return filename.endswith(('-feedback-shown-empty.png','-feedback-shown-cleared.png'))
for name in names:
 folder=b/name;r=project(folder/'record.json',d/'cohorts'/name/'record.json');require(r['cleanup'] and all(v['passed'] for v in r['cleanup'].values()),'Cleanup failure must remain a separate failed protocol')
 positive=name in current and not name.startswith('control-');control=name in current and name.startswith('control-')
 if positive:require(r['completed'] and r['failures']==0,'Final positive failed')
 if control:require(not r['completed'] and r['passes']==0 and r['failures']==2,'Final control mismatch')
 put(folder/'tests.log',d/'cohorts'/name/'tests.log','redact-text')
 outer=b/(name+'-outer.log')
 if outer.exists():put(outer,d/'cohorts'/name/'outer.log','redact-text')
 for filename,wanted in r['captures'].items():
  require(re.fullmatch(r'[a-z0-9-]+\.(png|xml|json)',filename) is not None,'Invalid capture path');p=folder/'captures'/filename;require(sha(p.read_bytes())==wanted,'Capture mismatch');retained.append({'original':str(p),'sha256':wanted})
  if filename.endswith('.json') and not filename.endswith('-frame.json'):put(p,d/'cohorts'/name/'captures'/filename)
 for filename in sorted(r['captures']):
  if not filename.endswith('.png') or not choose(name,filename):continue
  p=folder/'captures'/filename;xml=p.with_suffix('.xml');frame=p.with_name(p.stem+'-frame.json');require(xml.name in r['captures'] and frame.name in r['captures'],'Missing triplet');require(b'\r\n' in xml.read_bytes(),'Raw CRLF lost')
  targets=[images/name/p.name,d/'cohorts'/name/'captures'/xml.name,d/'cohorts'/name/'captures'/frame.name]
  for source,target in zip([p,xml,frame],targets):put(source,target)
  selected.append(dict(zip(['png','xml','frame'],map(str,targets)),cohort=name))
 rows.append({'name':name,'credit':'current-positive' if positive else 'current-control' if control else 'historical','passes':r['passes'],'failures':r['failures'],'completed':r['completed'],'cases':r['methods'],'cleanup_checks':len(r['cleanup']),'app_sha256':r['apk_sha256'],'test_apk_sha256':r['test_apk_sha256']})
for version in ('candidate-v1','candidate-v2','candidate-v3'):
 for filename in ('identity.json','app-manifest.xml','test-manifest.xml'):
  p=b/version/filename
  if p.exists():put(p,d/'identities'/version/filename)
 put(b/version/'debug-build.log',d/'checks'/version/'debug-build.log','redact-text')
for filename in ('initial-settings-receipt.json','final-preservation.json','final-controls-calibration.json','orchestration-stop-v2.json','host-checks-summary.json','source-and-asset-verification.json','test-payload-differences.json'):
 put(b/filename,d/filename)
for name in ('native-v1.json','native-v2.json','native-v3.json'):put(b/name,d/'pipelines'/name)
for name in ('host-v1.log','host-v2.log','host-v3.log','python-v1.log','python-v1-opt.log'):put(b/name,d/'checks'/name,'redact-text')
for name in ('capture-initial-settings.py','final-preservation.py','run-native-v1.py','run-native-v2.py','run-native-v3.py','calibrate-final-controls.py','verify-host-and-identities.py','build-freeze-v1.py','build-freeze-v2.py','build-freeze-v3.py','publish.py'):
 put(b/name,d/'collection-source'/name,'redact-text')
for variant in ('debug','benchmark'):put(Path(f'app/build/reports/lint-results-{variant}.xml'),d/'checks'/f'lint-results-{variant}.xml','redact-xml')
for p in sorted(Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):put(p,d/'checks/jvm'/p.name,'redact-xml')
pos=[r for r in rows if r['credit']=='current-positive'];neg=[r for r in rows if r['credit']=='current-control']
summary={'final_positive_executions':sum(r['passes'] for r in pos),'final_positive_cohorts':len(pos),'final_positive_restoration_checks':sum(r['cleanup_checks'] for r in pos),'final_control_failures':sum(r['failures'] for r in neg),'final_control_passes':sum(r['passes'] for r in neg),'final_control_cohorts':len(neg),'final_control_restoration_checks':sum(r['cleanup_checks'] for r in neg),'all_retained_executions':sum(r['passes']+r['failures'] for r in rows),'all_retained_passes':sum(r['passes'] for r in rows),'all_retained_failures':sum(r['failures'] for r in rows),'all_cohort_restoration_checks':sum(r['cleanup_checks'] for r in rows),'separate_orchestration_stop_checks':len(stop['checks']),'separate_final_preservation_checks':len(load(b/'final-preservation.json')['checks']),'selected_png_xml_frame_sets':len(selected),'retained_capture_files':len(retained),'cohorts':rows,'scope':'Only final app/test pair earns current credit. All intermediate/failed cohorts remain separate; v2 registry is incomplete after a restored whole-cohort stop, including a separately completed phone200 receipt. No later planned v2 work is credited. Primary lines/target geometry are distinct from nonempty functional smoke and source tracing.'}
(d/'summary.json').write_text(json.dumps(summary,indent=2)+'\n');(d/'retained-captures.json').write_text(json.dumps(retained,indent=2)+'\n');(d/'selected-captures.json').write_text(json.dumps(selected,indent=2)+'\n')
checkpoint={'schema_version':1,'source_commit':final['source'],'bindings':bindings,'projections':projections,'summary':{k:v for k,v in summary.items() if k!='cohorts'},'publisher_sha256':sha(Path(__file__).read_bytes()),'publication_rules':'Raw identity copies preserve CRLF. Text host prefixes, temporary directories and isolated32hex owners are redacted; XML placeholders are escaped. Projections use only listed fields. Private originals/settings/owners and their fingerprints are excluded. Unselected captures retain independent hashes.'}
paths=[p for root in (d,images) for p in root.rglob('*') if p.is_file()];checkpoint['public_sha256']={str(p):sha(p.read_bytes()) for p in sorted(paths)};(d/'checkpoint.json').write_text(json.dumps(checkpoint,indent=2)+'\n');print(json.dumps(checkpoint['summary'],indent=2));print('public',len(paths),'bindings',len(bindings),'projections',len(projections))
