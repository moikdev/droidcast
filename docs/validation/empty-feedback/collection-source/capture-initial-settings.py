from pathlib import Path
import json,os,subprocess
b=Path('artifacts/empty-feedback');out=b/'private-initial-settings.json';assert not out.exists()
adb='<android-sdk>/platform-tools/adb';keys=('default_input_method','enabled_input_methods','selected_input_method_subtype','input_methods_subtype_history');values={};checks={}
for serial,avd,api in [('emulator-5580','Tinycast_Tablet_API_36','36'),('emulator-5598','Droidcast_WebView_API26_27caf3','26')]:
 def get(*args):return subprocess.check_output([adb,'-s',serial,*args],text=True,timeout=30).strip()
 checks[serial+'-owned-avd']=get('emu','avd','name').splitlines()[0]==avd
 checks[serial+'-api']=get('shell','getprop','ro.build.version.sdk')==api
 checks[serial+'-unprivileged']=get('shell','id').startswith('uid=2000(')
 checks[serial+'-idle']='ActiveInstrumentation{' not in get('shell','dumpsys','activity','processes')
 rows=get('shell','for key in '+' '.join(keys)+'; do settings get secure "$key"; done').splitlines();assert len(rows)==4;values[serial]=dict(zip(keys,rows))
assert all(checks.values()),checks
with os.fdopen(os.open(out,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as f:json.dump(values,f)
(b/'initial-settings-receipt.json').write_text(json.dumps({'checks':checks,'current_four_field_baseline_saved_privately':True,'scope':'Read-only current baseline after PR112; older original pre-probe IME-history identity remains uncertified. The first read-only preflight used an unavailable API26 boot property and wrote no snapshot; the emulator console verifies exact AVD identity here. No device mutation.'},indent=2)+'\n');print(json.dumps(checks))
