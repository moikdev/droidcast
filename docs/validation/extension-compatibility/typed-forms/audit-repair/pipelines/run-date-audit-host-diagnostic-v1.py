"""One whole-cohort rerun with read-only host load observations; no input retries."""
from pathlib import Path
import json, os, subprocess, time

b = Path('artifacts/form-pickers')
state = b / 'date-audit-host-diagnostic-v1-state.json'
assert not state.exists()
fonts = json.loads((b / 'final-typed-native-criticalfonts-auditfinalv1-state.json').read_text())
assert fonts['completed'] and all(c['completed'] and c['cleanup_all_passed'] for c in fonts['cohorts'])
prior = json.loads((b / 'final-typed-native-auditrepairs-auditfinalv1-state.json').read_text())
assert not prior['completed'] and [c['passes'] for c in prior['cohorts']] == [4,4,3]
assert all(c['cleanup_all_passed'] for c in prior['cohorts'])
assert not (b / 'final-typed-native-auditrepairs-auditdiagv1-state.json').exists()
record = dict(completed=False, pid=os.getpid(), samples=[],
    scope='One fresh complete landscape four-case cohort on identical frozen APKs. Read-only host load averages; no additional device commands, assertion change, input retry, helper repair or relabeling of the prior failed setup.')
def save():
    state.write_text(json.dumps(record, indent=2)+'\n')
save()
child = subprocess.Popen(['python3',str(b/'run-date-audit-landscape-diagnostic-v1.py'),
    'auditrepairs','typed-build-audit-repair-v1-state.json','auditdiagv1'])
record['engine_pid'] = child.pid; save()
while child.poll() is None:
    log = b/'final-typed-native-auditrepairs-auditdiagv1-landscape200'/'tests.log'
    active = None
    if log.exists():
        for line in log.read_text(errors='replace').splitlines():
            if line.startswith('INSTRUMENTATION_STATUS: test='):
                active = line.split('=',1)[1]
    record['samples'].append(dict(time=time.time(),host_load_averages=list(os.getloadavg()),last_reported_test=active))
    save(); time.sleep(1)
record.update(completed=True,engine_exit_code=child.returncode); save()
print(json.dumps(dict(completed=True,engine_exit_code=child.returncode,host_samples=len(record['samples']))),flush=True)
raise SystemExit(child.returncode)
