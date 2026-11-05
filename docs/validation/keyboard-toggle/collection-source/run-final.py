from pathlib import Path
import json,os,subprocess,sys,time
b=Path('artifacts/keyboard-toggle');out=b/'final-orchestration.json';assert not out.exists();state={'completed':False,'parent_pid':os.getpid(),'phases':[]}
def save():out.write_text(json.dumps(state,indent=2)+'\n')
save()
for name,script,logname in [('back-diagnostics','run-back-diagnostics.py','back-diagnostics.log'),('native-matrix','run-native-v4.py','native-v4.log')]:
 row={'name':name,'state':'running','started':time.time()};state['phases'].append(row);save()
 with (b/logname).open('xb') as log:
  p=subprocess.Popen([sys.executable,str(b/script)],stdout=log,stderr=subprocess.STDOUT);row['child_pid']=p.pid;save();code=p.wait()
 row.update(state='finished',finished=time.time(),exit_code=code);save();print(name,code,flush=True)
 if code:raise SystemExit(code)
state['completed']=True;save()
