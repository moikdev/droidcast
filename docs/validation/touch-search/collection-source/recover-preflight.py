from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys,time
sys.path.insert(0,'scripts')
spec=importlib.util.spec_from_file_location('owned_runner','scripts/test-webview-engine.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
b=Path('artifacts/touch-clear');failed=b/'final-tablet200';adb=['<android-sdk>/platform-tools/adb','-s','emulator-5580'];app='dev.tinycast.android';lease='files/touch-search-lease'
checks={};report=b/'preflight-recovery.json'
def raw(*args):return subprocess.check_output(adb+list(args),stderr=subprocess.STDOUT,timeout=60)
def text(*args):return raw(*args).decode().strip()
def check(name,condition):
 checks[name]=bool(condition)
 report.write_text(json.dumps({'completed':False,'checks':checks,'scope':'Pre-install archive failure; verify current originals before releasing the retained owner. No candidate installs or tests occurred.'},indent=2)+'\n')
 if not condition:raise ValueError(name)
check('no-native-log-created',not (failed/'tests.log').exists())
check('owned-avd',text('emu','avd','name').splitlines()[0]=='Tinycast_Tablet_API_36')
check('api36',text('shell','getprop','ro.build.version.sdk')=='36')
check('unprivileged-adbd',text('shell','id').startswith('uid=2000('))
check('idle-instrumentation','ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes'))
owner=json.loads((failed/'private-owner.json').read_text())
check('host-owner-namespace',set(owner)=={'kind','nonce','extension_id','fresh_disposable_avd'} and owner['kind']=='touch-search-v1' and re.fullmatch('[0-9a-f]{32}',owner['nonce']) is not None and owner['extension_id']=='touch-search-'+owner['nonce'] and owner['fresh_disposable_avd'] is False)
check('live-owner-exact',json.loads(raw('exec-out','run-as',app,'cat',lease+'/owner.json'))==owner)
for pkg in (app,app+'.test'):
 path=text('shell','pm','path',pkg);check('one-original-package-'+pkg,path.count('package:')==1)
 data=raw('exec-out','cat',path.removeprefix('package:'))
 check('original-apk-'+pkg,hashlib.sha256(data).digest()==hashlib.sha256((failed/('private-original-'+pkg+'.apk')).read_bytes()).digest())
initial=runner.archive_files((b/'old-tablet100-1/private-data-before.tar').read_bytes())
expected={p:hashlib.sha256(v).hexdigest() for p,v in initial.items() if not p.startswith(lease+'/')}
current=runner.archive_files(raw('exec-out','run-as',app,'tar','-cf','-','files','shared_prefs'))
check('lease-has-only-registered-owner',{p for p in current if p.startswith(lease+'/')}=={lease+'/owner.json'})
actual={p:hashlib.sha256(v).hexdigest() for p,v in current.items() if not p.startswith(lease+'/')}
check('all-original-files-and-preferences-equal',actual==expected)
r=json.loads((failed/'record.json').read_text())
check('original-size',text('shell','wm','size')==r['original_geometry']['size'])
check('original-density',text('shell','wm','density')==r['original_geometry']['density'])
check('original-raw-font',text('shell','settings','get','system','font_scale')==r['original_font_scale'])
check('owner-revalidated-last',json.loads(raw('exec-out','run-as',app,'cat',lease+'/owner.json'))==owner)
# Final device operation; all equality checks precede ownership release.
raw('shell','run-as',app,'rm','-rf',lease);checks['release-owner-last']=True
report.write_text(json.dumps({'completed':True,'checks':checks,'preserved_original_file_count':len(expected),'scope':'Zero-test pre-install archive timeout; original binaries, all files/shared_prefs, geometry and raw font matched before release. No restore writes were needed. Private originals/owners are not published; no cross-boot certification.'},indent=2)+'\n')
print(json.dumps({'completed':True,'checks':len(checks),'preserved_original_files':len(expected)}))
