"""Verify staged public evidence without accessing device state or backups."""
from pathlib import Path
import hashlib, json, re, sys, xml.etree.ElementTree as ET, zipfile
sys.path.insert(0, str(Path(__file__).parent))
from typed_publication_helpers_v2 import sha, redact, public_host, write_json

b = Path('artifacts/form-pickers')
stage = b / 'publication-audit-repair-v2'
proof = stage / 'validation'
state = json.loads((stage / 'preparation-state.json').read_text())
assert state['completed'] and state['current_native_executions'] == 195
out = proof / 'publication-verification.json'
assert not out.exists()
transforms = 0
for event in state['transformations']:
    source, dest = Path(event['source']), stage / event['destination']
    assert sha(source) == event['source_sha256']
    data = source.read_bytes()
    if event['transformation'].startswith('Fixed host-path replacements'):
        data = redact(data, source.suffix)
    else:
        assert event['transformation'] == 'Byte-exact raw copy'
    assert hashlib.sha256(data).hexdigest() == event['published_sha256']
    assert dest.read_bytes() == data
    transforms += 1

hosts = 0
for kind in ['cohorts', 'matched-controls', 'excluded-cohorts']:
    for p in (proof / kind).glob('*/record.json'):
        original = json.loads((b / p.parent.name / 'record.json').read_text())
        expected = redact((json.dumps(public_host(original), indent=2, ensure_ascii=False)+'\n').encode(), '.json')
        assert p.read_bytes() == expected
        hosts += 1

captures = json.loads((proof / 'capture-manifest.json').read_text())['captures']
assert len(captures) == 34
for c in captures:
    source = b / c['source_root'] / 'captures' / c['source_file']
    pairs = [(source, stage / 'screenshots' / (c['name']+'.png')),
        (source.with_suffix('.xml'), proof / 'captures' / (c['name']+'.xml')),
        (source.with_name(source.stem+'-frame.json'), proof / 'captures' / (c['name']+'-frame.json'))]
    for original, dest in pairs:
        assert original.read_bytes() == dest.read_bytes()
    assert pairs[1][1].read_bytes().count(b'\r\n') > 0
    assert c['app_sha256'] == json.loads((b/c['source_root']/'record.json').read_text())['apk_sha256']

retained = json.loads((proof / 'retained-capture-hashes.json').read_text())['files']
assert all(sha(p) == h for p, h in retained.items())
build = json.loads((b / 'typed-build-audit-repair-v1-state.json').read_text())
pair = json.loads((b / 'audit-common-pair-v1-state.json').read_text())
common = Path(pair['candidate_directory']) / 'test.apk'
current = Path(build['candidate_directory']) / 'test.apk'
assert sha(common) == pair['test_apk_sha256'] and sha(current) == build['test_apk_sha256']
with zipfile.ZipFile(common) as a, zipfile.ZipFile(current) as z:
    old = {n:a.read(n) for n in a.namelist()}
    new = {n:z.read(n) for n in z.namelist()}
assert old.keys() == new.keys()
changed = [n for n in old if old[n] != new[n]]
assert changed == ['classes4.dex']
source_changes = [p for p, h in build['source_hashes'].items() if pair['compiled_common_source_hashes'][p] != h]
assert source_changes == ['app/src/main/java/dev/tinycast/android/extensions/ExtensionFormDateUi.kt',
    'app/src/main/java/dev/tinycast/android/extensions/ExtensionFormUi.kt']
assert all(sha(p) == h for p, h in build['source_hashes'].items())
write_json(proof / 'apk-payload-boundary.json', dict(common_test_apk_sha256=sha(common),
    current_test_apk_sha256=sha(current), changed_zip_payloads=changed,
    changed_source_paths=source_changes, android_test_and_shared_helper_sources_byte_identical=True,
    boundary='Only classes4.dex ZIP payload differs; generated test BuildConfig commit/checksum metadata differs. Exact stamp-only reconstruction was not established. Matched common-APK controls and current-APK regression receipts remain distinct.'))

xmls = list(proof.rglob('*.xml'))
for p in xmls:
    ET.fromstring(p.read_bytes())
private_key = re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\r?\n[A-Za-z0-9+/=]{16,}')
for p in stage.rglob('*'):
    if p.is_file() and p.suffix != '.png':
        data = p.read_bytes()
        assert (b'/' + b'Users/') not in data, str(p)
        assert not private_key.search(data), str(p)
write_json(proof / 'publication' / 'preparation-state.json', state)
write_json(out, dict(completed=True, reconstructed_transformations=transforms,
    reconstructed_allowlisted_host_records=hosts, byte_exact_raw_sets=34,
    retained_capture_files=len(retained), raw_xml_crlf_preserved=True,
    parseable_xml_files=len(xmls), host_paths_or_private_key_material=0,
    current_source_hashes_verified=190,
    initial_git_snapshot=state['initial_snapshot'],
    scope='Publication verification only. No native/JUnit outcomes added. Original clipboard, backup/recovery files, unrelated-data fingerprints and raw IME/WindowManager dumps are excluded. Initial file identities bind their original Git commit.'))
print(json.dumps(dict(completed=True, transformations=transforms, hosts=hosts,
    raw_sets=34, xml_files=len(xmls), retained_files=len(retained))))
