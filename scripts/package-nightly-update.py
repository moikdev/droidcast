#!/usr/bin/env python3
"""Package an already-tested APK and monotonic updater metadata; never rebuild or sign it."""
from pathlib import Path
import argparse, hashlib, json, os, re, shutil, subprocess, urllib.request

PACKAGE = 'dev.tinycast.android'
SIGNER = 'e7bee2db540fcbb0633d82870b2c4e3d14ecc0c18474b1a7c73d47eb0bf92c08'
p = argparse.ArgumentParser()
p.add_argument('--apk', type=Path, required=True)
p.add_argument('--commit', required=True)
p.add_argument('--tag', required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
assert re.fullmatch('[a-f0-9]{40}', a.commit), 'A complete source commit is required'
assert re.fullmatch('nightly-[A-Za-z0-9._-]{1,140}', a.tag), 'Expected a nightly tag'
tools = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '36.0.0'
def command(*args):
    return subprocess.check_output([str(x) for x in args], text=True, stderr=subprocess.STDOUT, timeout=60)
badging = command(tools/'aapt2', 'dump', 'badging', a.apk)
package = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
assert package and package[1] == PACKAGE
code, version = int(package[2]), package[3]
signer = command(tools/'apksigner', 'verify', '--print-certs', a.apk)
assert re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)', signer)[1] == SIGNER, 'Nightly signer changed; updater requires matching installed certificates'
xml = command(tools/'aapt2', 'dump', 'xmltree', a.apk, '--file', 'AndroidManifest.xml').splitlines()
line = next(i for i, text in enumerate(xml) if 'dev.droidcast.BUILD_COMMIT' in text)
assert any('android:value' in text and '"'+a.commit+'"' in text for text in xml[line+1:line+4]), 'APK build commit does not match the packaged source'
def read_json(url, maximum):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'Droidcast-release-packager'}), timeout=30) as response:
        assert response.status == 200
        data = response.read(maximum+1)
    assert len(data) <= maximum
    return json.loads(data)
releases = read_json('https://api.github.com/repos/moikdev/droidcast/releases?per_page=100', 2*1024*1024)
assert len(releases) <= 100
previous = 5  # Published pre-updater nightlies r1-r9 used versionCode 5.
for release in releases:
    if release.get('draft') or not release['tag_name'].startswith('nightly-'):
        continue
    for asset in release['assets']:
        if asset['name'] != 'droidcast-update.json':
            continue
        assert re.fullmatch('nightly-[A-Za-z0-9._-]{1,140}', release['tag_name'])
        url = 'https://github.com/moikdev/droidcast/releases/download/'+release['tag_name']+'/droidcast-update.json'
        assert asset['browser_download_url'] == url
        metadata = read_json(url, 32*1024)
        assert metadata['format'] == 1 and metadata['packageName'] == PACKAGE
        previous = max(previous, int(metadata['versionCode']))
assert code > previous, f'Nightly versionCode {code} must exceed published {previous}; rebuild and retest with a new monotonic version'
a.output.mkdir(parents=True, exist_ok=False)
name = f'Droidcast-{a.tag}-{a.commit[:7]}.apk'
shutil.copyfile(a.apk, a.output/name)
data = (a.output/name).read_bytes()
metadata = dict(format=1, packageName=PACKAGE, versionCode=code, versionName=version, commit=a.commit,
                apk=name, sha256=hashlib.sha256(data).hexdigest(), bytes=len(data))
manifest = a.output/'droidcast-update.json'
manifest.write_text(json.dumps(metadata, indent=2)+'\n')
(a.output/'SHA256SUMS.txt').write_text(
    metadata['sha256']+'  '+name+'\n'+hashlib.sha256(manifest.read_bytes()).hexdigest()+'  '+manifest.name+'\n')
print(json.dumps(dict(tag=a.tag, previousVersionCode=previous, manifest=metadata)))
