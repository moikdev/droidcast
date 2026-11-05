from pathlib import Path
import json,hashlib,subprocess,difflib,zipfile
from xml.etree import ElementTree as E
B=Path('artifacts/webview-compatibility/audit-repair-v1');D=Path('docs/validation/webview-engine/audit-repair');S=Path('docs/screenshots/webview-engine/audit-repair');BASE=Path('docs/validation/webview-engine')
sha=lambda v:hashlib.sha256(v).hexdigest();read=lambda p:json.loads(Path(p).read_text())
def require(v,m):
 if not v:raise ValueError(m)
for n in ['legacy100','modern-tablet100']:require(read(B/n/'record.json')['completed'],'Incomplete repaired cohort')
require(read(B/'guard-controls.json')['completed'],'Incomplete mutation calibration')
require(not D.exists() and not S.exists(),'Repair publication already exists; inspect before modifying it')
D.mkdir(parents=True);S.mkdir(parents=True);bindings=[];projections=[]
def put(p,d,redact=False,xml=False):
 raw=p.read_bytes();value=raw
 if redact:
  for a,b in [(str(Path.cwd()),'<checkout>'),(str(Path.home()),'<host-home>')]:
   if xml:b=b.replace('<','&lt;').replace('>','&gt;')
   value=value.replace(a.encode(),b.encode())
 d.parent.mkdir(parents=True,exist_ok=True);d.write_bytes(value)
 bindings.append({'original':str(p.resolve().relative_to(Path.cwd())),'original_sha256':sha(raw),'public':str(d),'public_sha256':sha(value),'transform':'replace checkout/home with escaped placeholders' if redact and xml else 'replace checkout/home with placeholders' if redact else 'identity'})
 if xml:E.fromstring(value)
record_keys=set('completed expected_negative runner_sha256 apk_sha256 test_apk_sha256 methods api font_scale layout cleanup provider_before original_geometry original_font_scale instrumentation_exit_code passes failures initialization_error native_expected_outcome preserved_file_count'.split())
native_keys=set()
for row in read(BASE/'projections.json'):
 if Path(row['original']).name.startswith('case-'):native_keys.update(row['keys'])
native_keys.update('supported_probe_operation owned_probe_file_exists preflight_response_ok approved_response_ok approved_owned_file_exists same_engine_bridge_operation_and_arguments approved_control_scope'.split())
def project(p,d,keys):
 r=read(p);v={k:r[k] for k in sorted(keys) if k in r};d.parent.mkdir(parents=True,exist_ok=True);d.write_text(json.dumps(v,indent=2)+'\n')
 projections.append({'original':str(p),'original_sha256':sha(p.read_bytes()),'public':str(d),'public_sha256':sha(d.read_bytes()),'keys':sorted(v)})
 return v
roots={'legacy100':B/'legacy100','modern-tablet100':B/'modern-tablet100','mutant-old-insensitive':B/'guard-control/old-insensitive','mutant-new-sensitive':B/'guard-control/new-sensitive'}
capture_hashes={}
for n,root in roots.items():
 r=project(root/'record.json',D/'cohorts'/n/'record.json',record_keys)
 require(all(x['passed'] for x in r['cleanup'].values()),'Restoration failed')
 put(root/'tests.log',D/'cohorts'/n/'tests.log',redact=True)
 for p in (root/'captures').iterdir():
  require(p.is_file() and p.suffix in ['.png','.xml','.json'],'Unexpected capture')
  capture_hashes[str(p)]=sha(p.read_bytes())
  if p.name.startswith('case-'):project(p,D/'cohorts'/n/'cases'/p.name,native_keys)
  else:put(p,S/n/p.name if p.suffix=='.png' else D/'cohorts'/n/'captures'/p.name,xml=p.suffix=='.xml')
# The earlier checkpoint remains immutable. Replace only its two entire
# affected cohorts in the current matrix; never relabel their old receipts.
matrix=read(BASE/'matrix.json')
for row in matrix:
 n=row['cohort']
 if n in ['legacy100','modern-tablet100']:
  r=read(B/n/'record.json');row.update(passes=r['passes'],junit_failures=r['failures'],cleanup_checks=len(r['cleanup']),app_sha256=r['apk_sha256'],test_apk_sha256=r['test_apk_sha256'],source='fresh strengthened harness',record=str(D/'cohorts'/n/'record.json'))
 else:row.update(source='retained original scope, unaffected method bodies',record=str(BASE/'cohorts'/n/'record.json'))
(D/'matrix.json').write_text(json.dumps(matrix,indent=2)+'\n')
put(B/'identity.json',D/'identity.json');put(B/'guard-control/identity.json',D/'mutation-identity.json')
put(B/'guard-controls.json',D/'mutation-calibration.json');put(B/'post-control-binary-verification.json',D/'post-control-binary-verification.json');put(B/'post-control-apk-payload-comparison.json',D/'post-control-apk-payload-comparison.json')
identity=read(B/'identity.json')
for p,digest in identity['sources'].items():require(sha(subprocess.check_output(['git','show',identity['test_source']+':'+p]))==digest,'Frozen source mismatch')
P=Path('app/src/androidTest/java/dev/tinycast/android/WebViewRecoveryDeviceTest.kt');before=subprocess.check_output(['git','show','b417d94ab3932ae5775db22cbd5d55d802ea5e5b:'+str(P)]);after=P.read_bytes()
a=b'    @Test fun bridgeCannotPublishOrReadHostApisBeforeApproval() {';b=b'    @Test fun replacedRecoveryRejectsRetainedSettingsAndReturnCallbacks() {'
require(before.split(a)[0]==after.split(a)[0] and before.split(b)[1]==after.split(b)[1],'Another test body changed')
comparison={'before_source':'b417d94ab3932ae5775db22cbd5d55d802ea5e5b','after_source':identity['test_source'],'before_sha256':sha(before),'after_sha256':sha(after),'prefix_and_suffix_outside_target_method_byte_identical':True,'production_sources_byte_identical_to':'fec0a4bf1155c08c141606f92ae8fb1feab4bba0'}
(D/'test-source-boundary.json').write_text(json.dumps(comparison,indent=2)+'\n')
C=B/'guard-control';original=(C/'ExtensionRuntime.original.kt').read_text();mutant=(C/'ExtensionRuntime.mutated.kt').read_text();delta=''.join(difflib.unified_diff(original.splitlines(keepends=True),mutant.splitlines(keepends=True),fromfile='candidate/ExtensionRuntime.kt',tofile='control/ExtensionRuntime.kt'));(D/'single-guard-mutation.diff').write_text(delta)
for n in ['audit-repair-harness-build-v1.log','audit-repair-harness-build-v2.log','audit-repair-harness-build-v3.log','audit-repair-restored-build-v1.log','audit-repair-benchmark-harness-build-v1.log']:put(Path('artifacts/webview-compatibility')/n,D/'checks'/n,redact=True)
put(C/'build.log',D/'checks/mutation-build.log',redact=True)
for v in ['debug','benchmark']:put(Path('app/build/reports/lint-results-'+v+'.xml'),D/'checks'/('lint-results-'+v+'.xml'),redact=True,xml=True)
for p in [Path(__file__),B/'build-guard-control.py',B/'run-guard-controls.py']:put(p,D/'collectors'/p.name,redact=True)
(D/'capture-original-hashes.json').write_text(json.dumps(capture_hashes,indent=2)+'\n');(D/'bindings.json').write_text(json.dumps(bindings,indent=2)+'\n');(D/'projections.json').write_text(json.dumps(projections,indent=2)+'\n')
readme=BASE/'README.md';readme.write_text('''# WebView evidence checkpoints\n\nThe original `checkpoint.json` and its 320 files retain their b417d94 identities. Its unsupported-operation preflight assertion was not sufficient to prove synchronous gate enforcement. The two affected original cohorts remain historical.\n\nUse [the repaired matrix](audit-repair/matrix.json) and [repair checkpoint](audit-repair/checkpoint.json) for current credit. The repair substitutes two newly completed whole cohorts, preserves unaffected outcomes at their original binaries, and separately records a deliberately regressed guard. The old test passes that mutant; the strengthened test fails its intended supported-operation assertion. Neither mutation result earns candidate-runtime credit.\n''')
for p in D.rglob('*.xml'):E.parse(p)
files={str(p):sha(p.read_bytes()) for root in [D,S] for p in root.rglob('*') if p.is_file()};files[str(readme)]=sha(readme.read_bytes())
for p in files:require(str(Path.home()).encode() not in Path(p).read_bytes(),'Host path remains')
(D/'checkpoint.json').write_text(json.dumps({'public_files':files,'source_head':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'scope':'Two strengthened whole cohorts plus separately classified mutation calibration; original checkpoint retained unchanged'},indent=2)+'\n')
print(json.dumps({'repair_public_files':len(files),'new_screenshots':len(list(S.rglob('*.png'))),'current_passes':sum(x['passes'] for x in matrix if not x['expected_negative']),'current_cohorts':sum(not x['expected_negative'] for x in matrix),'current_checks':sum(x['cleanup_checks'] for x in matrix),'mutation_checks':30}))
