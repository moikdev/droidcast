from pathlib import Path
import os,json,subprocess,time,re,sys
b=Path('artifacts/form-pickers');build_name,version=sys.argv[1:];assert version.isalnum() and '/' not in build_name;root=b/('main-ime-group-system-'+version);root.mkdir(mode=0o700,exist_ok=False);state=b/('main-ime-group-monitor-'+version+'-state.json');assert not state.exists()
record={'completed':False,'scope':'Current repaired-runtime legacy group with the separately compiled current APK. Read-only input-method samples only while live IME test is actively running; raw dumps private, no repair/retry.','samples':[]}
def save():state.write_text(json.dumps(record,indent=2)+'\n')
with (root/'engine.log').open('x') as log:
 command=['python3',str(b/'run-final-typed-native-v3.py'),'legacy',build_name,version];process=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT);record['engine_pid']=process.pid;save();last=None;index=0;counts={}
 engine=b/('final-typed-native-legacy-'+version+'-state.json')
 while process.poll() is None:
  try:d=json.loads(engine.read_text()) if engine.exists() else {};cohort=d.get('cohorts',[])[-1] if d.get('cohorts') else None
  except json.JSONDecodeError:cohort=None
  tests=Path(cohort['output'])/'tests.log' if cohort else None;text=tests.read_text(errors='replace') if tests and tests.exists() else ''
  names=re.findall(r'^INSTRUMENTATION_STATUS: test=(.+)$',text,re.M);codes=re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)$',text,re.M)
  active=bool(names and codes and names[-1]=='liveImePointerSelectionSurvivesUnrelatedPublication' and codes[-1]=='1' and 'INSTRUMENTATION_CODE:' not in text)
  key=cohort['name'] if cohort else ''
  if active and counts.get(key,0)<45:
   started=time.time();result=subprocess.run(['<home>/Library/Android/sdk/platform-tools/adb','-s','emulator-5580','shell','dumpsys','input_method'],capture_output=True,timeout=10)
   p=root/f'private-input-method-{index:03}.txt';fd=os.open(p,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
   with os.fdopen(fd,'wb') as f:f.write(result.stdout+result.stderr)
   flags=[line.strip()[:180] for line in result.stdout.decode(errors='replace').splitlines() if any(k in line for k in ['mVisibleBound=','mImeWindowVis=','mInputShown='])]
   row={'sample':index,'time':started,'cohort':key,'active_test':names[-1],'instrumentation_status_code':1,'exit_code':result.returncode,'flags':flags};record['samples'].append(row);save();signature=(key,tuple(flags))
   if signature!=last:print(json.dumps(row),flush=True);last=signature
   counts[key]=counts.get(key,0)+1;index+=1
  time.sleep(.4)
 record.update(completed=True,engine_exit_code=process.returncode);save();print(json.dumps({'completed':True,'engine_exit_code':process.returncode,'samples':index}),flush=True);raise SystemExit(process.returncode)
