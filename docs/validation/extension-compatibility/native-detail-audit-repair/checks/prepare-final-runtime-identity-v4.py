from pathlib import Path
import hashlib,json,subprocess
B=Path('artifacts/markdown-detail');OUT=B/'audit-final-source-identity-v4.json';LOCAL=B/'audit-final-local-v3-state.json';assert not OUT.exists() and not LOCAL.exists()
read=lambda n:json.loads((B/n).read_text());sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
build=read('audit-repair-build-v3-state.json');local=read('audit-final-local-v2-state.json');py=read('transport-runner-python-v1-state.json');probe=read('private-query-deadline-v1-state.json');recovery=read('transport-cohort-recovery-v1/public-recovery.json')
assert all(x['completed'] for x in [build,local,py,probe,recovery]) and all(recovery['checks'].values())
head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip();assert head==py['runner_source_commit']
assert not subprocess.check_output(['git','status','--porcelain'],text=True).strip()
current={p:sha(p) for p in build['source_hashes']};assert current==py['source_hashes']
assert [p for p,h in build['source_hashes'].items() if current[p]!=h]==['scripts/test-reviewed-text-input.py']
identity={k:build[k] for k in ['production_source_commit','test_source_commit','common_test_source_commit','common_test_apk_sha256','android_test_sources_match_common','android_test_changes_from_common','host_json_provider_not_defined_in_app_or_instrumentation','candidate_directory','app_sha256','test_apk_sha256','jvm','lint']}
identity.update(completed=True,sources_unchanged=True,current_repository_source_commit=head,runner_source_commit=head,source_hashes=current,
    apk_build_source_hashes=build['source_hashes'],frozen_build_record=dict(record='audit-repair-build-v3-state.json',sha256=sha(B/'audit-repair-build-v3-state.json')),
    python_record=dict(record='transport-runner-python-v1-state.json',sha256=sha(B/'transport-runner-python-v1-state.json')),
    query_deadline_record=dict(record='private-query-deadline-v1-state.json',sha256=sha(B/'private-query-deadline-v1-state.json')),
    scope='Current source manifest with a bounded Python runner. Android/build/native-test sources and frozen binaries remain exactly8e75546. JVM/build/lint are retained8e results, Python89 per mode is fresh at this runner commit; no rebuild is claimed.')
OUT.write_text(json.dumps(identity,indent=2)+'\n')
for p,h in local['node_reuse']['matching_relevant_source_hashes'].items():assert sha(p)==h
value=dict(completed=True,production_source_commit=build['production_source_commit'],test_source_commit=build['test_source_commit'],runner_source_commit=head,current_repository_source_commit=head,source_hashes=current,
    frozen_android_results=dict(record='audit-final-local-v2-state.json',sha256=sha(B/'audit-final-local-v2-state.json'),android_build_and_jvm_sources_unchanged=True),
    retained_jvm=local['retained_jvm'],python_execution=dict(record='transport-runner-python-v1-state.json',sha256=sha(B/'transport-runner-python-v1-state.json'),runs=py['runs']),
    node_reuse=local['node_reuse'],candidate_directory=local['candidate_directory'],benchmark_apk_sha256=local['benchmark_apk_sha256'],benchmark_test_apk_sha256=local['benchmark_test_apk_sha256'],lint=local['lint'],
    scope='Fresh Python normal/optimized checks and isolated query-deadline control. Android builds/JVM/lint and Node contracts retain their exact earlier executions with unchanged relevant sources. Benchmark device execution is unclaimed.')
LOCAL.write_text(json.dumps(value,indent=2)+'\n');print('Prepared current218-source identity with unchanged8e APKs and fresh bounded-runner checks',flush=True)
