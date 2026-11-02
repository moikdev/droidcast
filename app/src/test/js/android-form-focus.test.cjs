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
  for (const name of ['TextField', 'PasswordField', 'TextArea', 'Checkbox', 'Dropdown', 'Description'])
    Form[name] = function (props) { return props; };
  Form.Dropdown.Item = function Item() {};
  Form.Dropdown.Section = function Section() {};
  const api = {Clipboard: {copy() {}}, marker: {}};
  Object.defineProperty(api, 'Form', {enumerable: true, get: () => Form});
  const http = {STATUS_CODES: {}};
  const other = {};
  const context = vm.createContext({window: {setTimeout, clearTimeout, setInterval, clearInterval}, AndroidHost: {}});
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/assets/android-runtime.js'), 'utf8'), context);
  function load() {
    const module = {exports: {}};
    context.__tinycastCompile('module.exports = [require("@raycast/api"), require("http"), require("node:http"), require("other")];', 'owned-adapter.js')
      (module.exports, name => name === '@raycast/api' ? api : name === 'other' ? other : http, module, 'owned-adapter.js', '.');
    return module.exports;
  }
  return {context, api, Form, http, other, load, native: (...args) => context.__androidDispatchFormFocus(...args)};
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
    assert.equal(field.onChange, change); assert.equal(field.children, child); assert.equal(field.ref, reference);
    assert.equal(field.onFocus, undefined); assert.equal(field.onBlur, undefined);
    assert.equal(field.__androidFormControlled, true);
  }
  assert.equal(api.Form.TextField({id: 'uncontrolled'}).__androidFormControlled, false);
  assert.equal(api.Form.TextField({id: 'undefined', value: undefined}).__androidFormControlled, false);
  assert.equal(api.Form.TextField({id: 'null', value: null}).__androidFormControlled, true);
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
