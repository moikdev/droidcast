"""Publication-only transforms; never read device state or private backups."""
from pathlib import Path
import hashlib,json,xml.etree.ElementTree as ET

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

HOST_FIELDS = {
    'completed','serial','avd','layout','font_scale','classes','expected',
    'runner_sha256','archive_sha256','typed_workflow_archive_sha256',
    'cleanup_helper_sha256','clipboard_helper_sha256','document_helper_sha256',
    'original_size','original_density','original_font_scale',
    'apk_sha256','test_apk_sha256','installed_apks_match','instrumentation_exit_code',
    'passes','tests_completed','shared_regression_cohort','document_fixture_restored',
    'owned_extension_count','evidence_directories'
}

def public_host(record):
    result = {key: value for key, value in record.items() if key in HOST_FIELDS}
    result['cleanup'] = {name: {key:value for key,value in check.items() if key in ['passed','error_type']}
                         for name,check in record.get('cleanup',{}).items()}
    result['publication_boundary'] = ('Allowlisted host outcomes only. Original preferences, clipboard snapshots/contents/fingerprints, '
                                      'backup hashes, recovery receipts, host PIDs and nonce owner material are excluded. '
                                      'No hash of the private full host record is published.')
    return result

def redact(data, suffix):
    replacements = [(str(Path.cwd()), '<checkout>'),
                    ('<catalog-checkout>','<catalog-checkout>'),
                    ('<project-checkout>','<project-checkout>'),
                    ('<home>','<home>')]
    for original, replacement in sorted(replacements,key=lambda pair:-len(pair[0])):
        if suffix == '.xml':
            replacement = replacement.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;')
        data = data.replace(original.encode(),replacement.encode())
    assert (b'/' + b'Users/') not in data, 'A local host path remains'
    if suffix == '.xml': ET.fromstring(data)
    elif suffix == '.json': json.loads(data)
    return data

def write_json(path, value):
    path = Path(path); path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(redact((json.dumps(value,indent=2,ensure_ascii=False)+'\n').encode(),'.json'))

def copy_text(source,destination):
    source,destination=Path(source),Path(destination);destination.parent.mkdir(parents=True,exist_ok=True)
    data=redact(source.read_bytes(),destination.suffix);destination.write_bytes(data)
    return dict(source=str(source),source_sha256=sha(source),published_sha256=sha(destination),
                transformation='Fixed host-path replacements; XML-escaped placeholders for XML; line endings preserved')

def copy_raw(source,destination):
    source,destination=Path(source),Path(destination);data=source.read_bytes()
    assert (b'/' + b'Users/') not in data, 'Raw evidence contains a host path'
    if destination.suffix=='.xml':ET.fromstring(data)
    elif destination.suffix=='.json':json.loads(data)
    destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(data)
    assert source.read_bytes()==destination.read_bytes()
    return dict(source=str(source),source_sha256=sha(source),published_sha256=sha(destination),transformation='Byte-exact raw copy')
