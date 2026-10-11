'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const model = require('../web/model.js');
const AT = '2026-10-11T15:00:00.000Z';
const nativeError = (code, message = code) => Object.assign(new Error(message), {code});
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return {promise, resolve, reject}; };
function receipt(extra = {}) {
  return {protocol: 'icmp', host: 'example.test', family: 'auto', status: 'ok', elapsedMs: 3020,
    exitCode: 0, packetWaitMs: 1000, summaryParsed: true, transmitted: 4, received: 4,
    packetLossPercent: 0, reachable: true, latencyMs: {min: 3, avg: 4, max: 5, source: 'utility_summary'},
    output: 'TEST FIXTURE ONLY', outputTruncated: false, ...extra};
}
const ready = () => ({status: 'ready', ready: true, permission: 'granted', channelStatus: 'ready'});
function fixture(overrides = {}) {
  const calls = [], stored = new Map();
  function method(name, implementation) {
    return async (...args) => { calls.push({name, args}); return implementation(...args); };
  }
  const api = {
    runtime: {info: method('runtime.info', () => ({mode: 'installed', nativePingProtocol: 'icmp'}))},
    storage: {
      get: method('storage.get', key => stored.has(key) ? stored.get(key) : null),
      set: method('storage.set', (key, value) => { stored.set(key, value); return null; }),
      remove: method('storage.remove', key => { stored.delete(key); return null; })},
    network: {ping: method('network.ping', () => receipt()), cancel: method('network.cancel', () => ({cancelled: 1, rollbackConfirmed: false}))},
    notifications: {
      status: method('notifications.status', ready), requestPermission: method('notifications.requestPermission', ready),
      channel: method('notifications.channel', ready), show: method('notifications.show', () => ({status: 'accepted', id: 1})),
      cancel: method('notifications.cancel', () => ({status: 'cancel_requested', id: 1}))},
    haptics: {perform: method('haptics.perform', () => true)},
    contacts: {pick: method('contacts.pick', () => ({kind: 'phone', value: '+15550100'}))},
    sms: {compose: method('sms.compose', () => ({deliveryConfirmed: false}))},
    browser: {open: method('browser.open', () => ({deliveryConfirmed: false}))},
    share: {text: method('share.text', () => ({chooserOpened: true, deliveryConfirmed: false}))}
  };
  for (const [name, implementation] of Object.entries(overrides)) {
    const [group, key] = name.split('.'); api[group][key] = method(name, implementation);
  }
  return {api, calls, stored, app: model.createController(api, null, () => AT), named: name => calls.filter(call => call.name === name)};
}

test('startup reads only local state/metadata; no probe, permission or external action', async () => {
  const f = fixture(); await f.app.load();
  assert.equal(f.app.state.canPing, true);
  assert.deepEqual(f.calls.map(call => call.name), ['runtime.info', 'storage.get', 'storage.get']);
  assert.equal(f.app.state.settings.notify, false);
});
test('installed run uses exact bounded ICMP arguments and persists a summary without output', async () => {
  const f = fixture(); await f.app.load(); await f.app.run('  example.test  ');
  assert.deepEqual(f.named('network.ping')[0].args, [{host: 'example.test', family: 'auto', count: 4,
    timeoutMs: 10000, packetTimeoutMs: 1000, intervalMs: 1000, packetBytes: 56, maxOutputBytes: 4096}]);
  const saved = JSON.parse(f.stored.get(model.HISTORY_KEY));
  assert.equal(saved.entries[0].latencyMs.avg, 4);
  assert.equal(saved.entries[0].output, undefined);
  assert.equal(f.app.state.output, 'TEST FIXTURE ONLY');
  assert.equal(f.app.state.busy, false);
  assert.equal(f.named('notifications.show').length, 0);
});
test('history reloads through supported string storage and stays capped at 30', async () => {
  const f = fixture(); await f.app.load();
  for (let i = 0; i < 32; i++) await f.app.run('example.test');
  const reloaded = model.createController(f.api, null, () => AT); await reloaded.load();
  assert.equal(reloaded.state.history.length, 30);
  assert.equal(reloaded.state.history[0].received, 4);
  assert.equal(reloaded.state.output, '');
  assert.equal(new TextEncoder().encode(f.stored.get(model.HISTORY_KEY)).length < 65536, true);
});
test('partial/no-summary results never invent packet loss, counts or RTT', async () => {
  const f = fixture({'network.ping': () => ({protocol: 'icmp', host: 'example.test', family: 'auto', status: 'partial',
    elapsedMs: 10, exitCode: 1, packetWaitMs: 1000, summaryParsed: false, reachable: true, replySamplesParsed: 1})});
  await f.app.load(); const result = await f.app.run('example.test');
  assert.equal(result.reachable, true); assert.equal(result.received, undefined); assert.equal(result.latencyMs, undefined);
  assert.doesNotMatch(model.summary(result), /% loss|avg/);
  assert.match(model.report(result), /RTT: not available/);
  assert.equal(model.decodeHistory(f.stored.get(model.HISTORY_KEY))[0].replySamplesParsed, 1);
});
for (const status of ['unsupported', 'platform_denied', 'unparsed', 'dns_error', 'failed']) {
  test(status + ' stays explicit with unknown reachability and no fabricated statistics', async () => {
    const f = fixture({'network.ping': () => ({protocol: 'icmp', host: 'example.test', family: 'auto', status,
      elapsedMs: 1, exitCode: null, packetWaitMs: 1000, summaryParsed: false})});
    await f.app.load(); const value = await f.app.run('example.test');
    assert.equal(value.status, status); assert.equal(value.reachable, undefined); assert.equal(value.latencyMs, undefined);
    assert.match(model.report(value), /Reachability: unknown/);
  });
}
test('real zero-reply summary records measured loss and does not invent latency', async () => {
  const result = receipt({status: 'no_reply', exitCode: 1, received: 0, packetLossPercent: 100, reachable: false});
  delete result.latencyMs;
  const f = fixture({'network.ping': () => result}); await f.app.load();
  const entry = await f.app.run('example.test'); assert.equal(entry.received, 0); assert.equal(entry.latencyMs, undefined);
  assert.match(model.report(entry), /no ICMP reply observed/);
});
test('preview and older installed runtime cannot invoke ping or setup notifications', async () => {
  const preview = fixture({'runtime.info': () => ({mode: 'preview', nativePingProtocol: 'icmp'})}); await preview.app.load();
  await assert.rejects(preview.app.run('example.test'), {code: 'UNAVAILABLE'});
  await assert.rejects(preview.app.setupNotifications(), {code: 'UNAVAILABLE'});
  assert.equal(preview.named('network.ping').length, 0); assert.equal(preview.named('notifications.requestPermission').length, 0);
  const old = fixture({'runtime.info': () => ({mode: 'installed'})}); await old.app.load();
  await assert.rejects(old.app.run('example.test'), {code: 'UNAVAILABLE'});
  assert.equal(old.named('network.ping').length, 0);
});
test('missing bridge yields a truthful unavailable screen and no fabricated history', async () => {
  const app = model.createController(undefined); await app.load();
  assert.equal(app.state.ready, true); assert.equal(app.state.canPing, false); assert.deepEqual(app.state.history, []);
  assert.equal(app.state.historyWritable, false);
});
test('HTTP-shaped or inconsistent responses become errors instead of ICMP measurements', async () => {
  for (const response of [{measurement: 'http_response_headers', icmp: false, reachable: true}, receipt({received: 99}), receipt({latencyMs: {min: 9, avg: 2, max: 1}})]) {
    const f = fixture({'network.ping': () => response}); await f.app.load(); const entry = await f.app.run('example.test');
    assert.equal(entry.status, 'error'); assert.equal(entry.error.code, 'INVALID_RESULT'); assert.equal(entry.latencyMs, undefined);
  }
});
test('native timeout/denial errors are recorded once without retry or fabricated zeros', async () => {
  for (const code of ['TIMEOUT', 'PERMISSION_DENIED', 'CAPABILITY_DENIED', 'CANCELLED']) {
    const f = fixture({'network.ping': () => { throw nativeError(code); }}); await f.app.load();
    const entry = await f.app.run('example.test');
    assert.equal(entry.error.code, code); assert.equal(entry.received, undefined); assert.equal(f.named('network.ping').length, 1);
  }
});
test('cancellation keeps the original error and does not replay or claim rollback', async () => {
  const ping = deferred();
  const f = fixture({'network.ping': () => ping.promise, 'network.cancel': () => { ping.reject(nativeError('CANCELLED')); return {cancelled: 1, rollbackConfirmed: false}; }});
  await f.app.load(); const running = f.app.run('example.test'); await f.app.cancel(); await running;
  assert.equal(f.app.state.selected.error.code, 'CANCELLED'); assert.equal(f.named('network.ping').length, 1);
  assert.equal(f.app.state.busy, false); assert.match(model.report(f.app.state.selected), /cannot be recalled/);
});
test('cancel/result race preserves actual result and blocks a new run until cancellation settles', async () => {
  const ping = deferred(), cancel = deferred();
  const f = fixture({'network.ping': () => ping.promise, 'network.cancel': () => cancel.promise});
  await f.app.load(); const running = f.app.run('example.test'); const cancelling = f.app.cancel();
  await f.app.cancel(); // duplicate cancel is suppressed
  ping.resolve(receipt()); await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.app.state.selected.status, 'ok'); assert.equal(f.app.state.busy, true);
  await assert.rejects(f.app.run('other.test'), {code: 'BUSY'});
  cancel.resolve({cancelled: 0, rollbackConfirmed: false}); await cancelling; await running;
  assert.equal(f.app.state.selected.status, 'ok'); assert.equal(f.named('network.cancel').length, 1); assert.equal(f.app.state.busy, false);
});
test('failed cancellation remains unconfirmed while original ping can still complete', async () => {
  const ping = deferred(); const f = fixture({'network.ping': () => ping.promise, 'network.cancel': () => { throw nativeError('UNAVAILABLE'); }});
  await f.app.load(); const running = f.app.run('example.test'); await f.app.cancel();
  assert.equal(f.app.state.pingPending, true); assert.match(f.app.state.warnings.join(' '), /not confirmed/);
  ping.resolve(receipt()); await running; assert.equal(f.app.state.selected.status, 'ok');
});
test('repeated run and mutations are rejected while a native operation is pending', async () => {
  const pending = deferred(); const f = fixture({'network.ping': () => pending.promise}); await f.app.load();
  const run = f.app.run('example.test');
  await assert.rejects(f.app.run('other.test'), {code: 'BUSY'});
  await assert.rejects(f.app.clearHistory(), {code: 'BUSY'});
  await assert.rejects(f.app.saveSettings(model.DEFAULTS), {code: 'BUSY'});
  await assert.rejects(f.app.share('chooser'), {code: 'BUSY'});
  pending.resolve(receipt()); await run; assert.equal(f.named('network.ping').length, 1);
});
test('corrupt history is preserved until explicit clear, and failed writes are not called saved', async () => {
  const f = fixture(); f.stored.set(model.HISTORY_KEY, '{bad history'); await f.app.load(); await f.app.run('example.test');
  assert.equal(f.stored.get(model.HISTORY_KEY), '{bad history'); assert.equal(f.app.state.historyWritable, false);
  assert.match(f.app.state.message, /Save not confirmed/);
  await f.app.clearHistory(); assert.equal(f.stored.has(model.HISTORY_KEY), false); assert.equal(f.app.state.historyWritable, true);
  const denied = fixture({'storage.set': () => { throw nativeError('STORAGE_ERROR'); }}); await denied.app.load(); await denied.app.run('example.test');
  assert.match(denied.app.state.message, /Save not confirmed/); assert.equal(denied.named('storage.set').length, 1);
});
test('failed clear preserves history and failed settings save preserves active settings', async () => {
  const f = fixture({'storage.remove': () => { throw nativeError('STORAGE_ERROR'); }}); await f.app.load(); await f.app.run('example.test');
  await assert.rejects(f.app.clearHistory(), {code: 'STORAGE_ERROR'}); assert.equal(f.app.state.history.length, 1);
  f.api.storage.set = async () => { throw nativeError('STORAGE_ERROR'); };
  await assert.rejects(f.app.saveSettings({...model.DEFAULTS, notify: true}), {code: 'STORAGE_ERROR'});
  assert.equal(f.app.state.settings.notify, false);
});
test('notification opt-in does not request Android permission on load/save/run', async () => {
  const f = fixture({'notifications.status': () => ({status: 'permission_required', permission: 'not_granted', channelStatus: 'missing', ready: false})});
  await f.app.load(); await f.app.saveSettings({...model.DEFAULTS, notify: true}); await f.app.run('example.test');
  assert.equal(f.named('notifications.requestPermission').length, 0); assert.equal(f.named('notifications.show').length, 0);
  assert.match(f.app.state.warnings.join(' '), /No notification posted/);
});
test('explicit notification setup requests OS permission and preserves a denied result', async () => {
  const f = fixture({'notifications.requestPermission': () => ({status: 'permission_required', permission: 'not_granted', channelStatus: 'missing', ready: false})});
  await f.app.load(); await f.app.setupNotifications();
  assert.equal(f.named('notifications.requestPermission').length, 1); assert.equal(f.named('notifications.channel').length, 0);
  assert.equal(f.named('notifications.show').length, 0); assert.match(f.app.state.message, /not granted/);
});
test('granted setup, own channel/show/cancel and haptics use only supported methods', async () => {
  const f = fixture(); await f.app.load(); await f.app.setupNotifications();
  assert.equal(f.named('notifications.channel')[0].args[0].id, model.CHANNEL);
  await f.app.saveSettings({...model.DEFAULTS, notify: true, haptics: true}); await f.app.run('example.test');
  assert.deepEqual(f.named('haptics.perform')[0].args, ['tap']);
  assert.equal(f.named('notifications.show')[0].args[0].id, 1);
  assert.equal(f.named('notifications.show')[0].args[0].channelId, model.CHANNEL);
  assert.match(f.app.state.notificationInfo, /unverified/);
  await f.app.cancelNotification(); assert.deepEqual(f.named('notifications.cancel')[0].args, [{id: 1}]);
});
test('suppressed haptics and rejected notification receipts never imply performed/delivered', async () => {
  const f = fixture({'haptics.perform': () => false, 'notifications.show': () => ({status: 'unknown'})});
  await f.app.load(); await f.app.saveSettings({...model.DEFAULTS, notify: true, haptics: true}); await f.app.run('example.test');
  assert.match(f.app.state.warnings.join(' '), /not reported performed/); assert.match(f.app.state.warnings.join(' '), /INVALID_RESULT/);
});
test('contact selection does not normalize, persist or send; edits are required for formatted numbers', async () => {
  const f = fixture({'contacts.pick': () => ({kind: 'phone', value: '+1 (555) 0100'})}); await f.app.load();
  const selected = await f.app.pickContact(); assert.equal(selected, '+1 (555) 0100');
  assert.deepEqual(f.named('contacts.pick')[0].args, [{kind: 'phone'}]); assert.equal(f.named('sms.compose').length, 0);
  await f.app.run('example.test'); await assert.rejects(f.app.share('sms', selected), {code: 'INVALID_PHONE'});
  assert.doesNotMatch(f.stored.get(model.HISTORY_KEY), /555/);
});
test('SMS, bounded WhatsApp HTTPS and generic chooser share only the selected summary', async () => {
  const f = fixture(); await f.app.load(); await f.app.run('example.test');
  await f.app.share('sms', '+15550100'); await f.app.share('whatsapp', '+15550100'); await f.app.share('chooser');
  assert.deepEqual(Object.keys(f.named('sms.compose')[0].args[0]).sort(), ['body', 'number']);
  const url = f.named('browser.open')[0].args[0].url;
  assert.equal(url.startsWith('https://wa.me/15550100?text='), true); assert.equal(url.length <= 2048, true);
  assert.match(decodeURIComponent(url), /ICMP/); assert.doesNotMatch(url, /TEST FIXTURE/);
  assert.equal(new URL(url).searchParams.get('text'), model.report(f.app.state.selected).replace(/\s+/g, ' '));
  // Mirrors BrowserUrl's exact forbidden percent-escape bytes. This must not
  // regress to ordinary encodeURIComponent(multilineText), which native rejects.
  for (const escape of url.matchAll(/%([0-9A-Fa-f]{2})/g)) {
    const byte = parseInt(escape[1], 16);
    assert.equal(byte > 0x20 && byte !== 0x7f && byte !== 0x5c, true);
  }
  assert.equal(f.named('share.text')[0].args[0].text, model.report(f.app.state.selected));
  assert.match(f.app.state.message, /sending and delivery are not confirmed/);
});
test('contact/compose rejection stays explicit without retry or fallback external transmission', async () => {
  const f = fixture({'contacts.pick': () => { throw nativeError('CANCELLED'); }, 'sms.compose': () => { throw nativeError('UNAVAILABLE'); }});
  await f.app.load(); await assert.rejects(f.app.pickContact(), {code: 'CANCELLED'}); await f.app.run('example.test');
  await assert.rejects(f.app.share('sms', '+15550100'), {code: 'UNAVAILABLE'});
  assert.equal(f.named('sms.compose').length, 1); assert.equal(f.named('browser.open').length, 0); assert.equal(f.named('share.text').length, 0);
});
test('forms validate conservative limits/host/number without performing any network lookup', () => {
  for (const host of ['https://example.test', 'host;id', 'host name', '', 'host/path', 'a@host']) assert.throws(() => model.hostInput(host), {code: 'INVALID_HOST'});
  for (const host of ['example.test', '192.0.2.1', '[2001:db8::1]', 'fe80::1%wlan0', 'münich.test']) assert.equal(model.hostInput(host), host);
  for (const value of [0, 21, 1.5, Infinity]) assert.throws(() => model.settings({...model.DEFAULTS, count: value}), {code: 'INVALID_SETTINGS'});
  assert.throws(() => model.phone('15550100', true), {code: 'INVALID_PHONE'});
  assert.throws(() => model.phone('+0123', true), {code: 'INVALID_PHONE'});
  assert.throws(() => model.whatsappUrl('+15550100', {at: AT, host: 'x'.repeat(3000), status: 'unsupported'}), {code: 'TOO_LARGE'});
});
test('UTF-8 history trims oldest entries to the native value limit, without losing the latest error', async () => {
  const f = fixture({'network.ping': () => { throw nativeError('INVALID_ARGUMENT'); }}); await f.app.load();
  for (let i = 0; i < 30; i++) await f.app.run('界'.repeat(1020));
  assert.equal(f.app.state.history.length < 30, true);
  assert.equal(f.app.state.history[0].error.code, 'INVALID_ARGUMENT');
  assert.equal(new TextEncoder().encode(f.stored.get(model.HISTORY_KEY)).length <= 65536, true);
  assert.match(f.app.state.message, /Saved locally/);
});
test('example assets use packaged paths, exact capabilities, no inline scripts and three wired views', () => {
  const base = path.resolve(__dirname, '..');
  const config = JSON.parse(fs.readFileSync(path.join(base, 'factory.json'), 'utf8'));
  assert.deepEqual(config.capabilities, ['storage', 'network', 'notifications', 'haptics', 'contacts', 'sms', 'browser', 'share']);
  assert.equal(config.documentBroker, undefined); assert.equal(config.webDir, 'web');
  const html = fs.readFileSync(path.join(base, 'web/index.html'), 'utf8');
  const js = fs.readFileSync(path.join(base, 'web/app.js'), 'utf8');
  assert.equal(html.indexOf('/factory-sdk.js') < html.indexOf('/www/model.js'), true);
  assert.doesNotMatch(html, /<script\b(?![^>]*\bsrc=)|\son(?:click|submit|load)=/i);
  for (const view of ['main', 'history', 'settings']) assert.match(html, new RegExp('id="view-' + view + '"'));
  for (const match of js.matchAll(/byId\('([a-z-]+)'\)/g)) assert.match(html, new RegExp('id="' + match[1] + '"'));
  const shipped = ['model.js', 'app.js'].map(file => fs.readFileSync(path.join(base, 'web', file), 'utf8')).join('\n');
  assert.doesNotMatch(shipped, /\bfetch\s*\(|XMLHttpRequest|network\.probe\s*\(|setInterval\s*\(|TEST FIXTURE/);
  assert.match(js, /addEventListener\('popstate'/); assert.match(js, /addEventListener\('hashchange'/);
});
