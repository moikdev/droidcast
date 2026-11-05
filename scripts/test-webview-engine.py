#!/usr/bin/env python3
"""Owned API26/API36 engine, input or touch-search checks; retain ownership on restoration failure."""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import io
import json
import os
import re
import shlex
import subprocess
import tarfile
import time
import uuid
from android_test_cleanup import Cleanup, private_path_absent

APP = 'dev.tinycast.android'
TEST = APP + '.test'
CLASS = 'dev.tinycast.android.WebViewRecoveryDeviceTest'
CASES = {
    'legacy': ['incompatibleEngineShowsNativeRecoveryWithoutDispatch',
               'incompatibleEngineOpensOnlyActualProviderSettingsAndReturns',
               'replacedRecoveryRejectsRetainedSettingsAndReturnCallbacks',
               'equivalentFailureGetsFreshRecoveryActionsBeforeRecomposition'],
    'modern': ['modernEnginePublishesListAndRunsLiveAction', 'modernEnginePublishesGridAndRunsLiveAction'],
    'shared': ['actualProviderParsesProbeWithoutRunningItsBodyAndRejectsSyntax',
               'closingBeforePreflightFinishesRejectsLatePublication',
               'bridgeCannotPublishOrReadHostApisBeforeApproval'],
}
INPUT_CASES = ['recordDefaultImeAndControlledCompositionNavigation',
               'recordCallerSelectionAcrossNativeRoutes',
               'recordLargeDraftTextAndAnnotationsAfterRecreation',
               'recordSameTurnLauncherActivationTextAndAnnotations',
               'recordInjectedTypingAndCommittedQueryNavigation',
               'exactContentAssertionAcceptsCompositionAndRejectsChangedCharacters',
               'committedEmojiNavigationAndCaretAreExact',
               'activeMultilingualCompositionKeepsResultSelection',
               'committedCallerSelectionSurvivesIdleNativeRoutes',
               'committedSameTurnLauncherActivationUsesCurrentNote',
               'largeDraftRestoresExactCharactersAfterRecreation']
CONTRACT_CASES = INPUT_CASES[5:]
TOUCH_CASES = ['launcherTouchClearPreservesFocusCategoryAndKeyboard',
               'emojiTouchClearPreservesCategoryGeometryAndKeyboard',
               'pointerClearRemovesControlledCompositionAndWhitespace',
               'retainedClearCallbacksRejectReplacedSurfacesAndMenus',
               'currentClearUsesLiveQueryAndRecreationRejectsOldCallback',
               'launcherEmptyFeedbackIsReadableAboveImeAndControlsRemainReachable',
               'emojiEmptyFeedbackIsReadableAboveImeAndControlsRemainReachable',
               'launcherKeyboardToggleRevealsResultsAndPreservesSearch',
               'emojiKeyboardToggleRevealsResultsAndPreservesSearch',
               'launcherKeyboardCallbacksRejectObsoleteModesOwnersAndMenus',
               'emojiKeyboardCallbacksRejectObsoleteModesOwnersAndMenus',
               'currentKeyboardAccessibilityActionsApplyTheirPublishedVisibility',
               'emojiMenuBackPreservesCallerQueryWithoutKeyboardToggle',
               'emojiMenuDismissalsPreserveRangeAndImeIntent',
               'launcherMenuDismissalsPreserveRangeAndFilter',
               'emojiCanceledRepeatedBackKeepsActiveMenu']
OWNED_IME = TEST + '/dev.tinycast.android.NativeInputTestIme'
IME_SETTING_KEYS = ('default_input_method', 'enabled_input_methods', 'selected_input_method_subtype',
                    'input_methods_subtype_history')


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def listed_package_presence(output, package):
    lines = output.splitlines()
    require(all(re.fullmatch(r'package:[A-Za-z0-9_.]+', line) for line in lines), 'Invalid PackageManager inventory')
    return 'package:' + package in lines


def archive_files(data):
    files = {}
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for item in archive:
            if item.isdir():
                continue
            path = PurePosixPath(item.name)
            require(item.isfile() and not path.is_absolute() and '..' not in path.parts
                    and path.parts[0] in ('files', 'shared_prefs') and item.size <= 64 * 1024 * 1024,
                    'Unexpected private archive entry')
            require(str(path) not in files, 'Duplicate private archive path')
            files[str(path)] = archive.extractfile(item).read()
    return files


def private_app_archive(adb, destination, timeout=60):
    """Stream large snapshots to a private file; publish only a validated archive.

    Keeping the transfer out of a Python stdout pipe bounds buffering and does
    not depend on a caller closing its interactive stdin. A failed capture stays
    partial, with the device lease retained by the caller.
    """
    destination = Path(destination)
    partial = destination.with_name(destination.name + '.partial')
    require(destination.name.startswith('private-') and destination.suffix == '.tar', 'Private archive destination required')
    require(not destination.exists() and not partial.exists(), 'Inspect the existing private snapshot before repeating capture')
    descriptor = os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as output:
        command = adb + ['exec-out', 'run-as', APP, 'tar', '-cf', '-', 'files', 'shared_prefs']
        result = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=output,
                                stderr=subprocess.PIPE, timeout=timeout)
        require(result.returncode == 0 and not result.stderr, 'Private archive capture failed')
        output.flush()
        os.fsync(output.fileno())
    files = archive_files(partial.read_bytes())
    os.replace(partial, destination)
    return files


def fixture(id):
    # ES5 source: a failure on API26 belongs to the unchanged shared bootstrap,
    # not to modern syntax added by the command fixture.
    sources = {}
    for name, kind, row in [('index', 'List', 'Owned engine row one'), ('grid', 'Grid', 'Owned grid row one')]:
        sources[name + '.js'] = ("var React=require('react'),api=require('@raycast/api');exports.default=function(){"
            "var state=React.useState(false),done=state[0],setDone=state[1];return React.createElement(api." + kind +
            ",{navigationTitle:'Owned WebView fixture'},React.createElement(api." + kind + ".Item,{id:'owned-one',"
            "title:done?'Owned " + kind + " action published':'" + row + "',actions:React.createElement(api.ActionPanel,null,"
            "React.createElement(api.Action,{title:'Publish owned action',onAction:function(){setDone(true);}}))}));};\n").encode()
    manifest = {'name': 'owned-webview-fixture', 'title': 'Owned engine fixture', 'commands': [
        {'name': name, 'title': 'Owned engine fixture', 'mode': 'view'} for name in ('index', 'grid')]}
    listing = {'id': id, 'name': 'owned-webview-fixture', 'title': 'Owned engine fixture',
        'description': 'Owned engine fixture; local React state only', 'author': 'Droidcast tests',
        'handle': 'owned-webview-tests', 'download_url': '', 'source_url': '', 'commit_sha': '', 'command_count': 2}
    return sources | {'package.json': json.dumps(manifest).encode(), '.tinycast-store.json': json.dumps(listing).encode()}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--avd', required=True)
    p.add_argument('--apk', type=Path, required=True)
    p.add_argument('--test-apk', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--cases', required=True, help='Comma-separated allowlisted methods from the selected suite')
    p.add_argument('--suite', choices=['engine', 'native-input', 'touch-search'], default='engine',
                   help='Input/touch-search reuse emulator restoration without executing an extension')
    p.add_argument('--input-mode', choices=['system', 'owned'], default='system',
                   help='Controlled contracts require the silent test IME; system-IME diagnostics retain the current keyboard')
    p.add_argument('--font-scale', type=float, choices=[1, 2], default=1)
    p.add_argument('--layout', choices=['tablet', 'phone', 'landscape'], default='phone')
    p.add_argument('--expected-negative', action='store_true', help='Only the missing recovery assertion on the old app')
    a = p.parse_args()
    legacy = a.avd == 'Droidcast_WebView_API26_27caf3'
    native_input = a.suite == 'native-input'
    touch_search = a.suite == 'touch-search'
    native_probe = native_input or touch_search
    namespace = 'touch-search' if touch_search else 'native-input' if native_input else 'webview-engine'
    lease_name = 'touch-search-lease' if touch_search else 'native-input-lease' if native_input else 'webview-compatibility-lease'
    evidence_name = 'touch-search-evidence' if touch_search else 'native-input-evidence' if native_input else 'webview-compatibility-evidence'
    argument_prefix = 'touchSearch' if touch_search else 'nativeInput' if native_input else 'webView'
    suite_class = ('dev.tinycast.android.TouchSearchDeviceTest' if touch_search else
                   'dev.tinycast.android.NativeInputCompatibilityDeviceTest' if native_input else CLASS)
    require(a.serial.startswith('emulator-') and a.avd in ('Droidcast_WebView_API26_27caf3', 'Tinycast_Tablet_API_36'), 'Use an explicitly owned AVD')
    methods = a.cases.split(',')
    require(len(methods) == len(set(methods)) and 1 <= len(methods) <= 7 and all(
        name in (TOUCH_CASES if touch_search else INPUT_CASES if native_input else CASES['shared'] + CASES['legacy' if legacy else 'modern']) for name in methods), 'Unsupported native cases')
    require(not a.expected_negative or not native_probe and legacy and methods == [CASES['legacy'][0]], 'Negative control is limited to the old missing-recovery assertion')
    require(a.input_mode != 'owned' or native_input and all(name in CONTRACT_CASES for name in methods), 'Owned IME is limited to controlled native-input contracts')
    require(not native_input or not any(name in CONTRACT_CASES for name in methods) or a.input_mode == 'owned', 'Controlled contracts require --input-mode owned')
    require(not legacy or a.layout == 'phone', 'Keep the actual API26 geometry unchanged')
    require(a.apk.is_file() and a.test_apk.is_file(), 'Both frozen APKs are required')
    sdk = os.environ.get('ANDROID_HOME') or next(x.removeprefix('sdk.dir=') for x in Path('local.properties').read_text().splitlines() if x.startswith('sdk.dir='))
    adb = [str(Path(sdk) / 'platform-tools/adb'), '-s', a.serial]

    def raw(*args, data=None, timeout=60):
        return subprocess.check_output(adb + list(args), input=data, stderr=subprocess.STDOUT, timeout=timeout)

    def text(*args):
        return raw(*args).decode().strip()

    def read_input_method_settings():
        # One ADB round trip observes this settings group. Four separate remote
        # sessions could exhaust the restoration deadline before confirmation.
        values = raw('shell', 'for key in ' + ' '.join(IME_SETTING_KEYS) +
                     '; do settings get secure "$key"; done', timeout=10).decode().splitlines()
        require(len(values) == len(IME_SETTING_KEYS), 'Invalid grouped input method settings response')
        return dict(zip(IME_SETTING_KEYS, values))

    def absent(path):
        # API26 run-as cannot exec toybox's `test` symlink. The shell builtin
        # preserves silent 0/1 semantics without treating transport errors as absence.
        command = 'run-as ' + shlex.quote(APP) + ' /system/bin/sh -c ' + shlex.quote('test -e "$1"') + ' x ' + shlex.quote(path)
        return private_path_absent(subprocess.run(adb + ['shell', command], capture_output=True, timeout=30))

    def package_path(pkg):
        # Old PackageManager returns exit 1 from `path` for an absent package.
        # Establish absence from its successful exact inventory first.
        return text('shell', 'pm', 'path', pkg) if listed_package_presence(text('shell', 'pm', 'list', 'packages', pkg), pkg) else ''

    def restore_font(wanted):
        # Android26 may materialize its default row after processing deletion
        # of a nondefault scale. Require the original raw value to settle;
        # never relabel an effective default as exact restoration.
        deadline = time.monotonic() + 5
        stable = None
        while time.monotonic() < deadline:
            value = text('shell', 'settings', 'get', 'system', 'font_scale')
            if value == wanted:
                if stable is None:
                    stable = time.monotonic()
                if time.monotonic() - stable >= .5:
                    return
            else:
                stable = None
                raw('shell', 'settings', 'delete', 'system', 'font_scale') if wanted == 'null' else raw('shell', 'settings', 'put', 'system', 'font_scale', wanted)
            time.sleep(.1)
        require(False, 'Original raw font setting did not settle')

    require(text('emu', 'avd', 'name').splitlines()[0] == a.avd and text('shell', 'getprop', 'ro.kernel.qemu') == '1', 'AVD identity changed')
    require(text('shell', 'getprop', 'ro.build.version.sdk') == ('26' if legacy else '36'), 'Unexpected Android API')
    require(text('shell', 'id').startswith('uid=2000('), 'adbd must remain unprivileged')
    require('ActiveInstrumentation{' not in text('shell', 'dumpsys', 'activity', 'processes'), 'Another instrumentation is active')
    initial_packages = {pkg: package_path(pkg) for pkg in (APP, TEST)}
    require(all(not value for value in initial_packages.values()) if legacy else all(initial_packages.values()), 'Inspect existing packages before repeating an install')
    size = text('shell', 'wm', 'size'); density = text('shell', 'wm', 'density')
    require('Override' not in size + density, 'An existing geometry override has an owner')
    font = text('shell', 'settings', 'get', 'system', 'font_scale')
    ime_settings = read_input_method_settings() if a.input_mode == 'owned' else None
    if ime_settings:
        require(re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+', ime_settings['default_input_method']), 'A restorable original input method is required')
        require(not any(OWNED_IME in value for value in ime_settings.values()), 'An existing owned IME activation/history has a recovery owner')
    nonce = uuid.uuid4().hex; id = namespace + '-' + nonce
    lease = 'files/' + lease_name
    evidence = 'files/' + evidence_name
    root = 'files/extensions/' + id
    owner = {'kind': namespace + '-v1' if native_probe else 'webview-compatibility-v1', 'nonce': nonce, 'extension_id': id, 'fresh_disposable_avd': legacy}
    if native_input:
        owner['input_mode'] = a.input_mode
    owned = [root, 'cache/' + id, 'files/extension-data/' + id, 'shared_prefs/extension-' + id + '.xml',
             'shared_prefs/extension-' + id + '.xml.bak', evidence]
    if not legacy:
        require(not text('shell', 'run-as', APP, 'id').startswith(('uid=0(', 'uid=1000(', 'uid=2000(')), 'The app must remain unprivileged')
        for path in [lease, evidence, 'files/webview-compatibility-lease', 'files/native-input-lease', 'files/touch-search-lease', 'files/reviewed-text-fixture-lease', 'files/empty-view-fixture-lease'] + owned[:5]:
            require(absent(path), 'A prior recovery owner/path remains: ' + path)
    a.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    (a.output / 'private-owner.json').write_text(json.dumps(owner)); (a.output / 'private-owner.json').chmod(0o600)
    r = {'completed': False, 'expected_negative': a.expected_negative, 'runner_sha256': sha(Path(__file__).read_bytes()),
         'apk_sha256': sha(a.apk.read_bytes()), 'test_apk_sha256': sha(a.test_apk.read_bytes()), 'methods': methods,
         'api': 26 if legacy else 36, 'font_scale': a.font_scale, 'layout': a.layout, 'cleanup': {},
         'provider_before': [line.strip() for line in text('shell', 'dumpsys', 'webviewupdate').splitlines() if 'Current WebView package' in line],
         'original_geometry': {'size': size, 'density': density}, 'original_font_scale': font}
    r['suite'] = a.suite
    r['input_mode'] = a.input_mode
    if ime_settings:
        path = a.output / 'private-ime-settings.json'; path.write_text(json.dumps(ime_settings)); path.chmod(0o600)
        r['ime_restoration_scope'] = 'Four raw secure settings: default, enabled methods, selected subtype and subtype history; verified before test-APK stop and after its removal/replacement'
    def save():
        (a.output / 'record.json').write_text(json.dumps(r, indent=2) + '\n')
    save()
    before = {}; originals = {}; palette = None; original_profile = None
    lease_claimed = False; installed = []; ime_activation_attempted = False
    def claim():
        # A never-launched fresh installation has no files directory yet.
        raw('shell', 'run-as', APP, 'mkdir', '-p', 'files')
        raw('shell', 'run-as', APP, 'mkdir', lease)
        raw('shell', 'run-as', APP, 'tee', lease + '/owner.json', data=json.dumps(owner).encode())
    def verify_owner():
        require(json.loads(raw('exec-out', 'run-as', APP, 'cat', lease + '/owner.json')) == owner, 'Recovery owner changed')
    def verify_input_method():
        require(read_input_method_settings() == ime_settings,
                'Original input method settings changed after test APK restoration')
    if not legacy:
        claim(); lease_claimed = True
        for pkg in (TEST, APP):
            raw('shell', 'am', 'force-stop', pkg)
        for pkg, value in initial_packages.items():
            require(value.count('package:') == 1, 'Split APK restoration is not supported by this runner')
            data = raw('exec-out', 'cat', value.removeprefix('package:'))
            path = a.output / ('private-original-' + pkg + '.apk'); path.write_bytes(data); path.chmod(0o600)
            originals[pkg] = (path, sha(data))
        original_files = private_app_archive(adb, a.output / 'private-data-before.tar')
        original_profile = original_files.get('files/profileInstalled')
        before = {path: sha(value) for path, value in original_files.items() if not path.startswith(lease + '/')}
        palette = raw('exec-out', 'run-as', APP, 'cat', 'shared_prefs/palette.xml')
        (a.output / 'private-palette.xml').write_bytes(palette); (a.output / 'private-palette.xml').chmod(0o600)
    try:
        for pkg, apk in [(APP, a.apk), (TEST, a.test_apk)]:
            raw('install', '-r', '-t', str(apk), timeout=120); installed.append(pkg)
            require(sha(raw('exec-out', 'cat', text('shell', 'pm', 'path', pkg).removeprefix('package:'))) == sha(apk.read_bytes()), 'Installed APK differs')
        if legacy:
            claim(); lease_claimed = True
        if ime_settings:
            ime_activation_attempted = True
            raw('shell', 'ime', 'enable', OWNED_IME)
            raw('shell', 'ime', 'set', OWNED_IME)
            require(text('shell', 'settings', 'get', 'secure', 'default_input_method') == OWNED_IME, 'Owned IME was not selected')
        if not native_probe:
            raw('shell', 'run-as', APP, 'mkdir', '-p', root)
        r['fixture_sha256'] = {}
        for name, data in ({} if native_probe else fixture(id)).items():
            raw('shell', 'run-as', APP, 'tee', root + '/' + name, data=data); r['fixture_sha256'][name] = sha(data)
        if not legacy and a.layout != 'tablet':
            raw('shell', 'wm', 'size', '1080x2340' if a.layout == 'phone' else '2340x1080'); raw('shell', 'wm', 'density', '420')
        raw('shell', 'settings', 'put', 'system', 'font_scale', str(a.font_scale))
        save()
        command = ['shell', 'am', 'instrument', '-w', '-r', '-e', argument_prefix + 'Fixture', 'true',
                   '-e', argument_prefix + 'Owner', nonce,
                   '-e', 'touchSearchFontScale', str(a.font_scale),
                   '-e', 'nativeInputMode', a.input_mode,
                   '-e', 'class', ','.join(suite_class + '#' + name for name in methods), TEST + '/androidx.test.runner.AndroidJUnitRunner']
        with (a.output / 'tests.log').open('xb') as log:
            result = subprocess.run(adb + command, stdout=log, stderr=subprocess.STDOUT, timeout=90 * len(methods))
        r['instrumentation_exit_code'] = result.returncode
        require('ActiveInstrumentation{' not in text('shell', 'dumpsys', 'activity', 'processes'), 'Instrumentation still active; retain recovery owner')
        log = (a.output / 'tests.log').read_text()
        r['passes'] = len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0$', log, re.M))
        r['failures'] = len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: -2$', log, re.M))
        r['initialization_error'] = 'INSTRUMENTATION_STATUS: test=initializationError' in log
        r['native_expected_outcome'] = (r['passes'] == 0 and r['failures'] == 1 and 'extension-engine-problem' in log and 'NoClassDefFoundError' not in log
            if a.expected_negative else r['passes'] == len(methods) and r['failures'] == 0 and f'OK ({len(methods)} test' in log)
        save()  # Keep the native outcome even when evidence collection fails.
        data = raw('exec-out', 'run-as', APP, 'tar', '-cf', '-', evidence)
        frames = archive_files(data)
        captures = a.output / 'captures'; captures.mkdir()
        for path, value in frames.items():
            name = PurePosixPath(path).name
            require(PurePosixPath(path).parent == PurePosixPath(evidence) and re.fullmatch(r'[a-z0-9-]+\.(png|xml|json)', name), 'Unexpected evidence path')
            (captures / name).write_bytes(value)
        r['captures'] = {PurePosixPath(path).name: sha(value) for path, value in frames.items()}
        save()
    finally:
        c = Cleanup(r)
        if lease_claimed:
            def idle_owner():
                require('ActiveInstrumentation{' not in text('shell', 'dumpsys', 'activity', 'processes'), 'Do not restore under active instrumentation')
                verify_owner()
            c.attempt('idle-owned-instrumentation', idle_owner)
            if not c.errors and ime_activation_attempted:
                def restore_input_method():
                    verify_owner()
                    raw('shell', 'ime', 'set', shlex.quote(ime_settings['default_input_method']))
                    raw('shell', 'ime', 'disable', OWNED_IME)
                    deadline = time.monotonic() + 5
                    stable = None
                    samples = r.setdefault('ime_restore_observations', [])
                    while time.monotonic() < deadline:
                        started = time.monotonic()
                        current = read_input_method_settings()
                        observed = time.monotonic()
                        samples.append({'query_ms': round((observed - started) * 1000),
                                        'all_raw_fields_equal': current == ime_settings})
                        if current == ime_settings:
                            if stable is None:
                                stable = observed
                            if observed - stable >= .5:
                                return
                        else:
                            stable = None
                            for key, value in ime_settings.items():
                                if current[key] != value:
                                    raw('shell', 'settings', 'delete', 'secure', key) if value == 'null' else raw('shell', 'settings', 'put', 'secure', key, shlex.quote(value))
                        time.sleep(.1)
                    require(False, 'Original raw input method settings did not settle')
                c.attempt('restore-input-method-before-test-apk-stop', restore_input_method)
            if not c.errors:
                for pkg in (TEST, APP):
                    c.attempt('stop-' + pkg, lambda pkg=pkg: raw('shell', 'am', 'force-stop', pkg))
                def remove_owned():
                    verify_owner()
                    raw('shell', 'run-as', APP, 'rm', '-rf', *owned)
                    require(all(absent(path) for path in owned), 'Registered fixture path remains')
                c.attempt('owned-paths', remove_owned)
                if palette is not None:
                    def restore_palette():
                        raw('shell', 'run-as', APP, 'rm', '-f', 'shared_prefs/palette.xml.bak')
                        raw('shell', 'run-as', APP, 'tee', 'shared_prefs/palette.xml', data=palette)
                        require(raw('exec-out', 'run-as', APP, 'cat', 'shared_prefs/palette.xml') == palette, 'Palette restoration differs')
                    c.attempt('palette-bytes', restore_palette)
                c.attempt('restore-font', lambda: restore_font(font))
                if not legacy and a.layout != 'tablet':
                    c.attempt('restore-size', lambda: raw('shell', 'wm', 'size', 'reset'))
                    c.attempt('restore-density', lambda: raw('shell', 'wm', 'density', 'reset'))
                def geometry():
                    require(text('shell', 'wm', 'size') == size and text('shell', 'wm', 'density') == density
                            and text('shell', 'settings', 'get', 'system', 'font_scale') == font, 'Original settings differ')
                c.attempt('verify-settings', geometry)
                if not legacy:
                    def restore_profile_stamp():
                        # AndroidX ProfileInstaller updates this build-derived
                        # marker after installing a different APK. It is not an
                        # extension/user-data restoration wildcard.
                        if original_profile is None:
                            raw('shell', 'run-as', APP, 'rm', '-f', 'files/profileInstalled')
                            require(absent('files/profileInstalled'), 'Profile marker remains')
                        else:
                            raw('shell', 'run-as', APP, 'tee', 'files/profileInstalled', data=original_profile)
                            require(raw('exec-out', 'run-as', APP, 'cat', 'files/profileInstalled') == original_profile, 'Profile marker differs')
                    c.attempt('profile-installer-marker', restore_profile_stamp)
                    def preserved_data():
                        after = private_app_archive(adb, a.output / 'private-data-after.tar')
                        after = {path: sha(value) for path, value in after.items() if not path.startswith(lease + '/')}
                        require(after == before, 'Installed data/evidence changed; retain private recovery inputs')
                        r['preserved_file_count'] = len(before)
                    c.attempt('installed-data-and-evidence', preserved_data)
                c.attempt('verify-unprivileged', lambda: require(text('shell', 'id').startswith('uid=2000(')
                    and not text('shell', 'run-as', APP, 'id').startswith(('uid=0(', 'uid=1000(', 'uid=2000(')), 'Unexpected UID'))
                if not c.errors:
                    verify_owner()
                    if legacy:
                        # The app lease stays held until PackageManager removes
                        # the entire owned app. Host journal stays for verification.
                        c.attempt('restore-absence-' + TEST, lambda: raw('uninstall', TEST))
                        if not c.errors and ime_activation_attempted:
                            c.attempt('verify-input-method-after-test-apk-removal', verify_input_method)
                        if not c.errors:
                            c.attempt('restore-absence-' + APP, lambda: raw('uninstall', APP))
                            c.attempt('verify-initial-package-absence', lambda: require(all(not package_path(pkg) for pkg in (APP, TEST)), 'A package remains'))
                    else:
                        for pkg, (path, expected) in originals.items():
                            c.attempt('restore-binary-' + pkg, lambda path=path: raw('install', '-r', '-t', str(path), timeout=120))
                            c.attempt('verify-binary-' + pkg, lambda pkg=pkg, expected=expected: require(sha(raw('exec-out', 'cat', text('shell', 'pm', 'path', pkg).removeprefix('package:'))) == expected, 'Original binary differs'))
                        if not c.errors and ime_activation_attempted:
                            c.attempt('verify-input-method-after-original-apks', verify_input_method)
                        if not c.errors:
                            verify_owner()
                            # Final device operation: no restoration runs after release.
                            c.attempt('release-owner-last', lambda: raw('shell', 'run-as', APP, 'rm', '-rf', lease))
        else:
            r['recovery_required'] = bool(installed)
        r['completed'] = bool(r.get('native_expected_outcome')) and not c.errors and lease_claimed
        save(); c.finish()
    require(r['completed'], 'Native outcome failed; inspect retained receipts')
    print(json.dumps({key: r.get(key) for key in ('completed', 'passes', 'failures', 'expected_negative', 'preserved_file_count')}))


if __name__ == '__main__':
    main()
