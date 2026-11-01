#!/usr/bin/env python3
"""Native Grid evidence on one explicitly owned, unprivileged emulator."""
from pathlib import Path
import argparse, subprocess, os, json, hashlib, re, shutil, uuid
from android_test_cleanup import Cleanup
p=argparse.ArgumentParser()
p.add_argument('--serial',required=True);p.add_argument('--avd',required=True);p.add_argument('--layout',choices=['tablet','phone'],required=True)
p.add_argument('--output',type=Path,required=True);p.add_argument('--apk',type=Path,default=Path('app/build/outputs/apk/debug/app-debug.apk'))
p.add_argument('--test-apk',type=Path,default=Path('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
p.add_argument('--evidence-dir',choices=['extension-grid-evidence','emoji-evidence','updates-evidence','readiness-evidence','system-settings-evidence'],default='extension-grid-evidence')
p.add_argument('--extra-evidence-dir',action='append',choices=['emoji-evidence','settings-evidence','shortcut-evidence','backup-evidence','folder-evidence','palette-evidence','rigid-palette-evidence','updates-evidence','readiness-evidence','system-settings-evidence'],default=[])
p.add_argument('--update-fixtures',type=Path,help='Signed newer/wrong-signer/wrong-package verification fixtures; never published')
p.add_argument('--readiness',action='store_true');p.add_argument('--soak-seconds',type=int,default=300)
p.add_argument('--classes',default='dev.tinycast.android.ExtensionGridDeviceTest');p.add_argument('--expected',type=int,default=16)
a=p.parse_args();sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:sdk=next(x.removeprefix('sdk.dir=') for x in Path('local.properties').read_text().splitlines() if x.startswith('sdk.dir='))
adb=[str(Path(sdk)/'platform-tools/adb'),'-s',a.serial];app='dev.tinycast.android'
def raw(*args,data=None):return subprocess.check_output(adb+list(args),input=data,stderr=subprocess.STDOUT,timeout=60)
def text(*args):return raw(*args).decode().strip()
def sha(data):return hashlib.sha256(data).hexdigest()
assert ('UpdateVerificationDeviceTest' not in a.classes) or a.update_fixtures is not None, 'Updater verification requires --update-fixtures'
assert not a.readiness or (60 <= a.soak_seconds <= 1800 and a.classes.startswith('dev.tinycast.android.ReadinessDeviceTest'))
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
fixture_owned=False
upload_owned=False
upload_root='/data/local/tmp/droidcast-readiness-'+uuid.uuid4().hex
source_approval=None
def approval_mode():
 value=text('shell','cmd','appops','get',app,'REQUEST_INSTALL_PACKAGES')
 assert 'Uid mode' not in value
 modes=re.findall(r'REQUEST_INSTALL_PACKAGES:\s*(allow|ignore|deny|default|foreground|ask)',value);assert len(modes)<=1
 return modes[0] if modes else 'default'
if a.update_fixtures is not None:source_approval=approval_mode();r['source_approval_before']=source_approval
try:
 for source in [a.apk,a.test_apk]:
  shutil.copyfile(source,a.output/source.name);raw('install','-r',str(source))
 r['apk_sha256']=sha(a.apk.read_bytes());r['test_apk_sha256']=sha((a.output/a.test_apk.name).read_bytes())
 installed=text('shell','pm','path',app).removeprefix('package:');assert sha(raw('exec-out','cat',installed))==r['apk_sha256'];r['installed_matches']=True
 if a.update_fixtures is not None:
  assert subprocess.run(adb+['shell','run-as',app,'test','-e','files/updates-fixtures'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0, 'Refuse to overwrite an existing fixture directory'
  fixture_owned=True;raw('shell','run-as',app,'mkdir','files/updates-fixtures')
  upload_owned=True;raw('shell','mkdir',upload_root)
  r['update_fixtures']={}
  for name in ['newer.apk','wrong-signer.apk','wrong-package.apk']:
   source=a.update_fixtures/name;assert source.is_file() and 1<=source.stat().st_size<=64*1024*1024
   remote=upload_root+'/'+name;raw('push',str(source),remote);raw('shell','chmod','0644',remote)
   raw('shell','run-as',app,'cp',remote,'files/updates-fixtures/'+name)
   expected=sha(source.read_bytes());assert text('shell','run-as',app,'sha256sum','files/updates-fixtures/'+name).split()[0]==expected
   r['update_fixtures'][name]=expected
  raw('shell','cmd','appops','set',app,'REQUEST_INSTALL_PACKAGES','allow');assert approval_mode()=='allow'
 if a.layout=='phone':raw('shell','wm','size','1080x2340');raw('shell','wm','density','420')
 evidence_dirs=list(dict.fromkeys([a.evidence_dir]+a.extra_evidence_dir))
 for folder in evidence_dirs:raw('shell','run-as',app,'rm','-rf','files/'+folder)
 r['suite_timeout_seconds']=max(600,a.expected*30,a.soak_seconds+300 if a.readiness else 0)
 with (a.output/'tests.log').open('wb') as log:subprocess.run(adb+['shell','am','instrument','-w','-r','-e','dedicatedAvd',a.avd,'-e','class',a.classes]+(['-e','runReadiness','true','-e','soakSeconds',str(a.soak_seconds)] if a.readiness else [])+[app+'.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=r['suite_timeout_seconds'])
 log=(a.output/'tests.log').read_text();r['passes']=len(re.findall('^INSTRUMENTATION_STATUS_CODE: 0$',log,re.M));r['tests_completed']=f'OK ({a.expected} test' in log and 'FAILURES!!!' not in log and r['passes']==a.expected
 present=text('shell','run-as',app,'ls','files').splitlines()
 for folder in evidence_dirs:
  if folder not in present:continue
  destination=a.output if folder==a.evidence_dir else a.output/folder
  destination.mkdir(exist_ok=True)
  for name in text('shell','run-as',app,'ls','files/'+folder).splitlines():
   assert re.fullmatch(r'[a-z0-9-]+\.(png|xml|json|tsv)',name)
   data=raw('exec-out','run-as',app,'cat','files/'+folder+'/'+name);assert len(data)==int(text('shell','run-as',app,'stat','-c','%s','files/'+folder+'/'+name));(destination/name).write_bytes(data)
 r['completed']=r['tests_completed']
 assert r['completed'],log[-3000:]
finally:
 c=Cleanup(r);c.attempt('stop-app',lambda:raw('shell','am','force-stop',app))
 if fixture_owned:c.attempt('update-fixtures',lambda:raw('shell','run-as',app,'rm','-rf','files/updates-fixtures'))
 if upload_owned:c.attempt('fixture-upload',lambda:raw('shell','rm','-rf',upload_root))
 if source_approval is not None:
  c.attempt('source-approval',lambda:raw('shell','cmd','appops','set',app,'REQUEST_INSTALL_PACKAGES',source_approval))
  c.attempt('verify-source-approval',lambda:c.require(approval_mode()==source_approval,'source approval restored'))
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
 r['completed']=r['completed'] and not c.errors;(a.output/'record.json').write_text(json.dumps(r,indent=2)+'\n');c.finish()
print(json.dumps({'completed':r['completed'],'passes':r.get('passes'),'apk':r['apk_sha256']}))
