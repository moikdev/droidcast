from pathlib import Path
import hashlib,json,os,re,subprocess
B=Path('artifacts/markdown-detail');STATE=B/'transport-runner-python-v1-state.json';assert not STATE.exists()
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip();assert head.startswith('a2cab95')
assert not subprocess.check_output(['git','status','--porcelain'],text=True).strip()
build=json.loads((B/'audit-repair-build-v3-state.json').read_text())
sources={p:sha(p) for p in build['source_hashes']}
changed=[p for p,h in build['source_hashes'].items() if sources[p]!=h]
assert changed==['scripts/test-reviewed-text-input.py']
s=dict(completed=False,pid=os.getpid(),runner_source_commit=head,production_source_commit=build['production_source_commit'],source_hashes=sources,changed_sources_since_apk_build=changed,runs=[])
def save():STATE.write_text(json.dumps(s,indent=2)+'\n')
save()
for mode,command in [('normal',['python3','-m','unittest','discover','-s','scripts/tests','-v']),('optimized',['python3','-O','-m','unittest','discover','-s','scripts/tests','-v'])]:
    log=B/('transport-runner-python-v1-'+mode+'.log')
    with log.open('x') as output:r=subprocess.run(command,stdout=output,stderr=subprocess.STDOUT)
    text=log.read_text();count=re.search(r'^Ran (\d+) tests? in ',text,re.M);assert count
    s['runs'].append(dict(mode=mode,command=command,exit_code=r.returncode,cases=int(count[1]),log_sha256=sha(log)));save()
    assert r.returncode==0 and int(count[1])==89 and re.search(r'^OK$',text,re.M)
s.update(completed=True,sources_unchanged=all(sha(p)==h for p,h in sources.items()));assert s['sources_unchanged'];save()
print(json.dumps(dict(completed=True,runner_source_commit=head,runs=s['runs'])),flush=True)
