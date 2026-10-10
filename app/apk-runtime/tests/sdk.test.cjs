'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../src/main/assets/factory-sdk.js'), 'utf8');
function environment(connected = true) {
  const sent = [], timers = new Map(), handlers = new Map();
  let timerId = 0;
  const window = {
    setTimeout: callback => { timers.set(++timerId, callback); return timerId; },
    clearTimeout: id => timers.delete(id),
    addEventListener: (event, callback) => handlers.set(event, callback)
  };
  if (connected) window.JarvysNative = { postMessage: value => sent.push(JSON.parse(value)) };
  vm.runInNewContext(source, { window, TextEncoder, Date, Map, Error, Promise });
  function reply(request, result) {
    window.JarvysNative.onmessage({ data: JSON.stringify({ v: 1, id: request.id, ok: true, result }) });
  }
  return { window, api: window.Jarvys, sent, timers, handlers, reply };
}
test('SDK exposes frozen Promise methods and correlates out-of-order responses', async () => {
  const env = environment();
  assert.equal(Object.isFrozen(env.api), true);
  assert.equal(Object.isFrozen(env.api.storage), true);
  const first = env.api.storage.get('first');
  const second = env.api.storage.get('second');
  assert.equal(env.sent[0].method, 'storage.get');
  assert.deepEqual(env.sent[0].args, { key: 'first' });
  env.reply(env.sent[1], 'two'); env.reply(env.sent[0], null);
  assert.equal(await first, null); assert.equal(await second, 'two');
  assert.equal(env.timers.size, 0);
});
test('native capability denial preserves error code', async () => {
  const env = environment();
  const pending = env.api.clipboard.write('private');
  env.window.JarvysNative.onmessage({ data: JSON.stringify({ v: 1, id: env.sent[0].id, ok: false, error: { code: 'CAPABILITY_DENIED', message: 'Not declared.' } }) });
  await assert.rejects(pending, { name: 'JarvysError', code: 'CAPABILITY_DENIED' });
});
test('malformed and unrelated replies cannot settle a pending operation', async () => {
  const env = environment();
  const pending = env.api.runtime.info();
  const receive = env.window.JarvysNative.onmessage;
  receive({ data: 'invalid json' });
  receive({ data: JSON.stringify({ v: 2, id: env.sent[0].id, ok: true, result: 'bad' }) });
  receive({ data: JSON.stringify({ v: 1, id: 'unknown', ok: true, result: 'bad' }) });
  assert.equal(env.timers.size, 1);
  env.reply(env.sent[0], { offline: true }); assert.equal((await pending).offline, true);
});
test('browser preview reports unavailable native bridge', async () => {
  await assert.rejects(environment(false).api.storage.list(), { code: 'BRIDGE_UNAVAILABLE' });
});
test('oversized or circular payloads are rejected without sending', async () => {
  const env = environment();
  await assert.rejects(env.api.storage.set('test', 'x'.repeat(524288)), { code: 'TOO_LARGE' });
  const circular = {}; circular.self = circular;
  await assert.rejects(env.api.export.text(circular), { code: 'INVALID_ARGUMENT' });
  assert.equal(env.sent.length, 0);
});
test('pending queue is bounded and pagehide clears it', async () => {
  const env = environment();
  const requests = Array.from({ length: 16 }, () => env.api.runtime.info());
  const rejections = requests.map(promise => assert.rejects(promise, { code: 'PAGE_CLOSED' }));
  await assert.rejects(env.api.runtime.info(), { code: 'BUSY' });
  assert.equal(env.sent.length, 16);
  env.handlers.get('pagehide')();
  await Promise.all(rejections); assert.equal(env.timers.size, 0);
});
test('timeout rejects once and late replies are ignored', async () => {
  const env = environment();
  const promise = env.api.device.info();
  const rejectCheck = assert.rejects(promise, { code: 'TIMEOUT' });
  env.timers.values().next().value();
  await rejectCheck;
  env.reply(env.sent[0], { platform: 'android' });
});
test('every convenience method uses the typed native allowlist', async () => {
  const env = environment();
  const operations = [
    ['runtime.info', () => env.api.runtime.info()],
    ['storage.set', () => env.api.storage.set('k', 'v')],
    ['storage.remove', () => env.api.storage.remove('k')],
    ['storage.list', () => env.api.storage.list()],
    ['export.text', () => env.api.export.text({ filename: 'note.txt', text: 'hello' })],
    ['share.text', () => env.api.share.text({ text: 'hello' })],
    ['clipboard.write', () => env.api.clipboard.write('hello')],
    ['haptics.perform', () => env.api.haptics.perform()],
    ['device.info', () => env.api.device.info()]
  ];
  for (const [method, invoke] of operations) {
    const promise = invoke(); const request = env.sent.at(-1);
    assert.equal(request.method, method); env.reply(request, null); await promise;
  }
});

test('native rate limit immediately rejects outstanding requests', async () => {
  const env = environment();
  const promise = env.api.runtime.info();
  env.window.JarvysNative.onmessage({ data: JSON.stringify({ v: 1, id: null, ok: false, error: { code: 'RATE_LIMITED' } }) });
  await assert.rejects(promise, { code: 'RATE_LIMITED' });
  assert.equal(env.timers.size, 0);
});

test('all SDK calls exactly match the catalog and explicit native validator/dispatch cases', async () => {
  const path = require('node:path');
  const catalog = fs.readFileSync(path.join(__dirname, '../../factory-contract/src/main/java/com/jarvys/factory/contract/CapabilityCatalog.java'), 'utf8');
  const rows = [...catalog.matchAll(/\b([A-Z][A-Z_]+)\("([a-z]+\.[a-z]+)", (?:"[a-z]+"|null)\)/g)];
  assert.equal(rows.length, 10);
  const env = environment();
  const args = { 'storage.get': ['key'], 'storage.set': ['key', 'value'], 'storage.remove': ['key'],
    'export.text': [{ filename: 'test.txt', text: 'hello' }], 'share.text': [{ text: 'hello' }],
    'clipboard.write': ['hello'], 'haptics.perform': ['tap'] };
  const exposed = Object.entries(env.api).flatMap(([group, values]) => Object.keys(values).map(name => group + '.' + name));
  assert.deepEqual(exposed.sort(), rows.map(r => r[2]).sort());
  for (const [, , wire] of rows) {
    const [group, method] = wire.split('.');
    const pending = env.api[group][method](...(args[wire] || []));
    assert.equal(env.sent.at(-1).method, wire); env.reply(env.sent.at(-1), null); await pending;
  }
  for (const name of ['BridgeProtocol', 'FactoryDispatcher']) {
    const java = fs.readFileSync(path.join(__dirname, '../../factory-runtime-core/src/main/java/com/jarvys/factory/runtime/' + name + '.java'), 'utf8');
    const dispatch = name === 'FactoryDispatcher' ? java.slice(java.indexOf('public static Object dispatch(')) : java;
    const cases = [...dispatch.matchAll(/case ([A-Z][A-Z_]+):/g)].map(r => r[1]);
    assert.deepEqual(cases.sort(), rows.map(r => r[1]).sort(), name);
  }
});
