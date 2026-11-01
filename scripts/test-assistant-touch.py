#!/usr/bin/env python3
from pathlib import Path
import subprocess,json,hashlib,re,shutil,os,argparse
p=argparse.ArgumentParser(description='Verify assistant touch activation on an explicitly owned emulator')
p.add_argument('--serial',required=True);p.add_argument('--avd',required=True)
p.add_argument('--layout',choices=('tablet','phone'),required=True);p.add_argument('--output',type=Path,required=True)
p.add_argument('--classes',default='dev.tinycast.android.AssistantTouchDeviceTest,dev.tinycast.android.AssistantDeviceTest,dev.tinycast.android.SettingsDeviceTest,dev.tinycast.android.ShortcutReadinessDeviceTest')
p.add_argument('--expected',type=int,default=26)
p.add_argument('--apk',type=Path,default=Path('app/build/outputs/apk/debug/app-debug.apk'))
p.add_argument('--test-apk',type=Path,default=Path('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
a=p.parse_args()
assert a.apk.is_file() and a.test_apk.is_file() and a.apk.resolve()!=a.test_apk.resolve(), "Two distinct APK files are required"
sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:sdk=next(line.removeprefix('sdk.dir=') for line in Path('local.properties').read_text().splitlines() if line.startswith('sdk.dir='))
adb=[str(Path(sdk)/'platform-tools/adb'),'-s',a.serial];pkg='dev.tinycast.android'
def call(*a,data=None):
 for attempt in range(3):
  r=subprocess.run(adb+list(a),input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
  if r.returncode==0:return r.stdout
  if attempt==2 or not any(x in r.stderr.lower() for x in (b'offline',b'closed',b'not found')):raise subprocess.CalledProcessError(r.returncode,r.args,r.stdout,r.stderr)
  subprocess.run(adb+['wait-for-device'],check=True,timeout=15)
def text(*a):return call(*a).decode().strip()
def sha(data):return hashlib.sha256(data).hexdigest()
layout,output,classes=a.layout,a.output,a.classes
assert a.serial.startswith('emulator-')
assert a.expected>0
assert re.fullmatch('[A-Za-z0-9_.,#]+',classes)
assert text('shell','getprop','ro.boot.qemu.avd_name')==a.avd
assert text('shell','id').startswith('uid=2000')
assert 'ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes')
assert 'Override' not in text('shell','wm','size')
assert 'Override' not in text('shell','wm','density')
root=Path(output);root.mkdir(exist_ok=False)
keys=['assistant','voice_interaction_service','voice_recognition_service','navigation_mode','assist_touch_gesture_enabled',
 'assist_long_press_home_enabled','search_all_entrypoints_enabled','enabled_accessibility_services','accessibility_enabled','show_ime_with_hard_keyboard']
settings={k:text('shell','settings','get','secure',k) for k in keys}
assert settings['enabled_accessibility_services']=='null' and settings['accessibility_enabled']=='0'
roles=text('shell','cmd','role','get-role-holders','android.app.role.ASSISTANT').splitlines()
overlays=re.findall(r'^\[x\] (com\.android\.internal\.systemui\.navbar\.\w+)$',text('shell','cmd','overlay','list'),re.M);assert len(overlays)==1
call('shell','am','force-stop',pkg)
prefs=call('exec-out','run-as',pkg,'cat','shared_prefs/palette.xml')
(root/'private-palette-backup.xml').write_bytes(prefs);(root/'private-palette-backup.xml').chmod(0o600)
record={'classes':classes,'layout':layout,'serial':a.serial,'avd':a.avd,'expected':a.expected,'completed':False,
 'runner_sha256':sha(Path(__file__).read_bytes()),'original_size':text('shell','wm','size'),'original_density':text('shell','wm','density')}
try:
 for source,filename in [(a.apk,'app-debug.apk'),(a.test_apk,'app-debug-androidTest.apk')]:
  snapshot=root/filename;shutil.copy2(source,snapshot);call('install','-r',str(snapshot))
 record['apk_sha256']=sha((root/'app-debug.apk').read_bytes());record['test_apk_sha256']=sha((root/'app-debug-androidTest.apk').read_bytes())
 installed=text('shell','pm','path',pkg).removeprefix('package:');assert sha(call('exec-out','cat',installed))==record['apk_sha256']
 record['installed_matches']=True
 if layout=='phone':call('shell','wm','size','1080x2340');call('shell','wm','density','420')
 call('shell','run-as',pkg,'rm','-rf','files/assistant-touch-evidence')
 call('shell','run-as',pkg,'mkdir','-p','files/assistant-touch-evidence')
 with (root/'tests.log').open('wb') as log:
  subprocess.run(adb+['shell','am','instrument','-w','-r','-e','dedicatedAvd',a.avd,'-e','class',classes,pkg+'.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT,check=True)
 for name in text('shell','run-as',pkg,'ls','files/assistant-touch-evidence').splitlines():
  assert re.fullmatch(r'[a-z0-9-]+\.(xml|png|json|txt)',name)
  raw=call('exec-out','run-as',pkg,'cat','files/assistant-touch-evidence/'+name)
  assert len(raw)==int(text('shell','run-as',pkg,'stat','-c','%s','files/assistant-touch-evidence/'+name))
  (root/name).write_bytes(raw)
 log=(root/'tests.log').read_text();print(log[-3500:],flush=True)
 record['completed']=f'OK ({a.expected} test' in log and 'FAILURES!!!' not in log
 record['pass_statuses']=len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0$',log,re.M))
 record['fail_statuses']=len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: -2$',log,re.M))
 assert record['completed'] and record['pass_statuses']==a.expected and record['fail_statuses']==0
finally:
 call('shell','am','force-stop',pkg)
 current=text('shell','cmd','role','get-role-holders','android.app.role.ASSISTANT').splitlines()
 for holder in current:
  if holder not in roles:call('shell','cmd','role','remove-role-holder','android.app.role.ASSISTANT',holder)
 for holder in roles:
  if holder not in current:call('shell','cmd','role','add-role-holder','android.app.role.ASSISTANT',holder)
 call('shell','cmd','overlay','enable-exclusive','--category',overlays[0])
 for k,v in settings.items():
  if text('shell','settings','get','secure',k)==v:continue
  call('shell','settings','delete','secure',k) if v=='null' else call('shell','settings','put','secure',k,v)
 call('shell','run-as',pkg,'tee','shared_prefs/palette.xml',data=prefs)
 if layout=='phone':call('shell','wm','size','reset');call('shell','wm','density','reset')
 record['settings_restored']=settings=={k:text('shell','settings','get','secure',k) for k in keys}
 record['roles_restored']=roles==text('shell','cmd','role','get-role-holders','android.app.role.ASSISTANT').splitlines()
 record['palette_preferences_restored']=prefs==call('exec-out','run-as',pkg,'cat','shared_prefs/palette.xml')
 record['size_restored']=text('shell','wm','size');record['density_restored']=text('shell','wm','density')
 record['adbd']=text('shell','id').split(' groups=')[0]
 record['app_uid']=text('shell','run-as',pkg,'id').split(' groups=')[0]
 record['api']=text('shell','getprop','ro.build.version.sdk');record['abi']=text('shell','getprop','ro.product.cpu.abi')
 record['navigation_overlay_restored']=overlays==re.findall(r'^\[x\] (com\.android\.internal\.systemui\.navbar\.\w+)$',text('shell','cmd','overlay','list'),re.M)
 (root/'record.json').write_text(json.dumps(record,indent=2)+'\n')
 assert record['settings_restored'] and record['roles_restored'] and record['palette_preferences_restored'] and record['navigation_overlay_restored']
 assert record['adbd'].startswith('uid=2000')
