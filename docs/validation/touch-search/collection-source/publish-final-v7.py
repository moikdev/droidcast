from pathlib import Path
import hashlib,json,re,xml.etree.ElementTree as ET
b=Path('artifacts/touch-clear');dest=Path('docs/validation/touch-search');images=Path('docs/screenshots/touch-search')
sha=lambda x:hashlib.sha256(x).hexdigest()
def require(ok,msg):
 if not ok:raise ValueError(msg)
load=lambda p:json.loads(p.read_text())
final=load(b/'native-final-v9.json')
require(final['completed'],'Final pipeline incomplete')
require(load(b/'final-preservation-receipt.json')['all_passed'],'Final preservation incomplete')
require(load(b/'final-controls-calibration.json')['all_completed'],'Final calibration incomplete')
require((b/'final-harness-v7/host-checks.log').read_text().count('BUILD SUCCESSFUL')==2,'Host checks incomplete')
require(not dest.exists() and not images.exists(),'Existing publication: inspect; never overwrite')
dest.mkdir(parents=True);images.mkdir(parents=True)
bindings=[];projections=[];retained=[];selected=[]

def put(source,target,mode='identity'):
 raw=source.read_bytes();out=raw
 if mode!='identity':
  text=raw.decode();xml=mode=='redact-xml'
  def token(label):return '&lt;'+label+'&gt;' if xml else '<'+label+'>'
  for prefix,label in [(str(Path.cwd()),'checkout'),(str(Path.home()/'Library/Android/sdk'),'android-sdk'),(str(Path.home()/'.gradle'),'gradle-cache'),(str(Path.home()),'user-home')]:text=text.replace(prefix,token(label))
  text=re.sub(r'/(?:private/)?var/folders/[^\s\"\x27<>&]+',lambda _:token('temporary-dir'),text)
  text=re.sub(r'(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])',lambda _:token('owned-id'),text)
  out=text.encode()
 target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(out)
 if target.suffix=='.xml':ET.fromstring(out)
 bindings.append({'original':str(source),'original_sha256':sha(raw),'public':str(target),'public_sha256':sha(out),'transformation':mode if out!=raw else 'identity'})

def project(source,target,fields):
 raw=source.read_bytes();original=load(source);out={k:original[k] for k in fields if k in original}
 target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(out,indent=2)+'\n')
 projections.append({'original':str(source),'original_sha256':sha(raw),'public':str(target),'public_sha256':sha(target.read_bytes()),'allowed_fields':list(out)})
 return out
fields=('completed','expected_negative','suite','input_mode','runner_sha256','apk_sha256','test_apk_sha256','methods','api','font_scale','layout','cleanup','passes','failures','native_expected_outcome','instrumentation_exit_code','initialization_error','provider_before','original_geometry','original_font_scale','captures','preserved_file_count','ime_restoration_scope','ime_restore_observations')
historical=[r['name'] for r in load(b/'native-pipeline-v1.json')['cohorts']]+['spacing-control-tablet200','legacy-independent-phone100']+[r['name'] for r in load(b/'native-final-v5.json')['cohorts']]+['diagnostic-landscape100-v6','verified-phone200','verified-api26-phone100']
current=[r['name'] for r in final['cohorts']]
names=historical+current
require(len(names)==len(set(names)),'Duplicate cohort')
cohorts=[]

def capture_files(folder,captures,group,all_triplets=False):
 for filename,wanted in captures.items():
  require(re.fullmatch(r'[a-z0-9-]+\.(png|xml|json)',filename) is not None,'Unregistered filename')
  p=folder/'captures'/filename;require(sha(p.read_bytes())==wanted,'Capture identity mismatch: '+str(p))
  retained.append({'original':str(p),'sha256':wanted})
 for p in sorted((folder/'captures').glob('*.json')):
  if p.name.endswith('-frame.json'):continue
  require(p.name in captures,'Unregistered case receipt')
  put(p,dest/'cohorts'/group/'captures'/p.name)
 for filename in sorted(captures):
  if not filename.endswith('.png'):continue
  choose=all_triplets or (group=='verified-phone200' or (group=='diagnostic-landscape100-v6' and filename.endswith('-shown-before.png')))
  if not choose:continue
  p=folder/'captures'/filename;xml=p.with_suffix('.xml');frame=p.with_name(p.stem+'-frame.json')
  require(xml.name in captures and frame.name in captures,'Missing triplet')
  require(b'\r\n' in xml.read_bytes(),'Raw XML lost CRLF')
  put(p,images/group/p.name);put(xml,dest/'cohorts'/group/'captures'/xml.name);put(frame,dest/'cohorts'/group/'captures'/frame.name)
  selected.append({'cohort':group,'png':str(images/group/p.name),'xml':str(dest/'cohorts'/group/'captures'/xml.name),'frame':str(dest/'cohorts'/group/'captures'/frame.name)})

for name in names:
 folder=b/name;r=load(folder/'record.json');require(all(x.get('passed') for x in r['cleanup'].values()),'Historical cleanup failure must be separate')
 if name in current and name.startswith('verified-'):require(r['completed'] and r['failures']==0 and r['test_apk_sha256']==final['test_apk_sha256'],'Final positive failed')
 if name in current and (name.startswith('final-old-') or name.startswith('final-spacing-')):require(not r['completed'] and r['passes']==0 and r['failures']==1 and r['test_apk_sha256']==final['test_apk_sha256'],'Final control mismatch')
 project(folder/'record.json',dest/'cohorts'/name/'record.json',fields)
 put(folder/'tests.log',dest/'cohorts'/name/'tests.log','redact-text')
 if (b/(name+'-outer.log')).exists():put(b/(name+'-outer.log'),dest/'cohorts'/name/'outer.log','redact-text')
 capture_files(folder,r.get('captures',{}),name,name in current)
 cohorts.append({'name':name,'passes':r['passes'],'failures':r['failures'],'completed':r['completed'],'cases':r['methods'],'cleanup_checks':len(r['cleanup']),'app_sha256':r['apk_sha256'],'test_apk_sha256':r['test_apk_sha256'],'credit':'current-positive' if name in current and name.startswith('verified-') else 'current-control' if name in current else 'historical'})

# Preserve failed protocols as failures, separately from their later recoveries.
f=b/'final-tablet200-v2';r=load(f/'record.json');recovered=load(f/'recovered-captures.json')
require(not r['completed'] and not r['cleanup']['idle-owned-instrumentation']['passed'],'Missing failed cleanup')
project(f/'record.json',dest/'failures/post-test/record.json',fields)
put(f/'tests.log',dest/'failures/post-test/tests.log','redact-text');put(f/'recovered-captures.json',dest/'failures/post-test/recovered-captures.json')
put(b/'final-tablet200-v2-outer.log',dest/'failures/post-test/outer.log','redact-text')
capture_files(f,recovered['captures'],'failed-post-test',False)
project(b/'final-tablet200/record.json',dest/'failures/zero-test-preflight/record.json',fields)
put(b/'final-tablet200-outer.log',dest/'failures/zero-test-preflight/outer.log','redact-text')

for version in ('candidate-v2','candidate-v3','diagnostic-harness-v6','final-harness-v7'):
 for filename in ('identity.json','app-manifest.xml','test-manifest.xml','python-source-identity.json','current-compiled-harness.json','host-checks-summary.json','source-and-asset-verification.json','test-apk-payload-differences.json'):
  p=b/version/filename
  if p.is_file():put(p,dest/'identities'/version/filename)
for filename in ('old-controls-calibration.json','spacing-control-calibration.json','final-controls-calibration.json','initial-settings-receipt.json','final-preservation-receipt.json','preflight-recovery.json','post-test-recovery.json','post-test-recovery-interrupted.json','archive-transport-file-probe.json','controller-final-identity.json','launcher-anr-observation.json','launcher-anr-recovery.json','historical-runner-source-bindings.json'):
 put(b/filename,dest/filename)
# The obsolete v1/v3/v4/v5 pipeline plans preserve what ran; no planned/unrun credit.
for filename in ('native-pipeline-v1.json','native-final-v3.json','native-final-v4.json','native-final-v5.json','native-final-v7.json','native-final-v8.json','native-final-v9.json'):
 put(b/filename,dest/'pipelines'/filename)
for filename in ('global-preservation.py','recover-preflight.py','recover-post-test.py','finish-post-test-recovery.py','calibrate-final-v7.py','run-final-v7.py','run-final-v8.py','run-final-v9.py','publish-final-v7.py'):
 put(b/filename,dest/'collection-source'/filename,'redact-text')
for filename in ('python-final.log','python-final-opt.log'):
 put(b/filename,dest/'checks'/filename,'redact-text')
for version,filename in [('candidate-v1','build.log'),('candidate-v1','build-retry-v2.log'),('candidate-v2','build.log'),('candidate-v2','benchmark-build.log'),('candidate-v3','build.log'),('candidate-v3','final-harness-and-benchmark.log'),('diagnostic-harness-v6','build.log'),('final-harness-v7','build.log'),('final-harness-v7','host-checks.log')]:
 put(b/version/filename,dest/'checks'/version/filename,'redact-text')
for variant in ('debug','benchmark'):put(Path(f'app/build/reports/lint-results-{variant}.xml'),dest/'checks'/f'lint-results-{variant}.xml','redact-xml')
for p in sorted(Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):put(p,dest/'checks/jvm'/p.name,'redact-xml')

positives=[r for r in cohorts if r['credit']=='current-positive'];controls=[r for r in cohorts if r['credit']=='current-control']
summary={'final_positive_executions':sum(r['passes'] for r in positives),'final_whole_cohorts':len(positives),'final_cleanup_checks':sum(r['cleanup_checks'] for r in positives),'final_control_failures':sum(r['failures'] for r in controls),'final_control_passes':sum(r['passes'] for r in controls),'final_control_cohorts':len(controls),'final_control_cleanup_checks':sum(r['cleanup_checks'] for r in controls),'all_retained_executions':sum(r['passes']+r['failures'] for r in cohorts)+sum(recovered['raw_junit'].values()),'all_retained_raw_passes':sum(r['passes'] for r in cohorts)+recovered['raw_junit']['passes'],'all_retained_failures':sum(r['failures'] for r in cohorts)+recovered['raw_junit']['failures'],'successful_cohort_cleanup_checks':sum(r['cleanup_checks'] for r in cohorts),'failed_post_test_protocol':{'raw_junit_passes':2,'raw_junit_failures':0,'failed_cleanup_checks':1,'completed':False,'final_credit':0},'zero_test_preflight_attempts':1,'selected_png_xml_frame_sets':len(selected),'retained_capture_files':len(retained),'separate_recovery_checks':{'preflight':len(load(b/'preflight-recovery.json')['checks']),'post_test':len(load(b/'post-test-recovery.json')['checks']),'launcher_anr':len(load(b/'launcher-anr-recovery.json')['checks'])},'separate_final_preservation_checks':len(load(b/'final-preservation-receipt.json')['checks']),'scope':'Final credit only uses app974b/test54a/current controller. All older harnesses and failed protocols are excluded. Clear uses the selected system IME; native-input regression cases use the explicitly owned silent test IME. Constrained empty-feedback visibility remains open in#111; v7 scopes that body assertion, keeping all Clear assertions mandatory.','cohorts':cohorts}
(dest/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
(dest/'retained-captures.json').write_text(json.dumps(retained,indent=2)+'\n')
(dest/'selected-captures.json').write_text(json.dumps(selected,indent=2)+'\n')
checkpoint={'schema_version':1,'app_source':final['app_source'],'test_source':final['test_source'],'controller_source':final['controller_source'],'bindings':bindings,'projections':projections,'publication_rules':'Raw identity copies preserve CRLF. Host path prefixes and temporary directories are redacted; isolated32hex lease IDs are replaced. XML placeholders are escaped. Projections use only listed fields. Private originals, owners, raw secure settings and their fingerprints are excluded. Unselected raw captures remain independently hash-listed.','publisher_sha256':sha(Path(__file__).read_bytes()),'summary':{k:v for k,v in summary.items() if k!='cohorts'}}
paths=[p for parent in (dest,images) for p in parent.rglob('*') if p.is_file()]
checkpoint['public_sha256']={str(p):sha(p.read_bytes()) for p in sorted(paths)}
(dest/'checkpoint.json').write_text(json.dumps(checkpoint,indent=2)+'\n')
print(json.dumps(checkpoint['summary'],indent=2));print('Public',len(paths),'bindings',len(bindings),'projections',len(projections))
