from pathlib import Path
import hashlib,importlib.util,json,shlex,subprocess,sys
sys.path.insert(0,'scripts')
from android_test_cleanup import Cleanup
spec=importlib.util.spec_from_file_location('engine_runner','scripts/test-webview-engine.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
p=Path('artifacts/native-input/modern-strict-control-v2');original_record=(p/'record.json').read_bytes();prior=json.loads(original_record)
require=m.require;sha=lambda b:hashlib.sha256(b).hexdigest();app=m.APP;test=m.TEST
adb=['<host-home>/Library/Android/sdk/platform-tools/adb','-s','emulator-5580']
def raw(*args,data=None):return subprocess.check_output(adb+list(args),input=data,stderr=subprocess.STDOUT,timeout=120)
def text(*args):return raw(*args).decode().strip()
owner=json.loads((p/'private-owner.json').read_text());lease='files/native-input-lease';identity=owner['extension_id']
require(owner['kind']=='native-input-v1' and owner['input_mode']=='owned' and identity=='native-input-'+owner['nonce'] and len(owner['nonce'])==32,'Invalid recovery owner')
require(text('emu','avd','name').splitlines()[0]=='Tinycast_Tablet_API_36' and text('shell','getprop','ro.build.version.sdk')=='36','Wrong emulator')
require(not prior['completed'] and prior['passes']==0 and prior['failures']==1,'Unexpected original control result')
def owned():
 require('ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes'),'Instrumentation active')
 require(json.loads(raw('exec-out','run-as',app,'cat',lease+'/owner.json'))==owner,'Recovery ownership changed')
def ime():
 values=raw('shell','for key in '+' '.join(m.IME_SETTING_KEYS)+'; do settings get secure "$key"; done').decode().splitlines()
 require(len(values)==len(m.IME_SETTING_KEYS),'Invalid settings response');return dict(zip(m.IME_SETTING_KEYS,values))
saved_ime=json.loads((p/'private-ime-settings.json').read_text())
archive=m.archive_files((p/'private-data-before.tar').read_bytes());expected={name:sha(value) for name,value in archive.items() if not name.startswith(lease+'/')}
palette=(p/'private-palette.xml').read_bytes()
result={'completed':False,'original_control_still_failed':True,'original_native_passes':0,'original_native_failures':1,'original_record_sha256':sha(original_record),'collector_sha256':sha(Path(__file__).read_bytes()),'checks':{}}
c=Cleanup({'cleanup':result['checks']})
def check_ime():require(ime()==saved_ime,'Original four-field IME snapshot differs')
def settings():
 require(text('shell','wm','size')==prior['original_geometry']['size'] and text('shell','wm','density')==prior['original_geometry']['density'] and text('shell','settings','get','system','font_scale')==prior['original_font_scale'],'Original geometry/font differs')
def uid():require(text('shell','id').startswith('uid=2000(') and not text('shell','run-as',app,'id').startswith(('uid=0(','uid=1000(','uid=2000(')),'Privilege differs')
def remove_owned():
 owned();paths=['files/extensions/'+identity,'cache/'+identity,'files/extension-data/'+identity,'shared_prefs/extension-'+identity+'.xml','shared_prefs/extension-'+identity+'.xml.bak','files/native-input-evidence']
 raw('shell','run-as',app,'rm','-rf',*paths)
 for path in paths:
  command='run-as '+shlex.quote(app)+' /system/bin/sh -c '+shlex.quote('test -e "$1"')+' x '+shlex.quote(path)
  require(m.private_path_absent(subprocess.run(adb+['shell',command],capture_output=True,timeout=30)),'Owned path remains')
def restore_palette():
 raw('shell','run-as',app,'rm','-f','shared_prefs/palette.xml.bak');raw('shell','run-as',app,'tee','shared_prefs/palette.xml',data=palette)
 require(raw('exec-out','run-as',app,'cat','shared_prefs/palette.xml')==palette,'Original palette differs')
def profile():
 value=archive.get('files/profileInstalled')
 if value is None:raw('shell','run-as',app,'rm','-f','files/profileInstalled')
 else:raw('shell','run-as',app,'tee','files/profileInstalled',data=value)
def data():
 actual=m.archive_files(raw('exec-out','run-as',app,'tar','-cf','-','files','shared_prefs'))
 require({name:sha(value) for name,value in actual.items() if not name.startswith(lease+'/')}==expected,'Original installed data differs')
 result['preserved_file_count']=len(expected)
def restore_binary(package):
 original=p/('private-original-'+package+'.apk');expected_hash=sha(original.read_bytes())
 raw('install','-r','-t',str(original));path=text('shell','pm','path',package);require(path.count('package:')==1,'Unexpected package layout')
 require(sha(raw('exec-out','cat',path.removeprefix('package:')))==expected_hash,'Original installed APK differs')
try:
 for label,action in [('idle-and-exact-owner',owned),('four-original-ime-settings',check_ime),('stop-test',lambda:raw('shell','am','force-stop',test)),('stop-app',lambda:raw('shell','am','force-stop',app)),('remove-owned-only',remove_owned),('restore-palette-bytes',restore_palette),('restore-profile-marker',profile),('original-settings',settings),('original-data-before-apks',data),('restore-original-app',lambda:restore_binary(app)),('restore-original-test',lambda:restore_binary(test)),('original-ime-after-apks',check_ime),('original-data-after-apks',data),('original-privilege',uid),('revalidate-owner-last',owned)]:
  if c.errors:break
  c.attempt(label,action)
 if not c.errors:c.attempt('release-owner-last',lambda:raw('shell','run-as',app,'rm','-rf',lease))
 result['completed']=not c.errors
finally:
 require((p/'record.json').read_bytes()==original_record,'Original failed receipt changed')
 (p/'recovery.json').write_text(json.dumps(result,indent=2)+'\n')
c.finish();print(json.dumps({'completed':result['completed'],'checks':len(result['checks']),'preserved_file_count':result.get('preserved_file_count')}))
