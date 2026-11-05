#!/usr/bin/env python3
"""Read-only reconstruction of the shipped subset from pinned historical Git receipts.

Run from the repository root after fetching the full repository history. No app,
Store or service is executed. Source inventory does not earn execution credit.
"""
from pathlib import Path
import hashlib,json,subprocess
sources={}
def require(condition, message):
 if not condition: raise ValueError(message)
sha=lambda value:hashlib.sha256(value).hexdigest()
def revision(short):return subprocess.check_output(['git','rev-parse',short],text=True).strip()
def raw(commit,path):
 data=subprocess.check_output(['git','show',commit+':'+path]);sources[commit+':'+path]=sha(data);return data
def read(commit,path):return json.loads(raw(commit,path))
old='8fd173baaadaf3e19ecc1cd04e9cb99d1211a141';http_commit='19a0a85334161ebbc6908bb360fea4f8dde2aa8f';fetch='a1381c9f879cfe76dace3600f995e3dabcbe3dd8';context_commit='729f22b5e5df766bc9cba882e75ac5b2a65af3f7';paste='3b5ec1918c5fe36a8f75a66e601d74da0e3c4b00'
original=read(paste,'docs/validation/extension-compatibility/batch-001-snapshot.json');prepared=json.loads(Path('app/src/test/resources/extension-payload-identities-v1.json').read_text());payloads={x['name']:x for x in prepared['snapshots']}
snapshots=[]
for source in original['extensions']:
 p=payloads[source['store_name']];require(p['archive_sha256']==source['archive_sha256'], 'Pinned historical record invariant differs');titles={x['name']:x['title'] for x in source['commands']}
 snapshots.append(dict(archive_sha256=p['archive_sha256'],handle=p['handle'],name=p['name'],package_name=source['package_name'],manifest_sha256=p['manifest_sha256'],payload_sha256=p['payload_sha256'],files=p['files'],commands=[dict(**x,title=titles[x['name']]) for x in p['commands']]))
by_name={x['name']:x for x in snapshots};observations=[]
def env(app_source,app_sha,test_source,test_sha,profiles,webview=None):
 return dict(app_source=app_source,app_sha256=app_sha,test_source=test_source,test_sha256=test_sha,android_api=36,device='Tinycast_Tablet_API_36',webview_package=webview[0] if webview else None,webview_version=webview[1] if webview else None,profiles=profiles)
def phases(**changes):
 out={
  'setup':dict(result='passed',detail='Sample inputs and configured preferences were prepared for the listed workflow.'),
  'startup':dict(result='passed',detail='The command started and its listed interface or status appeared.'),
  'interaction':dict(result='passed',detail='The listed action produced independently checked output.'),
  'destination':dict(result='passed',detail='The clipboard result matched the expected value or format.'),
  'persistence':dict(result='not_exercised',detail='Process restart and cross-process persistence were not exercised.'),
  'lifecycle':dict(result='passed',detail='Back or Close returned to the sample caller with its query preserved.')}
 for key,(result,detail) in changes.items():out[key]=dict(result=result,detail=detail)
 return out
def add(name,command,record_id,outcome,workflow,configuration,limits,phase,environment,commit,case,report,kind='execution'):
 snapshot=by_name[name];require(any(x['name']==command for x in snapshot['commands']), 'Pinned historical record invariant differs');raw(commit,case);raw(commit,report)
 observations.append(dict(id=record_id,archive_sha256=snapshot['archive_sha256'],command=command,outcome=outcome,kind=kind,workflow=workflow,configuration=configuration,limits=limits,phases=phase,environment=environment,evidence=dict(commit=commit,case_path=case,report_path=report)))
cp_path='docs/validation/extension-compatibility/batch-001-checkpoint.json';cp=read(old,cp_path)
legacy_env=env(cp['host_source_commit'],cp['app_sha256'],cp['host_source_commit'],cp['test_apk_sha256'],['Tablet 100%: 1920×1200 / 240 dpi','Phone 100%: 1080×2340 / 420 dpi'],('com.google.android.webview','133.0.6943.137'))
working=[('uuid-generator','generate','Generate UUID values and copy the checked result.'),('uuid-generator','generateV5','Generate a UUIDv5 result using the sample namespace/name inputs.'),('oklch-color-converter','convert-color','Convert the sample OKLCH color and copy the checked result.'),('palette-colors','index','Select the listed palette color and copy the checked result.'),('base64','index','Use the manual Base64 interface and copy its checked output.'),('base64','encode','Encode the sample clipboard text and verify the clipboard output.'),('base64','decode','Decode the sample Base64 clipboard value and verify the output.'),('json-format','index','Use the manual JSON formatter and verify its clipboard output.'),('json-format','formatClipboard','Format sample JSON from the clipboard and verify the result.'),('lorem-ipsum','words','Generate the requested sample word output and verify the clipboard result.')]
for name,command,workflow in working:
 directory=next(x['directory'] for x in original['extensions'] if x['store_name']==name)
 for layout in ['tablet','phone']:
  row=next(x for x in cp['runs'][layout]['workflows'] if x['directory']==directory and x['command']==command);require(row['status']=='tested-working-for-listed-workflow' and row['action_output_verified'], 'Pinned historical record invariant differs')
 add(name,command,'batch001-'+name+'-'+command.lower(),'worked_for_workflow',workflow,'Sample inputs and configured preferences from the published workflow report.',
  ['Only this listed workflow was checked. Other arguments/actions and your current app/device remain unverified.','Sample input setup does not certify physical typing or full accessibility.'],phases(),legacy_env,old,cp_path,'docs/EXTENSION-WORKFLOWS.md')
hp='docs/validation/extension-compatibility/http-startup-checkpoint.json';hc=read(http_commit,hp);runtime=hc['runtime'];require(hc['frozen_http']['archive_sha256']==by_name['http-status-codes']['archive_sha256'], 'Pinned historical record invariant differs')
for layout in ['tablet','phone']:require(hc['focused_runs']['repaired-'+layout]['status_counts']=={'tested-working-for-listed-workflow':1}, 'Pinned historical record invariant differs')
add('http-status-codes','index','http404-after-root-repair','worked_for_workflow','After the root repair: search for 404, select Copy Code, and return to the caller.','No command preferences or arguments required for this listed path.',
 ['Only the listed 404 search/Copy workflow was checked. Other codes/actions and the current environment are not certified.'],
 phases(interaction=('passed','Native 404 search and pointer Copy Code completed.'),destination=('passed','The clipboard contained exactly 404; caller return preserved the query.')),
 env(runtime['implementation_commit'],runtime['app_sha256'],runtime['harness_source_commit'],runtime['test_apk_sha256'],['Tablet 100%: 1920×1200 / 240 dpi','Phone 100%: 1080×2340 / 420 dpi']),http_commit,hp,'docs/EXTENSION-HTTP-STARTUP.md')
for layout in ['tablet','phone']:
 row=next(x for x in cp['runs'][layout]['workflows'] if x['directory']=='http-status-codes');require(row['status']=='observed-failed', 'Pinned historical record invariant differs')
add('http-status-codes','index','http-before-root-repair','observed_failure','Earlier record, before the root repair: startup failed at the filesystem boundary.','The original sample workflow and frozen source bundle.',
 ['This is a retained earlier failure. The separate after-repair record above completes the 404 workflow.'],
 phases(startup=('failed','The command reported an owned-root filesystem error before its search/Copy workflow.'),interaction=('not_exercised','Search and Copy were not completed.'),destination=('not_exercised','No successful action output was verified.')),
 legacy_env,old,cp_path,'docs/EXTENSION-WORKFLOWS.md')
weather_case='docs/validation/extension-compatibility/fetch/cohorts/frozen-weather-tablet-v1/captures/detail-frozenweatherreportstheownedtokyoworkflowoutcome-record.json';w=read(fetch,weather_case)
wr=read(fetch,'docs/validation/extension-compatibility/fetch/cohorts/frozen-weather-tablet-v1/runner-record.json');wh=read(fetch,'docs/validation/extension-compatibility/fetch/identities/build-harness-v3.json')
require(w['archive_sha256']==by_name['weather']['archive_sha256'] and w['workflow_outcome']=='partial-rendered-rows' and w['caller_returned'], 'Pinned historical record invariant differs')
require(wh['apk_hashes']['app-debug-androidTest.apk']==wr['test_apk_sha256'] and wh['apk_hashes']['app-debug.apk']==wr['apk_sha256'] and wh['runtime_source_commit']==w['installed_build_commit'], 'Weather source identity does not match the cohort APKs')
weather_phone=read(fetch,'docs/validation/extension-compatibility/fetch/cohorts/frozen-weather-phone-v1/runner-record.json')
require(weather_phone['completed'] and weather_phone['test_apk_sha256']==wr['test_apk_sha256'] and weather_phone['apk_sha256']==wr['apk_sha256'], 'Weather phone/tablet APK identities differ')
add('weather','index','weather-tokyo-observation','partial','The Tokyo search reached the service and rendered rows; forecast actions and city identity were not verified.','Tokyo was entered into the composed search using the recorded test setup.',
 ['Weather remains partial. This does not certify the city identity, forecast values or action destinations.','Requests before observer attachment are outside the recorded network scope.'],
 phases(interaction=('passed','The owned Tokyo request and subsequent native rows were observed.'),destination=('not_exercised','Forecast detail/actions and exact city identity were not exercised.'),lifecycle=('passed','The workflow returned to its owned caller.')),
 env(w['installed_build_commit'],wr['apk_sha256'],wh['test_source_commit'],wr['test_apk_sha256'],['Tablet 100%: 1920×1200 / 240 dpi','Phone 100%: 1080×2340 / 420 dpi']),fetch,weather_case,'docs/EXTENSION-FETCH-ROUTING.md')
for layout in ['tablet','phone']:
 row=next(x for x in cp['runs'][layout]['workflows'] if x['directory']=='weather');require(row['status']=='observed-failed', 'Pinned historical record invariant differs')
add('weather','index','weather-earlier-service-failure','observed_failure','Earlier record: the Weather workflow ended in a service-request failure.','Original sample Weather setup from the first frozen batch.',
 ['This earlier observed failure is retained separately from the later partial Tokyo observation.'],
 phases(interaction=('failed','The recorded service request failed.'),destination=('not_exercised','Forecast actions and outputs were not completed.')),
 legacy_env,old,cp_path,'docs/EXTENSION-WORKFLOWS.md')
pp='docs/validation/paste-copy/cases/frozen-copy-tablet100-v2/text-selectedtranslationrunsonlyafterreviewandreportsexactcopyorservicefailure-record.json';p=read(paste,pp)
pi=read(paste,'docs/validation/paste-copy/identities/candidate-v2.json');require(p['translation_archive_sha256']==by_name['translate']['archive_sha256'] and p['workflow_outcome']=='partial-paste-explicit-copy-fallback' and p['copy_exact'] and p['native_copy_was_explicit'] and p['source_text_and_selection_unchanged'], 'Pinned historical record invariant differs')
add('translate','instant-translate-paste','translate-paste-explicit-copy','partial','Paste stays unavailable. Explicit Copy copied the translation result, then Return preserved the original source and selection.','Sample hello selection; English to Japanese; explicit input review and Run.',
 ['This is an explicit Copy fallback. Arbitrary cross-app insertion remains unsupported.','Translation quality and arbitrary callers are not certified.'],
 phases(interaction=('passed','The paste Promise stayed rejected. A pointer explicitly selected Copy result instead.'),destination=('passed','Clipboard output matched the observed service response; no automatic Copy occurred.'),lifecycle=('passed','Pointer Return restored the sample source text and selection 0 to 5.')),
 env(pi['source_commit'],pi['apk_hashes']['app-debug.apk'],pi['test_source_commit'],pi['apk_hashes']['app-debug-androidTest.apk'],['Tablet 100% and 200%: 1920×1200 / 240 dpi','Phone 100% and 200%: 1080×2340 / 420 dpi','Short landscape 100% and 200%: 2340×1080 / 420 dpi']),paste,pp,'docs/EXTENSION-PASTE-COPY.md')
mp='docs/validation/context-workflows/cohorts/baseline-menu-bar-tablet100-v4/cases/text-desktopmenubarisunavailableinreviewedchooserandreturnstosource-record.json';m=read(context_commit,mp)
mi=read(context_commit,'docs/validation/context-workflows/identities/build-observations-v4.json');mc=read(context_commit,'docs/validation/context-workflows/checkpoint.json');mr=next(x for x in mc['cohorts'] if x['name']=='baseline-menu-bar-tablet100-v4')
require(m['weather_archive_sha256']==by_name['weather']['archive_sha256'] and m['extension_bytecode_executed'] is False and m['workflow_outcome']=='blocked-menu-bar-reviewed-entry', 'Pinned historical record invariant differs')
require(mi['apk_hashes']['app-debug-androidTest.apk']==mr['test_apk_sha256'] and mi['apk_hashes']['app-debug.apk']==mr['app_sha256'] and mi['runtime_source_commit']==m['installed_build_commit'], 'Menu-bar source identity does not match the cohort APKs')
add('weather','menubar','weather-menu-bar-entry','entry_unavailable','The menu-bar command was unavailable in the reviewed-input chooser. It was not executed.','Reviewed sample input and the declared menu-bar command.',
 ['This records the entry boundary only; it does not certify a desktop-menu equivalent or forecast execution.'],
 phases(startup=('blocked','The reviewed chooser did not offer the menu-bar command.'),interaction=('not_exercised','Extension bytecode was not executed.'),destination=('not_exercised','No Weather command action was performed.'),lifecycle=('passed','The source text/selection were unchanged after returning.')),
 env(m['installed_build_commit'],mr['app_sha256'],mi['test_source_commit'],mr['test_apk_sha256'],['Tablet and phone: 100% and 200%']),context_commit,mp,'docs/CONTEXT-COMMAND-WORKFLOWS.md',kind='entry_probe')
result=dict(schema_version=1,snapshots=snapshots,observations=observations)
expected=(json.dumps(result,indent=2,ensure_ascii=False)+'\n').encode()
asset=Path('app/src/main/assets/extension-workflow-evidence-v1.json')
require(asset.read_bytes()==expected, 'The shipped historical catalog differs from its pinned source projection')
bindings=Path('docs/validation/recorded-tests/catalog-sources.json')
expected_bindings=dict(asset_sha256=sha(expected),sources=sources,scope='Historical reviewed workflow records; no current-build or whole-Store promotion')
require(json.loads(bindings.read_text())==expected_bindings, 'Catalog source bindings differ')
print(json.dumps(dict(verified=True,snapshots=len(snapshots),observations=len(observations),commands=len({(x['archive_sha256'],x['command']) for x in observations}))))
