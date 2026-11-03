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
  const nativeActions = new WeakMap(), nativeSubmits = new WeakMap();
  const inputFields = new Set(["TextField", "PasswordField", "TextArea", "Checkbox", "Dropdown", "DatePicker", "TagPicker", "FilePicker"]);
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
  // SubmitForm loses its type in the generated generic Action projection.
  // Keep an Android-owned identity and validate after a fresh committed tree.
  // The returned Promise preserves the generated dispatcher's error handling.
  let submitSequence = 0, submitRevision = 0, submitView = null, submitDrainQueued = false;
  const pendingSubmits = new Set();
  function treeNodes(root) {
    const result = [], stack = root ? [root] : [];
    while (stack.length) {
      if (result.length >= 16384) return null;
      const node = stack.pop();
      if (!node || typeof node !== "object") continue;
      result.push(node);
      const children = Array.isArray(node.children) ? node.children : [];
      for (let index = children.length - 1; index >= 0; index--) stack.push(children[index]);
    }
    return result;
  }
  function readSubmitView(session, encodedTree) {
    const revision = ++submitRevision;
    if (!encodedTree.includes('"__tinycastFormSubmit"')) return null;
    try {
      const tree = JSON.parse(encodedTree), all = treeNodes(tree);
      if (!all) return null;
      const screens = all.filter(node => node.type === "__screen" && node.props?.active);
      const visible = treeNodes(screens.at(-1) || tree);
      const root = visible?.find(node => ["List", "Grid", "Detail", "Form"].includes(node.type));
      if (!root || root.id == null) return null;
      const actions = treeNodes(root.props?.actions), fields = treeNodes(root);
      if (!actions || !fields) return null;
      const owners = new Map();
      for (const action of actions) {
        const p = action.props || {}, token = p.__tinycastSubmitOwner;
        if (action.type !== "Action" || p.__tinycastFormSubmit !== true || typeof token !== "string") continue;
        const binding = {nativeId: String(action.id), signature: p.__tinycastSubmitSignature};
        owners.set(token, owners.has(token) ? null : binding);
      }
      const errors = root.type === "Form" && fields.some(node =>
        inputFields.has(String(node.type).replace(/^Form\./, "")) &&
        typeof node.props?.error === "string" && node.props.error.trim().length > 0);
      return {session, revision, rootId: String(root.id), type: root.type, owners, errors};
    } catch { return null; }
  }
  function cancelSubmit(owner) {
    const ticket = owner.pending;
    owner.pending = null; pendingSubmits.delete(owner);
    ticket?.resolve(undefined);
  }
  function queueSubmitDrain() {
    if (submitDrainQueued || !pendingSubmits.size) return;
    submitDrainQueued = true;
    Promise.resolve().then(() => {
      submitDrainQueued = false;
      for (const owner of [...pendingSubmits]) {
        const ticket = owner.pending, view = submitView, binding = view?.owners.get(owner.token);
        if (!ticket) { pendingSubmits.delete(owner); continue; }
        if (view && view.revision <= ticket.revision) continue;
        owner.pending = null; pendingSubmits.delete(owner);
        if (!owner.active || !binding || !view || view.session !== ticket.session || view.rootId !== ticket.rootId ||
            view.type !== ticket.type || binding.nativeId !== ticket.nativeId || binding.signature !== ticket.signature ||
            owner.signature !== ticket.signature || view.errors || typeof owner.action !== "function") {
          ticket.resolve(undefined); continue;
        }
        try { ticket.resolve(Reflect.apply(owner.action, undefined, [])); }
        catch (error) { ticket.reject(error); }
      }
    });
  }
  function requestSubmit(owner) {
    if (owner.pending) return owner.pending.promise;
    const view = submitView, binding = view?.owners.get(owner.token);
    if (!owner.active || !view || !binding || binding.signature !== owner.signature) return Promise.resolve(undefined);
    const ticket = {...binding, session: view.session, rootId: view.rootId, type: view.type, revision: view.revision};
    ticket.promise = new Promise((resolve, reject) => { ticket.resolve = resolve; ticket.reject = reject; });
    owner.pending = ticket; pendingSubmits.add(owner);
    try { owner.publish(); queueSubmitDrain(); }
    catch (error) { owner.pending = null; pendingSubmits.delete(owner); ticket.reject(error); }
    return ticket.promise;
  }
  function nativeSubmit(submit, react) {
    if (!nativeSubmits.has(submit)) nativeSubmits.set(submit, new Proxy(submit, {
      apply(target, receiver, args) {
        const [, publish] = react.useReducer(version => version + 1, 0), reference = react.useRef(null);
        if (!reference.current) reference.current = {token: "android-submit-" + (++submitSequence), active: false, pending: null, publish};
        const owner = reference.current, element = Reflect.apply(target, receiver, args);
        const valid = react.isValidElement(element) && typeof element.props?.onAction === "function";
        const p = valid ? element.props : {};
        const signature = JSON.stringify([p.title ?? null, p.icon ?? null, p.shortcut ?? null, p.style ?? null, p.autoFocus ?? null]);
        react.useLayoutEffect(() => {
          owner.active = true;
          return () => { owner.active = false; cancelSubmit(owner); };
        }, [owner]);
        react.useLayoutEffect(() => { owner.action = valid ? p.onAction : null; owner.signature = signature; }, [owner, p.onAction, signature]);
        if (!valid) return element;
        return react.cloneElement(element, {__tinycastFormSubmit: true, __tinycastSubmitOwner: owner.token,
          __tinycastSubmitSignature: signature, onAction: () => requestSubmit(owner)});
      }
    }));
    return nativeSubmits.get(submit);
  }
  function nativeAction(action, react) {
    if (!nativeActions.has(action)) nativeActions.set(action, new Proxy(action, {
      get(target, name, receiver) {
        const value = Reflect.get(target, name, receiver);
        return name === "SubmitForm" && typeof value === "function" ? nativeSubmit(value, react) : value;
      }
    }));
    return nativeActions.get(action);
  }
  function nativeApi(api, react) {
    if (!nativeApis.has(api)) nativeApis.set(api, new Proxy(api, {
      get(target, name, receiver) {
        const value = Reflect.get(target, name, receiver);
        if (name === "default" && value === target) return nativeApis.get(target);
        if (name === "Action" && typeof value === "function") return nativeAction(value, react);
        if (name === "SubmitFormAction" && typeof value === "function") return nativeSubmit(value, react);
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
    // Native trees encode Date values; callbacks still receive JavaScript Date.
    // Arrays of tag values/file paths retain their ordered string values.
    const values = JSON.parse(encodedValue, (key, value) => {
      if (value && typeof value === "object" && !Array.isArray(value) &&
          Object.keys(value).length === 1 && typeof value.$date === "string") {
        const date = new Date(value.$date);
        if (!Number.isFinite(date.getTime())) throw new TypeError("Invalid Form date value");
        return date;
      }
      return value;
    });
    focusEvent = {id, kind, value: values[0]};
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
    render: (id, tree) => {
      submitView = readSubmitView(id, tree);
      AndroidHost.render(tree);
      queueSubmitDrain();
    },
    failed: (id, error) => {
      submitView = null;
      for (const owner of [...pendingSubmits]) cancelSubmit(owner);
      AndroidHost.failed(error);
    },
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
