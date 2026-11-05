from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.path.insert(0,'scripts');spec=importlib.util.spec_from_file_location('native_runner','scripts/test-webview-engine.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
b=Path('artifacts/native-input');sha=lambda d:hashlib.sha256(d).hexdigest();rows=[]
for kind,serial,avd,cohort in [('legacy','emulator-5598','Droidcast_WebView_API26_27caf3','legacy-owned-full-v5'),('modern','emulator-5580','Tinycast_Tablet_API_36','modern-owned-full-v5')]:
 p=b/cohort;r=json.loads((p/'record.json').read_text());ime_before=json.loads((p/'private-ime-settings.json').read_text());m.require(set(ime_before)==set(m.IME_SETTING_KEYS),'Four-field baseline required')
 adb=['<host-home>/Library/Android/sdk/platform-tools/adb','-s',serial];checks={}
 def raw(*args):return subprocess.check_output(adb+list(args),stderr=subprocess.STDOUT,timeout=60)
 def text(*args):return raw(*args).decode().strip()
 def check(name,condition):
  checks[name]={'passed':bool(condition)}
  m.require(condition,'Final preservation failed: '+kind+' '+name)
 check('exact-emulator-and-api',text('emu','avd','name').splitlines()[0]==avd and text('shell','getprop','ro.build.version.sdk')==str(r['api']))
 check('idle-unprivileged-adbd',text('shell','id').startswith('uid=2000(') and 'ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes'))
 values=raw('shell','for key in '+' '.join(m.IME_SETTING_KEYS)+'; do settings get secure "$key"; done').decode().splitlines()
 check('four-original-ime-settings',len(values)==len(m.IME_SETTING_KEYS) and dict(zip(m.IME_SETTING_KEYS,values))==ime_before)
 check('original-geometry-and-raw-font',text('shell','wm','size')==r['original_geometry']['size'] and text('shell','wm','density')==r['original_geometry']['density'] and text('shell','settings','get','system','font_scale')==r['original_font_scale'])
 if kind=='legacy':
  check('original-package-absence',all(not text('shell','pm','list','packages',pkg) for pkg in [m.APP,m.TEST]))
 else:
  original=m.archive_files((p/'private-data-before.tar').read_bytes());expected={name:sha(value) for name,value in original.items() if not name.startswith('files/native-input-lease/')}
  current=m.archive_files(raw('exec-out','run-as',m.APP,'tar','-cf','-','files','shared_prefs'))
  check('all-original-files-after-original-apks', {name:sha(value) for name,value in current.items()}==expected)
  check('application-unprivileged',not text('shell','run-as',m.APP,'id').startswith(('uid=0(','uid=1000(','uid=2000(')))
  for pkg in [m.APP,m.TEST]:
   path=text('shell','pm','path',pkg);m.require(path.count('package:')==1,'Unexpected package layout')
   check('original-apk-'+pkg,sha(raw('exec-out','cat',path.removeprefix('package:')))==sha((p/('private-original-'+pkg+'.apk')).read_bytes()))
 rows.append({'device':kind,'checks':checks,'preserved_file_count':len(expected) if kind=='modern' else None})
result={'completed':True,'scope':'Read-only final state after all current installs/replacements, relative to the four-setting baselines after initial probe-history cleanup; original pre-probe IME-history identity remains unverified','collector_sha256':sha(Path(__file__).read_bytes()),'rows':rows}
(b/'final-preservation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'completed':True,'checks':sum(len(r['checks']) for r in rows),'modern_files':rows[1]['preserved_file_count']}))
