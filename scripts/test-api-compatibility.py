#!/usr/bin/env python3
"""Native compatibility checks on one explicitly owned emulator, including old AVD properties."""
from pathlib import Path
import argparse,hashlib,json,os,re,shutil,subprocess
from android_test_cleanup import Cleanup
p=argparse.ArgumentParser()
p.add_argument('--serial',required=True);p.add_argument('--avd',required=True);p.add_argument('--api',type=int,required=True)
p.add_argument('--apk',type=Path,required=True);p.add_argument('--test-apk',type=Path,required=True)
p.add_argument('--keep-geometry',action='store_true',help='Use the AVD physical viewport, including on legacy SurfaceFlinger')
p.add_argument('--classes',required=True);p.add_argument('--expected',type=int,required=True);p.add_argument('--output',type=Path,required=True)
a=p.parse_args();assert a.serial.startswith('emulator-') and a.expected>0 and re.fullmatch('[A-Za-z0-9_.,#]+',a.classes)
assert a.apk.is_file() and a.test_apk.is_file() and a.apk.name!=a.test_apk.name
sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:sdk=next(x[8:] for x in Path('local.properties').read_text().splitlines() if x.startswith('sdk.dir='))
adb=[str(Path(sdk)/'platform-tools/adb'),'-s',a.serial];app='dev.tinycast.android'
def raw(*args,data=None):return subprocess.check_output(adb+list(args),input=data,stderr=subprocess.STDOUT,timeout=90)
def text(*args):return raw(*args).decode().strip()
def sha(data):return hashlib.sha256(data).hexdigest()
def exists(path):
 r=subprocess.run(adb+['shell','run-as',app,'test','-e',path],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15)
 assert r.returncode in (0,1), 'Unable to verify file presence'
 return r.returncode==0
assert text('shell','getprop','ro.kernel.qemu')=='1'
assert text('emu','avd','name').splitlines()[0]==a.avd # API26 does not expose ro.boot.qemu.avd_name.
assert int(text('shell','getprop','ro.build.version.sdk'))==a.api
assert text('shell','id').startswith('uid=2000') and 'ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes')
size=text('shell','wm','size');density=text('shell','wm','density');assert 'Override' not in size+density
installed=text('shell','pm','path',app);assert installed.startswith('package:') and '\n' not in installed
assert sha(raw('exec-out','cat',installed[8:]))==sha(a.apk.read_bytes()), 'Installed app must match the explicit tested artifact'
initial_app_uid=text('shell','run-as',app,'id').split(' groups=')[0]
assert not initial_app_uid.startswith(('uid=0','uid=1000(','uid=2000')), 'Ordinary debuggable app required for guarded backups'
raw('shell','am','force-stop',app)
keys=['enabled_accessibility_services','accessibility_enabled','show_ime_with_hard_keyboard']
settings={k:text('shell','settings','get','secure',k) for k in keys}
assert settings['enabled_accessibility_services'] in ('null','') and settings['accessibility_enabled'] in ('null','0')
a.output.mkdir(parents=True,exist_ok=False);shutil.copy2(__file__,a.output/'runner.py')
original={}
for name in ['palette','emoji']:
 path='shared_prefs/'+name+'.xml';original[name]=raw('exec-out','run-as',app,'cat',path) if exists(path) else None
 if original[name] is not None:
  backup=a.output/('private-'+name+'-backup.xml');backup.write_bytes(original[name]);backup.chmod(0o600)
r={'serial':a.serial,'avd':a.avd,'api':a.api,'abi':text('shell','getprop','ro.product.cpu.abi'),'expected':a.expected,'classes':a.classes,
 'app_sha256':sha(a.apk.read_bytes()),'test_apk_sha256':sha(a.test_apk.read_bytes()),'runner_sha256':sha(Path(__file__).read_bytes()),
 'initial_app_uid':initial_app_uid,'original_size':size,'original_density':density,'original_preference_files':{k:v is not None for k,v in original.items()},'completed':False}
try:
 for source in [a.apk,a.test_apk]:shutil.copy2(source,a.output/source.name);raw('install','-r',str(source))
 r['installed_matches']=sha(raw('exec-out','cat',text('shell','pm','path',app)[8:]))==r['app_sha256'];assert r['installed_matches']
 if not a.keep_geometry:raw('shell','wm','size','1080x2340');raw('shell','wm','density','420')
 r['test_size']=text('shell','wm','size');r['test_density']=text('shell','wm','density')
 raw('shell','settings','put','secure','show_ime_with_hard_keyboard','1')
 for directory in ['readiness-evidence','emoji-evidence','calculator-intent-evidence','updates-evidence']:
  raw('shell','run-as',app,'rm','-rf','files/'+directory)
 with (a.output/'tests.log').open('wb') as log:
  subprocess.run(adb+['shell','am','instrument','-w','-r','-e','dedicatedAvd',a.avd,'-e','class',a.classes,app+'.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT,timeout=max(600,a.expected*45),check=True)
 log=(a.output/'tests.log').read_text();r['passes']=len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0$',log,re.M));r['failures']=len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: -2$',log,re.M))
 present=text('shell','run-as',app,'ls','files').splitlines()
 for directory in ['readiness-evidence','emoji-evidence','calculator-intent-evidence','updates-evidence']:
  if directory not in present:continue
  destination=a.output/directory;destination.mkdir(exist_ok=True)
  for name in text('shell','run-as',app,'ls','files/'+directory).splitlines():
   assert re.fullmatch('[a-z0-9-]+\\.(png|xml|json|tsv)',name)
   source='files/'+directory+'/'+name;data=raw('exec-out','run-as',app,'cat',source)
   assert len(data)==int(text('shell','run-as',app,'stat','-c','%s',source));(destination/name).write_bytes(data)
 r['completed']=r['passes']==a.expected and r['failures']==0 and f'OK ({a.expected} test' in log and 'FAILURES!!!' not in log
 assert r['completed'],log[-2500:]
finally:
 c=Cleanup(r);c.attempt('stop-app',lambda:raw('shell','am','force-stop',app))
 def restore(name,data):
  path='shared_prefs/'+name+'.xml';raw('shell','run-as',app,'rm','-f',path+'.bak')
  if data is None:raw('shell','run-as',app,'rm','-f',path)
  else:raw('shell','run-as',app,'tee',path,data=data)
 for name,data in original.items():c.attempt('preferences-'+name,lambda name=name,data=data:restore(name,data))
 for k,v in settings.items():c.attempt('secure-'+k,lambda k=k,v=v:raw('shell','settings','delete','secure',k) if v=='null' else raw('shell','settings','put','secure',k,v))
 c.attempt('size',lambda:raw('shell','wm','size','reset'));c.attempt('density',lambda:raw('shell','wm','density','reset'))
 def verify():
  for name,data in original.items():
   path='shared_prefs/'+name+'.xml';actual=raw('exec-out','run-as',app,'cat',path) if exists(path) else None
   c.require(actual==data,'Restored '+name+' bytes/presence')
  c.require(settings=={k:text('shell','settings','get','secure',k) for k in keys},'Restored settings')
  c.require(text('shell','wm','size')==size and text('shell','wm','density')==density,'Restored geometry')
  r['adbd']=text('shell','id').split(' groups=')[0];r['app_uid']=text('shell','run-as',app,'id').split(' groups=')[0]
  c.require(r['adbd'].startswith('uid=2000') and not r['app_uid'].startswith(('uid=0','uid=1000(','uid=2000')),'Unprivileged identities')
 c.attempt('verify-restoration',verify)
 r['completed']=r['completed'] and not c.errors;(a.output/'record.json').write_text(json.dumps(r,indent=2)+'\n');c.finish()
print(json.dumps({'completed':r['completed'],'passes':r['passes'],'api':a.api}))
