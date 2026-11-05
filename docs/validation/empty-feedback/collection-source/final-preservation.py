from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
b=Path('artifacts/empty-feedback');assert json.loads((b/'native-v3.json').read_text())['completed'];out=b/'final-preservation.json';assert not out.exists()
adb='<android-sdk>/platform-tools/adb';keys=('default_input_method','enabled_input_methods','selected_input_method_subtype','input_methods_subtype_history');original=json.loads((b/'private-initial-settings.json').read_text());checks={}
for serial in original:
 def get(*a):return subprocess.check_output([adb,'-s',serial,*a],text=True,timeout=60).strip()
 rows=get('shell','for key in '+' '.join(keys)+'; do settings get secure "$key"; done').splitlines();checks[serial+'-four-ime-fields']=len(rows)==4 and dict(zip(keys,rows))==original[serial]
 checks[serial+'-unprivileged']=get('shell','id').startswith('uid=2000(')
 checks[serial+'-idle']='ActiveInstrumentation{' not in get('shell','dumpsys','activity','processes')
 checks[serial+'-geometry-not-overridden']='Override' not in get('shell','wm','size')+get('shell','wm','density')
 cohort='old-landscape100' if serial=='emulator-5580' else 'final-api26-phone100'
 checks[serial+'-original-font']=get('shell','settings','get','system','font_scale')==json.loads((b/cohort/'record.json').read_text())['original_font_scale']
sys.path.insert(0,'scripts');spec=importlib.util.spec_from_file_location('runner','scripts/test-webview-engine.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
first=b/'old-landscape100';before=runner.archive_files((first/'private-data-before.tar').read_bytes());before={p:hashlib.sha256(v).hexdigest() for p,v in before.items() if not p.startswith('files/touch-search-lease/')};after=runner.private_app_archive([adb,'-s','emulator-5580'],b/'private-global-after.tar');checks['all-original-modern-app-evidence-files']=before=={p:hashlib.sha256(v).hexdigest() for p,v in after.items()}
for pkg in ('dev.tinycast.android','dev.tinycast.android.test'):
 old=(first/('private-original-'+pkg+'.apk')).read_bytes();location=subprocess.check_output([adb,'-s','emulator-5580','shell','pm','path',pkg],text=True,timeout=30).strip().removeprefix('package:');now=subprocess.check_output([adb,'-s','emulator-5580','exec-out','cat',location],timeout=60);checks['original-apk-'+pkg]=hashlib.sha256(old).digest()==hashlib.sha256(now).digest()
checks['legacy-original-package-absence']=not any(subprocess.check_output([adb,'-s','emulator-5598','shell','pm','list','packages',pkg],text=True,timeout=30).strip() for pkg in ('dev.tinycast.android','dev.tinycast.android.test'))
r={'all_passed':all(checks.values()),'checks':checks,'original_modern_file_count':len(before),'scope':'Read-only final equality to this slice current baseline after PR112; private originals/settings and fingerprints excluded. No whole-system/cross-boot identity certification; older pre-probe IME history remains uncertified.'};out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps({'all_passed':r['all_passed'],'checks':len(checks),'files':len(before)}));assert r['all_passed']
