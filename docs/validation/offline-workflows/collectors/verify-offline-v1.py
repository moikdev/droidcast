from pathlib import Path
import argparse,hashlib,json,re,subprocess,xml.etree.ElementTree as E,zipfile
parser=argparse.ArgumentParser();parser.add_argument('--git',action='store_true');args=parser.parse_args()
base=Path('artifacts/offline-workflows');public=Path('docs/validation/offline-workflows');pictures=Path('docs/screenshots/offline-workflows')
read=lambda p:json.loads(Path(p).read_text())
sha=lambda data:hashlib.sha256(data).hexdigest()
def require(v,m):
 if not v:raise RuntimeError(m)
checkpoint=read(public/'checkpoint.json');expected=set(checkpoint['public_files']);actual={str(p) for root in [public,pictures] for p in root.rglob('*') if p.is_file() and p!=public/'checkpoint.json'}
require(expected==actual,'Public inventory changed')
for p,h in checkpoint['public_files'].items():
 require(sha(Path(p).read_bytes())==h,'Public hash mismatch '+p)
 if args.git:require(Path(p).read_bytes()==subprocess.check_output(['git','show','HEAD:'+p]),'Git public bytes differ '+p)
for row in checkpoint['original_public_bindings']:
 original=(base/row['artifact']).read_bytes();require(sha(original)==row['original_sha256'],'Original changed')
 data=original
 if row['transformation'].startswith('UTF-8 host-home'):
  data=original.decode().replace('<user>','&lt;user&gt;' if row['public'].endswith('.xml') else '<user>').encode()
 require(data==Path(row['public']).read_bytes() and sha(data)==row['public_sha256'],'Declared transformation differs')
for row in checkpoint['allowlisted_projections']:
 original=read(base/row['artifact']);value={k:original[k] for k in row['allowed_keys'] if k in original}
 if row['cleanup_projection']:value['cleanup']={k:{'passed':v.get('passed') is True} for k,v in original['cleanup'].items()}
 require(value==read(row['public']),'Projection mismatch')
for p,h in checkpoint['retained_capture_hashes'].items():require(sha((base/p).read_bytes())==h,'Retained capture changed')
for p,h in checkpoint['source_hashes'].items():
 require(sha(Path(p).read_bytes())==h,'Current source changed')
 require(sha(subprocess.check_output(['git','show',checkpoint['source_head']+':'+p]))==h,'Frozen source differs')
 if args.git:require(sha(subprocess.check_output(['git','show','HEAD:'+p]))==h,'Final Git source differs')
xmls=list(public.rglob('*.xml'))
for p in xmls:E.parse(p)
for row in checkpoint['original_public_bindings']:
 if row['public'].endswith('.xml') and '/captures/' in row['public'] or row['public'].endswith('.xml') and '/touch-return-baseline/' in row['public']:
  raw=(base/row['artifact']).read_bytes();require(raw==Path(row['public']).read_bytes() and b'\r\n' in raw and raw.count(b'\n')==raw.count(b'\r\n'),'Raw XML CRLF changed')
for c in checkpoint['cohorts']:
 dest=public/'cohorts'/c['name'];r=read(dest/'runner-record.json');state=read(dest/'state.json');log=(dest/'tests.log').read_text()
 require(c['passes']==r['passes']==len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0$',log,re.M)),'JUnit accounting mismatch')
 require(c['cleanup_checks']==len(r['cleanup']) and all(v['passed'] for v in r['cleanup'].values()),'Cleanup accounting mismatch')
 require(state['cleanup_passed'] and state['durable_data_unchanged'] and state['pre_existing_evidence_unchanged'] and state['same_boot'],'Preservation failed')
 if c['category']=='final-positive':require(state['completed'] and state['native_positive_completed'] and c['passes']==r['expected'],'Final cohort incomplete')
 if c['category']=='expected-missing-return':require(state['intended_negative_completed'] and c['passes']==0 and 'A completed no-view command must expose its touch Return action' in log,'Unintended control result')
 if c['category']=='expected-initial-target':require(state['intended_negative_completed'] and c['passes']==0 and 'Return needs a complete 48dp visible target' in log,'Unintended initial-target result')
for name in ['build-harness-v1','build-return-v2','build-harness-v3','build-return-v5','build-harness-v7']:
 identity=read(public/'identities'/(name+'.json'))
 revision=identity.get('source_commit',identity.get('test_source_commit'))
 for path,h in identity['source_hashes'].items():require(sha(subprocess.check_output(['git','show',revision+':'+path]))==h,'Historical source differs')
 for file,h in identity['apk_hashes'].items():require(sha((base/name/'candidate'/file).read_bytes())==h,'Frozen APK differs')
with zipfile.ZipFile(base/'build-harness-v1/candidate/app-debug.apk') as a,zipfile.ZipFile(base/'build-return-v5/candidate/app-debug.apk') as b:
 assets=lambda apk:{n:sha(apk.read(n)) for n in apk.namelist() if n.startswith('assets/')}
 require(assets(a)==assets(b) and len(assets(a))==10,'Assets changed')
python_final=read(public/'checks/python-final/record.json');require(python_final['completed'] and all(sha(Path(p).read_bytes())==h for p,h in python_final['source_hashes'].items()),'Fresh Python binding differs')
for mode in ['normal','optimized']:
 log=(public/'checks/python-final'/(mode+'.log')).read_text();require('Ran 105 tests' in log and '\nOK\n' in log,'Fresh Python checks incomplete')
for mode in ['','-optimized']:
 log=(public/'checks'/('python-return-v2'+mode+'.log')).read_text();require('Ran 105 tests' in log and '\nOK\n' in log,'Python receipt incomplete')
require(sum(c['passes'] for c in checkpoint['cohorts'] if c['category']=='final-positive')==checkpoint['final_native_executions'],'Final total mismatch')
for p in [*public.rglob('*'),*pictures.rglob('*')]:
 if p.is_file() and p.suffix!='.png':require((b'/' + b'Users' + b'/') not in p.read_bytes(),'Host path remains')
result={'completed':True,'public_files':len(expected),'sources':len(checkpoint['source_hashes']),'bindings':len(checkpoint['original_public_bindings']),'projections':len(checkpoint['allowlisted_projections']),'retained_capture_files':len(checkpoint['retained_capture_hashes']),'selected_frame_sets':len(checkpoint['selected_frame_sets']),'parseable_xmls':len(xmls),'final_native_executions':checkpoint['final_native_executions'],'final_native_cleanup_checks':checkpoint['final_native_cleanup_checks'],'git_bytes_checked':args.git}
print(json.dumps(result,indent=2))
