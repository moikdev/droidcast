// Android host adapter. The upstream generated Tinycast runtime is loaded separately, unchanged.
(function () {
  const timers = new Map();
  const later = window.setTimeout.bind(window), cancel = window.clearTimeout.bind(window);
  const every = window.setInterval.bind(window), cancelEvery = window.clearInterval.bind(window);
  // HTTP phrases: Node.js v20.7.0, a71037ee5618c32c7c9e76d55f7d0640ab7f5e96, lib/_http_server.js.
  // Copyright Joyent, Inc. and other Node contributors.
  //
  // Permission is hereby granted, free of charge, to any person obtaining a
  // copy of this software and associated documentation files (the
  // "Software"), to deal in the Software without restriction, including
  // without limitation the rights to use, copy, modify, merge, publish,
  // distribute, sublicense, and/or sell copies of the Software, and to permit
  // persons to whom the Software is furnished to do so, subject to the
  // following conditions:
  //
  // The above copyright notice and this permission notice shall be included
  // in all copies or substantial portions of the Software.
  //
  // THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
  // OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
  // MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
  // NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
  // DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
  // OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
  // USE OR OTHER DEALINGS IN THE SOFTWARE.
  const httpStatusCodes = {"100":"Continue","101":"Switching Protocols","102":"Processing","103":"Early Hints","200":"OK","201":"Created","202":"Accepted","203":"Non-Authoritative Information","204":"No Content","205":"Reset Content","206":"Partial Content","207":"Multi-Status","208":"Already Reported","226":"IM Used","300":"Multiple Choices","301":"Moved Permanently","302":"Found","303":"See Other","304":"Not Modified","305":"Use Proxy","307":"Temporary Redirect","308":"Permanent Redirect","400":"Bad Request","401":"Unauthorized","402":"Payment Required","403":"Forbidden","404":"Not Found","405":"Method Not Allowed","406":"Not Acceptable","407":"Proxy Authentication Required","408":"Request Timeout","409":"Conflict","410":"Gone","411":"Length Required","412":"Precondition Failed","413":"Payload Too Large","414":"URI Too Long","415":"Unsupported Media Type","416":"Range Not Satisfiable","417":"Expectation Failed","418":"I'm a Teapot","421":"Misdirected Request","422":"Unprocessable Entity","423":"Locked","424":"Failed Dependency","425":"Too Early","426":"Upgrade Required","428":"Precondition Required","429":"Too Many Requests","431":"Request Header Fields Too Large","451":"Unavailable For Legal Reasons","500":"Internal Server Error","501":"Not Implemented","502":"Bad Gateway","503":"Service Unavailable","504":"Gateway Timeout","505":"HTTP Version Not Supported","506":"Variant Also Negotiates","507":"Insufficient Storage","508":"Loop Detected","509":"Bandwidth Limit Exceeded","510":"Not Extended","511":"Network Authentication Required"};
  const hydratedHttp = new WeakSet();
  const nativeApis = new WeakMap(), nativeForms = new WeakMap(), nativeFields = new WeakMap();
  const inputFields = new Set(["TextField", "PasswordField", "TextArea", "Checkbox", "Dropdown"]);
  let focusEvent = null;
  // The generated wrapper captures a React value. Android's native editor may
  // have received another edit before that asynchronous render is published.
  // Supply only the matching field callback's event-time target.value; retain
  // the original API functions, props, static members and other event data.
  function nativeField(field, react) {
    if (!nativeFields.has(field)) nativeFields.set(field, new Proxy(field, {
      apply(target, receiver, args) {
        const props = args[0];
        const [, publish] = react.useReducer(version => version + 1, 0);
        if (!props || typeof props !== "object") return Reflect.apply(target, receiver, args);
        // A controlled onChange can intentionally retain its previous value.
        // Publish that decision even without an application state update, so
        // the native optimistic editor can restore the authoritative value.
        const change = props.value === undefined ? props.onChange : function (value) {
          try { return typeof props.onChange === "function" ? Reflect.apply(props.onChange, this, [value]) : undefined; }
          finally { publish(); }
        };
        const callback = (kind, original) => typeof original !== "function" ? original : function (event) {
          const current = focusEvent;
          if (!current || current.id !== props.id || current.kind !== kind) return Reflect.apply(original, this, [event]);
          return Reflect.apply(original, this, [{...event, target: {...event?.target, value: current.value}}]);
        };
        return Reflect.apply(target, receiver, [{...props,
          onChange: change,
          onFocus: callback("focus", props.onFocus), onBlur: callback("blur", props.onBlur)}, ...args.slice(1)]);
      }
    }));
    return nativeFields.get(field);
  }
  function nativeApi(api, react) {
    if (!nativeApis.has(api)) nativeApis.set(api, new Proxy(api, {
      get(target, name, receiver) {
        const value = Reflect.get(target, name, receiver);
        if (name !== "Form" || typeof value !== "function") return value;
        if (!nativeForms.has(value)) nativeForms.set(value, new Proxy(value, {
          get(form, member, formReceiver) {
            const item = Reflect.get(form, member, formReceiver);
            return inputFields.has(member) && typeof item === "function" ? nativeField(item, react) : item;
          }
        }));
        return nativeForms.get(value);
      }
    }));
    return nativeApis.get(api);
  }
  globalThis.__androidDispatchFormFocus = (session, handle, id, kind, encodedValue) => {
    const previous = focusEvent;
    focusEvent = {id, kind, value: JSON.parse(encodedValue)[0]};
    try { return globalThis.__tinycast.dispatch(session, handle, "[]"); }
    finally { focusEvent = previous; }
  };
  globalThis.__tinycastCompile = (code, filename) => {
    const compiled = new Function("exports", "require", "module", "__filename", "__dirname", code + "\n//# sourceURL=" + filename);
    return function (exports, require, module, path, directory) {
      "use strict";
      const androidRequire = new Proxy(require, {
        apply(target, receiver, args) {
          const value = Reflect.apply(target, receiver, args);
          if (args[0] === "@raycast/api") return nativeApi(value, Reflect.apply(target, receiver, ["react"]));
          if ((args[0] === "http" || args[0] === "node:http") && !hydratedHttp.has(value)) {
            if (Object.keys(value.STATUS_CODES).length === 0) Object.assign(value.STATUS_CODES, httpStatusCodes);
            hydratedHttp.add(value);
          }
          return value;
        }
      });
      return Reflect.apply(compiled, this, [exports, androidRequire, module, path, directory]);
    };
  };
  globalThis.__tinycastHost = {
    invoke: (id, api, method, args) => AndroidHost.invoke(id, api, method, args),
    invokeSync: (api, method, args) => AndroidHost.invokeSync(api, method, args),
    render: (id, tree) => AndroidHost.render(tree),
    failed: (id, error) => AndroidHost.failed(error),
    finished: () => AndroidHost.finished(),
    navigationDepthChanged: (id, depth) => AndroidHost.depth(depth),
    fieldCommand: () => {},
    log: (level, message) => { if (level === "error") AndroidHost.log(message); },
    startTimer: (id, delay, repeats) => {
      const tick = () => { if (!repeats) timers.delete(id); globalThis.__tinycast?.fireTimer(id); };
      timers.set(id, {handle: repeats ? every(tick, Math.max(4, delay)) : later(tick, delay), repeats});
    },
    clearTimer: id => { const t = timers.get(id); if (t) (t.repeats ? cancelEvery : cancel)(t.handle); timers.delete(id); }
  };
  globalThis.__androidStopTimers = () => { for (const id of [...timers.keys()]) __tinycastHost.clearTimer(id); };
})();
