from pathlib import Path
import hashlib,json,subprocess,sys
base=Path('artifacts/offline-workflows');out=base/'python-final-v1'
def require(v,m):
 if not v:raise RuntimeError(m)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
identity=json.loads((base/'build-harness-v7/state.json').read_text());sources={p:h for p,h in identity['source_hashes'].items() if p.endswith('.py')}
require(all(sha(p)==h for p,h in sources.items()),'Python sources differ from final harness identity')
out.mkdir(exist_ok=False);r={'completed':False,'source_head':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'source_hashes':sources,'runs':[]}
def save():(out/'record.json').write_text(json.dumps(r,indent=2)+'\n')
save()
for mode in ['normal','optimized']:
 command=[sys.executable]+(['-O'] if mode=='optimized' else [])+['-m','unittest','discover','-s','scripts/tests']
 with (out/(mode+'.log')).open('wb') as log:process=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
 text=(out/(mode+'.log')).read_text();r['runs'].append({'mode':mode,'invocation':['python3']+command[1:],'exit_code':process.returncode,'cases':105 if 'Ran 105 tests' in text else None});save()
 require(process.returncode==0 and 'Ran 105 tests' in text and '\nOK\n' in text,'Python check failed')
 require(all(sha(p)==h for p,h in sources.items()),'Python source changed during checks')
r['completed']=True;save();print(json.dumps({'completed':True,'cases_per_mode':105,'source_files':len(sources),'source_head':r['source_head']},indent=2))
