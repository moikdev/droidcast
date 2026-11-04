from pathlib import Path, PurePosixPath
import argparse, hashlib, io, json, os, subprocess, tarfile, uuid

parser = argparse.ArgumentParser()
parser.add_argument('--candidate', choices=['current', 'baseline', 'pre-repair'], required=True)
parser.add_argument('--case', choices=['legacy-category', 'legacy-actions', 'touch-category', 'touch-actions', 'touch', 'inline', 'all', 'extension-menus', 'caller-diagnostics'], required=True)
parser.add_argument('--layout', choices=['tablet', 'phone', 'landscape'], required=True)
parser.add_argument('--font-scale', type=float, choices=[1, 1.3, 1.5, 2], required=True)
parser.add_argument('--restore-only', action='store_true')
args = parser.parse_args()
out = Path('artifacts/menu-readability') / f'candidate-{args.candidate}-{args.case}-{args.layout}{round(args.font_scale * 100)}-current-v18'
state_path = out / 'probe.json'
adb = ['<android-sdk>/platform-tools/adb', '-s', 'emulator-5580']
app = 'dev.tinycast.android'
owner_root = 'files/compact-menu-evidence-preservation-v1'
folders = ['extension-grid-evidence', 'readiness-evidence']
def require(value, message):
    if not value:
        raise RuntimeError(message)
def raw(*values, data=None):
    return subprocess.check_output(adb + list(values), input=data, stderr=subprocess.STDOUT, timeout=60)
def text(*values):
    return raw(*values).decode().strip()
def absent(path):
    r = subprocess.run(adb + ['shell', 'run-as', app, 'test', '-e', path], capture_output=True, timeout=30)
    require(r.returncode in [0, 1] and not r.stdout and not r.stderr, 'Cannot establish private path state')
    return r.returncode == 1
def sha(data):
    return hashlib.sha256(data).hexdigest()
def save():
    state_path.write_text(json.dumps(state, indent=2) + '\n')
def idle():
    require(text('shell', 'getprop', 'ro.boot.qemu.avd_name') == 'Tinycast_Tablet_API_36', 'Wrong AVD')
    require(text('shell', 'id').startswith('uid=2000('), 'adbd is privileged')
    require('ActiveInstrumentation{' not in text('shell', 'dumpsys', 'activity', 'processes'), 'Instrumentation is live')
def snapshot(roots):
    for path in roots.values():
        require(not absent(path) and not text('shell', 'run-as', app, 'find', path, '-type', 'l'), 'Evidence missing or symlinked')
    data = raw('exec-out', 'run-as', app, 'tar', '-cf', '-', *roots.values())
    hashes = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:') as archive:
        for member in archive:
            path = PurePosixPath(member.name)
            require(not path.is_absolute() and '..' not in path.parts, 'Invalid evidence archive member')
            if member.isdir():
                continue
            require(member.isfile(), 'Evidence archive has a nonregular member')
            matches = [(folder, root) for folder, root in roots.items() if member.name.startswith(root + '/')]
            require(len(matches) == 1, 'Unexpected evidence namespace')
            folder, root = matches[0]; key = folder + '/' + member.name.removeprefix(root + '/')
            require(key not in hashes, 'Duplicate evidence archive member')
            hashes[key] = sha(archive.extractfile(member).read())
    return data, hashes
def restore():
    idle()
    for path in ['files/reviewed-text-fixture-lease', 'files/empty-view-fixture-lease', 'files/compatibility-batch-fixtures', 'files/reviewed-text-evidence']:
        require(absent(path), 'A failed cohort retains recovery ownership; inspect and recover it first')
    expected_owner = dict(nonce=state['nonce'], folders=folders, origin='compact-menu-candidate-v18')
    require(json.loads(raw('exec-out', 'run-as', app, 'cat', owner_root + '/owner.json')) == expected_owner, 'Preservation owner changed')
    for folder in folders:
        target = 'files/' + folder; saved = owner_root + '/' + folder
        if not absent(saved):
            require(absent(target), 'New evidence remains; do not overwrite it')
            _, hashes = snapshot({folder: saved})
            require(hashes == {k: v for k, v in state['original_hashes'].items() if k.startswith(folder + '/')}, 'Saved original bytes differ')
            raw('shell', 'run-as', app, 'mv', saved, target)
        else:
            require(not absent(target), 'Original directory is absent in both locations')
        state['restored_folders'] = list(dict.fromkeys(state.get('restored_folders', []) + [folder])); save()
    _, restored = snapshot({folder: 'files/' + folder for folder in folders})
    require(restored == state['original_hashes'], 'Restored evidence differs')
    state['original_evidence_restored_and_verified'] = True; save()
    require(json.loads(raw('exec-out', 'run-as', app, 'cat', owner_root + '/owner.json')) == expected_owner, 'Owner changed before release')
    raw('shell', 'run-as', app, 'rm', owner_root + '/owner.json')
    raw('shell', 'run-as', app, 'rmdir', owner_root)
    require(absent(owner_root), 'Preservation owner remains')
    state['preservation_owner_released'] = True; save()

if args.restore_only:
    state = json.loads(state_path.read_text())
    require(not state.get('preservation_owner_released'), 'Restoration already completed; do not repeat it')
    restore()
    print(json.dumps({'restored': True, 'original_failed_run_unchanged': True}), flush=True)
    raise SystemExit(0)

require(not out.exists(), 'Earlier run exists; inspect it rather than repeating native mutations')
build = json.loads(Path('artifacts/menu-readability/build-harness-v18/state.json').read_text())
require(build['completed'] and build['sources_unchanged'], 'Build incomplete')
require(all(sha(Path(p).read_bytes()) == h for p, h in build['source_hashes'].items()), 'Source changed')
pair = Path('artifacts/menu-readability/build-harness-v18/candidate')
test_apk = pair / 'test.apk'
expected_test = build['test_apk_sha256']
test_source = build['test_source_commit']
apk = pair / 'app.apk' if args.candidate == 'current' else (Path('<user-home>/.t3/scratch/droidcast-markdown-detail-27caf3/artifacts/markdown-detail/category-menu-baseline-v1-phone200/app.apk') if args.candidate == 'baseline' else Path('artifacts/menu-readability/build-candidate-v3/candidate/app.apk'))
expected_app = build['app_sha256'] if args.candidate == 'current' else ('088f8f521add723e08f5f906d8b443be8b120dcafe5de0dc9128582c7cef3e25' if args.candidate == 'baseline' else 'b152b4396810205d209186d82cf78045e579c9da3e1d43fac43874746c05f626')
require(sha(apk.read_bytes()) == expected_app and sha(test_apk.read_bytes()) == expected_test, 'Frozen APK changed')
archive = Path('<user-home>/.t3/scratch/droidcast-command-catalog-27caf3/artifacts/extension-catalog/representative-001-bundles-v1/archives/223987eaca2c894e37ada9e087669f260cda79d6a3b88ff11463f429cc952e6a.zip')
require(sha(archive.read_bytes()) == 'fc420406a59251daee0c9af32e216026724631480b3a4e2f5d65d0c507b09a58', 'Archive changed')
idle()
for path in [owner_root, 'files/reviewed-text-fixture-lease', 'files/empty-view-fixture-lease', 'files/compatibility-batch-fixtures', 'files/reviewed-text-evidence']:
    require(absent(path), 'Prior ownership or evidence remains')
backup, hashes = snapshot({folder: 'files/' + folder for folder in folders})
require(len(hashes) == 136, 'Unexpected original evidence inventory')
out.mkdir(parents=True, exist_ok=False, mode=0o700)
p = out / 'private-original-evidence.tar'; p.write_bytes(backup); p.chmod(0o600)
state = dict(completed=False, pid=os.getpid(), nonce=uuid.uuid4().hex, candidate=args.candidate, case=args.case,
             app_sha256=expected_app, test_apk_sha256=expected_test, source_commit=build['source_commit'], production_source_commit=(build['production_source_commit'] if args.candidate == 'current' else ('350c2fe775ecdac2094538c977aeca7ac0737bfc' if args.candidate == 'baseline' else 'fd79b900acec98768190833eda4dee11af2cf308')), test_source_commit=test_source,
             original_hashes=hashes, moved_folders=[], scope='Current pointer/IME and revised legacy menu contracts. Original v1 failures remain historical. Failures retain ownership and need separate verified recovery.')
save()
raw('shell', 'run-as', app, 'mkdir', owner_root)
raw('shell', 'run-as', app, 'tee', owner_root + '/owner.json', data=(json.dumps(dict(nonce=state['nonce'], folders=folders, origin='compact-menu-candidate-v18')) + '\n').encode())
try:
    for folder in folders:
        raw('shell', 'run-as', app, 'mv', 'files/' + folder, owner_root + '/' + folder)
        state['moved_folders'].append(folder); save()
    _, saved = snapshot({folder: owner_root + '/' + folder for folder in folders})
    require(saved == hashes, 'Moved original evidence differs')
    methods = {
        'legacy-category': ['categoryMenuIsInsidePaletteAndKeyboardReturnsFocus'],
        'legacy-actions': ['actionCapsuleAndSearchableActionsPreserveTheSelectedCommand'],
        'touch-category': ['categoryPointerChoiceAndReturnWorkFromBothKeyboardStates'],
        'touch-actions': ['touchActionSearchPreservesEmptyTextCaretKeyboardAndFreshCopy'],
        'touch': ['categoryPointerChoiceAndReturnWorkFromBothKeyboardStates', 'touchActionSearchPreservesEmptyTextCaretKeyboardAndFreshCopy'],
        'inline': ['categoryPointerChoiceAndReturnWorkFromBothKeyboardStates', 'touchActionSearchPreservesEmptyTextCaretKeyboardAndFreshCopy', 'editorReplacesPaletteAndRestoresLauncherQueryCaretAndFrame', 'categoryMenuIsInsidePaletteAndKeyboardReturnsFocus', 'returningFromEditorRetainsTheScrolledLauncherViewport', 'editorSaveRemainsReachableAboveTheRealDockedKeyboard', 'programmaticCommandDismissesMenuBeforeEditingNewQuery', 'staleMenuOwnerCannotClearTheCurrentWindowState', 'menuOwnershipEpochCancelsModifierPairsAcrossOpeningAndClosing', 'inlineAliasConflictDeleteCancellationAndFeedbackStayInThePalette', 'actionSearchImeActivatesOnlyTheCurrentEnabledResultOutsideComposition', 'actionCapsuleAndSearchableActionsPreserveTheSelectedCommand', 'emojiFilterUsesSameWindowAndEscapeRestoresTyping'],
        'extension-menus': ['formsWithErrorsBlockActionsMenuKeyboardSubmit', 'ordinaryActionsRemainUsableWhileSubmitIsBlocked', 'blockedSubmitFeedbackStaysReachableAtLargeFontsAndImeTransitions'],
        'caller-diagnostics': ['inlineAliasConflictDeleteCancellationAndFeedbackStayInThePalette', 'editorSaveRemainsReachableAboveTheRealDockedKeyboard', 'editorReplacesPaletteAndRestoresLauncherQueryCaretAndFrame'],
        'all': ['categoryMenuIsInsidePaletteAndKeyboardReturnsFocus', 'actionCapsuleAndSearchableActionsPreserveTheSelectedCommand',
                'categoryPointerChoiceAndReturnWorkFromBothKeyboardStates', 'touchActionSearchPreservesEmptyTextCaretKeyboardAndFreshCopy']
    }[args.case]
    layout, font = args.layout, str(args.font_scale)
    test_class = 'ExtensionFormDeviceTest' if args.case == 'extension-menus' else 'InlinePaletteDeviceTest'
    classes = ','.join('dev.tinycast.android.' + test_class + '#' + method for method in methods)
    command = ['python3', 'scripts/test-reviewed-text-input.py', '--serial', 'emulator-5580', '--avd', 'Tinycast_Tablet_API_36',
               '--layout', layout, '--font-scale', font, '--output', str(out / 'cohort'), '--apk', str(apk), '--test-apk', str(test_apk),
               '--json-archive', str(archive), '--classes', classes, '--expected', str(len(methods))]
    state['command'] = command; save()
    print(json.dumps({'running': out.name}), flush=True)
    with (out / 'host.log').open('x') as log:
        state['exit_code'] = subprocess.run(command, env=dict(os.environ, ANDROID_HOME='<android-sdk>'), stdout=log, stderr=subprocess.STDOUT).returncode
    if (out / 'cohort/record.json').exists():
        receipt = json.loads((out / 'cohort/record.json').read_text())
        state['junit_passes'] = receipt.get('passes')
        state['failed_cleanup_checks'] = [k for k, v in receipt.get('cleanup', {}).items() if not v['passed']]
    save()
finally:
    try:
        restore()
    except BaseException as failure:
        state['restoration_pending'] = True; state['restoration_failure_type'] = type(failure).__name__; save()
        raise
state['completed'] = state.get('exit_code') == 0 and state.get('preservation_owner_released') is True; save()
print(json.dumps({k: state.get(k) for k in ['completed', 'junit_passes', 'failed_cleanup_checks', 'original_evidence_restored_and_verified']}), flush=True)
require(state['completed'], 'Native assertions failed; do not credit a passing cohort')
