(function () {
  'use strict';
  const model = window.PingNotebook;
  const byId = id => document.getElementById(id);
  const settingIds = ['family', 'count', 'timeoutMs', 'packetTimeoutMs', 'intervalMs', 'packetBytes', 'notify', 'haptics'];
  let displayedSettings = null;
  function render(state) {
    const locked = !state.ready || state.busy || state.actionBusy || state.cancelPending;
    byId('runtime').textContent = state.canPing ? 'Installed runtime · native ICMP · network traffic occurs only when you start a run.' :
      state.mode === 'preview' ? 'Preview: ICMP, notifications and external editors are unavailable. Storage is temporary preview memory.' :
        'Compatible installed runtime required. This screen cannot create native capabilities or invent ICMP results.';
    byId('status').textContent = state.message;
    byId('warning').textContent = state.warnings.join('\n');
    byId('warning').hidden = state.warnings.length === 0;
    byId('run').disabled = locked || !state.canPing;
    byId('host').disabled = state.busy;
    byId('cancel').disabled = !state.pingPending || state.cancelPending;
    for (const id of ['save-settings', 'clear-history', 'confirm-clear']) byId(id).disabled = locked;
    for (const id of ['notification-setup', 'notification-status', 'notification-cancel', 'pick-contact']) byId(id).disabled = locked || !state.installed;
    for (const id of ['sms', 'whatsapp', 'share']) byId(id).disabled = locked || !state.installed || !state.selected;
    byId('notification-info').textContent = state.notificationInfo;
    byId('result').hidden = !state.selected;
    if (state.selected) {
      const entry = state.selected;
      byId('result-summary').textContent = model.summary(entry);
      byId('result-details').textContent = entry.at + ' · ' + (entry.error ? entry.error.message + ' No measurement returned. Already transmitted packets cannot be recalled.' :
        'Elapsed: ' + entry.elapsedMs.toFixed(1) + ' ms (operation duration, not RTT). Exit code: ' + (entry.exitCode === null ? 'unavailable' : entry.exitCode) +
        '. Reachability: ' + (entry.reachable === true ? 'reply observed' : entry.reachable === false ? 'no reply observed' : 'unknown') +
        '. RTT min/avg/max: ' + (entry.latencyMs ? [entry.latencyMs.min, entry.latencyMs.avg, entry.latencyMs.max].join('/') + ' ms' : 'unavailable') + '.');
    }
    byId('raw-details').hidden = !state.output;
    byId('raw-output').textContent = state.output;
    byId('history-empty').hidden = state.history.length !== 0;
    byId('history-note').textContent = state.mode === 'preview' ? 'Preview history is temporary and is lost on reset/close.' :
      'Up to 30 recent summaries in this app’s private storage; a failed save stays explicit. No contact numbers or raw output are saved.';
    byId('history-list').replaceChildren();
    state.history.forEach((entry, index) => {
      const item = document.createElement('li'), text = document.createElement('span'), button = document.createElement('button');
      text.textContent = entry.at + ' · ' + model.summary(entry);
      button.type = 'button'; button.textContent = 'View summary'; button.disabled = locked;
      button.addEventListener('click', () => handle(async () => { app.select(index); showView('main', true); }));
      item.appendChild(text); item.appendChild(button); byId('history-list').appendChild(item);
    });
    if (state.ready && displayedSettings !== state.settings) {
      for (const id of settingIds) {
        if (typeof state.settings[id] === 'boolean') byId(id).checked = state.settings[id];
        else byId(id).value = state.settings[id];
      }
      displayedSettings = state.settings;
    }
  }
  const app = model.createController(window.Jarvys, render);
  async function handle(action) {
    try { await action(); }
    catch (failure) { app.state.message = model.errorText(failure) + ' No automatic retry.'; render(app.state); }
  }
  function showView(requested, push) {
    const view = ['main', 'history', 'settings'].includes(requested) ? requested : 'main';
    for (const name of ['main', 'history', 'settings']) byId('view-' + name).hidden = name !== view;
    document.querySelectorAll('[data-view]').forEach(button => {
      if (button.dataset.view === view) button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
    });
    byId('clear-confirm').hidden = true;
    if (push && window.location.hash !== '#' + view) window.history.pushState({view}, '', '#' + view);
    if (push) byId('title-' + view).focus();
  }
  document.querySelectorAll('[data-view]').forEach(button => button.addEventListener('click', () => showView(button.dataset.view, true)));
  window.addEventListener('popstate', () => showView(window.location.hash.slice(1), false));
  window.addEventListener('hashchange', () => showView(window.location.hash.slice(1), false));
  byId('ping-form').addEventListener('submit', event => { event.preventDefault(); handle(() => app.run(byId('host').value)); });
  byId('cancel').addEventListener('click', () => handle(() => app.cancel()));
  byId('settings-form').addEventListener('submit', event => {
    event.preventDefault(); const value = {};
    for (const id of settingIds) value[id] = ['notify', 'haptics'].includes(id) ? byId(id).checked : id === 'family' ? byId(id).value : Number(byId(id).value);
    handle(() => app.saveSettings(value));
  });
  byId('clear-history').addEventListener('click', () => { byId('clear-confirm').hidden = false; byId('keep-history').focus(); });
  byId('keep-history').addEventListener('click', () => { byId('clear-confirm').hidden = true; byId('clear-history').focus(); });
  byId('confirm-clear').addEventListener('click', () => handle(async () => { await app.clearHistory(); byId('clear-confirm').hidden = true; }));
  byId('notification-setup').addEventListener('click', () => handle(() => app.setupNotifications()));
  byId('notification-status').addEventListener('click', () => handle(() => app.checkNotifications()));
  byId('notification-cancel').addEventListener('click', () => handle(() => app.cancelNotification()));
  byId('pick-contact').addEventListener('click', () => handle(async () => { byId('recipient').value = await app.pickContact(); byId('recipient').focus(); }));
  for (const [id, route] of [['sms', 'sms'], ['whatsapp', 'whatsapp'], ['share', 'chooser']]) {
    byId(id).addEventListener('click', () => handle(() => app.share(route, byId('recipient').value)));
  }
  showView(window.location.hash.slice(1), false);
  handle(() => app.load());
}());
