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
  const rows = [...catalog.matchAll(/\b([A-Z][A-Z_]+)\("([a-zA-Z]+\.[a-zA-Z]+)", (?:"[a-zA-Z]+"|null)\)/g)];
  assert.equal(rows.length, 134);
  const env = environment();
  const args = { 'storage.get': ['key'], 'storage.set': ['key', 'value'], 'storage.remove': ['key'],
    'export.text': [{ filename: 'test.txt', text: 'hello' }], 'share.text': [{ text: 'hello' }],
    'clipboard.write': ['hello'], 'haptics.perform': ['tap'],
    'network.request': [{url: 'https://example.com/'}], 'network.probe': [{url: 'https://example.com/'}],
    'network.ping': [{host: 'example.com'}], 'notifications.channel': [{id: 'updates', name: 'Updates'}],
    'notifications.show': [{id: 1, channelId: 'updates', title: 'Update', body: 'Ready'}],
    'notifications.cancel': [{id: 1}], 'speech.speak': [{text: 'Hello'}] };
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


test('file sharing preserves opaque handle metadata and bounded streaming and chooser fallback', async () => {
  const env = environment();
  const options = {handle: 'a'.repeat(64), filename: 'fixture.bin', mimeType: 'application/octet-stream'};
  const promise = env.api.share.file(options);
  const request = env.sent.at(-1);
  assert.equal(request.method, 'share.file');
  assert.deepEqual(request.args, options);
  assert.equal(env.delays.at(-1), 259500000);
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

 test('audio uses an opaque handle and declared playback lifetime', async () => {
  const env = environment(); assert.equal(Object.isFrozen(env.api.audio), true);
  const options = {handle: 'a'.repeat(64)};
  const promise = env.api.audio.play(options); const request = env.sent.at(-1);
  assert.equal(request.method, 'audio.play'); assert.deepEqual(request.args, options);
  assert.equal(env.delays.at(-1), 441245454);
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


test('typed email and SMS composers preserve fields, review timeout and unconfirmed receipts', async () => {
  const env = environment();
  for (const [group, options] of [
    ['email', {to: 'fixture+tag@example.invalid', subject: 'Subject', body: 'Line 1\nLine 2'}],
    ['sms', {number: '+00123', body: 'Body %2F & + #'}],
    ['email', {to: 'fixture@example.invalid', subject: '', body: '', bcc: 'blocked@example.invalid'}],
    ['sms', {number: '123', body: '', extras: {arbitrary: true}}]
  ]) {
    assert.equal(Object.isFrozen(env.api[group]), true); assert.deepEqual(Object.keys(env.api[group]), ['compose']);
    const pending = env.api[group].compose(options); const request = env.sent.at(-1);
    assert.equal(request.method, group + '.compose'); assert.deepEqual(request.args, options);
    assert.equal(env.delays.at(-1), 600000);
    env.reply(request, {launchRequested: true, actionConfirmed: false});
    const result = await pending; assert.equal(result.launchRequested, true); assert.equal(result.actionConfirmed, false); assert.equal(Object.keys(result).length, 2);
  }
});
test('editor timeout and pagehide never become sending or delivery confirmation', async () => {
  for (const event of ['timeout', 'pagehide']) for (const group of ['email', 'sms']) {
    const env = environment();
    const options = group === 'email' ? {to: 'fixture@example.invalid', subject: '', body: ''} : {number: '0', body: ''};
    const pending = env.api[group].compose(options);
    const rejected = assert.rejects(pending, {code: event === 'timeout' ? 'TIMEOUT' : 'PAGE_CLOSED'});
    if (event === 'timeout') env.timers.values().next().value(); else env.handlers.get('pagehide')();
    await rejected; env.reply(env.sent.at(-1), {launchRequested: true, actionConfirmed: false});
  }
});


test('contact picker exposes only frozen pick and preserves explicit kind/options without defaults', async () => {
  const env = environment();
  assert.equal(Object.isFrozen(env.api.contacts), true); assert.deepEqual(Object.keys(env.api.contacts), ['pick']);
  for (const options of [{kind: 'phone'}, {kind: 'email'}, {}, {kind: 'phone', uri: 'content://forbidden'}, {kind: 'all'}, null, undefined]) {
    const pending = env.api.contacts.pick(options); const request = env.sent.at(-1);
    assert.equal(request.method, 'contacts.pick'); assert.deepEqual(request.args, options);
    assert.equal(env.delays.at(-1), 600000);
    env.reply(request, {kind: 'phone', value: ' +1 (555) 0100 ext. 42 '});
    const result = await pending; assert.equal(result.value, ' +1 (555) 0100 ext. 42 '); assert.deepEqual(Object.keys(result), ['kind', 'value']);
  }
});
test('contact selection timeout and pagehide cannot release a late selected value', async () => {
  for (const event of ['timeout', 'pagehide']) {
    const env = environment(); const pending = env.api.contacts.pick({kind: 'email'});
    const rejected = assert.rejects(pending, {code: event === 'timeout' ? 'TIMEOUT' : 'PAGE_CLOSED'});
    if (event === 'timeout') env.timers.values().next().value(); else env.handlers.get('pagehide')();
    await rejected; env.reply(env.sent.at(-1), {kind: 'email', value: 'fixture@example.invalid'});
  }
});


test('calendar insert exposes one frozen method and preserves all prefills without hidden defaults', async () => {
  const env = environment();
  assert.equal(Object.isFrozen(env.api.calendar), true); assert.deepEqual(Object.keys(env.api.calendar), ['insert']);
  for (const options of [
    {title: 'Synthetic event', location: '', description: 'Line 1\nLine 2', startTimeMillis: 0, endTimeMillis: 86400000, timeZone: 'UTC', allDay: true},
    {title: 'Timed event', location: 'Room &url=content://forbidden', description: '', startTimeMillis: 1234, endTimeMillis: 5678, timeZone: 'America/New_York', allDay: false},
    {title: 'Forbidden extra', attendees: ['fixture@example.invalid']}, {}, null, undefined
  ]) {
    const pending = env.api.calendar.insert(options); const request = env.sent.at(-1);
    assert.equal(request.method, 'calendar.insert'); assert.deepEqual(request.args, options); assert.equal(env.delays.at(-1), 600000);
    env.reply(request, {launchRequested: true, actionConfirmed: false});
    const result = await pending; assert.equal(result.launchRequested, true); assert.equal(result.actionConfirmed, false); assert.deepEqual(Object.keys(result), ['launchRequested', 'actionConfirmed']);
  }
});
test('calendar timeout and pagehide cannot become event saving or sync confirmation', async () => {
  for (const event of ['timeout', 'pagehide']) {
    const env = environment();
    const pending = env.api.calendar.insert({title: 'Fixture', location: '', description: '', startTimeMillis: 0, endTimeMillis: 86400000, timeZone: 'UTC', allDay: true});
    const rejected = assert.rejects(pending, {code: event === 'timeout' ? 'TIMEOUT' : 'PAGE_CLOSED'});
    if (event === 'timeout') env.timers.values().next().value(); else env.handlers.get('pagehide')();
    await rejected; env.reply(env.sent.at(-1), {launchRequested: true, actionConfirmed: false});
    if (event === 'pagehide') assert.equal(env.timers.size, 0);
  }
});

test('database preserves its six original methods with ordinary timeouts and lossless options', async () => {
  const env = environment();
  assert.equal(Object.isFrozen(env.api.database), true);
  assert.deepEqual(Object.keys(env.api.database), ['info', 'migrate', 'transact', 'select', 'backupExport', 'backupImport', 'close', 'cancel']);
  for (const method of ['info', 'migrate', 'transact', 'select', 'close', 'cancel']) {
    for (const options of [undefined, {}, null, {extra: 'native exact-key validation must see this'},
      {statements: [{sql: 'SELECT ? AS value', args: ['Café 東京 😀']}]}]) {
      const pending = env.api.database[method](options);
      assert.equal(typeof pending.then, 'function');
      const request = env.sent.at(-1);
      const defaults = ['info', 'close', 'cancel'].includes(method);
      assert.equal(request.v, 1);
      assert.equal(request.method, 'database.' + method);
      assert.deepEqual(request.args, defaults && options === undefined ? {} : options);
      assert.equal(env.delays.at(-1), 30000);
      env.reply(request, {synthetic: true});
      assert.equal((await pending).synthetic, true);
      assert.equal(env.timers.size, 0);
    }
  }
});

test('database native denials reject every method and never resolve from late success', async () => {
  for (const method of ['info', 'migrate', 'transact', 'select', 'backupExport', 'backupImport', 'close', 'cancel']) {
    for (const code of ['CAPABILITY_DENIED', 'INVALID_ARGUMENT', 'CANCELLED', 'NATIVE_ERROR']) {
      const env = environment();
      const pending = env.api.database[method]({});
      const request = env.sent.at(-1);
      const rejected = assert.rejects(pending, {name: 'JarvysError', code, message: 'Synthetic native rejection'});
      env.window.JarvysNative.onmessage({data: JSON.stringify({v: 1, id: request.id, ok: false,
        error: {code, message: 'Synthetic native rejection'}})});
      await rejected;
      env.reply(request, {late: true});
      assert.equal(env.timers.size, 0);
    }
  }
});

test('database timeout, pagehide, and missing bridge are terminal for every method', async () => {
  for (const method of ['info', 'migrate', 'transact', 'select', 'backupExport', 'backupImport', 'close', 'cancel']) {
    await assert.rejects(environment(false).api.database[method]({}), {code: 'BRIDGE_UNAVAILABLE'});
    for (const event of ['timeout', 'pagehide']) {
      const env = environment();
      const pending = env.api.database[method]({});
      const rejected = assert.rejects(pending, {code: event === 'timeout' ? 'TIMEOUT' : 'PAGE_CLOSED'});
      assert.equal(env.delays.at(-1), method.startsWith('backup') ? 310000 : 30000);
      if (event === 'timeout') env.timers.values().next().value(); else env.handlers.get('pagehide')();
      await rejected;
      env.reply(env.sent.at(-1), {late: true});
      if (event === 'pagehide') assert.equal(env.timers.size, 0);
    }
  }
});

test('presentation exposes exactly three frozen methods with lossless options and ordinary timeouts', async () => {
  const env = environment();
  assert.equal(Object.isFrozen(env.api.presentation), true);
  assert.deepEqual(Object.keys(env.api.presentation), ['get', 'set', 'reset']);
  for (const method of ['get', 'set', 'reset']) {
    for (const options of [undefined, {}, null, false, [], 'dark',
      {theme: 'dark', orientation: 'portrait'}, {extra: 'native exact-key validation must see this'}]) {
      const pending = env.api.presentation[method](options);
      assert.equal(typeof pending.then, 'function');
      const request = env.sent.at(-1);
      assert.equal(request.v, 1);
      assert.equal(request.method, 'presentation.' + method);
      assert.deepEqual(request.args, method !== 'set' && options === undefined ? {} : options);
      assert.equal(env.delays.at(-1), 30000);
      env.reply(request, {synthetic: true});
      assert.equal((await pending).synthetic, true);
      assert.equal(env.timers.size, 0);
    }
  }
});

test('presentation native errors remain terminal and are never masked by late success', async () => {
  for (const method of ['get', 'set', 'reset']) {
    for (const code of ['CAPABILITY_DENIED', 'INVALID_ARGUMENT', 'UNAVAILABLE', 'CANCELLED', 'NATIVE_ERROR', 'PRESENTATION_STORAGE_ERROR']) {
      const env = environment();
      const pending = env.api.presentation[method]({theme: 'light', orientation: 'landscape'});
      const request = env.sent.at(-1);
      const rejected = assert.rejects(pending, {name: 'JarvysError', code, message: 'Synthetic rejection'});
      env.window.JarvysNative.onmessage({data: JSON.stringify({v: 1, id: request.id, ok: false,
        error: {code, message: 'Synthetic rejection'}})});
      await rejected;
      env.reply(request, {late: true});
      assert.equal(env.timers.size, 0);
    }
  }
});

test('presentation timeout, pagehide, and absent bridge reject each method', async () => {
  for (const method of ['get', 'set', 'reset']) {
    await assert.rejects(environment(false).api.presentation[method]({}), {code: 'BRIDGE_UNAVAILABLE'});
    for (const event of ['timeout', 'pagehide']) {
      const env = environment();
      const pending = env.api.presentation[method]({});
      const rejected = assert.rejects(pending, {code: event === 'timeout' ? 'TIMEOUT' : 'PAGE_CLOSED'});
      assert.equal(env.delays.at(-1), 30000);
      if (event === 'timeout') env.timers.values().next().value(); else env.handlers.get('pagehide')();
      await rejected;
      env.reply(env.sent.at(-1), {late: true});
      if (event === 'pagehide') assert.equal(env.timers.size, 0);
    }
  }
});

test('sensors exposes finite capture and streaming methods with unchanged arguments', async () => {
  const env = environment();
  assert.equal(Object.isFrozen(env.api.sensors), true);
  assert.deepEqual(Object.keys(env.api.sensors), ['subscribe', 'poll', 'unsubscribe', 'list', 'capture', 'cancel']);
  for (const method of ['list', 'capture', 'cancel']) {
    for (const options of [undefined, {}, null, false, [], 'light',
      {kind: 'light', durationMs: 100, intervalMs: 100, maxSamples: 1},
      {kind: 'accelerometer', durationMs: 5000, intervalMs: 1000, maxSamples: 50},
      {kind: 'light', durationMs: '100', intervalMs: 100.5, maxSamples: 0, extra: true}]) {
      const pending = env.api.sensors[method](options);
      assert.equal(typeof pending.then, 'function');
      const request = env.sent.at(-1);
      assert.equal(request.v, 1);
      assert.equal(request.method, 'sensors.' + method);
      assert.deepEqual(request.args, method !== 'capture' && options === undefined ? {} : options);
      assert.equal(env.delays.at(-1), method === 'capture' ? 310000 : 30000);
      env.reply(request, {synthetic: true});
      assert.equal((await pending).synthetic, true);
      assert.equal(env.timers.size, 0);
    }
  }
});

test('sensors native errors including preview UNAVAILABLE are terminal', async () => {
  for (const method of ['list', 'capture', 'cancel']) {
    for (const code of ['CAPABILITY_DENIED', 'INVALID_ARGUMENT', 'UNAVAILABLE', 'BUSY', 'CANCELLED', 'NATIVE_ERROR']) {
      const env = environment();
      const pending = env.api.sensors[method]({kind: 'light', durationMs: 100, intervalMs: 100, maxSamples: 1});
      const request = env.sent.at(-1);
      const rejected = assert.rejects(pending, {name: 'JarvysError', code, message: 'Synthetic rejection'});
      env.window.JarvysNative.onmessage({data: JSON.stringify({v: 1, id: request.id, ok: false,
        error: {code, message: 'Synthetic rejection'}})});
      await rejected;
      env.reply(request, {late: true});
      assert.equal(env.timers.size, 0);
    }
  }
});

test('sensors timeout, pagehide and absent bridge reject each method', async () => {
  for (const method of ['list', 'capture', 'cancel']) {
    await assert.rejects(environment(false).api.sensors[method]({}), {code: 'BRIDGE_UNAVAILABLE'});
    for (const event of ['timeout', 'pagehide']) {
      const env = environment();
      const pending = env.api.sensors[method]({});
      const rejected = assert.rejects(pending, {code: event === 'timeout' ? 'TIMEOUT' : 'PAGE_CLOSED'});
      assert.equal(env.delays.at(-1), method === 'capture' ? 310000 : 30000);
      if (event === 'timeout') env.timers.values().next().value(); else env.handlers.get('pagehide')();
      await rejected;
      env.reply(env.sent.at(-1), {late: true});
      if (event === 'pagehide') assert.equal(env.timers.size, 0);
    }
  }
});

test('sensors cancel and capture correlate independently without fabricating cleanup evidence', async () => {
  const env = environment();
  const capture = env.api.sensors.capture({kind: 'accelerometer', durationMs: 5000, intervalMs: 100, maxSamples: 50});
  const captureRequest = env.sent.at(-1);
  const rejected = assert.rejects(capture, {code: 'CANCELLED'});
  const cancel = env.api.sensors.cancel();
  env.reply(env.sent.at(-1), {cancelled: true});
  assert.equal((await cancel).cancelled, true);
  env.window.JarvysNative.onmessage({data: JSON.stringify({v: 1, id: captureRequest.id, ok: false,
    error: {code: 'CANCELLED', message: 'Capture canceled'}})});
  await rejected;
  env.reply(captureRequest, {samples: [{elapsedMs: 0, values: [1, 2, 3]}]});
  const again = env.api.sensors.cancel();
  env.reply(env.sent.at(-1), {cancelled: false});
  assert.equal((await again).cancelled, false);
  assert.equal(env.timers.size, 0);
});

test('sensors cancellation has one bounded reserved slot when ordinary admission is saturated', async () => {
  const env = environment();
  const ordinary = Array.from({length: 16}, () => env.api.runtime.info());
  const closed = ordinary.map(promise => assert.rejects(promise, {code: 'PAGE_CLOSED'}));
  await assert.rejects(env.api.runtime.info(), {code: 'BUSY'});
  const firstCancel = env.api.sensors.cancel();
  assert.equal(env.sent.length, 17);
  assert.equal(env.timers.size, 17);
  assert.equal(env.sent.at(-1).method, 'sensors.cancel');
  await assert.rejects(env.api.sensors.cancel(), {code: 'BUSY'});
  await assert.rejects(env.api.runtime.info(), {code: 'BUSY'});
  assert.equal(env.sent.length, 17);
  env.reply(env.sent.at(-1), {cancelled: false});
  assert.equal((await firstCancel).cancelled, false);
  assert.equal(env.timers.size, 16);
  await assert.rejects(env.api.runtime.info(), {code: 'BUSY'});
  const secondCancel = env.api.sensors.cancel();
  assert.equal(env.sent.length, 18);
  assert.equal(env.timers.size, 17);
  env.reply(env.sent.at(-1), {cancelled: false});
  await secondCancel;
  env.handlers.get('pagehide')();
  await Promise.all(closed);
  assert.equal(env.timers.size, 0);
});

test('biometrics exposes three frozen methods with exact options and a bounded authentication timeout', async () => {
  const env = environment();
  assert.equal(Object.isFrozen(env.api.biometrics), true);
  assert.deepEqual(Object.keys(env.api.biometrics), ['availability', 'authenticate', 'cancel']);
  for (const method of ['availability', 'authenticate', 'cancel']) {
    for (const options of [undefined, {}, null, false, [], 'confirm',
      {reason: 'Confirm this local action'}, {reason: 'Confirmación 東京'},
      {reason: 1, credentialFallback: true, extra: 'native validation must see this'}]) {
      const pending = env.api.biometrics[method](options);
      assert.equal(typeof pending.then, 'function');
      const request = env.sent.at(-1);
      assert.equal(request.v, 1);
      assert.equal(request.method, 'biometrics.' + method);
      assert.deepEqual(request.args, method !== 'authenticate' && options === undefined ? {} : options);
      assert.equal(env.delays.at(-1), method === 'authenticate' ? 90000 : 30000);
      env.reply(request, {synthetic: true});
      assert.equal((await pending).synthetic, true);
      assert.equal(env.timers.size, 0);
    }
  }
});

test('biometrics native rejections never become authentication from a late reply', async () => {
  for (const method of ['availability', 'authenticate', 'cancel']) {
    for (const code of ['CAPABILITY_DENIED', 'INVALID_ARGUMENT', 'UNAVAILABLE', 'BUSY', 'CANCELLED', 'TIMEOUT', 'RATE_LIMITED', 'AUTHENTICATION_FAILED', 'NATIVE_ERROR']) {
      const env = environment();
      const pending = env.api.biometrics[method]({reason: 'Confirm this local action'});
      const request = env.sent.at(-1);
      const rejected = assert.rejects(pending, {name: 'JarvysError', code, message: 'Synthetic rejection'});
      env.window.JarvysNative.onmessage({data: JSON.stringify({v: 1, id: request.id, ok: false,
        error: {code, message: 'Synthetic rejection'}})});
      await rejected;
      env.reply(request, {authenticated: true, late: true});
      assert.equal(env.timers.size, 0);
    }
  }
});

test('biometrics missing bridge, timeout, and page closure never invent success', async () => {
  for (const method of ['availability', 'authenticate', 'cancel']) {
    await assert.rejects(environment(false).api.biometrics[method]({}), {code: 'BRIDGE_UNAVAILABLE'});
    for (const event of ['timeout', 'pagehide']) {
      const env = environment();
      const pending = env.api.biometrics[method]({reason: 'Confirm this local action'});
      const rejected = assert.rejects(pending, {code: event === 'timeout' ? 'TIMEOUT' : 'PAGE_CLOSED'});
      assert.equal(env.delays.at(-1), method === 'authenticate' ? 90000 : 30000);
      if (event === 'timeout') env.timers.values().next().value(); else env.handlers.get('pagehide')();
      await rejected;
      env.reply(env.sent.at(-1), {authenticated: true});
      if (event === 'pagehide') assert.equal(env.timers.size, 0);
    }
  }
});

test('biometrics cancel receipt is independent and cannot claim prompt closure or authentication', async () => {
  const env = environment();
  const authenticate = env.api.biometrics.authenticate({reason: 'Confirm this local action'});
  const authRequest = env.sent.at(-1);
  const rejected = assert.rejects(authenticate, {code: 'CANCELLED'});
  const cancel = env.api.biometrics.cancel();
  const cancelRequest = env.sent.at(-1);
  assert.notEqual(authRequest.id, cancelRequest.id);
  env.reply(cancelRequest, {cancelRequested: true, cancellationAcknowledged: false});
  assert.deepEqual(JSON.parse(JSON.stringify(await cancel)), {cancelRequested: true, cancellationAcknowledged: false});
  env.window.JarvysNative.onmessage({data: JSON.stringify({v: 1, id: authRequest.id, ok: false,
    error: {code: 'CANCELLED', message: 'Authentication canceled'}})});
  await rejected;
  env.reply(authRequest, {authenticated: true, simulated: false});
  assert.equal(env.timers.size, 0);
});

test('biometrics cancellation has its own bounded reserve alongside saturated sensor cancellation', async () => {
  const env = environment();
  const ordinary = Array.from({length: 16}, () => env.api.runtime.info());
  const closed = ordinary.map(promise => assert.rejects(promise, {code: 'PAGE_CLOSED'}));
  const sensorCancel = env.api.sensors.cancel();
  const sensorRequest = env.sent.at(-1);
  const biometricCancel = env.api.biometrics.cancel();
  const biometricRequest = env.sent.at(-1);
  assert.equal(env.sent.length, 18);
  assert.equal(env.timers.size, 18);
  await assert.rejects(env.api.runtime.info(), {code: 'BUSY'});
  await assert.rejects(env.api.biometrics.authenticate({reason: 'Confirm'}), {code: 'BUSY'});
  await assert.rejects(env.api.sensors.cancel(), {code: 'BUSY'});
  await assert.rejects(env.api.biometrics.cancel(), {code: 'BUSY'});
  env.reply(biometricRequest, {cancelRequested: false, cancellationAcknowledged: false});
  assert.deepEqual(JSON.parse(JSON.stringify(await biometricCancel)), {cancelRequested: false, cancellationAcknowledged: false});
  env.reply(sensorRequest, {cancelled: false});
  await sensorCancel;
  env.handlers.get('pagehide')();
  await Promise.all(closed);
  assert.equal(env.timers.size, 0);
});

test('per-ID biometric control rate limit preserves auth while global rate limit rejects everything', async () => {
  const env = environment();
  const auth = env.api.biometrics.authenticate({reason: 'Confirm this local action'});
  const authRequest = env.sent.at(-1);
  const cancel = env.api.biometrics.cancel();
  const cancelRequest = env.sent.at(-1);
  const cancelRejected = assert.rejects(cancel, {code: 'RATE_LIMITED'});
  env.window.JarvysNative.onmessage({data: JSON.stringify({v: 1, id: cancelRequest.id, ok: false,
    error: {code: 'RATE_LIMITED', message: 'Control quota reached'}})});
  await cancelRejected;
  assert.equal(env.timers.size, 1);
  const authRejected = assert.rejects(auth, {code: 'RATE_LIMITED'});
  env.window.JarvysNative.onmessage({data: JSON.stringify({v: 1, id: null, ok: false,
    error: {code: 'RATE_LIMITED', message: 'Global quota reached'}})});
  await authRejected;
  env.reply(authRequest, {authenticated: true});
  assert.equal(env.timers.size, 0);
});

 test('gyroscope capture forwards angular-velocity request and native metadata unchanged', async () => {
  const env = environment();
  const options = {kind: 'gyroscope', durationMs: 5000, intervalMs: 100, maxSamples: 50};
  const pending = env.api.sensors.capture(options);
  const request = env.sent.at(-1);
  assert.equal(request.method, 'sensors.capture');
  assert.deepEqual(request.args, options);
  env.reply(request, {kind: 'gyroscope', unit: 'rad/s', frame: 'device-natural', includesGravity: false, samples: [{elapsedMs: 0, values: [-1, 2, -3]}], mode: 'installed', simulated: false, reason: 'duration'});
  const result = await pending;
  assert.equal(result.unit, 'rad/s');
  assert.deepEqual(Array.from(result.samples[0].values), [-1, 2, -3]);
});

test('database backups preserve exact options and allow native five minute review plus callback time', async () => {
  for (const method of ['backupExport', 'backupImport']) {
    const env = environment();
    const options = method === 'backupExport'
      ? {version: 1, tables: ['notes'], filename: 'backup.json'}
      : {version: 1, tables: ['notes'], handle: 'a'.repeat(64), mode: 'replaceSelectedTables'};
    const promise = env.api.database[method](options);
    assert.equal(env.sent[0].method, 'database.' + method);
    assert.deepEqual(env.sent[0].args, options);
    assert.equal(env.delays[0], 310000);
    const rejected = assert.rejects(promise, error => error.code === 'TIMEOUT' &&
      /may have completed/.test(error.message) && /do not automatically retry/.test(error.message));
    env.timers.values().next().value();
    await rejected;
    env.reply(env.sent[0], {committed: true});
    assert.equal(env.sent.length, 1, 'timeout never retries');
  }
});

test('database backup wrappers never silently drop malformed or extra options', async () => {
  const env = environment();
  for (const method of ['backupExport', 'backupImport']) for (const options of [undefined, null, {}, {tables: ['notes'], extra: 'must reach native validator'}]) {
    const promise = env.api.database[method](options);
    const request = env.sent.at(-1);
    assert.deepEqual(request.args, options);
    env.reply(request, null); await promise;
  }
});

test('four cancellation methods retain one reserved control each beside sixteen ordinary requests', async () => {
  const env = environment();
  const ordinary = Array.from({length: 16}, () => env.api.runtime.info());
  const rejected = ordinary.map(promise => assert.rejects(promise, {code: 'PAGE_CLOSED'}));
  const controls = [];
  for (const group of ['sensors', 'biometrics', 'database', 'documents']) {
    controls.push(env.api[group].cancel());
    assert.equal(env.sent.at(-1).method, group + '.cancel');
    await assert.rejects(env.api[group].cancel(), {code: 'BUSY'});
  }
  assert.equal(env.sent.length, 20); assert.equal(env.timers.size, 20);
  await assert.rejects(env.api.database.backupExport({version: 1, tables: ['notes'], filename: 'backup.json'}), {code: 'BUSY'});
  for (let index = 0; index < controls.length; index++) {
    env.reply(env.sent[16 + index], {cancelRequested: true, rollbackConfirmed: false});
    assert.equal((await controls[index]).rollbackConfirmed, false);
  }
  assert.equal(env.timers.size, 16);
  env.handlers.get('pagehide')(); await Promise.all(rejected);
});


test('storage backup API preserves exact options with five minute review and explicit uncertainty', async () => {
  for (const method of ['backupExport', 'backupImport']) {
    for (const options of [undefined, null, {}, {version: 1, keys: ['settings'], filename: 'backup.json'},
      {version: 1, keys: ['settings'], handle: 'a'.repeat(64), mode: 'replaceSelectedKeys'},
      {keys: ['settings'], extra: 'native exact-key validator must see this'}]) {
      const env = environment();
      assert.equal(Object.isFrozen(env.api.storage), true);
      assert.deepEqual(Object.keys(env.api.storage), ['get', 'set', 'remove', 'list', 'listPage', 'backupExport', 'backupImport']);
      const pending = env.api.storage[method](options);
      const request = env.sent.at(-1);
      assert.equal(request.method, 'storage.' + method);
      assert.deepEqual(request.args, options);
      assert.equal(env.delays.at(-1), 310000);
      env.reply(request, {synthetic: true});
      assert.equal((await pending).synthetic, true);
    }
    const env = environment();
    const pending = env.api.storage[method]({version: 1, keys: ['settings']});
    const rejected = assert.rejects(pending, error => error.code === 'TIMEOUT' &&
      /may have completed/.test(error.message) && /do not automatically retry/.test(error.message));
    env.timers.values().next().value();
    await rejected;
    env.reply(env.sent.at(-1), {committed: true});
    assert.equal(env.sent.length, 1, 'timeout never replays a backup');
  }
});

test('storage backup denials, page closure and missing bridge never become late success', async () => {
  for (const method of ['backupExport', 'backupImport']) {
    await assert.rejects(environment(false).api.storage[method]({}), {code: 'BRIDGE_UNAVAILABLE'});
    for (const code of ['CAPABILITY_DENIED', 'BACKUP_DENIED', 'INVALID_ARGUMENT', 'UNAVAILABLE', 'CANCELLED', 'BACKUP_UNKNOWN', 'PAGE_CLOSED']) {
      const env = environment();
      const pending = env.api.storage[method]({version: 1, keys: ['settings']});
      const request = env.sent.at(-1);
      const rejected = assert.rejects(pending, {name: 'JarvysError', code});
      if (code === 'PAGE_CLOSED') env.handlers.get('pagehide')();
      else env.window.JarvysNative.onmessage({data: JSON.stringify({v: 1, id: request.id, ok: false,
        error: {code, message: 'Synthetic backup rejection'}})});
      await rejected;
      env.reply(request, {committed: true});
      assert.equal(env.timers.size, 0);
      assert.equal(env.sent.length, 1);
    }
  }
});

test('documents cancel can accompany storage-only backup under saturated ordinary admission', async () => {
  const env = environment();
  const backup = env.api.storage.backupImport({version: 1, keys: ['settings'], handle: 'a'.repeat(64), mode: 'insertOnly'});
  const backupRequest = env.sent.at(-1);
  const ordinary = [backup, ...Array.from({length: 15}, () => env.api.runtime.info())];
  const rejected = ordinary.map(promise => assert.rejects(promise, {code: 'PAGE_CLOSED'}));
  const cancel = env.api.documents.cancel();
  const cancelRequest = env.sent.at(-1);
  assert.equal(cancelRequest.method, 'documents.cancel');
  assert.notEqual(cancelRequest.id, backupRequest.id);
  assert.equal(env.sent.length, 17);
  await assert.rejects(env.api.documents.cancel(), {code: 'BUSY'});
  await assert.rejects(env.api.storage.backupExport({version: 1, keys: ['settings'], filename: 'backup.json'}), {code: 'BUSY'});
  env.reply(cancelRequest, {cancelRequested: true, rollbackConfirmed: false});
  assert.equal((await cancel).rollbackConfirmed, false);
  assert.equal(env.timers.size, 16, 'cancel receipt does not settle backup or invent rollback');
  env.handlers.get('pagehide')();
  await Promise.all(rejected);
});

test('network exposes separate native HTTP and ICMP APIs with exact arguments and bounded response timers', async () => {
  const env = environment();
  assert.equal(Object.isFrozen(env.api.network), true);
  assert.deepEqual(Object.keys(env.api.network), ['upload', 'download', 'request', 'probe', 'ping', 'cancel']);
  const cases = [
    ['request', {url: 'http://127.0.0.1:8080/', method: 'POST', body: 'developer payload', maxResponseBytes: 262144}],
    ['probe', {url: 'https://example.com/', method: 'HEAD', timeoutMs: 120000}],
    ['ping', {host: 'fe80::1%wlan0', family: 'ipv6', count: 1000, packetBytes: 65507, timeoutMs: 120000}],
    ['cancel', undefined]
  ];
  for (const [method, options] of cases) {
    const pending = env.api.network[method](options);
    const request = env.sent.at(-1);
    assert.equal(request.method, 'network.' + method);
    assert.deepEqual(request.args, options === undefined ? {} : options);
    assert.equal(env.delays.at(-1), 420000);
    env.reply(request, {synthetic: true}); await pending;
  }
  for (const method of ['request', 'probe', 'ping', 'cancel']) {
    for (const options of [null, false, [], 'example.com', {host: 'example.com', executable: '/system/bin/sh', extra: true}]) {
      const pending = env.api.network[method](options);
      assert.deepEqual(env.sent.at(-1).args, options, 'SDK must not strip fields before exact native validation');
      env.reply(env.sent.at(-1), null); await pending;
    }
  }
});

test('ICMP and HTTP results stay distinct and unsupported ping never becomes fabricated success', async () => {
  const env = environment();
  const receipts = [
    ['probe', {url: 'https://example.com/'}, {measurement: 'http_response_headers', icmp: false, reachable: true, status: 503, ok: false, latencyMs: 42}],
    ['ping', {host: 'example.com'}, {protocol: 'icmp', status: 'ok', summaryParsed: true, transmitted: 4, received: 3, packetLossPercent: 25, latencyMs: {min: 10, avg: 12, max: 14, source: 'utility_summary'}}],
    ['ping', {host: 'example.com'}, {protocol: 'icmp', status: 'unsupported', summaryParsed: false, exitCode: null}],
    ['ping', {host: 'example.com'}, {protocol: 'icmp', status: 'platform_denied', summaryParsed: false, exitCode: 2}],
    ['ping', {host: 'example.com'}, {protocol: 'icmp', status: 'unparsed', summaryParsed: false, exitCode: 0}]
  ];
  for (const [method, options, receipt] of receipts) {
    const pending = env.api.network[method](options); env.reply(env.sent.at(-1), receipt);
    const result = await pending;
    assert.deepEqual(JSON.parse(JSON.stringify(result)), receipt);
    if (method === 'ping' && !receipt.summaryParsed) {
      assert.equal(result.reachable, undefined); assert.equal(result.packetLossPercent, undefined); assert.equal(result.latencyMs, undefined);
    }
  }
});

test('network cancel bypasses a full multiview ping queue without inventing cancellation or rollback', async () => {
  const env = environment();
  const pings = Array.from({length: 16}, (_, i) => env.api.network.ping({host: 'view' + i + '.example'}));
  const requests = env.sent.slice();
  const rejected = pings.map(pending => assert.rejects(pending, {code: 'CANCELLED'}));
  await assert.rejects(env.api.network.ping({host: 'extra.example'}), {code: 'BUSY'});
  const cancel = env.api.network.cancel(); const cancelRequest = env.sent.at(-1);
  assert.equal(cancelRequest.method, 'network.cancel'); assert.equal(env.sent.length, 17);
  await assert.rejects(env.api.network.cancel(), {code: 'BUSY'});
  env.reply(cancelRequest, {cancelled: 16, rollbackConfirmed: false});
  assert.equal((await cancel).rollbackConfirmed, false);
  assert.equal(env.timers.size, 16, 'cancellation receipt does not fabricate each pending operation outcome');
  for (const request of requests) env.window.JarvysNative.onmessage({data: JSON.stringify({v: 1, id: request.id, ok: false, error: {code: 'CANCELLED'}})});
  await Promise.all(rejected);
  for (const request of requests) env.reply(request, {protocol: 'icmp', status: 'ok', reachable: true});
  assert.equal(env.timers.size, 0);
});

test('network denials timeout and page closure reject once without retrying target traffic', async () => {
  for (const method of ['request', 'probe', 'ping', 'cancel']) {
    await assert.rejects(environment(false).api.network[method]({}), {code: 'BRIDGE_UNAVAILABLE'});
    for (const code of ['CAPABILITY_DENIED', 'PERMISSION_DENIED', 'INVALID_ARGUMENT', 'CANCELLED', 'TIMEOUT', 'PAGE_CLOSED']) {
      const env = environment(); const pending = env.api.network[method]({host: 'example.com'});
      const request = env.sent.at(-1); const rejected = assert.rejects(pending, {code});
      if (code === 'PAGE_CLOSED') env.handlers.get('pagehide')();
      else if (code === 'TIMEOUT') env.timers.values().next().value();
      else env.window.JarvysNative.onmessage({data: JSON.stringify({v: 1, id: request.id, ok: false, error: {code}})});
      await rejected; env.reply(request, {late: true, reachable: true});
      assert.equal(env.sent.length, 1, 'SDK must never silently retry network effects');
    }
  }
});

test('notification and speech APIs are frozen and preserve native options and truthful receipts', async () => {
  const env = environment();
  assert.equal(Object.isFrozen(env.api.notifications), true); assert.equal(Object.isFrozen(env.api.speech), true);
  assert.deepEqual(Object.keys(env.api.notifications), ['schedule','resumeScheduled','scheduleStatus','cancelScheduled','forgetScheduled','pollActions','ackAction','generationStatus','forgetGeneration','requestExactAlarmAccess','status', 'channel', 'show', 'cancel', 'requestPermission']);
  assert.deepEqual(Object.keys(env.api.speech), ['status', 'voices', 'speak', 'stop']);
  const cases = [
    ['notifications', 'status', undefined, {status: 'permission_required'}],
    ['notifications', 'channel', {id: 'updates', name: 'Updates', importance: 'high'}, {status: 'ready', channelCreated: true}],
    ['notifications', 'show', {id: 1, channelId: 'updates', title: 'Ready', body: 'Test'}, {status: 'accepted', id: 1}],
    ['notifications', 'cancel', {id: 1}, {status: 'cancel_requested', id: 1}],
    ['notifications', 'requestPermission', undefined, {status: 'permission_required'}],
    ['speech', 'status', undefined, {status: 'idle', audibilityVerified: false}],
    ['speech', 'voices', {engine: 'developer.engine'}, {voices: []}],
    ['speech', 'speak', {text: 'Hello 🌱', rate: 1.2}, {status: 'completed', completionMeans: 'engine_reported_completion', audibilityVerified: false}],
    ['speech', 'stop', undefined, {status: 'stop_requested', cancelled: true, emittedAudioCannotBeRecalled: true}]
  ];
  for (const [group, method, options, receipt] of cases) {
    const pending = env.api[group][method](options); const request = env.sent.at(-1);
    assert.equal(request.method, group + '.' + method); assert.deepEqual(request.args, options === undefined ? {} : options);
    assert.equal(env.delays.at(-1), method === 'speak' ? 86400000 : 420000);
    env.reply(request, receipt); assert.deepEqual(JSON.parse(JSON.stringify(await pending)), receipt);
  }
});

test('speech stop has independent bounded control admission while ordinary requests are saturated', async () => {
  const env = environment();
  const requests = Array.from({length: 16}, () => env.api.runtime.info());
  const rejected = requests.map(pending => assert.rejects(pending, {code: 'PAGE_CLOSED'}));
  const stop = env.api.speech.stop(); assert.equal(env.sent.at(-1).method, 'speech.stop');
  await assert.rejects(env.api.speech.stop(), {code: 'BUSY'});
  env.reply(env.sent.at(-1), {status: 'stop_requested', cancelled: false}); assert.equal((await stop).cancelled, false);
  assert.equal(env.timers.size, 16); env.handlers.get('pagehide')(); await Promise.all(rejected);
});


test('generic secrets and incoming-share APIs preserve typed options without retrying', async () => {
  for (const [group, names] of [['secrets', ['put','capture','get','list','describe','remove','reset','request']], ['incomingShare', ['list','take','discard']]]) {
    for (const name of names) {
      const env = environment(); assert.equal(Object.isFrozen(env.api[group]), true);
      const args = ['list','reset'].includes(name) ? undefined : {id:'synthetic', options:{nested:true}};
      const pending = env.api[group][name](args); const request = env.sent.at(-1);
      assert.equal(request.method, group+'.'+name); assert.deepEqual(request.args, args || {});
      assert.equal(env.delays.at(-1), 600000);
      const rejected = assert.rejects(pending, {code:'PERMISSION_DENIED'});
      env.window.JarvysNative.onmessage({data:JSON.stringify({v:1,id:request.id,ok:false,error:{code:'PERMISSION_DENIED'}})});
      await rejected; env.reply(request,{late:true}); assert.equal(env.sent.length,1);
    }
  }
});

test('notification scheduling and action inbox controls preserve requests and unknown outcomes', async () => {
  const methods = ['schedule','resumeScheduled','scheduleStatus','cancelScheduled','forgetScheduled','pollActions','ackAction','generationStatus','forgetGeneration','requestExactAlarmAccess'];
  for (const method of methods) {
    const env = environment();
    const options = ['pollActions','requestExactAlarmAccess'].includes(method) ? undefined : {scheduleId:'synthetic',generationId:'a'.repeat(64),acknowledgeDeliveryUnknown:true};
    const pending = env.api.notifications[method](options); const request = env.sent.at(-1);
    assert.equal(request.method, 'notifications.'+method); assert.deepEqual(request.args, options || {});
    const outcome = {status:'delivery_unknown', displayed:false, receiptVerified:false};
    env.reply(request,outcome); assert.deepEqual(JSON.parse(JSON.stringify(await pending)),outcome);
    assert.equal(env.sent.length,1);
  }
});


test('document HTTP transfer wrappers preserve long bounds and never infer completion', async () => {
  for (const method of ['upload','download']) {
    const env = environment(); const options = {url:'https://example.com',handle:'a'.repeat(64),maxBytes:4294967296,offset:0};
    const pending = env.api.network[method](options); const request=env.sent.at(-1);
    assert.equal(request.method,'network.'+method); assert.deepEqual(request.args,options); assert.equal(env.delays.at(-1),86700000);
    const outcome={complete:false,eofConfirmed:false,partialEffectsPossible:true,providerCommitConfirmed:false};
    env.reply(request,outcome); assert.deepEqual(JSON.parse(JSON.stringify(await pending)),outcome); assert.equal(env.sent.length,1);
  }
});


test('generic hardware and persistent-service SDK APIs forward data and reserve cleanup capacity', async () => {
  const env = environment();
  for (const [group, method, args] of [
    ['nfc','write',{records:[{tnf:1,typeHex:'54',idHex:'',payloadHex:'02656e4869'}]}],
    ['bluetooth','write',{connectionId:'c',attributeId:'a',valueBase64:'AQ==',writeType:'response'}],
    ['widgets','update',{widgetIds:[7],model:{root:{type:'text',text:'Generic'}}}],
    ['jobs','enqueueHttp',{jobId:'j',request:{url:'https://example.com/'}}]
  ]) {
    const promise=env.api[group][method](args);
    assert.deepEqual(env.sent.at(-1).args,args);
    assert.equal(env.delays.at(-1),620000);
    env.reply(env.sent.at(-1),{accepted:true}); await promise;
  }
  const pending=Array.from({length:16},()=>env.api.device.info());
  for(const [group,method,args] of [['nfc','cancel',{}],['bluetooth','disconnect',{connectionId:'c'}],['jobs','cancel',{jobId:'j'}]]) {
    const promise=env.api[group][method](args); assert.equal(env.sent.at(-1).method,group+'.'+method);
    env.reply(env.sent.at(-1),{cancelled:true}); await promise;
  }
  env.sent.filter(row=>row.method==='device.info').forEach(row=>env.reply(row,{}));
  await Promise.all(pending);
});

test('storage keyset pages preserve optional cursor while legacy list remains argument-free', async () => {
  const env=environment();
  const first=env.api.storage.listPage({afterKey:'a',limit:17});
  assert.deepEqual(env.sent.at(-1).args,{afterKey:'a',limit:17});
  env.reply(env.sent.at(-1),{keys:['b'],hasMore:false,nextAfterKey:null,snapshotAcrossPages:false});
  assert.equal((await first).snapshotAcrossPages,false);
  const old=env.api.storage.list(); assert.deepEqual(env.sent.at(-1).args,{});
  env.reply(env.sent.at(-1),['a','b']); assert.deepEqual(Array.from(await old),['a','b']);
});
