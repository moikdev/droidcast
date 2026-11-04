// Real portable runtime plus Android adapter; owned host responses, no network.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {Blob: BrowserBlob, File: BrowserFile} = require('node:buffer');
const streams = require('node:stream/web');
const options = process.argv.slice(2);
const option = name => options.includes(name) ? options[options.indexOf(name) + 1] : undefined;
const adapterPath = option('--adapter') || path.join(__dirname, '../../main/assets/android-runtime.js');
const runtime = fs.readFileSync(path.join(__dirname, '../../main/assets/RaycastRuntime.generated.js'), 'utf8');
const tests = [];
const test = (name, body) => tests.push([name, body]);

function fixture({browser = true, response = {}, reject = null} = {}) {
  const calls = [], browserCalls = [];
  const browserFetch = async (...args) => { browserCalls.push(args); throw new Error('Owned browser fetch sentinel'); };
  const context = vm.createContext({TextEncoder, TextDecoder, URL, URLSearchParams, AbortController,
    setTimeout, clearTimeout, setInterval, clearInterval});
  context.window = context;
  if (browser) Object.assign(context, {fetch: browserFetch, Headers: globalThis.Headers, Request: globalThis.Request,
    Response: globalThis.Response, Blob: BrowserBlob, File: BrowserFile, FormData: globalThis.FormData,
    ReadableStream: streams.ReadableStream, WritableStream: streams.WritableStream, TransformStream: streams.TransformStream});
  context.AndroidHost = {
    invoke(id, api, method, encoded) {
      assert.equal(api, 'fetch'); assert.equal(method, 'request');
      const [request] = JSON.parse(encoded); calls.push(request);
      queueMicrotask(() => {
        if (reject) return context.__tinycast.settle(id, false, reject);
        const bytes = response.bytes || Buffer.from('Owned 日本 response');
        context.__tinycast.settle(id, true, JSON.stringify({status: response.status || 200, statusText: 'Owned status',
          url: request.url, headers: {'x-owned': 'kept', 'content-type': response.type || 'text/plain; charset=utf-8'},
          bodyBase64: bytes.toString('base64')}));
      });
    },
    invokeSync() { throw new Error('Unexpected synchronous host call'); },
    render() {}, failed() {}, finished() {}, depth() {}, log() {}
  };
  vm.runInContext(fs.readFileSync(adapterPath, 'utf8'), context, {filename: 'android-runtime.js', timeout: 5000});
  vm.runInContext(runtime, context, {filename: 'RaycastRuntime.generated.js', timeout: 5000});
  return {context, calls, browserCalls,
    run: source => vm.runInContext(source, context, {timeout: 5000}),
    close: () => context.__androidStopTimers()};
}
async function use(options, body) {
  const f = fixture(options);
  try { await body(f); } finally { f.close(); }
}
const target = 'https://example.invalid/owned-fetch-contract';

test('fetch_routes_to_host_with_browser_globals', () => use({}, async f => {
  const response = await f.context.fetch(target);
  assert.equal(await response.text(), 'Owned 日本 response');
  assert.equal(response.status, 200); assert.equal(response.ok, true);
  assert.equal(response.headers.get('X-Owned'), 'kept'); assert.equal(response.url, target);
  assert.equal(f.browserCalls.length, 0); assert.equal(f.calls.length, 1);
  assert.equal(f.calls[0].url, target); assert.equal(f.calls[0].bodyBase64, null);
}));
test('request_preserves_unicode_method_and_headers', () => use({}, async f => {
  await f.run(`fetch(new Request(${JSON.stringify(target)}, {method:'post', headers:new Headers([['X-Owned','request']]), body:'café é 日本 🌱'}))`);
  const call = f.calls[0]; assert.equal(call.method, 'POST'); assert.equal(call.headers['x-owned'], 'request');
  assert.equal(Buffer.from(call.bodyBase64, 'base64').toString(), 'café é 日本 🌱');
}));
test('typed_array_body_preserves_binary_bytes', () => use({}, async f => {
  await f.run(`fetch(${JSON.stringify(target)}, {method:'POST',body:new Uint8Array([0,255,128,10])})`);
  assert.deepEqual([...Buffer.from(f.calls[0].bodyBase64, 'base64')], [0,255,128,10]);
}));
test('blob_and_file_bodies_preserve_bytes_and_mime', () => use({}, async f => {
  await f.run(`fetch(${JSON.stringify(target)}, {method:'POST',body:new Blob([new Uint8Array([0,255,65])],{type:'application/x-owned'})})`);
  await f.run(`fetch(${JSON.stringify(target)}, {method:'POST',body:new File(['owned 日本'], 'owned.txt',{type:'text/x-owned'})})`);
  assert.deepEqual([...Buffer.from(f.calls[0].bodyBase64, 'base64')], [0,255,65]);
  assert.equal(f.calls[0].headers['content-type'], 'application/x-owned');
  assert.equal(Buffer.from(f.calls[1].bodyBase64, 'base64').toString(), 'owned 日本');
  assert.equal(f.calls[1].headers['content-type'], 'text/x-owned');
}));
test('multipart_fields_and_file_content_match_the_boundary', () => use({}, async f => {
  await f.run(`(function(){const data=new FormData();data.append('message','owned 日本');data.append('file',new File(['owned file bytes'],'owned.txt',{type:'text/plain'}));return fetch(${JSON.stringify(target)},{method:'POST',body:data});})()`);
  const call = f.calls[0], type = call.headers['content-type'];
  assert.match(type, /^multipart\/form-data; boundary=/);
  const boundary = type.split('boundary=')[1], body = Buffer.from(call.bodyBase64, 'base64').toString();
  assert.ok(body.startsWith('--' + boundary + '\r\n'));
  assert.ok(body.endsWith('--' + boundary + '--\r\n'));
  assert.ok(body.includes('name="message"\r\n\r\nowned 日本\r\n'));
  assert.ok(body.includes('name="file"; filename="owned.txt"\r\nContent-Type: text/plain\r\n\r\nowned file bytes\r\n'));
}));
test('response_stream_and_blob_use_the_global_portable_types', () => use({response: {status: 503, bytes: Buffer.from('owned error bytes')}}, async f => {
  const response = await f.context.fetch(target), clone = response.clone();
  assert.equal(response.ok, false); assert.equal(response.status, 503);
  assert.equal(response.body instanceof f.context.ReadableStream, true);
  const reader = response.body.getReader(), chunks = [];
  for (;;) { const value = await reader.read(); if (value.done) break; chunks.push(Buffer.from(value.value)); }
  assert.equal(Buffer.concat(chunks).toString(), 'owned error bytes');
  const blob = await clone.blob(); assert.equal(blob instanceof f.context.Blob, true);
  assert.equal(await blob.text(), 'owned error bytes');
}));
test('transport_errors_and_preaborted_requests_keep_their_boundary', async () => {
  await use({reject: 'Owned native transport failure'}, async f => {
    await assert.rejects(f.context.fetch(target), /Owned native transport failure/);
    assert.equal(f.calls.length, 1); assert.equal(f.browserCalls.length, 0);
  });
  await use({}, async f => {
    await assert.rejects(f.run(`(function(){const controller=new AbortController();controller.abort();return fetch(${JSON.stringify(target)},{signal:controller.signal});})()`), error => error.name === 'AbortError');
    assert.equal(f.calls.length, 0); assert.equal(f.browserCalls.length, 0);
  });
});
test('missing_browser_globals_keep_the_existing_host_transport', () => use({browser: false, response: {bytes: Buffer.from('{"owned":"日本"}'), type: 'application/json'}}, async f => {
  const response = await f.context.fetch(target); assert.deepEqual(JSON.parse(JSON.stringify(await response.json())), {owned: '日本'});
  assert.equal(f.calls.length, 1); assert.equal(f.browserCalls.length, 0);
}));

(async () => {
  const selected = tests.filter(([name]) => !option('--only') || name === option('--only'));
  assert.ok(selected.length > 0, 'No selected fetch contracts');
  let failures = 0;
  for (const [name, body] of selected) {
    try { await body(); process.stdout.write('PASS ' + name + '\n'); }
    catch (error) { failures++; process.stdout.write('FAIL ' + name + ': ' + error.stack + '\n'); }
  }
  process.stdout.write(JSON.stringify({tests: selected.length, passed: selected.length - failures, failed: failures}) + '\n');
  if (failures) process.exitCode = 1;
})().catch(error => { process.stderr.write(error.stack + '\n'); process.exitCode = 1; });
