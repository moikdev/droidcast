from pathlib import Path
import hashlib,importlib.util,json,re,shlex,subprocess,sys
sys.path.insert(0,'scripts');spec=importlib.util.spec_from_file_location('owned_runner','scripts/test-webview-engine.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
b=Path('artifacts/touch-clear');failed=b/'final-tablet200-v2';report=b/'post-test-recovery.json';prior=json.loads(report.read_text());assert not prior['completed'] and all(prior['checks'].values());assert not (b/'post-test-recovery-interrupted.json').exists();(b/'post-test-recovery-interrupted.json').write_bytes(report.read_bytes())
checks=prior['checks'];adb=['<android-sdk>/platform-tools/adb','-s','emulator-5580'];app='dev.tinycast.android';lease='files/touch-search-lease'
def raw(*args):return subprocess.check_output(adb+list(args),stdin=subprocess.DEVNULL,stderr=subprocess.STDOUT,timeout=30)
def text(*args):return raw(*args).decode().strip()
def check(name,value):
 checks[name]=bool(value);report.write_text(json.dumps({'completed':False,'checks':checks},indent=2)+'\n')
 if not value:raise ValueError(name)
owner=json.loads((failed/'private-owner.json').read_text());check('continuation-owner-exact',json.loads(raw('exec-out','run-as',app,'cat',lease+'/owner.json'))==owner)
check('continuation-owned-avd',text('emu','avd','name').splitlines()[0]=='Tinycast_Tablet_API_36')
check('continuation-idle','ActiveInstrumentation{' not in text('shell','-n','dumpsys','activity','processes'))
script='find files shared_prefs ! -type f ! -type d -print';check('no-unexpected-nonregular-paths',raw('shell','-n','run-as',app,'sh','-c',shlex.quote(script)).strip()==b'')
original=runner.archive_files((failed/'private-data-before.tar').read_bytes());expected={p:hashlib.sha256(v).hexdigest() for p,v in original.items() if not p.startswith(lease+'/')};assert all(not any(c in p for c in '\n\r\\') for p in expected)
script='find files shared_prefs -type f -exec sha256sum {} +';actual={}
for line in raw('shell','-n','run-as',app,'sh','-c',shlex.quote(script)).decode().splitlines():
 assert re.fullmatch('[0-9a-f]{64}',line[:64]) and line[64:66]=='  ';path=line[66:];assert path not in actual;actual[path]=line[:64]
check('lease-only-registered-owner',{p for p in actual if p.startswith(lease+'/')}=={lease+'/owner.json'})
check('all-original-files-and-preferences',{p:h for p,h in actual.items() if not p.startswith(lease+'/')}==expected)
r=json.loads((failed/'record.json').read_text())
for pkg in (app,app+'.test'):
 location=text('shell','pm','path',pkg).removeprefix('package:');check('final-original-binary-'+pkg,hashlib.sha256(raw('exec-out','cat',location)).digest()==hashlib.sha256((failed/('private-original-'+pkg+'.apk')).read_bytes()).digest())
check('final-size',text('shell','wm','size')==r['original_geometry']['size']);check('final-density',text('shell','wm','density')==r['original_geometry']['density']);check('final-font',text('shell','settings','get','system','font_scale')==r['original_font_scale'])
ime=json.loads((b/'private-initial-settings.json').read_text())['emulator-5580'];values=text('shell','; '.join('settings get secure '+k for k in ime)).splitlines();check('final-four-ime-fields',dict(zip(ime,values))==ime and len(values)==4)
check('final-unprivileged',text('shell','id').startswith('uid=2000('));check('final-owner-revalidated',json.loads(raw('exec-out','run-as',app,'cat',lease+'/owner.json'))==owner)
raw('shell','run-as',app,'rm','-rf',lease);checks['release-owner-last']=True
manifest=json.loads((failed/'recovered-captures.json').read_text())
report.write_text(json.dumps({'completed':True,'checks':checks,'preserved_original_file_count':len(expected),'recovered_capture_files':len(manifest['captures']),'raw_junit':manifest['raw_junit'],'verification':'All original regular-file hashes computed on device and compared with private before-data bytes; nonregular paths rejected. Large tar re-export timed out; original failed receipts remain failed.','scope':'Separate recovery after graceful restart of the same persisted AVD. APKs, files/shared_prefs, geometry, raw font and four IME fields verified before final release. Private originals excluded; no whole-system/cross-boot logical-identity certification.'},indent=2)+'\n');print(json.dumps({'completed':True,'checks':len(checks),'original_files':len(expected),'recovered_capture_files':len(manifest['captures'])}))
