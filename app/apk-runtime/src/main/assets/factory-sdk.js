/* Jarvys Factory SDK v2. Offline, Promise-only, capability-checked by the native runtime. */
(function (global) {
  'use strict';
  if (Object.prototype.hasOwnProperty.call(global, 'Jarvys')) return;
  var MAX_MESSAGE_BYTES = 524288;
  var pending = new Map();
  var sequence = 0;
  var prefix = Date.now().toString(36) + '_';
  var bridge = global.JarvysNative;

  function failure(code, message) {
    var error = new Error(message);
    error.name = 'JarvysError';
    error.code = code;
    return error;
  }
  function failAll(code, message) {
    pending.forEach(function (entry) {
      global.clearTimeout(entry.timer);
      entry.reject(failure(code, message));
    });
    pending.clear();
  }
  if (bridge && typeof bridge.postMessage === 'function') {
    bridge.onmessage = function (event) {
      if (!event || typeof event.data !== 'string' || event.data.length > 1048576) return;
      var response;
      try { response = JSON.parse(event.data); } catch (_) { return; }
      if (!response || response.v !== 1 || typeof response.ok !== 'boolean') return;
      if (response.id === null && !response.ok && response.error && response.error.code === 'RATE_LIMITED') {
        failAll('RATE_LIMITED', 'Too many native requests; try again shortly.'); return;
      }
      if (typeof response.id !== 'string') return;
      var entry = pending.get(response.id);
      if (!entry) return;
      pending.delete(response.id);
      global.clearTimeout(entry.timer);
      if (response.ok) entry.resolve(response.result);
      else entry.reject(failure(response.error && typeof response.error.code === 'string' ? response.error.code : 'NATIVE_ERROR',
        response.error && typeof response.error.message === 'string' ? response.error.message : 'Native operation failed.'));
    };
  }
  function call(method, args) {
    return new Promise(function (resolve, reject) {
      if (!bridge || typeof bridge.postMessage !== 'function') {
        reject(failure('BRIDGE_UNAVAILABLE', 'Open this application in its generated Android APK.')); return;
      }
      if (pending.size >= 16) { reject(failure('BUSY', 'Too many pending native requests.')); return; }
      var id = prefix + (++sequence).toString(36);
      var payload;
      try {
        payload = JSON.stringify({ v: 1, id: id, method: method, args: args });
        if (new TextEncoder().encode(payload).length > MAX_MESSAGE_BYTES) throw failure('TOO_LARGE', 'Native request exceeds 512 KiB.');
      } catch (error) { reject(error.code ? error : failure('INVALID_ARGUMENT', 'Arguments must be serializable JSON.')); return; }
      var timeout = /^(export|clipboard|share)\./.test(method) || /^documents\.(open|create)$/.test(method) ? 600000 : 30000;
      var timer = global.setTimeout(function () {
        if (!pending.has(id)) return;
        pending.delete(id);
        reject(failure('TIMEOUT', 'Native response timed out. A system action already chosen may still finish.'));
      }, timeout);
      pending.set(id, { resolve: resolve, reject: reject, timer: timer });
      try { bridge.postMessage(payload); }
      catch (_) {
        pending.delete(id); global.clearTimeout(timer);
        reject(failure('BRIDGE_UNAVAILABLE', 'Native bridge is unavailable.'));
      }
    });
  }
  var api = Object.freeze({
    runtime: Object.freeze({ info: function () { return call('runtime.info', {}); } }),
    storage: Object.freeze({
      get: function (key) { return call('storage.get', { key: key }); },
      set: function (key, value) { return call('storage.set', { key: key, value: value }); },
      remove: function (key) { return call('storage.remove', { key: key }); },
      list: function () { return call('storage.list', {}); }
    }),
    documents: Object.freeze({
      open: function (options) { return call('documents.open', options); },
      create: function (options) { return call('documents.create', options); },
      read: function (options) { return call('documents.read', options); },
      write: function (options) { return call('documents.write', options); },
      close: function (options) { return call('documents.close', options); },
      cancel: function (options) { return call('documents.cancel', options === undefined ? {} : options); }
    }),
    export: Object.freeze({ text: function (options) { return call('export.text', options); } }),
    share: Object.freeze({ text: function (options) { return call('share.text', options); },
      file: function (options) { return call('share.file', options); } }),
    clipboard: Object.freeze({ write: function (text) { return call('clipboard.write', { text: text }); } }),
    haptics: Object.freeze({ perform: function (kind) { return call('haptics.perform', { kind: kind === undefined ? 'tap' : kind }); } }),
    device: Object.freeze({ info: function () { return call('device.info', {}); } })
  });
  Object.defineProperty(global, 'Jarvys', { value: api, configurable: false, writable: false, enumerable: true });
  global.addEventListener('pagehide', function () { failAll('PAGE_CLOSED', 'Application page was closed.'); });
}(window));
