/* App-owned example logic. The frozen, runtime-owned Jarvys SDK is never replaced. */
(function (root) {
  'use strict';
  const HISTORY_KEY = 'ping.history.v1', SETTINGS_KEY = 'ping.settings.v1';
  const CHANNEL = 'ping-results', NOTIFICATION_ID = 1, MAX_HISTORY = 30;
  const DEFAULTS = Object.freeze({family: 'auto', count: 4, timeoutMs: 10000,
    packetTimeoutMs: 1000, intervalMs: 1000, packetBytes: 56, notify: false, haptics: false});
  const STATUSES = ['ok', 'no_reply', 'partial', 'unparsed', 'unsupported', 'platform_denied', 'dns_error', 'failed'];
  const LABELS = {ok: 'ICMP replies received', no_reply: 'No ICMP replies', partial: 'Partial ICMP evidence',
    unparsed: 'Utility output could not be interpreted', unsupported: 'ICMP utility or options unsupported',
    platform_denied: 'Android denied the ICMP utility', dns_error: 'Host lookup failed', failed: 'ICMP utility failed'};
  const finite = value => typeof value === 'number' && Number.isFinite(value) && value >= 0;
  function error(code, message) { return Object.assign(new Error(message), {code}); }
  function errorText(value) { return String(value && value.code || 'ERROR') + ': ' + String(value && value.message || 'Operation failed.').slice(0, 240); }
  function settings(value) {
    if (!value || !['auto', 'ipv4', 'ipv6'].includes(value.family)) throw error('INVALID_SETTINGS', 'Choose a supported address family.');
    const out = {family: value.family};
    for (const [key, min, max] of [['count', 1, 20], ['timeoutMs', 1000, 60000],
      ['packetTimeoutMs', 1000, 10000], ['intervalMs', 200, 5000], ['packetBytes', 0, 1400]]) {
      if (!Number.isInteger(value[key]) || value[key] < min || value[key] > max) throw error('INVALID_SETTINGS', 'Check ' + key + ' and its limits.');
      out[key] = value[key];
    }
    if (typeof value.notify !== 'boolean' || typeof value.haptics !== 'boolean') throw error('INVALID_SETTINGS', 'Feedback options must be true or false.');
    return Object.assign(out, {notify: value.notify, haptics: value.haptics});
  }
  function hostInput(input) {
    const host = String(input).trim();
    if (!host || host.length > 1024 || /[\s\/@?#\\;&|<>`$"'=()]/.test(host))
      throw error('INVALID_HOST', 'Enter a domain or IP only, without a URL, spaces or command.');
    return host; // Native code performs authoritative DNS/IP/family syntax validation, without lookup here.
  }
  function entryFromResult(value, at) {
    if (!value || value.protocol !== 'icmp' || !STATUSES.includes(value.status) ||
        typeof value.host !== 'string' || !value.host || value.host.length > 1024 ||
        !['auto', 'ipv4', 'ipv6'].includes(value.family) || !finite(value.elapsedMs) ||
        !(value.exitCode === null || Number.isInteger(value.exitCode)) ||
        typeof value.summaryParsed !== 'boolean' || !finite(value.packetWaitMs))
      throw error('INVALID_RESULT', 'The runtime did not return a recognized ICMP result.');
    const entry = {at, protocol: 'icmp', host: value.host, family: value.family,
      status: value.status, elapsedMs: value.elapsedMs, exitCode: value.exitCode,
      packetWaitMs: value.packetWaitMs, summaryParsed: value.summaryParsed};
    if (value.summaryParsed) {
      if (!Number.isInteger(value.transmitted) || value.transmitted < 1 ||
          !Number.isInteger(value.received) || value.received < 0 || value.received > value.transmitted ||
          !finite(value.packetLossPercent) || value.packetLossPercent > 100 ||
          value.reachable !== (value.received > 0)) throw error('INVALID_RESULT', 'Inconsistent ICMP summary.');
      Object.assign(entry, {transmitted: value.transmitted, received: value.received,
        packetLossPercent: value.packetLossPercent, reachable: value.reachable});
    } else if (value.reachable === true && Number.isInteger(value.replySamplesParsed) && value.replySamplesParsed > 0) {
      entry.reachable = true;
      entry.replySamplesParsed = value.replySamplesParsed;
    }
    if (value.latencyMs !== undefined) {
      const rtt = value.latencyMs;
      if (entry.reachable !== true || !rtt || rtt.source !== 'utility_summary' ||
          !finite(rtt.min) || !finite(rtt.avg) || !finite(rtt.max) || rtt.min > rtt.avg || rtt.avg > rtt.max)
        throw error('INVALID_RESULT', 'Inconsistent measured RTT.');
      entry.latencyMs = {min: rtt.min, avg: rtt.avg, max: rtt.max, source: rtt.source};
    }
    return entry;
  }
  function summary(entry) {
    if (!entry) return 'No result selected.';
    let text = entry.host + ' · ICMP · ' + (entry.status === 'error' ? entry.error.code : LABELS[entry.status]);
    if (entry.summaryParsed) text += ' · ' + entry.received + '/' + entry.transmitted + ' replies · ' + entry.packetLossPercent + '% loss';
    if (entry.latencyMs) text += ' · avg ' + entry.latencyMs.avg + ' ms';
    return text;
  }
  function report(entry) {
    if (!entry) throw error('NO_RESULT', 'Run a ping or select a history entry first.');
    return 'Ping Notebook\n' + entry.at + '\n' + summary(entry) +
      (entry.latencyMs ? '\nRTT min/avg/max: ' + [entry.latencyMs.min, entry.latencyMs.avg, entry.latencyMs.max].join('/') + ' ms' : '\nRTT: not available') +
      (entry.status === 'error' ? '\nNo measurement result returned. Previously sent packets cannot be recalled.' :
        '\nReachability: ' + (entry.reachable === true ? 'ICMP reply observed' : entry.reachable === false ? 'no ICMP reply observed' : 'unknown'));
  }
  function phone(value, international) {
    // Contacts return an unchanged selected datum, not a normalized/dialable number.
    const number = String(value).trim();
    if (!(international ? /^\+[1-9][0-9]{1,14}$/ : /^\+?[0-9]{1,15}$/).test(number))
      throw error('INVALID_PHONE', international ? 'Review the number: use +country code and digits for WhatsApp.' : 'Review the number: use only digits and an optional leading + for SMS.');
    return number;
  }
  function whatsappUrl(number, entry) {
    // BrowserUrl rejects escaped whitespace/backslashes. Use one line and the
    // form-query '+' space encoding, never a forbidden %20 or %0A escape.
    const text = report(entry).replace(/\s+/g, ' ');
    if (/[\x00-\x1f\x7f\\]/.test(text)) throw error('INVALID_SHARE', 'This summary contains characters unsupported by browser links. Use the chooser.');
    const query = encodeURIComponent(text).replace(/%20/g, '+');
    const url = 'https://wa.me/' + phone(number, true).slice(1) + '?text=' + query;
    if (url.length > 2048) throw error('TOO_LARGE', 'This summary is too long for the browser link. Use the sharing chooser instead.');
    return url;
  }
  function decodeHistory(text) {
    if (text === null) return [];
    const saved = JSON.parse(text);
    if (!saved || saved.version !== 1 || !Array.isArray(saved.entries) || saved.entries.length > MAX_HISTORY) throw error('INVALID_HISTORY', 'Unrecognized local history.');
    return saved.entries.map(value => {
      if (!value || typeof value.at !== 'string' || !Number.isFinite(Date.parse(value.at))) throw error('INVALID_HISTORY', 'Invalid history timestamp.');
      if (value.status === 'error') {
        if (value.protocol !== 'icmp' || typeof value.host !== 'string' || value.host.length > 1024 ||
            !value.error || typeof value.error.code !== 'string' || value.error.code.length > 80 ||
            typeof value.error.message !== 'string' || value.error.message.length > 240) throw error('INVALID_HISTORY', 'Invalid error record.');
        return {at: value.at, protocol: 'icmp', host: value.host, status: 'error',
          error: {code: value.error.code, message: value.error.message}};
      }
      return entryFromResult(value, value.at);
    });
  }
  function createController(api, changed, now) {
    const state = {ready: false, installed: false, canPing: false, busy: false, pingPending: false,
      actionBusy: false, cancelPending: false, historyWritable: true, history: [], settings: {...DEFAULTS},
      selected: null, output: '', message: 'Loading local data…', warnings: [], notificationInfo: 'Not checked.'};
    let cancelPromise = null, cancelRequested = false;
    now = now || (() => new Date().toISOString());
    const emit = () => { if (changed) changed(state); };
    const warn = message => { state.warnings.push(message); emit(); };
    function requireIdle() {
      if (!state.ready) throw error('NOT_READY', 'Wait for local data to load.');
      if (state.busy || state.actionBusy || state.cancelPending) throw error('BUSY', 'Wait for the current operation.');
    }
    function requireInstalled() { if (!state.installed) throw error('UNAVAILABLE', 'This needs an installed generated APK with its native bridge.'); }
    async function exclusive(work) {
      requireIdle(); state.actionBusy = true; emit();
      try { return await work(); } finally { state.actionBusy = false; emit(); }
    }
    async function load() {
      if (state.ready) return;
      try {
        const info = await api.runtime.info();
        state.installed = info.mode === 'installed';
        state.canPing = state.installed && info.nativePingProtocol === 'icmp' && !!api.network && typeof api.network.ping === 'function';
        state.mode = info.mode;
      } catch (failure) { warn('Runtime: ' + errorText(failure)); }
      try { state.history = decodeHistory(await api.storage.get(HISTORY_KEY)); }
      catch (failure) { state.historyWritable = false; warn('History was not loaded; new results will be session-only until you explicitly clear history. ' + errorText(failure)); }
      try {
        const text = await api.storage.get(SETTINGS_KEY);
        if (text !== null) {
          const saved = JSON.parse(text);
          if (!saved || saved.version !== 1) throw error('INVALID_SETTINGS', 'Unrecognized stored settings.');
          state.settings = settings(saved.value);
        }
      } catch (failure) { warn('Using default settings for this session. ' + errorText(failure)); }
      state.ready = true; state.message = state.canPing ? 'Ready. Enter a host to start.' : 'ICMP is unavailable in this runtime. No measurements were made.'; emit();
    }
    async function persistHistory() {
      if (!state.historyWritable) return false;
      try {
        let text = JSON.stringify({version: 1, entries: state.history});
        while (new TextEncoder().encode(text).length > 65536 && state.history.length > 1) {
          state.history.pop();
          text = JSON.stringify({version: 1, entries: state.history});
        }
        if (new TextEncoder().encode(text).length > 65536) throw error('TOO_LARGE', 'History exceeds the storage value limit.');
        await api.storage.set(HISTORY_KEY, text); return true;
      } catch (failure) { warn('History save was not confirmed; this result may be session-only. No automatic retry. ' + errorText(failure)); return false; }
    }
    function notificationStatus(value) {
      if (!value || typeof value.status !== 'string' || typeof value.ready !== 'boolean') throw error('INVALID_RESULT', 'No recognized notification status.');
      state.notificationInfo = value.status + ' · permission: ' + value.permission + ' · channel: ' + value.channelStatus + '. Snapshot only.';
      emit(); return value.ready;
    }
    async function feedback(entry) {
      if (cancelRequested || (entry.error && ['CANCELLED', 'PAGE_CLOSED'].includes(entry.error.code))) return;
      if (state.settings.haptics) {
        try {
          const receipt = await api.haptics.perform('tap');
          if (receipt !== true) warn('Haptic feedback was not reported performed; system settings or preview may suppress it.');
        } catch (failure) { warn('Haptics: ' + errorText(failure)); }
      }
      if (state.settings.notify) {
        try {
          if (!notificationStatus(await api.notifications.status({channelId: CHANNEL}))) {
            warn('No notification posted. Check Android permission and this channel in Settings.'); return;
          }
          const receipt = await api.notifications.show({id: NOTIFICATION_ID, channelId: CHANNEL,
            title: 'Ping Notebook result', body: summary(entry), priority: 'default'});
          if (!receipt || receipt.status !== 'accepted' || receipt.id !== NOTIFICATION_ID) throw error('INVALID_RESULT', 'No recognized notification receipt.');
          state.notificationInfo = 'Android accepted this app’s notification request. Display and delivery are unverified.'; emit();
        } catch (failure) { warn('Notification: ' + errorText(failure)); }
      }
    }
    async function run(input) {
      requireIdle(); requireInstalled();
      if (!state.canPing) throw error('UNAVAILABLE', 'The packaged DEX does not advertise ICMP ping. Rebuild with a compatible runtime.');
      const host = hostInput(input), config = settings(state.settings), at = now();
      const options = {host, family: config.family, count: config.count, timeoutMs: config.timeoutMs,
        packetTimeoutMs: config.packetTimeoutMs, intervalMs: config.intervalMs, packetBytes: config.packetBytes, maxOutputBytes: 4096};
      state.busy = true; state.pingPending = true; state.output = ''; state.selected = null;
      state.warnings = []; cancelRequested = false; state.message = 'Running ICMP; waiting for the native result…'; emit();
      let entry;
      try {
        try {
          const result = await api.network.ping(options);
          entry = entryFromResult(result, at);
          if (typeof result.output === 'string') state.output = result.output + (result.outputTruncated ? '\n[Native output was truncated.]' : '');
        } catch (failure) {
          entry = {at, host, protocol: 'icmp', status: 'error', error: {
            code: String(failure && failure.code || 'ERROR').slice(0, 80),
            message: String(failure && failure.message || 'Operation failed.').slice(0, 240)}};
        }
        state.pingPending = false; state.selected = entry;
        state.history = [entry, ...state.history].slice(0, MAX_HISTORY);
        state.message = summary(entry); emit();
        const persisted = await persistHistory();
        state.message = summary(entry) + (persisted ? (state.installed ? ' · Saved locally.' : ' · Preview session only.') : ' · Save not confirmed.'); emit();
        await feedback(entry);
        return entry;
      } finally {
        // Do not admit a newer run while cancellation of this run is still returning.
        if (cancelPromise) await cancelPromise;
        state.busy = false; state.pingPending = false; emit();
      }
    }
    async function cancel() {
      if (!state.pingPending || state.cancelPending) return;
      state.cancelPending = true; cancelRequested = true; state.message = 'Requesting cancellation; previously sent packets cannot be recalled.'; emit();
      cancelPromise = (async () => {
        try {
          const receipt = await api.network.cancel({});
          if (!receipt || !Number.isInteger(receipt.cancelled) || receipt.cancelled < 0 || receipt.rollbackConfirmed !== false)
            throw error('INVALID_RESULT', 'No recognized cancellation receipt.');
          if (state.pingPending) state.message = 'Cancellation requested for ' + receipt.cancelled + ' operation(s); waiting for the ping result. No network rollback is claimed.';
          else warn('Cancellation returned after the result. That result remains authoritative; no network rollback is claimed.');
        } catch (failure) { warn('Cancellation was not confirmed: ' + errorText(failure)); }
        finally { state.cancelPending = false; emit(); }
      })();
      await cancelPromise; cancelPromise = null;
    }
    function select(index) {
      requireIdle(); if (!state.history[index]) throw error('NO_RESULT', 'History entry no longer exists.');
      state.selected = state.history[index]; state.output = ''; state.message = 'Selected saved/session summary. No new ping was run.'; emit();
    }
    async function saveSettings(value) {
      return exclusive(async () => {
        const checked = settings(value);
        await api.storage.set(SETTINGS_KEY, JSON.stringify({version: 1, value: checked}));
        state.settings = checked; state.message = state.installed ? 'Settings saved locally. Android notification permission is separate.' : 'Settings saved in preview memory only.'; emit();
      });
    }
    async function clearHistory() {
      return exclusive(async () => {
        await api.storage.remove(HISTORY_KEY);
        state.history = []; state.selected = null; state.output = ''; state.historyWritable = true;
        state.message = 'Local history cleared.'; state.warnings = []; emit();
      });
    }
    async function setupNotifications() {
      return exclusive(async () => {
        requireInstalled();
        // This method is bound only to the explicit Setup button, never startup/save/run.
        const permission = await api.notifications.requestPermission({});
        if (!permission || !['granted', 'not_required'].includes(permission.permission)) {
          notificationStatus(permission); state.message = 'Android notification permission was not granted. No notification was posted.'; return;
        }
        await api.notifications.channel({id: CHANNEL, name: 'Ping results', description: 'Immediate results requested in Ping Notebook', importance: 'default'});
        const ready = notificationStatus(await api.notifications.status({channelId: CHANNEL}));
        state.message = ready ? 'Notification channel ready. Save the notification option to use it after a run.' : 'Notifications remain unavailable or blocked in Android settings.';
      });
    }
    async function checkNotifications() {
      return exclusive(async () => { requireInstalled(); notificationStatus(await api.notifications.status({channelId: CHANNEL})); });
    }
    async function cancelNotification() {
      return exclusive(async () => {
        requireInstalled(); const receipt = await api.notifications.cancel({id: NOTIFICATION_ID});
        if (!receipt || receipt.status !== 'cancel_requested' || receipt.id !== NOTIFICATION_ID) throw error('INVALID_RESULT', 'No recognized removal receipt.');
        state.notificationInfo = 'Removal requested for this app’s notification. Prior display/read status is unknown.';
      });
    }
    async function pickContact() {
      return exclusive(async () => {
        requireInstalled(); const selected = await api.contacts.pick({kind: 'phone'});
        if (!selected || selected.kind !== 'phone' || typeof selected.value !== 'string' || selected.value.length > 256) throw error('INVALID_RESULT', 'No phone datum returned.');
        state.message = 'Selected a phone datum. Check/edit its format; no message has been opened or sent.';
        return selected.value;
      });
    }
    async function share(kind, recipient) {
      return exclusive(async () => {
        requireInstalled(); const entry = state.selected;
        if (kind === 'sms') await api.sms.compose({number: phone(recipient, false), body: report(entry)});
        else if (kind === 'whatsapp') await api.browser.open({url: whatsappUrl(recipient, entry)});
        else if (kind === 'chooser') await api.share.text({text: report(entry), title: 'ICMP ping summary'});
        else throw error('INVALID_ARGUMENT', 'Unknown sharing route.');
        state.message = 'The external action returned. Review the receiving app; sending and delivery are not confirmed.';
      });
    }
    return {state, load, run, cancel, select, saveSettings, clearHistory, setupNotifications,
      checkNotifications, cancelNotification, pickContact, share};
  }
  const exports = {createController, settings, hostInput, entryFromResult, summary, report,
    phone, whatsappUrl, decodeHistory, errorText, DEFAULTS, HISTORY_KEY, SETTINGS_KEY, CHANNEL, MAX_HISTORY};
  if (typeof module === 'object' && module.exports) module.exports = exports;
  else Object.defineProperty(root, 'PingNotebook', {value: Object.freeze(exports)});
}(typeof window === 'object' ? window : globalThis));
