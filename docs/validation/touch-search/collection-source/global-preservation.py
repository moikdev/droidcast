from pathlib import Path
import hashlib,json,os,subprocess,sys
base=Path('artifacts/touch-clear');private=base/'private-initial-settings.json'
adb='<android-sdk>/platform-tools/adb'
keys=('default_input_method','enabled_input_methods','selected_input_method_subtype','input_methods_subtype_history')
def read(serial):
 command='; '.join('settings get secure '+k for k in keys)
 values=subprocess.check_output([adb,'-s',serial,'shell',command],text=True).splitlines()
 assert len(values)==len(keys)
 return dict(zip(keys,values))
if sys.argv[1]=='before':
 assert not private.exists(),'Snapshot already exists; never replace the original baseline'
 values={s:read(s) for s in ('emulator-5580','emulator-5598')}
 private.write_text(json.dumps(values));private.chmod(0o600)
 (base/'initial-settings-receipt.json').write_text(json.dumps({'four_raw_secure_settings_captured_privately_per_device':True,'scope':'Current baseline after PR109; original earlier pre-probe history identity remains uncertified'})+'\n')
 print('Saved current baseline privately; no device mutation.')
else:
 assert sys.argv[1]=='after'
 values=json.loads(private.read_text())
 checks={s+'-four-ime-fields':read(s)==v for s,v in values.items()}
 for s in values:
  def text(*args):return subprocess.check_output([adb,'-s',s,*args],text=True).strip()
  checks[s+'-unprivileged']=text('shell','id').startswith('uid=2000(')
  checks[s+'-no-active-instrumentation']='ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes')
  checks[s+'-geometry-not-overridden']='Override' not in text('shell','wm','size')+text('shell','wm','density')
  cohort='old-tablet100-1' if s=='emulator-5580' else 'verified-api26-established-clear-phone100'
  original_font=json.loads((base/cohort/'record.json').read_text())['original_font_scale']
  checks[s+'-original-font-scale']=text('shell','settings','get','system','font_scale')==original_font
 if (base/'old-tablet100-1/private-data-before.tar').exists():
  import importlib.util,sys
  sys.path.insert(0,'scripts')
  spec=importlib.util.spec_from_file_location('owned_runner','scripts/test-webview-engine.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
  original=runner.archive_files((base/'old-tablet100-1/private-data-before.tar').read_bytes())
  expected={p:hashlib.sha256(b).hexdigest() for p,b in original.items() if not p.startswith('files/touch-search-lease/')}
  data=runner.private_app_archive([adb,'-s','emulator-5580'],base/'private-global-after.tar')
  actual={p:hashlib.sha256(b).hexdigest() for p,b in data.items()}
  checks['all-original-modern-app-and-evidence-files']=actual==expected
  for pkg in ('dev.tinycast.android','dev.tinycast.android.test'):
   original=(base/'old-tablet100-1'/('private-original-'+pkg+'.apk')).read_bytes()
   location=subprocess.check_output([adb,'-s','emulator-5580','shell','pm','path',pkg],text=True).strip().removeprefix('package:')
   actual=subprocess.check_output([adb,'-s','emulator-5580','exec-out','cat',location])
   checks['original-apk-'+pkg]=hashlib.sha256(actual).digest()==hashlib.sha256(original).digest()
  checks['legacy-original-package-absence']=not any(subprocess.check_output([adb,'-s','emulator-5598','shell','pm','list','packages',pkg],text=True).strip() for pkg in ('dev.tinycast.android','dev.tinycast.android.test'))
 r={'checks':checks,'all_passed':all(checks.values()),'scope':'Read-only current-baseline equality after the whole pipeline; no private original contents or fingerprints published'}
 (base/'final-preservation-receipt.json').write_text(json.dumps(r,indent=2)+'\n')
 print(json.dumps(r));assert all(checks.values())
