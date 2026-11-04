from pathlib import Path
import hashlib,json,re,subprocess,xml.etree.ElementTree as E,zipfile
base=Path('artifacts/command-handoff');public=Path('docs/validation/command-handoff')
read=lambda p:json.loads(Path(p).read_text());sha=lambda b:hashlib.sha256(b).hexdigest()
def require(value,message):
 if not value:raise RuntimeError(message)
c=read(public/'checkpoint.json')
for path,digest in c['public_files'].items():require(sha(Path(path).read_bytes())==digest,'Public hash differs: '+path)
for path,digest in c['source_hashes'].items():
 require(sha(Path(path).read_bytes())==digest,'Current source differs: '+path)
 require(sha(subprocess.check_output(['git','show','73e719c602bc378c71df83eba34440771bc8d999:'+path]))==digest,'Frozen final source differs')
for row in c['original_public_bindings']:
 path=base/row['artifact'];require(not any(p.startswith('private-') for p in path.parts),'Private original is forbidden')
 data=path.read_bytes();require(sha(data)==row['original_sha256'],'Original hash differs')
 if row['transformation']!='identity':data=data.decode().replace(str(Path.home()),'&lt;user&gt;' if Path(row['public']).suffix=='.xml' else '<user>').encode()
 require(data==Path(row['public']).read_bytes() and sha(data)==row['public_sha256'],'Publication transform differs')
for row in c['allowlisted_projections']:
 value=read(base/row['artifact']);expected={k:value[k] for k in row['allowed_keys'] if k in value}
 if row['cleanup_projection']:expected['cleanup']={k:{'passed':r.get('passed') is True} for k,r in value['cleanup'].items()}
 require(expected==read(row['public']),'Projection differs')
for path,digest in c['retained_capture_hashes'].items():require(sha((base/path).read_bytes())==digest,'Capture original differs')
expected_total=pass_total=cleanup_total=final_pass=final_cleanup=0
for row in c['cohorts']:
 dest=public/'cohorts'/row['name'];state=read(dest/'state.json');runner=read(dest/'runner-record.json');log=(dest/'tests.log').read_text()
 passes=len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0$',log,re.M));require(passes==row['passes']==runner['passes'],'JUnit count differs')
 require(runner['installed_apks_match'] and runner['apk_sha256']==row['app_sha256'] and runner['test_apk_sha256']==row['test_apk_sha256'],'Installed APK identity differs')
 require(all(v['passed'] for v in runner['cleanup'].values()),'Restoration failed')
 for key,name in [('apk_sha256','app-debug.apk'),('test_apk_sha256','app-debug-androidTest.apk')]:require(sha((base/row['name']/'cohort'/name).read_bytes())==runner[key],'Cohort archive differs')
 if row['category']=='final-positive':
  require(state['completed'] and passes==runner['expected'],'Incomplete positive')
  require(row['app_sha256']=='2a41ea1407cccf23b70afada6fde64de55bc0bd3d8f035645a44532b12d14e3d','Wrong final runtime')
  final_pass+=passes;final_cleanup+=len(runner['cleanup'])
  for p in (dest/'cases').glob('*-record.json'):require(read(p).get('passed') is True,'Failed native case in positive cohort')
  if row['name'].startswith('final-quick-'):
   cases=list((dest/'cases').glob('*-record.json'));require(len(cases)==1,'Missing real workflow receipt');case=read(cases[0])
   require(case['workflow_outcome']=='working-observed-path' and case['handoff_target']=='quick-translate-view' and case['child_raw_input_grant_absent'] and case['copy_exact'] and case['returned_to_external_caller'] and case['source_text_and_selection_unchanged'],'Missing actual handoff/Copy/source witness')
  if row['name'].startswith('protocol-'):
   case=read(next((dest/'cases').glob('*-record.json')));probe=case['paused_parent_probe']
   require(probe['acks']==1 and probe['copiesAttempted']==1 and probe['copiesResolved']==0 and probe['statusesAttempted']==1 and probe['publicationsAttempted']==1,'Missing obsolete-parent witness')
 if row['category']=='final-matched-negative':
  needle='Disposed Back must not close the recreated child' if 'recreation' in row['name'] else 'Disposed Preferences must not replace the restored command'
  require(passes==0 and needle in log,'Wrong intended negative')
 expected_total+=runner['expected'];pass_total+=passes;cleanup_total+=len(runner['cleanup'])
require((expected_total,pass_total,cleanup_total)==(c['total_executions'],c['total_passes'],c['total_cleanup_checks']),'Totals differ')
require((final_pass,final_cleanup)==(c['final_native_executions'],c['final_cleanup_checks']),'Final totals differ')
for folder in ['build-candidate-v1','build-candidate-v2']:
 value=read(public/'identities'/(folder+'.json'))
 for path,digest in value['source_hashes'].items():require(sha(subprocess.check_output(['git','show',value['source_commit']+':'+path]))==digest,'Historical build source differs')
 for name,digest in value['apk_hashes'].items():require(sha((base/folder/'candidate'/name).read_bytes())==digest,'Build APK differs')
for folder in ['owned-harness-v1','owned-harness-v2','owned-harness-v3']:
 value=read(public/'identities'/(folder+'.json'))
 for path,digest in value['source_hashes'].items():require(sha(subprocess.check_output(['git','show',value['test_source_commit']+':'+path]))==digest,'Harness source differs')
 require(sha((base/folder/'app-debug-androidTest.apk').read_bytes())==value['test_apk_sha256'],'Harness APK differs')
assets={}
for label,path in [('old',base/'common-v5/app-debug.apk'),('current',base/'build-candidate-v2/candidate/app-debug.apk')]:
 with zipfile.ZipFile(path) as z:assets[label]={n:sha(z.read(n)) for n in z.namelist() if n.startswith('assets/') and not n.endswith('/')}
require(len(assets['old'])==10 and assets['old']==assets['current'],'Packaged asset changed')
for path in public.rglob('*.xml'):E.parse(path)
for frame in c['selected_frame_sets']:
 path=public/'cohorts'/frame['cohort']/'captures'/(frame['stem']+'.xml');require(b'\r\n' in path.read_bytes(),'Raw capture CRLF changed')
reports=list((public/'checks/build-candidate-v2/reports/jvm').glob('TEST-*.xml'));cases=[]
for p in reports:
 root=E.parse(p).getroot();require(not root.findall('.//failure') and not root.findall('.//error') and not root.findall('.//skipped'),'JVM outcome failed');cases.extend(root.findall('testcase'))
require(len(cases)==289,'JVM case count differs')
print(json.dumps(dict(verified=True,public_files=len(c['public_files']),sources=len(c['source_hashes']),bindings=len(c['original_public_bindings']),projections=len(c['allowlisted_projections']),capture_hashes=len(c['retained_capture_hashes']),frames=len(c['selected_frame_sets']),parseable_xml=len(list(public.rglob('*.xml'))),final_executions=final_pass,final_cleanup=final_cleanup,total_executions=expected_total,total_passes=pass_total,total_cleanup=cleanup_total),indent=2))
