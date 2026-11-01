#!/usr/bin/env python3
"""Real self-update on one owned emulator. Private backup bytes are never printed/published."""
from pathlib import Path, PurePosixPath
import argparse, hashlib, json, os, re, subprocess, tarfile, time, uuid
import xml.etree.ElementTree as ET
from android_test_cleanup import Cleanup

p=argparse.ArgumentParser()
p.add_argument('--serial',required=True);p.add_argument('--avd',required=True)
p.add_argument('--layout',choices=['tablet','phone'],required=True)
p.add_argument('--mode',choices=['required','best-effort'],required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--apk',type=Path,default=Path('app/build/outputs/apk/debug/app-debug.apk'))
p.add_argument('--payload',type=Path,required=True)
p.add_argument('--test-apk',type=Path,default=Path('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
a=p.parse_args();sdk=Path(os.environ['ANDROID_HOME']);adb=[str(sdk/'platform-tools/adb'),'-s',a.serial];APP='dev.tinycast.android'
def raw(*args,data=None,timeout=60):
 return subprocess.check_output(adb+list(args),input=data,stderr=subprocess.STDOUT,timeout=timeout)
def text(*args):return raw(*args).decode().strip()
def sha(data):return hashlib.sha256(data).hexdigest()
def installed_hash():
 paths=text('shell','pm','path',APP).splitlines();assert len(paths)==1
 return sha(raw('exec-out','cat',paths[0].removeprefix('package:')))
def code():
 package=text('shell','dumpsys','package',APP)
 return int(re.search(r'\bversionCode=(\d+)',package)[1])
def uid():return int(re.search(r'uid=(\d+)',text('shell','run-as',APP,'id'))[1])
def exists(path):
 return subprocess.run(adb+['shell','run-as',APP,'test','-e',path],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
assert a.serial.startswith('emulator-')
assert text('shell','getprop','ro.kernel.qemu')=='1'
assert text('shell','getprop','ro.boot.qemu.avd_name')==a.avd
assert text('shell','getprop','ro.build.version.sdk')=='36'
assert text('shell','id').startswith('uid=2000')
assert 'ActiveInstrumentation{' not in text('shell','dumpsys','activity','processes')
assert 'Override' not in text('shell','wm','size')+text('shell','wm','density')
assert code()==6 and installed_hash()==sha(a.apk.read_bytes()),'Unexpected installed bootstrap'
assert '.PaletteShortcutService' not in text('shell','settings','get','secure','enabled_accessibility_services')
for path in ['files/updates-self-test','files/updates-self-input','files/app-updates/staged.json','files/app-updates/verified.apk']:
 assert not exists(path),'A user update or prior fixture is pending'
a.output.mkdir(parents=True,exist_ok=False)
r={'serial':a.serial,'avd':a.avd,'api':36,'layout':a.layout,'mode':a.mode,'bootstrap_apk_sha256':sha(a.apk.read_bytes()),
 'payload_apk_sha256':sha(a.payload.read_bytes()),'test_apk_sha256':sha(a.test_apk.read_bytes()),'initial_uid':uid(),'initial_code':code(),
 'initial_size':text('shell','wm','size'),'initial_density':text('shell','wm','density'),'completed':False}
def save():(a.output/'record.json').write_text(json.dumps(r,indent=2)+'\n')
save()
operation='REQUEST_INSTALL_PACKAGES'
def approval_mode():
 value=text('shell','cmd','appops','get',APP,operation);assert 'Uid mode' not in value
 modes=re.findall(operation+r':\s*(allow|ignore|deny|default|foreground|ask)',value);assert len(modes)<=1
 return modes[0] if modes else 'default'
original_mode=approval_mode();r['initial_source_approval_mode']=original_mode
original_ime=text('shell','settings','get','secure','show_ime_with_hard_keyboard')
backup=a.output/'private-original-data.tar'
def capture_private(path,roots):
 with open(path,'wb') as stream:
  os.chmod(path,0o600)
  subprocess.run(adb+['exec-out','run-as',APP,'tar','-cf','-',*roots],stdout=stream,stderr=subprocess.PIPE,check=True,timeout=120)
 assert path.stat().st_size<=256*1024*1024,'Private snapshot exceeded bounded size'
def inventory(path):
 result={}
 with tarfile.open(path) as archive:
  for member in archive:
   parts=PurePosixPath(member.name).parts
   assert parts and parts[0] in {'files','shared_prefs'} and '..' not in parts and not member.name.startswith('/')
   result[member.name.rstrip('/')]=(member.type,member.mode,member.size,member.linkname,sha(archive.extractfile(member).read()) if member.isfile() else None)
 return result
raw('shell','am','force-stop',APP)
capture_private(backup,['files','shared_prefs']);original_inventory=inventory(backup);backup_sha=sha(backup.read_bytes())
r['private_snapshot_created']=True;save()
def native(method,label):
 args=adb+['shell','am','instrument','-w','-r','-e','confirmationMode',a.mode,'-e','class',
  'dev.tinycast.android.UpdateSelfInstallDeviceTest#'+method,APP+'.test/androidx.test.runner.AndroidJUnitRunner']
 with open(a.output/(label+'.log'),'wb') as log:subprocess.run(args,stdout=log,stderr=subprocess.STDOUT,timeout=180,check=True)
 log=(a.output/(label+'.log')).read_text()
 assert 'OK (1 test)' in log and 'FAILURES!!!' not in log,label+' did not pass'
 r.setdefault('native_cases',[]).append(label);save()
def dump():
 remote='/data/local/tmp/droidcast-self-update-ui.xml'
 raw('shell','uiautomator','dump',remote,timeout=30)
 data=raw('exec-out','cat',remote);raw('shell','rm','-f',remote)
 return ET.fromstring(data),data
def tag(node):return node.get('resource-id','').split('/')[-1]
last=None
def wait_for(predicate,label,timeout=45):
 global last
 deadline=time.monotonic()+timeout
 while time.monotonic()<deadline:
  try:
   root,data=dump();last=(root,data)
   found=predicate(root)
   if found is not None and found is not False:return found
  except (subprocess.CalledProcessError,subprocess.TimeoutExpired,ET.ParseError):pass
  time.sleep(.25)
 raise AssertionError(label)
def node_tag(root,value):
 return next((n for n in root.iter('node') if tag(n)==value and n.get('package')==APP and n.get('enabled')=='true'),None)
def tap(node):
 bounds=list(map(int,re.findall(r'-?\d+',node.get('bounds',''))));assert len(bounds)==4
 x=(bounds[0]+bounds[2])//2;y=(bounds[1]+bounds[3])//2
 assert x>=0 and y>=0 and bounds[2]>bounds[0] and bounds[3]>bounds[1]
 raw('shell','input','tap',str(x),str(y))
def screenshot(name,public_predicate):
 root,data=dump();assert public_predicate(root),'Refuse to publish an unrelated screen'
 (a.output/(name+'.xml')).write_bytes(data)
 image=raw('exec-out','screencap','-p');assert image.startswith(b'\x89PNG')
 (a.output/(name+'.png')).write_bytes(image)
def app_update(root):return any(tag(n)=='updates-status' for n in root.iter('node'))
SYSTEM={'com.android.packageinstaller','com.google.android.packageinstaller','com.android.permissioncontroller','com.google.android.permissioncontroller'}
def system_button(root,labels):
 return next((n for n in root.iter('node') if n.get('package') in SYSTEM and n.get('text','').casefold() in labels and n.get('enabled')=='true'),None)
def installer_dialog(root):
 return system_button(root,{'update','install'}) is not None and any('Droidcast' in n.get('text','') for n in root.iter('node'))
def open_updates():
 raw('shell','am','start','-n',APP+'/.MainActivity')
 search=wait_for(lambda root:node_tag(root,'search'),'Launcher search')
 tap(search);raw('shell','input','text','Check')
 command=wait_for(lambda root:node_tag(root,'result-check-updates'),'Check for Updates command')
 tap(command)
 wait_for(lambda root:next((n for key in ['updates-permission','updates-install'] if (n:=node_tag(root,key)) is not None),None),'Verified update recovered')
def confirm_install(capture_label):
 confirm=wait_for(lambda root:node_tag(root,'updates-confirm'),'Pending-user-action receipt')
 r.setdefault('pending_user_action_receipts',0);r['pending_user_action_receipts']+=1;save()
 screenshot(capture_label,app_update);tap(confirm)
 button=wait_for(lambda root:system_button(root,{'update','install'}) if installer_dialog(root) else None,'Android update confirmation')
 return button

def collect_public():
 remote='files/updates-evidence'
 if not exists(remote):return
 for name in text('shell','run-as',APP,'ls',remote).splitlines():
  assert re.fullmatch('[a-z0-9-]+\\.(png|xml|json)',name)
  data=raw('exec-out','run-as',APP,'cat',remote+'/'+name)
  (a.output/name).write_bytes(data)
def restore_data():
 assert sha(backup.read_bytes())==backup_sha,'Private snapshot changed'
 stage='.updates-restore-'+uuid.uuid4().hex[:8]
 old='.updates-replaced-'+uuid.uuid4().hex[:8]
 raw('shell','run-as',APP,'mkdir',stage)
 raw('shell','run-as',APP,'mkdir',old)
 with open(backup,'rb') as stream:
  subprocess.run(adb+['shell','-T','run-as',APP,'tar','-xpf','-','-C',stage],stdin=stream,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,check=True,timeout=120)
 staged=a.output/'private-staged-restoration.tar'
 capture_private(staged,[stage+'/files',stage+'/shared_prefs'])
 # Re-root staged inventory without exposing snapshot contents.
 staged_map={}
 with tarfile.open(staged) as archive:
  for member in archive:
   name=member.name.removeprefix(stage+'/').rstrip('/')
   staged_map[name]=(member.type,member.mode,member.size,member.linkname,sha(archive.extractfile(member).read()) if member.isfile() else None)
 assert staged_map==original_inventory,'Staged private restoration differs'
 for name in ['files','shared_prefs']:
  raw('shell','run-as',APP,'mv','-T',name,old+'/'+name)
  try:raw('shell','run-as',APP,'mv','-T',stage+'/'+name,name)
  except Exception:
   raw('shell','run-as',APP,'mv','-T',old+'/'+name,name);raise
 restored=a.output/'private-restored-data.tar'
 capture_private(restored,['files','shared_prefs'])
 assert inventory(restored)==original_inventory,'Private app data was not restored'
 raw('shell','run-as',APP,'rm','-rf',stage,old)
 r['private_data_restored']=True

error=None;setup_started=False
try:
 raw('install','-r',str(a.test_apk));setup_started=True
 if a.layout=='phone':
  raw('shell','wm','size','1080x2340');raw('shell','wm','density','420')
 raw('shell','settings','put','secure','show_ime_with_hard_keyboard','0')
 raw('shell','cmd','appops','set',APP,operation,'deny')
 raw('shell','run-as',APP,'rm','-rf','files/updates-evidence')
 raw('shell','run-as',APP,'mkdir','-p','files/updates-self-input')
 remote='/data/local/tmp/droidcast-self-newer.apk'
 raw('push',str(a.payload),remote);raw('shell','chmod','0644',remote)
 raw('shell','run-as',APP,'cp',remote,'files/updates-self-input/newer.apk')
 native('preparePublicDataAndVerifiedUpdate','prepare')
 raw('shell','am','force-stop',APP);raw('shell','input','keyevent','KEYCODE_HOME');open_updates()
 screenshot('app-approval-needed',app_update)
 permission=wait_for(lambda root:node_tag(root,'updates-permission'),'App source approval action');tap(permission)
 switch=wait_for(lambda root:next((n for n in root.iter('node') if n.get('package')=='com.android.settings' and n.get('checkable')=='true'),None),'Android source approval switch')
 screenshot('android-source-approval',lambda root:any('Droidcast' in n.get('text','') for n in root.iter('node')))
 if switch.get('checked')!='true':tap(switch)
 wait_for(lambda root:next((n for n in root.iter('node') if n.get('package')=='com.android.settings' and n.get('checkable')=='true' and n.get('checked')=='true'),None),'Source approval enabled')
 raw('shell','input','keyevent','KEYCODE_BACK')
 install=wait_for(lambda root:node_tag(root,'updates-install'),'Install after returning from approval')
 screenshot('app-ready-install',app_update);tap(install)
 if a.mode=='required':
  confirm_install('app-pending-confirmation')
  screenshot('android-installer-review',installer_dialog)
  cancel=wait_for(lambda root:system_button(root,{'cancel'}),'Android installer Cancel');tap(cancel)
  wait_for(lambda root:node_tag(root,'updates-install') if any('Installation cancelled' in n.get('text','') for n in root.iter('node')) else None,'Cancelled installation returns to ready')
  assert code()==6;r['installer_cancellation_preserved_version']=True
  screenshot('app-installer-cancelled',app_update)
  tap(wait_for(lambda root:node_tag(root,'updates-install'),'Retry verified install'))
  button=confirm_install('app-retry-confirmation');tap(button)
 else:
  deadline=time.monotonic()+60;handled=False
  while time.monotonic()<deadline:
   if code()==7:break
   root,_=dump();confirm=node_tag(root,'updates-confirm')
   if confirm is not None:
    handled=True;r['platform_confirmation_fallback']=True
    button=confirm_install('app-best-effort-fallback');tap(button);break
   time.sleep(.25)
  r['best_effort_required_confirmation']=handled
 deadline=time.monotonic()+90
 while code()!=7 and time.monotonic()<deadline:time.sleep(.3)
 assert code()==7,'Android did not install the update'
 assert installed_hash()==r['payload_apk_sha256'] and uid()==r['initial_uid']
 r['installed_payload_matches']=True;r['updated_code']=7;save()
 native('verifyRetainedDataAfterInstall','verify')
 collect_public();r['completed']=True
except Exception as exc:
 error=exc;r['error_type']=type(exc).__name__;r['failure_phase']='see saved command/native logs'
 try:
  if last and app_update(last[0]):screenshot('failure-update-screen',app_update)
 except Exception:pass
finally:
 cleanup=Cleanup(r)
 if setup_started:cleanup.attempt('fixture-grant-and-session',lambda:native('releasePublicFixtureGrant','fixture-cleanup'))
 cleanup.attempt('stop-app',lambda:raw('shell','am','force-stop',APP))
 cleanup.attempt('collect-public-evidence',collect_public)
 cleanup.attempt('bootstrap-apk',lambda:raw('install','-r','-d',str(a.apk)))
 cleanup.attempt('stop-restored-app',lambda:raw('shell','am','force-stop',APP))
 cleanup.attempt('private-data',restore_data)
 cleanup.attempt('source-approval-mode',lambda:raw('shell','cmd','appops','set',APP,operation,original_mode))
 cleanup.attempt('ime-setting',lambda:raw('shell','settings','delete','secure','show_ime_with_hard_keyboard') if original_ime=='null' else raw('shell','settings','put','secure','show_ime_with_hard_keyboard',original_ime))
 if a.layout=='phone':
  cleanup.attempt('size',lambda:raw('shell','wm','size','reset'));cleanup.attempt('density',lambda:raw('shell','wm','density','reset'))
 cleanup.attempt('public-upload',lambda:raw('shell','rm','-f','/data/local/tmp/droidcast-self-newer.apk','/data/local/tmp/droidcast-self-update-ui.xml'))
 def check_restoration():
  assert installed_hash()==r['bootstrap_apk_sha256'] and code()==6 and uid()==r['initial_uid']
  assert approval_mode()==original_mode
  assert text('shell','wm','size')==r['initial_size'] and text('shell','wm','density')==r['initial_density']
  assert text('shell','settings','get','secure','show_ime_with_hard_keyboard')==original_ime
  assert text('shell','id').startswith('uid=2000')
  r['bootstrap_restored']=True;r['adbd_unrooted']=True;r['approval_mode_restored']=True;r['geometry_restored']=True
 cleanup.attempt('verify-restoration',check_restoration)
 save();cleanup.finish()
if error:raise error
assert r['completed']
print(json.dumps({'completed':True,'layout':a.layout,'mode':a.mode,'updatedCode':7,'cleanup':'passed'}))
