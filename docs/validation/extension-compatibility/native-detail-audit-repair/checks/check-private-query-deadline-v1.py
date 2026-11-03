from pathlib import Path
import ast,hashlib,json,os,signal,subprocess,sys,time
B=Path('artifacts/markdown-detail');OUT=B/'private-query-deadline-v1';STATE=B/'private-query-deadline-v1-state.json'
assert not OUT.exists() and not STATE.exists();OUT.mkdir(mode=0o700)
old=B/'audit-final-native-v2-markdown-phone200/runner.py';new=Path('scripts/test-reviewed-text-input.py')
assert old.is_file() and new.is_file()
def function(p):
    main=next(x for x in ast.parse(p.read_text()).body if isinstance(x,ast.FunctionDef) and x.name=='main')
    return next(x for x in main.body if isinstance(x,ast.FunctionDef) and x.name=='absent')
before=function(old);after=function(new)
assert 'timeout=60' not in ast.unparse(before) and 'timeout=60' in ast.unparse(after)
(OUT/'delayed_client.py').write_text('import sys,time\ntime.sleep(120)\nsys.exit(1)\n')
worker='''from pathlib import Path
import ast,json,subprocess,sys,time
source=Path(sys.argv[1]);output=Path(sys.argv[2]);client=Path(sys.argv[3])
tree=ast.parse(source.read_text());main=next(x for x in tree.body if isinstance(x,ast.FunctionDef) and x.name=='main');node=next(x for x in main.body if isinstance(x,ast.FunctionDef) and x.name=='absent')
calls=[]
def inspect(result):calls.append(True);raise AssertionError('A delayed client must never certify absence')
namespace=dict(adb=[sys.executable,str(client)],app='owned.deadline.probe',subprocess=subprocess,private_path_absent=inspect)
exec(compile(ast.Module(body=[node],type_ignores=[]),str(source),'exec'),namespace)
start=time.monotonic()
output.write_text(json.dumps(dict(started=True,scope='Isolated real host subprocess; no Android/device command'))+'\\n')
try:
    namespace['absent']('owned-deadline-probe')
    raise AssertionError('Delayed query returned instead of timing out')
except subprocess.TimeoutExpired:
    output.write_text(json.dumps(dict(completed=True,outcome='TimeoutExpired',elapsed_seconds=time.monotonic()-start,absence_inspection_calls=len(calls),absence_certified=False))+'\\n')
'''
(OUT/'worker.py').write_text(worker)
s=dict(completed=False,pid=os.getpid(),scope='Two isolated copies of the actual old/current absent function with the same delayed host client. No device, UI or JUnit executions. Original probe is terminated after the observation bound; current probe must raise its real 60-second timeout without certifying absence.',source_hashes={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in [old,new]},probes=[])
def save():STATE.write_text(json.dumps(s,indent=2)+'\n')
save();jobs=[];start=time.monotonic()
for label,path in [('before',old),('after',new)]:
    log=(OUT/(label+'.log')).open('x')
    p=subprocess.Popen([sys.executable,str(OUT/'worker.py'),str(path),str(OUT/(label+'.json')),str(OUT/'delayed_client.py')],stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    jobs.append((label,p,log))
try:
    while time.monotonic()-start<66:time.sleep(.25)
    for label,p,log in jobs:
        if label=='before':
            assert p.poll() is None,'The old no-timeout function should still be blocked'
            os.killpg(p.pid,signal.SIGTERM);p.wait(timeout=5)
            s['probes'].append(dict(phase=label,expected_blocked=True,terminated_after_seconds=time.monotonic()-start,credited_pass=False))
        else:
            assert p.poll()==0,(p.poll(),(OUT/(label+'.log')).read_text())
            result=json.loads((OUT/(label+'.json')).read_text());assert result['outcome']=='TimeoutExpired' and 59<=result['elapsed_seconds']<66 and result['absence_inspection_calls']==0 and not result['absence_certified']
            s['probes'].append(dict(phase=label,**result))
        log.close();save()
    s['completed']=True;save();print(json.dumps(s['probes']),flush=True)
finally:
    for label,p,log in jobs:
        if p.poll() is None:os.killpg(p.pid,signal.SIGTERM);p.wait(timeout=5)
        if not log.closed:log.close()
