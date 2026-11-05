from pathlib import Path
import hashlib,json,re,xml.etree.ElementTree as ET
b=Path('artifacts/keyboard-toggle');d=Path('docs/validation/keyboard-toggle');images=Path('docs/screenshots/keyboard-toggle');load=lambda p:json.loads(p.read_text());sha=lambda x:hashlib.sha256(x).hexdigest()
def require(v,msg):
 if not v:raise ValueError(msg)
final=load(b/'native-v4.json');require(final['completed'],'Final native matrix incomplete');require(load(b/'back-diagnostics.json')['completed'],'Back observations incomplete');require(load(b/'final-preservation.json')['all_passed'],'Final preservation incomplete');require((b/'host-final-harness.log').read_text().count('BUILD SUCCESSFUL')==2,'Final fixture builds incomplete');require(not d.exists() and not images.exists(),'Publication exists; inspect rather than overwrite');d.mkdir(parents=True);images.mkdir(parents=True)
bindings=[];projections=[];retained=[];selected=[]
prefixes=[(str(Path.cwd()),'checkout'),('<supplement-checkout>','supplement-checkout'),('<previous-checkout>','previous-checkout'),('<java-home>','java-home'),('<android-sdk>','android-sdk'),(str(Path.home()/'.gradle'),'gradle-cache'),(str(Path.home()),'user-home')]
def put(source,target,mode='identity'):
 raw=source.read_bytes();data=raw
 if mode!='identity':
  s=raw.decode();token=lambda label:'&lt;'+label+'&gt;' if mode=='redact-xml' else '<'+label+'>'
  for p,label in prefixes:s=s.replace(p,token(label))
  s=re.sub(r'/(?:private/)?var/folders/[^\s\"\x27<>&]+',lambda _:token('temporary-dir'),s);s=re.sub(r'(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])',lambda _:token('owned-id'),s);data=s.encode()
 target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
 if target.suffix=='.xml':ET.fromstring(data)
 bindings.append({'original':str(source),'original_sha256':sha(raw),'public':str(target),'public_sha256':sha(data),'transformation':mode if data!=raw else 'identity'})
fields=('completed','expected_negative','suite','input_mode','runner_sha256','apk_sha256','test_apk_sha256','methods','api','font_scale','layout','cleanup','passes','failures','native_expected_outcome','instrumentation_exit_code','initialization_error','provider_before','original_geometry','original_font_scale','captures','preserved_file_count','ime_restoration_scope','ime_restore_observations')
def project(source,target):
 raw=source.read_bytes();r=json.loads(raw);j={k:r[k] for k in fields if k in r};target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(j,indent=2)+'\n');projections.append({'original':str(source),'original_sha256':sha(raw),'public':str(target),'public_sha256':sha(target.read_bytes()),'allowed_fields':list(j)});return r
registry_names=['baseline-controls.json','baseline-controls-v3.json','native-v1.json','native-v2.json','native-v3.json','back-diagnostics.json','native-v4.json'];names=[]
for reg in registry_names:names.extend(r['name'] for r in load(b/reg)['cohorts'])
require(len(set(names))==len(names),'Duplicate cohort');current={r['name']:r for r in final['cohorts']};rows=[]
def choose(name,f):
 if name in ['final-landscape100','final-landscape200','final-tablet100','final-tablet200']:return f.endswith(('-keyboard-0-before-hide.png','-keyboard-0-after-hide.png','-keyboard-0-after-show.png')) or name=='final-landscape200' and f.endswith(('-keyboard-1-after-hide.png','-keyboard-1-after-show.png'))
 if name=='final-guards-tablet100':return any(f.endswith('-'+phase+'.png') for phase in ['keyboard-guard-obsolete-mode','keyboard-guard-epoch','keyboard-guard-open-menu','keyboard-guard-replaced-route','keyboard-guard-recreated-owner','keyboard-guard-current-recreated-show','keyboard-accessibility-launcher-0','keyboard-accessibility-launcher-1','keyboard-accessibility-emoji-0','keyboard-accessibility-emoji-1'])
 if name.startswith('control-old-final-') or name.startswith('baseline-'):return f.endswith('-keyboard-0-after-hide.png')
 if name=='control-proxy-final-tablet100' or name in ['candidate-guards-tablet100','candidate-v2-guards-tablet100','candidate-v3-guards-tablet100']:return f.endswith(('-keyboard-guard-obsolete-mode.png','-keyboard-guard-open-menu.png'))
 if name in ['back-diagnostic-old','back-diagnostic-current']:return True
 if name=='candidate-landscape200':return f.endswith('-keyboard-0-after-hide.png')
 if name=='final-feedback-landscape200':return f.endswith('-feedback-shown-empty.png')
 if name=='final-clear-phone200':return f.endswith(('-shown-before.png','-shown-after.png'))
 if name=='final-core-guards-tablet100':return True
 if name.startswith('final-input-'):return f.endswith('-settled.png') or f.endswith('-composition.png') or f.endswith('-committed.png')
 return False
for name in names:
 folder=b/name;r=project(folder/'record.json',d/'cohorts'/name/'record.json');require(r.get('cleanup') and all(v['passed'] for v in r['cleanup'].values()),'A failed restoration must be explicitly handled, not silently published as complete')
 kind='current-control' if name in current and current[name]['expected_control'] else 'current-positive' if name in current else 'back-diagnostic' if name.startswith('back-diagnostic-') else 'historical'
 put(folder/'tests.log',d/'cohorts'/name/'tests.log','redact-text')
 if (b/(name+'-outer.log')).exists():put(b/(name+'-outer.log'),d/'cohorts'/name/'outer.log','redact-text')
 for f,h in r['captures'].items():
  require(re.fullmatch(r'[a-z0-9-]+\.(png|xml|json)',f) is not None,'Unsafe capture name');p=folder/'captures'/f;require(sha(p.read_bytes())==h,'Capture changed');retained.append({'original':str(p),'sha256':h})
  if f.endswith('.json') and not f.endswith('-frame.json'):put(p,d/'cohorts'/name/'captures'/f)
 for f in sorted(r['captures']):
  if not f.endswith('.png') or not choose(name,f):continue
  p=folder/'captures'/f;xml=p.with_suffix('.xml');frame=p.with_name(p.stem+'-frame.json');require(xml.name in r['captures'] and frame.name in r['captures'],'Incomplete raw set');require(b'\r\n' in xml.read_bytes(),'Raw CRLF missing');targets=[images/name/p.name,d/'cohorts'/name/'captures'/xml.name,d/'cohorts'/name/'captures'/frame.name]
  for source,target in zip([p,xml,frame],targets):put(source,target)
  selected.append(dict(zip(['png','xml','frame'],map(str,targets)),cohort=name))
 rows.append({'name':name,'credit':kind,'passes':r['passes'],'failures':r['failures'],'completed':r['completed'],'cases':r['methods'],'cleanup_checks':len(r['cleanup']),'app_sha256':r['apk_sha256'],'test_apk_sha256':r['test_apk_sha256']})
for name in ['baseline-harness','baseline-harness-v3','candidate-v1','candidate-v2','candidate-v3','final-harness']:
 for f in ['identity.json','app-manifest.xml','test-manifest.xml']:
  p=b/name/f
  if p.exists():put(p,d/'identities'/name/f)
 if (b/name/'debug-build.log').exists():put(b/name/'debug-build.log',d/'checks'/name/'debug-build.log','redact-text')
for name in ['initial-settings-receipt.json','final-preservation.json','frozen-host-verification.json','final-verification.json','semantics-proxy-inspection.json','final-controls-calibration.json']:put(b/name,d/name)
for name in registry_names:put(b/name,d/'pipelines'/name)
for name in ['build-baseline.log','build-baseline-v2.log','build-baseline-v3.log','host-v1.log','host-v2.log','host-v3.log','host-final-harness.log','python-v1-normal.log','python-v1-optimized.log','python-supplement-normal.log','python-supplement-optimized.log','python-final-normal.log','python-final-optimized.log']:put(b/name,d/'checks'/name,'redact-text')
for name in ['capture-initial-settings.py','build-baseline.py','build-baseline-v2.py','build-baseline-v3.py','run-baseline.py','run-baseline-v3.py','build-freeze-v1.py','build-freeze-v2.py','build-freeze-v3.py','build-final-harness.py','run-native-v1.py','run-native-v2.py','run-native-v3.py','run-native-v4.py','run-back-diagnostics.py','run-final.py','final-preservation.py','verify-frozen-host.py','verify-final.py','calibrate-final-controls.py','publish.py']:put(b/name,d/'collection-source'/name,'redact-text')
for variant in ['debug','benchmark']:put(Path(f'app/build/reports/lint-results-{variant}.xml'),d/'checks'/f'lint-results-{variant}.xml','redact-xml')
for p in sorted(Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):put(p,d/'checks/jvm'/p.name,'redact-xml')
pos=[r for r in rows if r['credit']=='current-positive'];neg=[r for r in rows if r['credit']=='current-control'];diag=[r for r in rows if r['credit']=='back-diagnostic']
summary={'final_positive_executions':sum(r['passes'] for r in pos),'final_positive_cohorts':len(pos),'final_positive_restoration_checks':sum(r['cleanup_checks'] for r in pos),'final_control_failures':sum(r['failures'] for r in neg),'final_control_passes':sum(r['passes'] for r in neg),'final_control_cohorts':len(neg),'final_control_restoration_checks':sum(r['cleanup_checks'] for r in neg),'back_diagnostic_executions':sum(r['passes']+r['failures'] for r in diag),'back_diagnostic_passes':sum(r['passes'] for r in diag),'back_diagnostic_failures':sum(r['failures'] for r in diag),'all_retained_executions':sum(r['passes']+r['failures'] for r in rows),'all_retained_passes':sum(r['passes'] for r in rows),'all_retained_failures':sum(r['failures'] for r in rows),'all_cohort_restoration_checks':sum(r['cleanup_checks'] for r in rows),'separate_final_preservation_checks':len(load(b/'final-preservation.json')['checks']),'selected_png_xml_frame_sets':len(selected),'retained_capture_files':len(retained),'cohorts':rows,'scope':'Only the final frozen app/test pair earns current toggle/callback credit. Earlier driver/candidate failures and incomplete pipelines remain separate; Back observations earn no toggle or Back-repair credit. No unrun work or private-original identity is certified.'}
(d/'summary.json').write_text(json.dumps(summary,indent=2)+'\n');(d/'selected-captures.json').write_text(json.dumps(selected,indent=2)+'\n');(d/'retained-captures.json').write_text(json.dumps(retained,indent=2)+'\n')
c={'schema_version':1,'source_commit':final['source'],'app_source':final['app_source'],'test_source':final['test_source'],'bindings':bindings,'projections':projections,'summary':{k:v for k,v in summary.items() if k!='cohorts'},'publisher_sha256':sha(Path(__file__).read_bytes()),'redaction_prefixes':[{'token':label,'kind':'declared host prefix'} for _,label in prefixes],'publication_rules':'Raw identity copies preserve CRLF. Declared host prefixes, temporary directories and isolated32hex owners are redacted; XML placeholders are escaped. Record projections contain only allowlisted fields. Private originals/settings/owners and fingerprints are excluded.'}
paths=[p for root in [d,images] for p in root.rglob('*') if p.is_file()];c['public_sha256']={str(p):sha(p.read_bytes()) for p in sorted(paths)};(d/'checkpoint.json').write_text(json.dumps(c,indent=2)+'\n');print(json.dumps(c['summary'],indent=2));print('public',len(paths),'bindings',len(bindings),'projections',len(projections))
