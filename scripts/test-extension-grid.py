#!/usr/bin/env python3
"""Native Grid evidence on one explicitly owned, unprivileged emulator."""
from pathlib import Path
import argparse, subprocess, os, json, hashlib, re, shutil
from android_test_cleanup import Cleanup
p=argparse.ArgumentParser()
p.add_argument('--serial',required=True);p.add_argument('--avd',required=True);p.add_argument('--layout',choices=['tablet','phone'],required=True)
p.add_argument('--output',type=Path,required=True);p.add_argument('--apk',type=Path,default=Path('app/build/outputs/apk/debug/app-debug.apk'))
p.add_argument('--evidence-dir',choices=['extension-grid-evidence','emoji-evidence'],default='extension-grid-evidence')
p.add_argument('--extra-evidence-dir',action='append',choices=['emoji-evidence','settings-evidence','shortcut-evidence','backup-evidence','folder-evidence','palette-evidence','rigid-palette-evidence'],default=[])
p.add_argument('--classes',default='dev.tinycast.android.ExtensionGridDeviceTest');p.add_argument('--expected',type=int,default=16)
a=p.parse_args();sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:sdk=next(x.removeprefix('sdk.dir=') for x in Path('local.properties').read_text().splitlines() if x.startswith('sdk.dir='))
adb=[str(Path(sdk)/'platform-tools/adb'),'-s',a.serial];app='dev.tinycast.android'
def raw(*args,data=None):return subprocess.check_output(adb+list(args),input=data,stderr=subprocess.STDOUT,timeout=60)
def text(*args):return raw(*args).decode().strip()
def sha(data):return hashlib.sha256(data).hexdigest()
assert a.serial.startswith('emulator-') and a.expected>0 and re.fullmatch('[A-Za-z0-9_.,#]+',a.classes)
assert text('shell','getprop','ro.kernel.qemu')=='1' and text('shell','getprop','ro.boot.qemu.avd_name')==a.avd
assert text('shell','id').startswith('uid=2000') and 'ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes')
assert 'Override' not in text('shell','wm','size')+text('shell','wm','density')
keys=['enabled_accessibility_services','accessibility_enabled','show_ime_with_hard_keyboard']
settings={k:text('shell','settings','get','secure',k) for k in keys};assert settings['enabled_accessibility_services']=='null' and settings['accessibility_enabled']=='0'
raw('shell','am','force-stop',app);prefs=raw('exec-out','run-as',app,'cat','shared_prefs/palette.xml')
emoji_prefs=raw('exec-out','run-as',app,'cat','shared_prefs/emoji.xml')
a.output.mkdir(exist_ok=False,parents=True);shutil.copyfile(__file__,a.output/'runner.py')
(a.output/'private-palette-backup.xml').write_bytes(prefs);(a.output/'private-palette-backup.xml').chmod(0o600)
(a.output/'private-emoji-backup.xml').write_bytes(emoji_prefs);(a.output/'private-emoji-backup.xml').chmod(0o600)
r={'avd':a.avd,'serial':a.serial,'layout':a.layout,'classes':a.classes,'expected':a.expected,'completed':False,'runner_sha256':sha(Path(__file__).read_bytes()),'original_size':text('shell','wm','size'),'original_density':text('shell','wm','density')}
try:
 for source in [a.apk,Path('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
  shutil.copyfile(source,a.output/source.name);raw('install','-r',str(source))
 r['apk_sha256']=sha(a.apk.read_bytes());r['test_apk_sha256']=sha((a.output/'app-debug-androidTest.apk').read_bytes())
 installed=text('shell','pm','path',app).removeprefix('package:');assert sha(raw('exec-out','cat',installed))==r['apk_sha256'];r['installed_matches']=True
 if a.layout=='phone':raw('shell','wm','size','1080x2340');raw('shell','wm','density','420')
 evidence_dirs=list(dict.fromkeys([a.evidence_dir]+a.extra_evidence_dir))
 for folder in evidence_dirs:raw('shell','run-as',app,'rm','-rf','files/'+folder)
 r['suite_timeout_seconds']=max(600,a.expected*30)
 with (a.output/'tests.log').open('wb') as log:subprocess.run(adb+['shell','am','instrument','-w','-r','-e','dedicatedAvd',a.avd,'-e','class',a.classes,app+'.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=r['suite_timeout_seconds'])
 present=text('shell','run-as',app,'ls','files').splitlines()
 for folder in evidence_dirs:
  if folder not in present:continue
  destination=a.output if folder==a.evidence_dir else a.output/folder
  destination.mkdir(exist_ok=True)
  for name in text('shell','run-as',app,'ls','files/'+folder).splitlines():
   assert re.fullmatch(r'[a-z0-9-]+\.(png|xml|json)',name)
   data=raw('exec-out','run-as',app,'cat','files/'+folder+'/'+name);assert len(data)==int(text('shell','run-as',app,'stat','-c','%s','files/'+folder+'/'+name));(destination/name).write_bytes(data)
 log=(a.output/'tests.log').read_text();r['passes']=len(re.findall('^INSTRUMENTATION_STATUS_CODE: 0$',log,re.M));r['completed']=f'OK ({a.expected} test' in log and 'FAILURES!!!' not in log and r['passes']==a.expected
 assert r['completed'],log[-3000:]
finally:
 c=Cleanup(r);c.attempt('stop-app',lambda:raw('shell','am','force-stop',app))
 c.attempt('preferences-backup',lambda:raw('shell','run-as',app,'rm','-f','shared_prefs/palette.xml.bak'))
 c.attempt('preferences',lambda:raw('shell','run-as',app,'tee','shared_prefs/palette.xml',data=prefs))
 c.attempt('emoji-backup',lambda:raw('shell','run-as',app,'rm','-f','shared_prefs/emoji.xml.bak'))
 c.attempt('emoji-preferences',lambda:raw('shell','run-as',app,'tee','shared_prefs/emoji.xml',data=emoji_prefs))
 for k,v in settings.items():c.attempt('secure-'+k,lambda k=k,v=v:raw('shell','settings','delete','secure',k) if v=='null' else raw('shell','settings','put','secure',k,v))
 if a.layout=='phone':
  c.attempt('size',lambda:raw('shell','wm','size','reset'));c.attempt('density',lambda:raw('shell','wm','density','reset'))
 c.attempt('private-fixture',lambda:raw('shell','run-as',app,'rm','-f','files/grid-private-fixture.png'))
 c.attempt('fixture-cache',lambda:raw('shell','run-as',app,'rm','-rf','cache/extension-grid-fixture'))
 def verify():
  r['preferences_restored']=prefs==raw('exec-out','run-as',app,'cat','shared_prefs/palette.xml');c.require(r['preferences_restored'],'preferences')
  r['emoji_preferences_restored']=emoji_prefs==raw('exec-out','run-as',app,'cat','shared_prefs/emoji.xml');c.require(r['emoji_preferences_restored'],'emoji preferences')
  r['settings_restored']=settings=={k:text('shell','settings','get','secure',k) for k in keys};c.require(r['settings_restored'],'settings')
 c.attempt('verify-data',verify)
 def geometry():
  r['size']=text('shell','wm','size');r['density']=text('shell','wm','density');c.require(r['size']==r['original_size'] and r['density']==r['original_density'],'geometry')
 c.attempt('verify-geometry',geometry)
 def identity():
  r['adbd']=text('shell','id').split(' groups=')[0];r['app_uid']=text('shell','run-as',app,'id').split(' groups=')[0];c.require(r['adbd'].startswith('uid=2000'),'adbd');c.require(not r['app_uid'].startswith(('uid=0','uid=1000(','uid=2000')),'app UID')
 c.attempt('verify-identity',identity)
 r['tests_completed']=r['completed'];r['completed']=r['completed'] and not c.errors;(a.output/'record.json').write_text(json.dumps(r,indent=2)+'\n');c.finish()
print(json.dumps({'completed':r['completed'],'passes':r.get('passes'),'apk':r['apk_sha256']}))
