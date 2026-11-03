// Android adapter contracts. The separate device tests use the actual frozen
// React runtime; these stubs isolate API identity and event-context behavior.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
let cases = 0;
function test(name, body) { body(); cases++; process.stdout.write(`PASS ${name}\n`); }
function fixture() {
  function Form(props) { return props; }
  for (const name of ['TextField', 'PasswordField', 'TextArea', 'Checkbox', 'Dropdown', 'DatePicker', 'TagPicker', 'FilePicker', 'Description'])
    Form[name] = function (props) { return props; };
  Form.Dropdown.Item = function Item() {};
  Form.Dropdown.Section = function Section() {};
  const api = {Clipboard: {copy() {}}, marker: {}};
  Object.defineProperty(api, 'Form', {enumerable: true, get: () => Form});
  for (const kind of ['TextField', 'TextArea', 'Checkbox', 'Dropdown', 'DatePicker', 'TagPicker']) api['Form' + kind] = Form[kind];
  Form.DatePicker.Type = Object.freeze({Date: 'date', DateTime: 'date_time'});
  const http = {STATUS_CODES: {}};
  const react = {publications: 0, useReducer() { return [0, () => { react.publications++; }]; }};
  const other = {};
  const context = vm.createContext({window: {setTimeout, clearTimeout, setInterval, clearInterval}, AndroidHost: {}});
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/assets/android-runtime.js'), 'utf8'), context);
  function load() {
    const module = {exports: {}};
    context.__tinycastCompile('module.exports = [require("@raycast/api"), require("http"), require("node:http"), require("other")];', 'owned-adapter.js')
      (module.exports, name => name === '@raycast/api' ? api : name === 'react' ? react : name === 'other' ? other : http, module, 'owned-adapter.js', '.');
    return module.exports;
  }
  return {context, api, Form, http, react, other, load, native: (...args) => context.__androidDispatchFormFocus(...args)};
}
test('API aliases, original members and field statics retain identity', () => {
  const f = fixture(), first = f.load(), second = f.load();
  assert.equal(first[0], second[0]); assert.equal(first[0].Form, second[0].Form);
  assert.equal(first[0].Form.Dropdown, second[0].Form.Dropdown);
  assert.equal(first[0].Clipboard, f.api.Clipboard); assert.equal(first[0].marker, f.api.marker);
  assert.equal(first[0].Form.Description, f.Form.Description);
  assert.equal(first[0].Form.Dropdown.Item, f.Form.Dropdown.Item); assert.equal(first[0].Form.Dropdown.Section, f.Form.Dropdown.Section);
  assert.equal(first[1], f.http); assert.equal(first[1], first[2]); assert.equal(first[3], f.other);
  assert.equal(first[1].STATUS_CODES['404'], 'Not Found');
});
test('field props, callbacks and absent focus handlers are preserved', () => {
  const f = fixture(), api = f.load()[0], change = () => {}, child = {}, reference = {};
  for (const kind of ['TextField', 'PasswordField', 'TextArea', 'Checkbox', 'Dropdown']) {
    const props = Object.freeze({id: 'owned', value: '', defaultValue: 'initial', onChange: change, children: child, ref: reference});
    const field = api.Form[kind](props);
    assert.equal(field.id, props.id); assert.equal(field.value, props.value); assert.equal(field.defaultValue, props.defaultValue);
    assert.equal(field.children, child); assert.equal(field.ref, reference);
    const before = f.react.publications; field.onChange('owned edit'); assert.equal(f.react.publications, before + 1);
    assert.equal(field.onFocus, undefined); assert.equal(field.onBlur, undefined);
  }
  assert.equal(api.Form.TextField({id: 'uncontrolled', onChange: change}).onChange, change);
  assert.equal(api.Form.TextField({id: 'undefined', value: undefined, onChange: change}).onChange, change);
});
test('full-day dates use the reserved millisecond, including non-midnight and invalid dates', () => {
  const f = fixture(), api = f.load()[0], helper = api.Form.DatePicker.isFullDay;
  assert.equal(typeof helper, 'function');
  assert.equal(helper, api.FormDatePicker.isFullDay);
  assert.equal(api.Form.DatePicker.Type, f.Form.DatePicker.Type);
  for (const value of [undefined, null, new Date(NaN), new Date('2024-02-29T00:00:00.000Z'), new Date('2024-02-29T12:34:56.345Z')])
    assert.equal(helper(value), false);
  for (const value of [new Date('2024-02-29T00:00:00.001Z'), new Date('2024-02-29T12:34:56.001Z'), new Date(-999)])
    assert.equal(helper(value), true);
  assert.equal(f.Form.DatePicker.isFullDay, undefined, 'The Android adapter must not mutate the upstream export');
});
test('deprecated field aliases share wrapping and native event values with Form members', () => {
  const f = fixture(), api = f.load()[0];
  for (const kind of ['TextField', 'TextArea', 'Checkbox', 'Dropdown', 'DatePicker', 'TagPicker']) {
    assert.equal(api['Form' + kind], api.Form[kind]);
    let observed;
    const field = api['Form' + kind]({id: 'alias', value: 'controlled', onBlur: event => { observed = event.target.value; }});
    f.context.__tinycast = {dispatch() { field.onBlur({target: {value: 'previous'}}); }};
    const encoded = kind === 'DatePicker' ? [{$date: '2024-02-29T00:00:00.001Z'}] : kind === 'TagPicker' ? [['café 日本', 'second']] : [true];
    f.native('session', 'handle', 'alias', 'blur', JSON.stringify(encoded));
    if (kind === 'DatePicker') { assert.equal(observed.toISOString(), encoded[0].$date); assert.equal(api.FormDatePicker.isFullDay(observed), true); }
    else if (kind === 'TagPicker') assert.deepEqual(Array.from(observed), encoded[0]);
    else assert.equal(observed, true);
  }
});
test('controlled acceptance, rejection and exceptions publish without changing callback results', () => {
  const f = fixture(), api = f.load()[0], marker = {}; let received;
  const field = api.Form.TextField({id: 'controlled', value: 'fixed', marker,
    onChange: function (value) { assert.equal(this.marker, marker); received = value; return marker; }});
  assert.equal(field.onChange('native'), marker); assert.equal(received, 'native'); assert.equal(f.react.publications, 1);
  const readOnly = api.Form.TextField({id: 'read-only', value: 'fixed'});
  readOnly.onChange('rejected'); assert.equal(f.react.publications, 2);
  const failure = api.Form.TextField({id: 'throwing', value: 'fixed', onChange() { throw new Error('Owned change failure'); }});
  assert.throws(() => failure.onChange('owned'), /Owned change failure/); assert.equal(f.react.publications, 3);
});
test('matching focus and blur receive the native Unicode or boolean value', () => {
  const f = fixture(), api = f.load()[0], marker = {}, original = {target: {value: 'previous', marker}, detail: marker};
  for (const kind of ['focus', 'blur']) {
    for (const value of ['Owned café é 日本 🌱', true, false, '']) {
      let event;
      const props = api.Form.TextField({id: 'owned', marker, [kind === 'focus' ? 'onFocus' : 'onBlur']: function (next) { event = next; assert.equal(this.marker, marker); }});
      f.context.__tinycast = {dispatch() { props[kind === 'focus' ? 'onFocus' : 'onBlur'](original); return '1'; }};
      assert.equal(f.native('session', 'handle', 'owned', kind, JSON.stringify([value])), '1');
      assert.equal(event.target.value, value); assert.equal(event.target.marker, marker); assert.equal(event.detail, marker);
      assert.equal(original.target.value, 'previous');
    }
  }
});
test('a different field or callback kind cannot inherit the override', () => {
  const f = fixture(), api = f.load()[0]; let observed;
  const props = api.Form.TextField({id: 'owned', onBlur: event => { observed = event; }});
  const event = {target: {value: 'original'}};
  f.context.__tinycast = {dispatch() { props.onBlur(event); }};
  f.native('session', 'handle', 'another-field', 'blur', '["native"]'); assert.equal(observed, event);
  f.native('session', 'handle', 'owned', 'focus', '["native"]'); assert.equal(observed, event);
});
test('typed focus values revive dates and retain exact tag/file arrays', () => {
  const f = fixture(), api = f.load()[0];
  for (const [kind, encoded, expected] of [
    ['DatePicker', [{$date: '2024-02-29T12:34:56.123Z'}], '2024-02-29T12:34:56.123Z'],
    ['DatePicker', [null], null],
    ['TagPicker', [['internal-two', 'café é 日本 🌱']], ['internal-two', 'café é 日本 🌱']],
    ['FilePicker', [['/owned/a.json', '/owned/café 日本.txt']], ['/owned/a.json', '/owned/café 日本.txt']]
  ]) {
    let observed;
    const props = api.Form[kind]({id: 'typed', onBlur: event => { observed = event.target.value; }});
    f.context.__tinycast = {dispatch() { props.onBlur({target: {value: 'previous'}}); }};
    f.native('session', 'handle', 'typed', 'blur', JSON.stringify(encoded));
    if (expected === null) assert.equal(observed, null);
    else if (kind === 'DatePicker') assert.equal(observed.toISOString(), expected);
    else assert.deepEqual(Array.from(observed), expected);
  }
});
test('invalid typed date payloads do not dispatch or retain event overrides', () => {
  const f = fixture(), api = f.load()[0]; let observed, dispatches = 0;
  const props = api.Form.DatePicker({id: 'typed', onBlur: event => { observed = event.target.value; }});
  f.context.__tinycast = {dispatch() { dispatches++; props.onBlur({target: {value: 'original'}}); }};
  assert.throws(() => f.native('session', 'handle', 'typed', 'blur', '[{"$date":"invalid"}]'), /Invalid Form date/);
  assert.equal(dispatches, 0);
  props.onBlur({target: {value: 'outside'}}); assert.equal(observed, 'outside');
});
test('throwing and nested dispatch restore their caller context', () => {
  const f = fixture(), api = f.load()[0], values = [];
  const first = api.Form.TextField({id: 'first', onBlur: event => values.push(event.target.value)});
  const second = api.Form.TextField({id: 'second', onBlur() { throw new Error('Owned callback failure'); }});
  f.context.__tinycast = {dispatch(session, handle) {
    if (handle === 'second') return second.onBlur({target: {value: 'original second'}});
    first.onBlur({target: {value: 'original first'}});
    assert.throws(() => f.native('session', 'second', 'second', 'blur', '["native second"]'), /Owned callback failure/);
    first.onBlur({target: {value: 'original first'}});
  }};
  f.native('session', 'first', 'first', 'blur', '["native first"]');
  first.onBlur({target: {value: 'outside'}});
  assert.deepEqual(values, ['native first', 'native first', 'outside']);
});
test('malformed payload cannot retain a previous event value', () => {
  const f = fixture(), api = f.load()[0]; let value;
  const props = api.Form.TextField({id: 'owned', onBlur: event => { value = event.target.value; }});
  f.context.__tinycast = {dispatch() { props.onBlur({target: {value: 'ordinary'}}); }};
  f.native('session', 'handle', 'owned', 'blur', '["native"]'); assert.equal(value, 'native');
  assert.throws(() => f.native('session', 'handle', 'owned', 'blur', 'invalid'));
  props.onBlur({target: {value: 'outside'}}); assert.equal(value, 'outside');
});
process.stdout.write(`${cases} adapter cases passed\n`);
