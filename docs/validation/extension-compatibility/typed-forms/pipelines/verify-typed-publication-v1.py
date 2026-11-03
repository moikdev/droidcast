from pathlib import Path
import json, sys, xml.etree.ElementTree as ET
sys.path.insert(0, str(Path(__file__).parent))
from typed_publication_helpers_v2 import sha, redact, public_host, write_json

b = Path('artifacts/form-pickers')
stage = b / 'publication-v5'
proof = stage / 'validation'
out = proof / 'publication-verification.json'
assert not out.exists()
states = []
for name in ['preparation-state.json', 'history-preparation-state.json', 'supplement-preparation-state.json']:
    d = json.loads((stage / name).read_text())
    assert d['completed']
    states.append(d)
    write_json(proof / 'publication' / name, d)
published_hashes = {sha(p) for p in stage.rglob('*') if p.is_file()}
transforms = 0
for d in states:
    for event in d['transformations']:
        source = Path(event['source'])
        assert sha(source) == event['source_sha256']
        data = source.read_bytes()
        kind = event['transformation']
        if kind.startswith('Fixed host-path replacements'):
            data = redact(data, source.suffix)
        elif kind.startswith('JSON remove only owned operational'):
            case = json.loads(data)
            case = {k: v for k, v in case.items() if k not in {'owner_nonce', 'instrumentation_pid'}}
            data = redact((json.dumps(case, indent=2, ensure_ascii=False) + '\n').encode(), '.json')
        else:
            assert kind == 'Byte-exact raw copy'
        import hashlib
        actual = hashlib.sha256(data).hexdigest()
        assert actual == event['published_sha256'] and actual in published_hashes
        transforms += 1

hosts = 0
for parent in [proof / 'cohorts', proof / 'historical' / 'cohorts']:
    for p in parent.glob('*/record.json'):
        original = json.loads((b / p.parent.name / 'record.json').read_text())
        expected = redact((json.dumps(public_host(original), indent=2, ensure_ascii=False) + '\n').encode(), '.json')
        assert p.read_bytes() == expected
        hosts += 1

captures = json.loads((proof / 'capture-manifest.json').read_text())['captures']
assert len(captures) == 33
raw_crlf = []
for capture in captures:
    original = b / capture['source_root'] / 'captures' / capture['source_file']
    targets = [(original, stage / 'screenshots' / (capture['name'] + '.png')),
               (original.with_suffix('.xml'), proof / 'captures' / (capture['name'] + '.xml')),
               (original.with_name(original.stem + '-frame.json'), proof / 'captures' / (capture['name'] + '-frame.json'))]
    for source, dest in targets:
        assert source.read_bytes() == dest.read_bytes()
    raw_crlf.append(targets[1][1].read_bytes().count(b'\r\n'))
assert all(raw_crlf)
xmls = list(proof.rglob('*.xml'))
for p in xmls:
    ET.fromstring(p.read_bytes())
for p in stage.rglob('*'):
    if p.is_file() and p.suffix != '.png':
        data = p.read_bytes()
        assert (b'/' + b'Users/') not in data
        assert b'BEGIN PRIVATE KEY' not in data and b'BEGIN RSA PRIVATE KEY' not in data
write_json(out, dict(completed=True, reconstructed_transformations=transforms,
    reconstructed_allowlisted_host_records=hosts, byte_exact_raw_sets=33,
    raw_xml_crlf_preserved=True, parseable_xml_files=len(xmls),
    host_paths_or_private_key_markers=0,
    scope='Publication verification only; does not add native/JUnit passes. No private full host record, recovery snapshot, original clipboard or original-data fingerprint is copied or hashed.'))
print(out.read_text())
