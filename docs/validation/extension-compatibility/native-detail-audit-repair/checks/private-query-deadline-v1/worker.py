from pathlib import Path
import ast,json,subprocess,sys,time
source=Path(sys.argv[1]);output=Path(sys.argv[2]);client=Path(sys.argv[3])
tree=ast.parse(source.read_text());main=next(x for x in tree.body if isinstance(x,ast.FunctionDef) and x.name=='main');node=next(x for x in main.body if isinstance(x,ast.FunctionDef) and x.name=='absent')
calls=[]
def inspect(result):calls.append(True);raise AssertionError('A delayed client must never certify absence')
namespace=dict(adb=[sys.executable,str(client)],app='owned.deadline.probe',subprocess=subprocess,private_path_absent=inspect)
exec(compile(ast.Module(body=[node],type_ignores=[]),str(source),'exec'),namespace)
start=time.monotonic()
output.write_text(json.dumps(dict(started=True,scope='Isolated real host subprocess; no Android/device command'))+'\n')
try:
    namespace['absent']('owned-deadline-probe')
    raise AssertionError('Delayed query returned instead of timing out')
except subprocess.TimeoutExpired:
    output.write_text(json.dumps(dict(completed=True,outcome='TimeoutExpired',elapsed_seconds=time.monotonic()-start,absence_inspection_calls=len(calls),absence_certified=False))+'\n')
