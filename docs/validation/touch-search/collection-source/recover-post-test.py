from pathlib import Path
import hashlib,importlib.util,json,re,shlex,subprocess,sys,time
sys.path.insert(0,'scripts');spec=importlib.util.spec_from_file_location('owned_runner','scripts/test-webview-engine.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
b=Path('artifacts/touch-clear');failed=b/'final-tablet200-v2';adb=['<android-sdk>/platform-tools/adb','-s','emulator-5580'];app='dev.tinycast.android';test=app+'.test';lease='files/touch-search-lease';evidence='files/touch-search-evidence';checks={};report=b/'post-test-recovery.json'
sha=lambda v:hashlib.sha256(v).hexdigest()
def raw(*args,data=None):
 kw={'stdin':subprocess.DEVNULL} if data is None else {'input':data}
 return subprocess.check_output(adb+list(args),stderr=subprocess.STDOUT,timeout=60,**kw)
def text(*args):return raw(*args).decode().strip()
def check(name,condition):
 checks[name]=bool(condition);report.write_text(json.dumps({'completed':False,'checks':checks},indent=2)+'\n')
 if not condition:raise ValueError(name)
check('owned-avd',text('emu','avd','name').splitlines()[0]=='Tinycast_Tablet_API_36')
check('api36',text('shell','getprop','ro.build.version.sdk')=='36')
check('unprivileged-adbd',text('shell','id').startswith('uid=2000('))
check('idle-instrumentation','ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes'))
owner=json.loads((failed/'private-owner.json').read_text())
check('owner-namespace',set(owner)=={'kind','nonce','extension_id','fresh_disposable_avd'} and owner['kind']=='touch-search-v1' and re.fullmatch('[0-9a-f]{32}',owner['nonce']) is not None and owner['extension_id']=='touch-search-'+owner['nonce'] and owner['fresh_disposable_avd'] is False)
def verify_owner():check('live-owner-exact',json.loads(raw('exec-out','run-as',app,'cat',lease+'/owner.json'))==owner)
verify_owner()
receipt=json.loads((failed/'record.json').read_text())
for pkg,key in ((app,'apk_sha256'),(test,'test_apk_sha256')):
 path=text('shell','pm','path',pkg);check('candidate-package-'+pkg,path.count('package:')==1 and sha(raw('exec-out','cat',path.removeprefix('package:')))==receipt[key])
frames=runner.archive_files(raw('exec-out','run-as',app,'tar','-cf','-',evidence));captures=failed/'captures';captures.mkdir(exist_ok=False)
manifest={}
for path,data in frames.items():
 p=Path(path);check('registered-capture-names',str(p.parent)==evidence and re.fullmatch(r'[a-z0-9-]+\.(png|xml|json)',p.name) is not None)
 (captures/p.name).write_bytes(data);manifest[p.name]=sha(data)
log=(failed/'tests.log').read_text();observed={'passes':len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0$',log,re.M)),'failures':len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: -2$',log,re.M))}
check('raw-junit-two-passes',observed=={'passes':2,'failures':0} and 'OK (2 tests)' in log and receipt['instrumentation_exit_code']==0)
(failed/'recovered-captures.json').write_text(json.dumps({'captures':manifest,'raw_junit':observed,'app_sha256':receipt['apk_sha256'],'test_apk_sha256':receipt['test_apk_sha256'],'completed_protocol':False,'scope':'Actual pre-reboot test files recovered under the retained owner. The runner failed its idle/cleanup protocol; these raw passes earn no final-cohort credit.'},indent=2)+'\n')
for pkg in (test,app):raw('shell','am','force-stop',pkg)
verify_owner()
id=owner['extension_id'];owned=['files/extensions/'+id,'cache/'+id,'files/extension-data/'+id,'shared_prefs/extension-'+id+'.xml','shared_prefs/extension-'+id+'.xml.bak',evidence]
raw('shell','run-as',app,'rm','-rf',*owned)
original=runner.archive_files((failed/'private-data-before.tar').read_bytes());expected={p:sha(v) for p,v in original.items() if not p.startswith(lease+'/')}
check('palette-before-bytes-bound',(failed/'private-palette.xml').read_bytes()==original['shared_prefs/palette.xml'] and 'shared_prefs/palette.xml.bak' not in original)
raw('shell','run-as',app,'rm','-f','shared_prefs/palette.xml.bak');raw('shell','run-as',app,'tee','shared_prefs/palette.xml',data=(failed/'private-palette.xml').read_bytes())
def restore_profile():
 value=original.get('files/profileInstalled')
 if value is None:raw('shell','run-as',app,'rm','-f','files/profileInstalled')
 else:raw('shell','run-as',app,'tee','files/profileInstalled',data=value)
restore_profile()
font=receipt['original_font_scale'];raw('shell','settings','delete','system','font_scale') if font=='null' else raw('shell','settings','put','system','font_scale',font)
check('original-font',text('shell','settings','get','system','font_scale')==font)
check('original-size',text('shell','wm','size')==receipt['original_geometry']['size']);check('original-density',text('shell','wm','density')==receipt['original_geometry']['density'])
initial_ime=json.loads((b/'private-initial-settings.json').read_text())['emulator-5580']
def ime_fields():
 values=text('shell','; '.join('settings get secure '+k for k in initial_ime)).splitlines();check('four-ime-values-readable',len(values)==4);return dict(zip(initial_ime,values))
current=ime_fields()
if current!=initial_ime:
 require=runner.require;require(re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+',initial_ime['default_input_method']),'Original IME component invalid')
 raw('shell','ime','set',shlex.quote(initial_ime['default_input_method']))
 for key,value in initial_ime.items():
  if value=='null':raw('shell','settings','delete','secure',key)
  else:raw('shell','settings','put','secure',key,shlex.quote(value))
check('original-four-ime-fields',ime_fields()==initial_ime)
for pkg in (app,test):
 verify_owner();p=failed/('private-original-'+pkg+'.apk');raw('install','-r','-t',str(p))
 location=text('shell','pm','path',pkg).removeprefix('package:');check('original-binary-'+pkg,sha(raw('exec-out','cat',location))==sha(p.read_bytes()))
for pkg in (test,app):raw('shell','am','force-stop',pkg)
restore_profile()
after=runner.archive_files(raw('exec-out','run-as',app,'tar','-cf','-','files','shared_prefs'))
check('all-original-files-and-preferences',{p:sha(v) for p,v in after.items() if not p.startswith(lease+'/')}==expected)
check('final-four-ime-fields',ime_fields()==initial_ime)
check('final-font',text('shell','settings','get','system','font_scale')==font)
check('final-idle','ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes'))
verify_owner()
# Final device operation; all restoration and verification precede release.
raw('shell','run-as',app,'rm','-rf',lease);checks['release-owner-last']=True
report.write_text(json.dumps({'completed':True,'checks':checks,'preserved_original_file_count':len(expected),'recovered_capture_files':len(manifest),'raw_junit':observed,'scope':'Separate recovery after guest/console stall and graceful restart of the same persisted AVD. Original APKs, files/shared_prefs, geometry, font and four raw IME fields verified before final release. No whole-system or cross-boot logical-identity certification; private originals and owners are excluded.'},indent=2)+'\n')
print(json.dumps({'completed':True,'checks':len(checks),'original_files':len(expected),'recovered_capture_files':len(manifest),'raw_junit':observed}))
