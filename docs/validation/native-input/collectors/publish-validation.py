from pathlib import Path
import hashlib,json,re,subprocess
from xml.etree import ElementTree as ET
B=Path('artifacts/native-input');D=Path('docs/validation/native-input');S=Path('docs/screenshots/native-input')
sha=lambda value:hashlib.sha256(value).hexdigest()
read=lambda p:json.loads(Path(p).read_text())
def require(value,message):
 if not value:raise ValueError(message)
positive={'legacy100':'legacy-owned-full-v5','tablet100':'modern-owned-full-v5','legacy200':'legacy-owned-200-v5','phone200':'modern-owned-phone200-v5','landscape200':'modern-owned-landscape200-v5','legacy-same-length':'legacy-same-length-v6','tablet-same-length':'modern-same-length-v6'}
negative={'legacy-strict':'legacy-strict-control-v3','tablet-strict':'modern-strict-control-v3'}
for label,name in positive.items():
 r=read(B/name/'record.json');require(r['completed'] and r['failures']==0,label+' incomplete')
 require(all(v['passed'] for v in r['cleanup'].values()),label+' restoration failed')
 cases=[read(p) for p in (B/name/'captures').glob('case-*.json')]
 require(len(cases)==len(r['methods']) and {q['case'] for q in cases}==set(r['methods']),label+' case IDs mismatch')
 require(all(q['contract_completed'] and not q['diagnostic_only'] and q['font_scale']==r['font_scale'] for q in cases),label+' contract/profile mismatch')
 require(r['runner_sha256']==sha(Path('scripts/test-webview-engine.py').read_bytes()),label+' collector mismatch')
for label,name in negative.items():
 r=read(B/name/'record.json');log=(B/name/'tests.log').read_text();q=read(B/name/'captures/case-exactcontentassertionacceptscompositionandrejectschangedcharacters.json')
 require(not r['completed'] and r['passes']==0 and r['failures']==1,label+' is not the intended negative')
 require('Exact character matcher must accept the composing title' in log and q['strict_annotation_equal'] is False and q['assertion_witness']['single_ui_turn'],label+' lacks coherent intended assertion')
 require(all(v['passed'] for v in r['cleanup'].values()),label+' restoration failed')
require(not D.exists() and not S.exists(),'Publication already exists; inspect before replacing')
D.mkdir(parents=True);S.mkdir(parents=True);bindings=[];projections=[];all_captures={}
def put(source,destination,xml=False,redact=False):
 require(not any(part.startswith('private') for part in source.parts),'Do not publish private recovery inputs')
 original=source.read_bytes();value=original
 if redact:
  for old,new in [(str(Path.cwd()),'<checkout>'),(str(Path.home()),'<host-home>')]:
   if xml:new=new.replace('<','&lt;').replace('>','&gt;')
   value=value.replace(old.encode(),new.encode())
 destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(value)
 if xml:ET.fromstring(value)
 bindings.append({'original':str(source.relative_to(Path.cwd()) if source.is_absolute() else source),'original_sha256':sha(original),'public':str(destination),'public_sha256':sha(value),'transform':'replace checkout/home with XML-escaped placeholders' if xml and redact else 'replace checkout/home with placeholders' if redact else 'identity'})
 return destination
record_keys=set('completed expected_negative runner_sha256 apk_sha256 test_apk_sha256 methods api font_scale layout cleanup provider_before original_geometry original_font_scale fixture_sha256 instrumentation_exit_code passes failures initialization_error native_expected_outcome preserved_file_count suite input_mode ime_restoration_scope ime_restore_observations'.split())
case_keys=set('diagnostic_only scope case api font_scale default_ime updates_scope selection_setup diagnostic_completed palette_preferences_restored emoji_preferences_unchanged title_plain_exact title_annotated_exact body_plain_exact body_annotated_exact expected_body_length expected_body_sha256 updates_root_focus_requested_before_return expected_note_id actual_note_id destination_present strict_annotation_equal assertion_witness wrong_content_rejected input_setup contract_completed observations'.split())
def project(source,destination,keys):
 original=read(source);payload={k:original[k] for k in sorted(keys) if k in original}
 destination.parent.mkdir(parents=True,exist_ok=True);destination.write_text(json.dumps(payload,indent=2)+'\n')
 projections.append({'original':str(source.relative_to(Path.cwd()) if source.is_absolute() else source),'original_sha256':sha(source.read_bytes()),'public':str(destination),'public_sha256':sha(destination.read_bytes()),'keys':sorted(payload)})
 return payload
cohorts=[];current_names=set(positive.values())|set(negative.values())
for file in sorted(B.glob('*/record.json')):
 name=file.parent.name;r=project(file,D/'cohorts'/name/'record.json',record_keys);logfile=file.parent/'tests.log';log=logfile.read_text() if logfile.exists() else ''
 if log:put(logfile,D/'cohorts'/name/'tests.log',redact=True)
 passes=len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0$',log,re.M));failures=len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: -2$',log,re.M))
 require(r.get('passes',passes)==passes and r.get('failures',failures)==failures,'Native count mismatch '+name)
 cohort={'cohort':name,'junit_passes':passes,'junit_failures':failures,'completed':r['completed'],'restoration_checks':len(r['cleanup']),'failed_restoration_checks':sum(not v['passed'] for v in r['cleanup'].values()),'app_sha256':r['apk_sha256'],'test_apk_sha256':r['test_apk_sha256'],'collector_sha256':r['runner_sha256'],'final_credit':'positive' if name in positive.values() else 'intended-negative' if name in negative.values() else 'retained-only'}
 cohorts.append(cohort)
 for p in sorted((file.parent/'captures').glob('*')):
  require(p.is_file() and p.suffix in ('.png','.xml','.json'),'Unexpected capture')
  all_captures[str(p)]=sha(p.read_bytes())
  if p.name.startswith('case-'):project(p,D/'cohorts'/name/'cases'/p.name,case_keys)
# Publish a deliberately small visual set. All remaining raw capture hashes and
# native case/log records remain bound without claiming unseen frame visibility.
selected=[
 ('legacy100',positive['legacy100'],'exactcontentassertionacceptscompositionandrejectschangedcharacters-composed-title-after-assertion'),
 ('tablet100',positive['tablet100'],'exactcontentassertionacceptscompositionandrejectschangedcharacters-composed-title-after-assertion'),
 ('legacy-strict',negative['legacy-strict'],'exactcontentassertionacceptscompositionandrejectschangedcharacters-failure'),
 ('tablet-strict',negative['tablet-strict'],'exactcontentassertionacceptscompositionandrejectschangedcharacters-failure'),
 ('legacy100',positive['legacy100'],'committedemojinavigationandcaretareexact-committed-query-selected-range'),
 ('legacy100',positive['legacy100'],'activemultilingualcompositionkeepsresultselection-active-japanese-composition'),
 ('legacy100',positive['legacy100'],'committedcallerselectionsurvivesidlenativeroutes-updates-destination'),
 ('legacy100',positive['legacy100'],'committedcallerselectionsurvivesidlenativeroutes-updates-caller-return'),
 ('legacy100',positive['legacy100'],'committedsameturnlauncheractivationusescurrentnote-same-turn-current-note'),
 ('legacy100',positive['legacy100'],'largedraftrestoresexactcharactersafterrecreation-large-draft-exact-characters'),
 ('tablet100',positive['tablet100'],'committedemojinavigationandcaretareexact-committed-query-selected-range'),
 ('legacy200',positive['legacy200'],'exactcontentassertionacceptscompositionandrejectschangedcharacters-composed-title-after-assertion'),
 ('legacy200',positive['legacy200'],'committedcallerselectionsurvivesidlenativeroutes-updates-caller-return'),
 ('phone200',positive['phone200'],'activemultilingualcompositionkeepsresultselection-active-japanese-composition'),
 ('phone200',positive['phone200'],'committedcallerselectionsurvivesidlenativeroutes-updates-caller-return'),
 ('landscape200',positive['landscape200'],'exactcontentassertionacceptscompositionandrejectschangedcharacters-composed-title-after-assertion'),
 ('landscape200',positive['landscape200'],'committedcallerselectionsurvivesidlenativeroutes-updates-caller-return'),
 ('legacy-same-length',positive['legacy-same-length'],'exactcontentassertionacceptscompositionandrejectschangedcharacters-composed-title-after-assertion'),
 ('tablet-same-length',positive['tablet-same-length'],'exactcontentassertionacceptscompositionandrejectschangedcharacters-composed-title-after-assertion'),
 ('system-ime-history','legacy-before-v2','recorddefaultimeandcontrolledcompositionnavigation-default-before'),
 ('system-ime-history','legacy-contracts-v1','committedemojinavigationandcaretareexact-failure')]
frames=[]
for label,name,stem in selected:
 root=B/name/'captures';target=S/label/(stem+'.png');put(root/(stem+'.png'),target)
 put(root/(stem+'.xml'),D/'frames'/label/(stem+'.xml'),xml=True)
 put(root/(stem+'-frame.json'),D/'frames'/label/(stem+'-frame.json'))
 frames.append({'cohort':name,'stem':stem,'png':str(target),'xml':str(D/'frames'/label/(stem+'.xml')),'frame':str(D/'frames'/label/(stem+'-frame.json'))})
identity_names=['before-v1','before-v2','contracts-v1','strict-control-v1','final-v2','owned-v3','owned-v4','strict-control-v2','owned-v5','strict-control-v3','same-length-v6']
for name in identity_names:
 source=B/name/'identity.json';x=read(source);put(source,D/'identities'/(name+'.json'))
 if 'source_override' in x:
  override=x['source_override'];path=B/('strict-control-v1' if name=='strict-control-v1' else 'strict-control-v2')/'NativeEditableAssertions.kt'
  require(sha(path.read_bytes())==override['sha256'],'Override source mismatch');put(path,D/'identities'/(name+'-helper.kt'))
 for field,revision in [('sources',x['collector_source']),('apk_build_sources',x['test_source'])]:
  for path,digest in x.get(field,{}).items():
   if x.get('source_override',{}).get('path')==path:require(digest==x['source_override']['sha256'],'Override binding mismatch')
   else:require(sha(subprocess.check_output(['git','show',revision+':'+path]))==digest,'Source mismatch '+name+' '+path)
 for filename,digest in x['apks'].items():require(sha((B/name/filename).read_bytes())==digest,'APK mismatch '+name)
for name in ['diagnostic-harness-build-v1.log','diagnostic-harness-build-v2.log','diagnostic-harness-build-v3.log','contracts-harness-build-v1.log','contracts-harness-build-v2.log','final-build-checks-v1.log','final-build-checks-v2.log','final-build-checks-v3.log','final-owned-build-v1.log','final-benchmark-build-v2.log','python-v1.log','python-optimized-v1.log','python-final-v2.log','python-optimized-final-v2.log','python-final-v4.log','python-optimized-final-v4.log','same-length-debug-build-v1.log','same-length-benchmark-build-v1.log']:
 put(B/name,D/'checks'/name,redact=True)
for name in ['strict-control-v1','strict-control-v2']:put(B/name/'build.log',D/'checks'/(name+'-build.log'),redact=True)
for name in ['final-v2','owned-v3']:put(B/name/'debug-assembly-completed-prefix.log',D/'checks'/(name+'-debug-assembly-prefix.log'),redact=True)
for p in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):put(p,D/'checks/jvm'/p.name,xml=True,redact=True)
for v in ['debug','benchmark']:put(Path('app/build/reports/lint-results-'+v+'.xml'),D/'checks'/('lint-results-'+v+'.xml'),xml=True,redact=True)
for p in (B/'binary-metadata').glob('*'):put(p,D/'binary-metadata'/p.name,xml=p.suffix=='.xml')
for name in ['profiles.json','strict-calibration-v1.json','post-probe-ime-history-cleanup.json','control-source-reference-clarification.json']:put(B/name,D/name)
put(B/'final-preservation.json',D/'final-preservation.json')
put(B/'verify-final-preservation.py',D/'collectors/verify-final-preservation.py',redact=True)
put(B/'modern-strict-control-v2/recovery.json',D/'history/modern-control-recovery.json')
put(B/'recover-modern-control-v2.py',D/'collectors/recover-modern-control-v2.py',redact=True)
for name in ['source.json','api26-ime-source.json']:put(B/'dependencies'/name,D/'dependencies'/name)
put(Path('scripts/test-webview-engine.py'),D/'collectors/test-webview-engine.py')
put(Path(__file__),D/'collectors/publish-validation.py')
(D/'cohorts.json').write_text(json.dumps(cohorts,indent=2)+'\n');(D/'frames.json').write_text(json.dumps(frames,indent=2)+'\n');(D/'capture-original-hashes.json').write_text(json.dumps(all_captures,indent=2)+'\n')
(D/'calibration.json').write_text(json.dumps({'completed':True,'scope':'Two intended strict-annotation failures, zero JUnit passes; both positive-only runner receipts remain failed; all restoration checks succeed','cohorts':negative,'test_payload_delta':['classes3.dex'],'production_app_unchanged':True},indent=2)+'\n')
(D/'bindings.json').write_text(json.dumps(bindings,indent=2)+'\n');(D/'projections.json').write_text(json.dumps(projections,indent=2)+'\n')
for p in D.rglob('*.xml'):ET.parse(p)
for root in [D,S]:
 for p in root.rglob('*'):
  if p.is_file():require(str(Path.home()).encode() not in p.read_bytes(),'Unredacted host path')
files={str(p):sha(p.read_bytes()) for root in [D,S] for p in root.rglob('*') if p.is_file()}
summary={'scope':'Exact character assertions and explicitly controlled native-input fixtures; system-IME/original API26 gaps remain open','source_head':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'positive_executions':sum(c['junit_passes'] for c in cohorts if c['final_credit']=='positive'),'positive_cohorts':len(positive),'intended_negatives':sum(c['junit_failures'] for c in cohorts if c['final_credit']=='intended-negative'),'current_restoration_checks':sum(c['restoration_checks'] for c in cohorts if c['cohort'] in current_names),'all_retained_executions':sum(c['junit_passes']+c['junit_failures'] for c in cohorts),'all_retained_passes':sum(c['junit_passes'] for c in cohorts),'all_retained_failures':sum(c['junit_failures'] for c in cohorts),'capture_hashes':len(all_captures),'published_frames':len(frames),'public_files':files}
(D/'checkpoint.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps({k:v for k,v in summary.items() if k!='public_files'}));print('public_files',len(files))
