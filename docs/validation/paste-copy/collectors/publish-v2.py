"""Publish only owned paste-copy evidence, with exact input bindings."""
from pathlib import Path
import hashlib, json, re, subprocess, xml.etree.ElementTree as ET, zipfile

b = Path('artifacts/paste-copy')
d = Path('docs/validation/paste-copy')
s = Path('docs/screenshots/paste-copy')
read = lambda p: json.loads(Path(p).read_text())
sha = lambda value: hashlib.sha256(value).hexdigest()
def require(ok, message):
    if not ok:
        raise RuntimeError(message)
matrix = read(b/'final-matrix-v6/record.json')
continuation = read(b/'continuation-v7/record.json')
require(not matrix['completed'] and continuation['completed'], 'Historical failure or final continuation identity differs')
require(not d.exists() and not s.exists(), 'Publication exists; inspect before replacing')
positive = ['frozen-copy-'+layout+str(font)+'-v2' for layout in ['tablet','phone','landscape'] for font in [100,200]]
positive += ['owned-full-tablet100-v6', 'owned-full-phone100-v6']
positive += [r['name'] for r in matrix['cohorts'] if r['exit_code']==0]
positive += [r['name'] for r in continuation['cohorts']]
negative = ['matched-old-tablet100-v2', 'matched-old-phone100-v2']
gesture_negative = ['matched-legacy-reveal-landscape200-v7']
historical = ['paste-pending-tablet100-v1', 'owned-unicode-tablet100-v3', 'owned-full-tablet100-v5','owned-keyboard-landscape200-v6']
for name in positive + negative + gesture_negative:
    require(read(b/name/'state.json')['completed'], 'Required cohort incomplete: '+name)
require(len(positive)==18 and len(set(positive))==18, 'Cohort inventory changed')
for directory in [d,s]:
    directory.mkdir(parents=True)
checkpoint = dict(schema_version=1, issue=101, base_commit='987b78a7256e7fa3c6356f6699afc23d1f451a74',
    runtime_source_commit='08210fa24674be924a6ebc93ce09b4fd170dfbc5',
    current_test_source_commit='51de2cab70d14afb980d396288ca889685587421',
    scope='Explicit Copy following rejected paste; no cross-app insertion parity',
    bindings=[], projections=[], raw_capture_sets=[], retained_capture_hashes={}, cohorts=[], public_files={})
replacements = [(str(Path.cwd()).encode(), b'<checkout>'),
    (b'<related-checkout>/', b'<related-checkout>/'),
    (b'<android-sdk>', b'<android-sdk>'),
    (b'<java-vms>/', b'<java-vms>/'),
    (b'<gradle-cache>/', b'<gradle-cache>/'),
    (b'<user>/', b'<user>/')]
def cleaned(data, xml=False):
    for old, new in replacements:
        data=data.replace(old, new.replace(b'<',b'&lt;').replace(b'>',b'&gt;') if xml else new)
    return data
def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    require(not path.exists(), 'Duplicate public path: '+str(path))
    require(not re.search(rb'/Users/[A-Za-z0-9_.-]+',data), 'Unredacted host path: '+str(path))
    path.write_bytes(data)
def bind(source, destination, redact=False):
    original=source.read_bytes()
    public=cleaned(original, destination.suffix=='.xml') if redact else original
    write(destination, public)
    checkpoint['bindings'].append(dict(original=str(source), original_sha256=sha(original),
        public=str(destination), public_sha256=sha(public), transform='host-path-byte-redaction' if redact else 'identity'))
def project(source, destination, value, recipe):
    data=(json.dumps(value, indent=2, ensure_ascii=False)+'\n').encode()
    write(destination, data)
    checkpoint['projections'].append(dict(original=str(source), original_sha256=sha(source.read_bytes()),
        public=str(destination), public_sha256=sha(data), recipe=recipe))
state_keys=['completed','scope','runtime_source_commit','test_source_commit','app_sha256','test_apk_sha256',
    'collector_sha256','layout','font','method','group','classes','expected','passes','runner_exit_code',
    'cleanup_checks','cleanup_passed','same_boot','durable_data_unchanged','pre_existing_evidence_unchanged',
    'durable_file_count','evidence_file_count','case_records','native_case_records','workflow_outcome','expected_negative','app']
runner_keys=['completed','serial','avd','layout','font_scale','classes','expected','runner_sha256',
    'archive_sha256','typed_workflow_archive_sha256','cleanup_helper_sha256','clipboard_helper_sha256',
    'document_helper_sha256','instrumentation_excluded_tests','reviewed_json_command','reviewed_translation_command',
    'expect_completion_return','expect_paste_copy_fallback','original_size','original_density','original_font_scale',
    'paste_copy_legacy_reveal',
    'recovery_lease','evidence_directories','shared_regression_cohort','apk_sha256','test_apk_sha256',
    'installed_apks_match','clipboard_mutation_permitted','instrumentation_exit_code','passes','tests_completed','owned_extension_count']
recovery_keys=['schema_version','phase','private_backup_saved','identities_retained_privately']
def runner_projection(r):
    p={k:r[k] for k in runner_keys if k in r}
    p['cleanup']={name:{'passed':row['passed']} for name,row in r['cleanup'].items()}
    for key in ['clipboard_recovery','clipboard_restoration']:
        if key in r:p[key]={k:r[key][k] for k in recovery_keys if k in r[key]}
    return p
for name in positive + negative + gesture_negative + historical:
    root=b/name;state=read(root/'state.json');runner=read(root/'cohort/record.json')
    require(runner['cleanup'] and all(row.get('passed') for row in runner['cleanup'].values()), 'Failed cleanup: '+name)
    category='final-positive' if name in positive else 'matched-old-negative' if name in negative else 'matched-gesture-negative' if name in gesture_negative else 'historical-excluded'
    checkpoint['cohorts'].append(dict(name=name, category=category, executions=runner['expected'],
        passes=runner['passes'], failures=runner['expected']-runner['passes'], restoration_checks=len(runner['cleanup']),
        app_sha256=runner['apk_sha256'],test_apk_sha256=runner['test_apk_sha256']))
    project(root/'state.json', d/'cohorts'/name/'state.json', {k:state[k] for k in state_keys if k in state}, dict(kind='top-level-allowlist',keys=state_keys))
    project(root/'cohort/record.json',d/'cohorts'/name/'runner.json',runner_projection(runner),
        dict(kind='runner-allowlist',top_level_keys=runner_keys,recovery_keys=recovery_keys,cleanup_keys=['passed']))
    bind(root/'cohort/tests.log',d/'cohorts'/name/'tests.log',True)
    for path in sorted((root/'cohort/captures').iterdir()):
        if path.is_file():
            require(path.suffix in ['.json','.png','.xml'], 'Unknown capture type')
            checkpoint['retained_capture_hashes'][str(path)]=sha(path.read_bytes())
            if path.name.endswith('-record.json'):
                bind(path,d/'cases'/name/path.name,True)
def frame(name, pattern, label):
    paths=list((b/name/'cohort/captures').glob(pattern))
    require(len(paths)==1, 'Capture selector did not identify one file: '+name+' '+pattern)
    png=paths[0];stem=png.with_suffix('');files=[]
    for suffix,destination in [('.png',s/(label+'.png')),('.xml',d/'frames'/(label+'.xml')),('-frame.json',d/'frames'/(label+'-frame.json'))]:
        source=Path(str(stem)+suffix)
        require(source.is_file(), 'Incomplete raw capture set')
        bind(source,destination);files.append(str(destination))
    checkpoint['raw_capture_sets'].append(dict(label=label,cohort=name,original_stem=str(stem),files=files))
for layout in ['tablet','phone','landscape']:
    for font in [100,200]:
        name='frozen-copy-'+layout+str(font)+'-v2'
        for phase,label in [('paste-fallback-before-copy','pending'),('paste-fallback-exact-copy','copied'),('translation-context-return','source-return')]:
            frame(name,'*-'+phase+'.png',layout+str(font)+'-'+label)
for layout in ['tablet','phone']:
    frame('matched-old-'+layout+'100-v2','*-translation-paste-unavailable.png',layout+'100-before')
frame('frozen-copy-phone100-v2','*-translation-caller.png','phone100-source-before')
owned=[('owned-full-tablet100-v6','*pointercopyisexactonceandreturns-unicode-exact-copy.png','tablet100-unicode-copy'),
    ('owned-full-tablet100-v6','*uncaughtpastefailurestillrequiresexplicitcopy-uncaught-rejection-offer.png','tablet100-uncaught-offer'),
    ('owned-full-tablet100-v6','*uncaughtpastefailurestillrequiresexplicitcopy-uncaught-rejection-explicit-copy.png','tablet100-uncaught-copy'),
    ('owned-full-tablet100-v6','*sourcereplacementclearsoutputbeforeanyqueuedcopy-new-source-without-old-output.png','tablet100-new-source'),
    ('owned-full-phone100-v6','*pointercopyisexactonceandreturns-unicode-exact-copy.png','phone100-unicode-copy'),
    ('owned-full-phone100-v6','*pointercopyisexactonceandreturns-duplicate-sentinel-preserved.png','phone100-post-copy-sentinel-probe'),
    ('owned-full-phone100-v6','*recreationclearsthebufferanddisposedcopy-recreated-without-old-offer.png','phone100-recreated'),
    ('owned-full-phone100-v6','*preferencesdisposalandhiddenrequestscannotcopy-restored-parent-without-output.png','phone100-parent-restored')]
for name,pattern,label in owned:frame(name,pattern,label)
for layout in ['tablet','phone','landscape']:
    frame('owned-keyboard-'+layout+'200-'+('v7' if layout=='landscape' else 'v6'), '*-copy-keyboard-focused.png',layout+'200-keyboard-focus')
for name,prefix in [('matched-legacy-reveal-landscape200-v7','landscape200-legacy'),('owned-keyboard-landscape200-v7','landscape200-interior')]:
    for phase in ['before','after']:frame(name,'*-reveal-1-'+phase+'.png',prefix+'-reveal-'+phase)
frame('owned-keyboard-landscape200-v6','*-failure.png','landscape200-original-keyboard-failure')
frame('regression-callers-phone100-v7','*correctinganerrorsubmitsexactunicodeonce-corrected-destination.png','phone100-form-regression')
frame('regression-callers-phone100-v7','*listtouchdetailpreservesqueryselectionexactcopyandcaller-selected-detail.png','phone100-detail-regression')
frame('regression-handoff-phone100-v7','*contextargumentsandfallbackreachchildandcopy-child-context.png','phone100-handoff-regression')
for name in ['candidate-v1','candidate-v2','owned-harness-v3','owned-harness-v5','owned-harness-v6','owned-harness-v7']:
    identity=read(b/name/'identity.json')
    commit=identity.get('source_commit',identity.get('test_source_commit'))
    for path,digest in identity['source_hashes'].items():
        data=subprocess.check_output(['git','show',commit+':'+path])
        require(sha(data)==digest, 'Historical source mismatch: '+name+' '+path)
    bind(b/name/'identity.json',d/'identities'/(name+'.json'))
for path in [b/'candidate-v2/benchmark/identity.json',b/'owned-harness-v7/final-checks/record.json']:
    bind(path,d/'checks'/('initial-benchmark.json' if path.name=='identity.json' else 'current-benchmark-lint.json'))
for path in sorted((b/'candidate-v2/jvm-results').glob('*.xml')):
    bind(path,d/'checks/jvm'/path.name,True)
for path in sorted((b/'owned-harness-v7/final-checks').glob('*.xml')):
    bind(path,d/'checks'/path.name,True)
logs=['candidate-v2-build.log','candidate-v2-benchmark-build.log','owned-harness-v6-build.log',
    'owned-harness-v6-benchmark-lint.log','owned-harness-v6-benchmark-lint-v2.log',
    'python-owned-v3.log','python-owned-optimized-v3.log','owned-harness-v3-build.log','owned-harness-v5-build.log',
    'owned-harness-v7-build.log','owned-harness-v7-benchmark-lint.log','python-reveal-v7.log','python-reveal-optimized-v7.log']
for name in logs:bind(b/name,d/'checks'/name,True)
for name in ['run-frozen-paste-v2.py','run-owned-v3.py','run-owned-v5.py','run-owned-v6.py',
    'run-regressions-v6.py','run-final-matrix-v6.py','preservation.py','publish-v2.py']:
    bind(b/name,d/'collectors'/name,True)
for name in ['run-owned-v7.py','run-regressions-v7.py','run-continuation-v7.py']:
    bind(b/name,d/'collectors'/name,True)
for name in ['final-matrix-v6','continuation-v7']:
    bind(b/name/'record.json',d/'pipelines'/(name+'.json'))
bind(b/'apk-manifest-inspection-v1.json',d/'checks/apk-manifest-inspection.json')
checkpoint['totals']={}
for category in ['final-positive','matched-old-negative','matched-gesture-negative','historical-excluded']:
    rows=[r for r in checkpoint['cohorts'] if r['category']==category]
    checkpoint['totals'][category]=dict(cohorts=len(rows),**{key:sum(r[key] for r in rows) for key in ['executions','passes','failures','restoration_checks']})
old=Path('<related-checkout>/droidcast-command-handoff-27caf3/artifacts/command-handoff/build-candidate-v2/candidate/app-debug.apk')
with zipfile.ZipFile(old) as before, zipfile.ZipFile(b/'candidate-v2/app-debug.apk') as after:
    assets=[p for p in after.namelist() if p.startswith('assets/') and not p.endswith('/')]
    require(len(assets)==10 and all(before.read(p)==after.read(p) for p in assets),'Packaged assets changed')
    checkpoint['unchanged_packaged_assets']={p:sha(after.read(p)) for p in assets}
with zipfile.ZipFile(b/'owned-harness-v6/app-debug-androidTest.apk') as before, zipfile.ZipFile(b/'owned-harness-v7/app-debug-androidTest.apk') as after:
    names=set(before.namelist())|set(after.namelist())
    changed=sorted(p for p in names if p not in before.namelist() or p not in after.namelist() or before.read(p)!=after.read(p))
    require(changed==['classes3.dex','classes4.dex'],'Instrumentation payload difference changed')
    checkpoint['current_harness_payload_difference']=changed
for path in sorted(list(d.rglob('*'))+list(s.rglob('*'))):
    if path.is_file():
        if path.suffix=='.xml':ET.fromstring(path.read_bytes())
        checkpoint['public_files'][str(path)]=sha(path.read_bytes())
(d/'checkpoint.json').write_text(json.dumps(checkpoint,indent=2,ensure_ascii=False)+'\n')
print(json.dumps(dict(totals=checkpoint['totals'],public_files=len(checkpoint['public_files']),
    raw_capture_sets=len(checkpoint['raw_capture_sets']),retained_capture_hashes=len(checkpoint['retained_capture_hashes'])),indent=2))
