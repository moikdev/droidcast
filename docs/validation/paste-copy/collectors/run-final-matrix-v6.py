from pathlib import Path
import hashlib, json, subprocess, sys

base = Path('artifacts/paste-copy')
out = base / 'final-matrix-v6'
out.mkdir(exist_ok=False)
state = dict(completed=False, collector_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), cohorts=[])
def save():
    (out/'record.json').write_text(json.dumps(state, indent=2)+'\n')
save()
commands = []
for layout in ['tablet', 'phone', 'landscape']:
    for method, label in [('pointerCopyIsExactOnceAndReturns', 'unicode'), ('keyboardCopyHidesImeAndEscapeReturns', 'keyboard')]:
        name = 'owned-' + label + '-' + layout + '200-v6'
        commands.append((name, ['run-owned-v6.py', '--name', name, '--method', method, '--layout', layout, '--font', '2']))
for layout in ['tablet', 'phone']:
    for group in ['callers', 'handoff']:
        name = 'regression-' + group + '-' + layout + '100-v6'
        commands.append((name, ['run-regressions-v6.py', '--name', name, '--group', group, '--layout', layout]))
for name, command in commands:
    state['current'] = name
    save()
    result = subprocess.run([sys.executable, str(base/command[0]), *command[1:]])
    state['cohorts'].append(dict(name=name, exit_code=result.returncode))
    save()
    if result.returncode:
        raise RuntimeError('Cohort failed; later planned cohorts remain unrun: '+name)
state.update(completed=True, current=None)
save()
print(json.dumps(state, indent=2))
