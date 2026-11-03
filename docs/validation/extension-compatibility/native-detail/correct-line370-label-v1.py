"""One-time metadata correction over the immutable fd2a780 publication.

Run from the repository root. This never executes builds/tests or touches devices.
The original publication and its inaccurate label remain recoverable from Git.
"""
from pathlib import Path
import hashlib,json,shutil

OUT=Path('docs/validation/extension-compatibility/native-detail')
REPORT=Path('docs/EXTENSION-MARKDOWN-DETAIL.md')
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
expected={
 str(REPORT):'7f5e04075020d821363f2cb8734216dab89f564f61ba630d217ef9d8abe97d7f',
 str(OUT/'checkpoint.json'):'242a74008de2fb2a9c150b207a0f43a1c70e256c8531864bbcef13bc1d6fa80c',
 str(OUT/'publication-plan.json'):'4c42412eb4b91d574f4895b398af975579300f2afb1f86f3dd679f6c81ac4750',
 str(OUT/'pipelines/action-search-boundary-v1.json'):'2ed8b6958ad5fc2cf4e23285b4401161ef5668aa68d06e9968b66b506c343020',
 str(OUT/'publication-bindings.json'):'024c73b01c512fc52109a8e32bc60377a2d0f618097710ca8009a9c7fc901810',
}
assert all(sha(p)==h for p,h in expected.items()),'Only apply to the exact original publication'
cp=json.loads((OUT/'checkpoint.json').read_text())
assert all(sha(p)==h for p,h in cp['public_files'].items())
assert all(sha(p)==h for p,h in cp['source_hashes'].items())
correction_path=OUT/'metadata-corrections/line370-empty-state-label-v1.json'
script_path=OUT/'correct-line370-label-v1.py'
assert not correction_path.exists() and not script_path.exists()
def write(path,data):path.write_text(json.dumps(data,indent=2,ensure_ascii=False)+'\n')
def limits(values):
 old='Legacy action-search Copy-content visibility assertion fails on both base/current at landscape100%; cause unisolated under #71; excluded from the three-case landscape keyboard cohort.'
 new='Legacy action-search empty-state visibility assertion fails on both base/current at landscape100%; cause unisolated under #71; excluded from the three-case landscape keyboard cohort.'
 assert values.count(old)==1
 return [new if x==old else x for x in values]

report=REPORT.read_text();needle='(`action-Copy content` not displayed)';assert report.count(needle)==1
report=report.replace(needle,'(`inline-menu-empty`, the “No matching actions” empty state, not displayed)')
paragraph='Version 1 mislabeled line 370 as the later Copy content row. This metadata correction identifies the actual empty-state assertion; original test logs, failed outcomes, recovery receipts, source identities and raw captures remain unchanged. The first publication remains available at commit `fd2a780`.\n\n'
marker='Long paragraphs, unbroken URLs';assert report.count(marker)==1
REPORT.write_text(report.replace(marker,paragraph+marker,1))

boundary_path=OUT/'pipelines/action-search-boundary-v1.json'
boundary=json.loads(boundary_path.read_text());boundary['common_failing_assertion']='InlinePaletteDeviceTest.kt:370 inline-menu-empty assertIsDisplayed (No matching actions)'
boundary['label_correction']='Original Copy content label referred to the later assertion at line374; the actual failure is the empty state at line370. Runtime and test outcomes are unchanged.'
write(boundary_path,boundary)
plan_path=OUT/'publication-plan.json';plan=json.loads(plan_path.read_text());plan['remaining_limits']=limits(plan['remaining_limits']);write(plan_path,plan)
bindings_path=OUT/'publication-bindings.json';bindings=json.loads(bindings_path.read_text());matches=[x for x in bindings if x['public']==str(boundary_path)];assert len(matches)==1
matches[0].update(public_sha256=sha(boundary_path),transformation='correct-legacy-empty-state-label-v1')
write(bindings_path,bindings)
cp['remaining_limits']=limits(cp['remaining_limits'])
cp['metadata_corrections']=[str(correction_path)]
script_path.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(__file__,script_path)
correction_path.parent.mkdir(parents=True,exist_ok=True)
write(correction_path,dict(schema_version=1,baseline_publication_commit='fd2a78027f0189257cb31cc67c375cfaa2186036',
 scope='Correct assertion-target labels only; no change to source/test/runner binaries, captures, test outcomes or execution credit.',
 actual_assertion='InlinePaletteDeviceTest.kt:370 inline-menu-empty assertIsDisplayed',actual_text='No matching actions',
 previous_label='action-Copy content (a later assertion at line374)',
 original_input_sha256=expected,corrected_file_sha256={p:sha(p) for p in expected if not p.endswith('/checkpoint.json')},
 all_216_current_source_hashes_unchanged=True,all_43_raw_capture_sets_unchanged=True,
 current_native_executions=161,current_native_cohorts=24,current_cohort_cleanup_checks=467))
files=[p for root in [OUT,Path('docs/screenshots/extension-compatibility/native-detail')] for p in root.rglob('*') if p.is_file() and p!=OUT/'checkpoint.json']
cp['public_files']={str(p):sha(p) for p in sorted(files)};write(OUT/'checkpoint.json',cp)
assert len(files)==889 and all(sha(p)==h for p,h in cp['source_hashes'].items())
print(json.dumps({'public_files_including_checkpoint':890,'source_hashes_unchanged':216,'raw_capture_sets_unchanged':43,'corrected_target':'inline-menu-empty at line370'}))
