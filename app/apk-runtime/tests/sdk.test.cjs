'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../src/main/assets/factory-sdk.js'), 'utf8');
function environment(connected = true) {
  const sent = [], timers = new Map(), handlers = new Map(), delays = [];
  let timerId = 0;
  const window = {
    setTimeout: (callback, delay) => { delays.push(delay); timers.set(++timerId, callback); return timerId; },
    clearTimeout: id => timers.delete(id),
    addEventListener: (event, callback) => handlers.set(event, callback)
  };
  if (connected) window.JarvysNative = { postMessage: value => sent.push(JSON.parse(value)) };
  vm.runInNewContext(source, { window, TextEncoder, Date, Map, Error, Promise });
  function reply(request, result) {
    window.JarvysNative.onmessage({ data: JSON.stringify({ v: 1, id: request.id, ok: true, result }) });
  }
  return { window, api: window.Jarvys, sent, timers, handlers, delays, reply };
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
  assert.equal(rows.length, 23);
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


test('documents API preserves exact options and extends only picker timeouts', async () => {
  const env = environment();
  assert.equal(Object.isFrozen(env.api.documents), true);
  const handle = 'a'.repeat(64);
  const cases = [
    ['open', {mimeType: '*/*'}, 600000],
    ['create', {filename: 'document.txt', mimeType: 'text/plain'}, 600000],
    ['read', {handle, offset: 0, length: 32768}, 30000],
    ['write', {handle, offset: 0, data: 'AA=='}, 30000],
    ['close', {handle}, 30000],
    ['cancel', {}, 30000]
  ];
  for (const [method, options, timeout] of cases) {
    const result = env.api.documents[method](options);
    assert.equal(typeof result.then, 'function');
    const request = env.sent.at(-1);
    assert.equal(request.method, 'documents.' + method);
    assert.deepEqual(request.args, options);
    assert.equal(env.delays.at(-1), timeout);
    env.reply(request, {ok: true});
    assert.equal((await result).ok, true);
  }
  const cancel = env.api.documents.cancel();
  assert.deepEqual(env.sent.at(-1).args, {});
  env.reply(env.sent.at(-1), null); await cancel;
});


test('file sharing preserves opaque handle metadata and human chooser timeout', async () => {
  const env = environment();
  const options = {handle: 'a'.repeat(64), filename: 'fixture.bin', mimeType: 'application/octet-stream'};
  const promise = env.api.share.file(options);
  const request = env.sent.at(-1);
  assert.equal(request.method, 'share.file');
  assert.deepEqual(request.args, options);
  assert.equal(env.delays.at(-1), 600000);
  env.reply(request, {chooserOpened: true, deliveryConfirmed: false});
  const result = await promise;
  assert.equal(result.chooserOpened, true);
  assert.equal(result.deliveryConfirmed, false);
});


test('photos exposes frozen empty-options methods and human-action timeouts', async () => {
  const env = environment();
  assert.equal(Object.isFrozen(env.api.photos), true);
  for (const method of ['pick', 'capture']) {
    for (const options of [undefined, {}, {uri: 'content://forbidden'}]) {
      const promise = env.api.photos[method](options);
      const request = env.sent.at(-1);
      assert.equal(request.method, 'photos.' + method);
      assert.deepEqual(request.args, options === undefined ? {} : options);
      assert.equal(env.delays.at(-1), 600000);
      // Options are preserved so native exact-key validation cannot be bypassed by silently dropping them.
      env.reply(request, {handle: 'a'.repeat(64), mimeType: 'image/jpeg'});
      assert.equal((await promise).mimeType, 'image/jpeg');
    }
  }
});

 test('audio uses an opaque handle and a human review timeout', async () => {
  const env = environment(); assert.equal(Object.isFrozen(env.api.audio), true);
  const options = {handle: 'a'.repeat(64)};
  const promise = env.api.audio.play(options); const request = env.sent.at(-1);
  assert.equal(request.method, 'audio.play'); assert.deepEqual(request.args, options);
  assert.equal(env.delays.at(-1), 600000);
  env.reply(request, {playbackAttempted: true, audibilityConfirmed: false});
  const result = await promise; assert.equal(result.playbackAttempted, true); assert.equal(result.audibilityConfirmed, false);
});


test('browser open preserves exact URL/options and reports only a launch request', async () => {
  const env = environment(); assert.equal(Object.isFrozen(env.api.browser), true);
  assert.deepEqual(Object.keys(env.api.browser), ['open']);
  for (const options of [{url: 'https://example.com:443/a%2Fb?q=%C3%A9'}, {url: 'https://example.com/', headers: {extra: true}}]) {
    const promise = env.api.browser.open(options); const request = env.sent.at(-1);
    assert.equal(request.method, 'browser.open'); assert.deepEqual(request.args, options);
    assert.equal(env.delays.at(-1), 600000);
    env.reply(request, {launchRequested: true, pageLoadConfirmed: false});
    const result = await promise; assert.equal(result.launchRequested, true); assert.equal(result.pageLoadConfirmed, false);
  }
});

test('browser timeout/pagehide cannot be mistaken for external page confirmation', async () => {
  for (const event of ['timeout', 'pagehide']) {
    const env = environment(); const promise = env.api.browser.open({url: 'https://example.com/'});
    const rejected = assert.rejects(promise, {code: event === 'timeout' ? 'TIMEOUT' : 'PAGE_CLOSED'});
    if (event === 'timeout') env.timers.values().next().value(); else env.handlers.get('pagehide')();
    await rejected;
    env.reply(env.sent.at(-1), {launchRequested: true, pageLoadConfirmed: false});
  }
});

test('typed maps and phone preserve options, separate frozen APIs, and human review timeout', async () => {
  const env = environment();
  assert.equal(Object.isFrozen(env.api.maps), true); assert.equal(Object.isFrozen(env.api.phone), true);
  assert.deepEqual(Object.keys(env.api.maps), ['open']); assert.deepEqual(Object.keys(env.api.phone), ['dial']);
  for (const [group, method, options] of [
    ['maps', 'open', {latitude: -90, longitude: 180}],
    ['maps', 'open', {query: ' Café %2F & + # 東京 😀 '}],
    ['maps', 'open', {query: 'q', flags: 1}],
    ['phone', 'dial', {number: '+0012345'}],
    ['phone', 'dial', {number: '*123#', uri: 'tel:123'}]
  ]) {
    const pending = env.api[group][method](options); const request = env.sent.at(-1);
    assert.equal(request.method, group + '.' + method); assert.deepEqual(request.args, options);
    assert.equal(env.delays.at(-1), 600000);
    env.reply(request, {launchRequested: true, actionConfirmed: false});
    const result = await pending;
    assert.equal(result.launchRequested, true); assert.equal(result.actionConfirmed, false);
    assert.equal(Object.keys(result).length, 2);
  }
});

test('typed external action timeout and pagehide stay terminal despite late launch results', async () => {
  for (const event of ['timeout', 'pagehide']) for (const group of ['maps', 'phone']) {
    const env = environment();
    const pending = group === 'maps' ? env.api.maps.open({query: 'fixture'}) : env.api.phone.dial({number: '123'});
    const rejected = assert.rejects(pending, {code: event === 'timeout' ? 'TIMEOUT' : 'PAGE_CLOSED'});
    if (event === 'timeout') env.timers.values().next().value(); else env.handlers.get('pagehide')();
    await rejected;
    env.reply(env.sent.at(-1), {launchRequested: true, actionConfirmed: false});
  }
});
