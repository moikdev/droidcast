// Isolated adapter contracts; real generated React/Android workflows are separate.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const tests = [];
const test = (name, body) => tests.push([name, body]);
function fixture() {
  const f = {rootId: 'form', actionId: 'action', session: 'session', title: 'Submit owned values', error: '', values: {name: 'Owned 日本 🌱'}, calls: [], effects: [], hooks: [], defer: false};
  function Action(props) { return {__element: true, props}; }
  Action.CopyToClipboard = function Copy() {};
  Action.SubmitForm = props => Action({title: props.title ?? 'Submit Form', onAction: () => props.onSubmit?.({...f.values})});
  const api = {Action, SubmitFormAction: Action.SubmitForm, Clipboard: {}}; api.default = api;
  const react = {
    useReducer(reducer, initial) {
      const index = f.hookIndex++;
      if (!f.hooks[index]) f.hooks[index] = {value: initial, dispatch() { f.hooks[index].value = reducer(f.hooks[index].value); if (!f.defer) f.render(); }};
      return [f.hooks[index].value, f.hooks[index].dispatch];
    },
    useRef(initial) { const index = f.hookIndex++; return f.hooks[index] ??= {current: initial}; },
    useLayoutEffect(effect, deps) {
      const index = f.hookIndex++, previous = f.hooks[index];
      if (!previous || deps.some((item, n) => !Object.is(item, previous.deps[n]))) f.effects.push(() => {
        previous?.cleanup?.(); f.hooks[index] = {deps, cleanup: effect()};
      });
    },
    isValidElement: value => value?.__element === true,
    cloneElement: (element, props) => ({...element, props: {...element.props, ...props}})
  };
  const context = vm.createContext({window: {setTimeout, clearTimeout, setInterval, clearInterval}, AndroidHost: {render() {}, failed(error) { f.failure = error; }}});
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/assets/android-runtime.js'), 'utf8'), context);
  const module = {exports: {}};
  context.__tinycastCompile('module.exports=require("@raycast/api");', 'owned-submit.js')
    (module.exports, name => name === 'react' ? react : api, module, 'owned-submit.js', '.');
  f.api = module.exports; f.originalApi = api; f.context = context;
  f.callback = values => { f.calls.push(values); return 'owned result'; };
  f.publish = () => {
    const action = {id: f.actionId, type: 'Action', props: {...f.element.props, onAction: {$fn: 'action:onAction'}}, children: []};
    const field = {id: 'field', type: f.fieldType || 'Form.TextField', props: {id: 'name', error: f.error}, children: []};
    const root = {id: f.rootId, type: f.rootType || 'Form', props: {actions: {type: 'ActionPanel', props: {}, children: [action]}}, children: [field]};
    const inactive = {type: '__screen', props: {active: false}, children: [{id: 'old-form', type: 'Form', props: {}, children: [{type: 'Form.TextField', props: {error: 'Old hidden error'}}]}]};
    context.__tinycastHost.render(f.session, JSON.stringify({children: [inactive, {type: '__screen', props: {active: true}, children: [root]}]}));
  };
  f.render = () => {
    const before = f.beforeRender; f.beforeRender = null; before?.();
    f.hookIndex = 0; f.effects = [];
    f.element = f.api.Action.SubmitForm({title: f.title, onSubmit: f.callback});
    f.publish(); // Match the remote renderer: host commit precedes layout effects.
    const effects = f.effects; f.effects = []; effects.forEach(effect => effect());
  };
  f.unmount = () => f.hooks.forEach(hook => hook.cleanup?.());
  f.render(); return f;
}
test('typed aliases and default API share identity; ordinary statics stay intact', async () => {
  const f = fixture(); assert.equal(f.api.default, f.api);
  assert.equal(f.api.Action.SubmitForm, f.api.SubmitFormAction);
  assert.equal(f.api.Action.CopyToClipboard, f.originalApi.Action.CopyToClipboard);
  assert.equal(f.element.props.__tinycastFormSubmit, true); assert.equal(f.calls.length, 0);
});
test('a fresh committed error prevents the callback', async () => {
  const f = fixture(); f.beforeRender = () => { f.error = 'Owned validation error'; };
  assert.equal(await f.element.props.onAction(), undefined); assert.equal(f.calls.length, 0);
});
test('blank or absent errors allow exact Unicode values and callback results', async () => {
  for (const error of [undefined, null, '', '  ']) {
    const f = fixture(); f.error = error; f.render();
    assert.equal(await f.element.props.onAction(), 'owned result'); assert.deepEqual(f.calls, [{name: 'Owned 日本 🌱'}]);
  }
});
test('date, tag and file field errors block their fresh committed submission', async () => {
  for (const fieldType of ['Form.DatePicker', 'Form.TagPicker', 'Form.FilePicker']) {
    const f = fixture(); f.fieldType = fieldType; f.error = 'Owned typed validation error'; f.render();
    assert.equal(await f.element.props.onAction(), undefined); assert.equal(f.calls.length, 0);
    f.error = undefined; f.render();
    assert.equal(await f.element.props.onAction(), 'owned result'); assert.equal(f.calls.length, 1);
  }
});
test('the post-publication callback sees accepted current values', async () => {
  const f = fixture(); f.beforeRender = () => { f.values = {name: 'ACCEPTED 日本'}; };
  await f.element.props.onAction(); assert.deepEqual(f.calls, [{name: 'ACCEPTED 日本'}]);
});
test('duplicate pending invocations execute one callback', async () => {
  const f = fixture(), click = f.element.props.onAction;
  const first = click(), second = click(); assert.equal(first, second);
  await Promise.all([first, second]); assert.equal(f.calls.length, 1);
});
for (const [property, replacement] of [['rootId', 'replacement-root'], ['session', 'replacement-session'], ['actionId', 'replacement-action'], ['title', 'Different action']]) {
  test(`a changed ${property} cannot inherit a pending request`, async () => {
    const f = fixture(); f.beforeRender = () => { f[property] = replacement; };
    await f.element.props.onAction(); assert.equal(f.calls.length, 0);
  });
}
test('unmount resolves a queued request without invoking its callback', async () => {
  const f = fixture(); f.defer = true; const request = f.element.props.onAction(); f.unmount();
  assert.equal(await request, undefined); assert.equal(f.calls.length, 0);
});
test('surface failure cancels pending submission', async () => {
  const f = fixture(); f.defer = true; const request = f.element.props.onAction();
  f.context.__tinycastHost.failed('session', 'Owned failure');
  assert.equal(await request, undefined); assert.equal(f.calls.length, 0); assert.equal(f.failure, 'Owned failure');
});
test('synchronous and asynchronous callback failures remain rejected Promises', async () => {
  for (const callback of [() => { throw new Error('Owned submit failure'); }, () => Promise.reject(new Error('Owned submit failure'))]) {
    const f = fixture(); f.callback = callback; f.render(); await assert.rejects(f.element.props.onAction(), /Owned submit failure/);
  }
});
test('typed actions outside a Form retain their callback behavior', async () => {
  const f = fixture(); f.rootType = 'Detail'; f.error = 'Not a Form error'; f.render();
  assert.equal(await f.element.props.onAction(), 'owned result'); assert.equal(f.calls.length, 1);
});
(async () => {
  for (const [name, body] of tests) { await body(); process.stdout.write(`PASS ${name}\n`); }
  process.stdout.write(`${tests.length} submit adapter cases passed\n`);
})().catch(error => { process.stderr.write(error.stack + '\n'); process.exitCode = 1; });
