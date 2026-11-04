from pathlib import Path
import hashlib,json,subprocess,zipfile,xml.etree.ElementTree as E
base=Path('artifacts/workflow-expansion');root=Path('docs/validation/extension-compatibility/fetch');checkpoint=json.loads((root/'checkpoint.json').read_text())
sha=lambda data:hashlib.sha256(data).hexdigest()
for path,h in checkpoint['public_files'].items():assert sha(Path(path).read_bytes())==h,path
for path,h in checkpoint['source_hashes'].items():assert sha(Path(path).read_bytes())==h,path
for binding in checkpoint['original_public_bindings']:
 original=(base/binding['artifact']).read_bytes();public=Path(binding['public']).read_bytes();assert sha(original)==binding['original_sha256'] and sha(public)==binding['public_sha256']
 if binding['transformation']=='identity':assert original==public
 else:
  replacement='&lt;user&gt;' if binding['public'].endswith('.xml') else '<user>'
  assert original.decode('utf-8').replace('<user>',replacement).encode('utf-8')==public
for row in checkpoint['allowlisted_projections']:
 original=json.loads((base/row['artifact']).read_text());value={k:original[k] for k in row['allowed_keys'] if k in original}
 if row['cleanup_projection']:value['cleanup']={k:{'passed':v.get('passed') is True} for k,v in original['cleanup'].items()}
 assert value==json.loads(Path(row['public']).read_text())
for path,h in checkpoint['raw_capture_hashes'].items():assert sha((base/path).read_bytes())==h,path
for path in root.rglob('*.xml'):E.parse(path)
for name in ['build-probe-v1','build-runtime-v1','build-harness-v2','build-harness-v3','build-harness-v4']:
 value=json.loads((root/'identities'/(name+'.json')).read_text());commit=value.get('test_source_commit') or value['runtime_source_commit']
 for path,h in value['source_hashes'].items():assert sha(subprocess.check_output(['git','show',commit+':'+path]))==h,(name,path)
 for archive,h in value['apk_hashes'].items():assert sha((base/name/'candidate'/archive).read_bytes())==h,(name,archive)
with zipfile.ZipFile(base/'baseline-debug.apk') as old, zipfile.ZipFile(base/'build-runtime-v1/candidate/app-debug.apk') as current:
 old_assets={n:sha(old.read(n)) for n in old.namelist() if n.startswith('assets/')}
 new_assets={n:sha(current.read(n)) for n in current.namelist() if n.startswith('assets/')}
 assert set(old_assets)==set(new_assets) and [n for n in old_assets if old_assets[n]!=new_assets[n]]==['assets/android-runtime.js']
 assert new_assets['assets/android-runtime.js']==sha(Path('app/src/main/assets/android-runtime.js').read_bytes())
for p in root.rglob('*'):
 if p.is_file() and p.suffix!='.png':assert (b'/' + b'Users' + b'/') not in p.read_bytes(),p
print(json.dumps({'verified_public_files':len(checkpoint['public_files']),'verified_sources':len(checkpoint['source_hashes']),'original_bindings':len(checkpoint['original_public_bindings']),'projections':len(checkpoint['allowlisted_projections']),'raw_capture_hashes':len(checkpoint['raw_capture_hashes']),'cohorts':len(checkpoint['cohorts'])},indent=2))
