#!/usr/bin/env python3
"""Run release-like measurements in a new, disposable nondebuggable package."""
from pathlib import Path
import argparse, hashlib, json, os, re, shutil, subprocess
from android_test_cleanup import Cleanup

p=argparse.ArgumentParser()
p.add_argument('--serial',required=True);p.add_argument('--avd',required=True)
p.add_argument('--layout',choices=['tablet','phone'],required=True);p.add_argument('--output',type=Path,required=True)
p.add_argument('--method',choices=['queryAndNavigationFrameCommitLatency','pixelVerifiedInteractionLatency','sustainedSearchAndRouteReliability'],required=True)
p.add_argument('--apk',type=Path,default=Path('app/build/outputs/apk/benchmark/app-benchmark.apk'))
p.add_argument('--test-apk',type=Path,default=Path('app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk'))
p.add_argument('--soak-seconds',type=int,default=300)
p.add_argument('--startup',action='store_true',help='Also measure fresh disposable-package startup before instrumentation')
a=p.parse_args();assert a.serial.startswith('emulator-') and 60<=a.soak_seconds<=1800
sdk_value=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk_value:
 sdk_value=next((line.removeprefix('sdk.dir=') for line in Path('local.properties').read_text().splitlines() if line.startswith('sdk.dir=')),None)
if not sdk_value:raise SystemExit('Set ANDROID_HOME or sdk.dir in local.properties')
sdk=Path(sdk_value);adb=[str(sdk/'platform-tools/adb'),'-s',a.serial]
app='dev.tinycast.android.benchmark';test=app+'.test'
def raw(*args,data=None):return subprocess.check_output(adb+list(args),input=data,stderr=subprocess.STDOUT,timeout=60)
def shell(*args):return raw('shell',*args).decode().strip()
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
assert shell('getprop','ro.kernel.qemu')=='1' and shell('getprop','ro.boot.qemu.avd_name')==a.avd
assert shell('id').startswith('uid=2000') and 'ActiveInstrumentation{' not in shell('dumpsys','activity','processes')
size,density=shell('wm','size'),shell('wm','density');assert 'Override' not in size+density
keys=['enabled_accessibility_services','accessibility_enabled','show_ime_with_hard_keyboard']
settings={key:shell('settings','get','secure',key) for key in keys}
assert settings['enabled_accessibility_services']=='null' and settings['accessibility_enabled']=='0'
assert not shell('pm','list','packages',app), 'Disposable package already exists; refuse to overwrite it'
binaries=[a.apk,a.test_apk]
aapt=sdk/'build-tools/36.0.0/aapt2'
for binary,expected in zip(binaries,[app,test]):
 badging=subprocess.check_output([str(aapt),'dump','badging',str(binary)],text=True)
 assert re.search(r"^package: name='"+re.escape(expected)+r"'",badging,re.M),badging[:500]
 if expected==app:assert 'application-debuggable' not in badging
output=Path('/sdcard/Android/data')/app/'files/readiness-evidence'
a.output.mkdir(parents=True,exist_ok=False);shutil.copyfile(__file__,a.output/'runner.py')
r=dict(avd=a.avd,serial=a.serial,layout=a.layout,method=a.method,soak_seconds=a.soak_seconds,
       app_sha256=sha(binaries[0]),test_sha256=sha(binaries[1]),debuggable=False,disposable_package=app,
       original_size=size,original_density=density,host_load_start=os.getloadavg(),completed=False)
created=[]
try:
 for binary,package in zip(binaries,[app,test]):
  # Mark before installation so partial installation is independently cleaned up.
  created.append(package);raw('install',str(binary));shutil.copyfile(binary,a.output/binary.name)
 installed=shell('pm','path',app).removeprefix('package:')
 assert hashlib.sha256(raw('exec-out','cat',installed)).hexdigest()==r['app_sha256'];r['installed_matches']=True
 if a.startup:
  subprocess.run(['python3','scripts/test-startup-performance.py','--serial',a.serial,'--avd',a.avd,'--layout',a.layout,'--package',app,'--apk-sha256',r['app_sha256'],'--output',str(a.output/'startup')],check=True,timeout=900)
 if a.layout=='phone':shell('wm','size','1080x2340');shell('wm','density','420')
 shell('input','keyevent','KEYCODE_HOME')
 with (a.output/'tests.log').open('wb') as log:
  subprocess.run(adb+['shell','am','instrument','-w','-r','-e','dedicatedAvd',a.avd,'-e','runReadiness','true','-e','soakSeconds',str(a.soak_seconds),'-e','class','dev.tinycast.android.ReadinessDeviceTest#'+a.method,test+'/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT,timeout=a.soak_seconds+300,check=True)
 for name in shell('ls',str(output)).splitlines():
  assert re.fullmatch(r'[a-z0-9-]+\.(png|xml|json)',name)
  data=raw('exec-out','cat',str(output/name));assert len(data)==int(shell('stat','-c','%s',str(output/name)))
  (a.output/name).write_bytes(data)
 log=(a.output/'tests.log').read_text();r['passes']=len(re.findall('^INSTRUMENTATION_STATUS_CODE: 0$',log,re.M))
 assert r['passes']==1 and 'OK (1 test)' in log and 'FAILURES!!!' not in log,log[-2500:]
 r['completed']=True
finally:
 c=Cleanup(r)
 for package in created:c.attempt('uninstall-'+package,lambda package=package:raw('uninstall',package))
 for key,value in settings.items():c.attempt('secure-'+key,lambda key=key,value=value:shell('settings','delete','secure',key) if value=='null' else shell('settings','put','secure',key,value))
 if a.layout=='phone':
  c.attempt('size',lambda:shell('wm','size','reset'));c.attempt('density',lambda:shell('wm','density','reset'))
 def verify():
  c.require(not shell('pm','list','packages',app),'disposable packages removed')
  c.require(size==shell('wm','size') and density==shell('wm','density'),'geometry')
  c.require(settings=={key:shell('settings','get','secure',key) for key in keys},'settings')
  c.require(shell('id').startswith('uid=2000'),'unprivileged adbd')
 c.attempt('verify',verify);r['host_load_end']=os.getloadavg();r['completed']=r['completed'] and not c.errors
 (a.output/'record.json').write_text(json.dumps(r,indent=2)+'\n');c.finish()
print(json.dumps({key:r[key] for key in ['completed','layout','passes','app_sha256']}))
