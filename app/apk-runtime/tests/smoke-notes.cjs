'use strict';
// Browser-only UI smoke with a MOCKED native bridge. This is not Android/SAF acceptance.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const assets = path.join(__dirname, '../src/main/assets');
const storage = new Map();
const exportsReceived = [];
let cancelExport = false;
(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}) });
  try {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
    await context.exposeBinding('__mockNative', async (_, raw) => {
      const { id, method, args } = JSON.parse(raw);
      let result = null;
      if (method === 'storage.get') result = storage.get(args.key) ?? null;
      else if (method === 'storage.set') storage.set(args.key, args.value);
      else if (method === 'export.text') {
        if (cancelExport) return { v: 1, id, ok: false, error: { code: 'CANCELLED', message: 'Export cancelled.' } };
        exportsReceived.push(args); result = { saved: true };
      } else throw new Error('Unexpected mock call: ' + method);
      return { v: 1, id, ok: true, result };
    });
    await context.addInitScript(() => {
      window.JarvysNative = { postMessage(raw) {
        window.__mockNative(raw).then(result => window.JarvysNative.onmessage({ data: JSON.stringify(result) }));
      } };
    });
    await context.route('**/*', async route => {
      const url = new URL(route.request().url());
      const local = path.join(assets, url.pathname);
      if (url.origin !== 'https://app.jarvys.invalid' || !local.startsWith(assets + path.sep) || !fs.existsSync(local)) {
        await route.fulfill({ status: 403, body: '' }); return;
      }
      const ext = path.extname(local);
      const contentType = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css' }[ext] || 'application/octet-stream';
      await route.fulfill({ status: 200, body: fs.readFileSync(local), contentType, headers: {
        'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
      } });
    });
    const page = await context.newPage();
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.goto('https://app.jarvys.invalid/www/index.html');
    await page.getByText('Write your first note.', { exact: true }).waitFor();
    await page.locator('#title').fill('Reusable runtime');
    await page.locator('#body').fill('Offline storage and explicit export.');
    await page.locator('#save').click();
    await page.getByText('Saved on this device.', { exact: true }).waitFor();
    assert.equal(JSON.parse(storage.get('notes.v1'))[0].title, 'Reusable runtime');
    await page.reload();
    await page.locator('#notes button').getByText('Reusable runtime', { exact: true }).click();
    assert.equal(await page.locator('#body').inputValue(), 'Offline storage and explicit export.');
    await page.locator('#export').click();
    await page.getByText('Export saved to the location you chose.', { exact: true }).waitFor();
    assert.equal(exportsReceived[0].filename, 'Reusable runtime.txt');
    assert.equal(exportsReceived[0].text, 'Reusable runtime\n\nOffline storage and explicit export.');
    cancelExport = true; await page.locator('#export').click();
    await page.getByText('CANCELLED: Export cancelled.', { exact: true }).waitFor();
    assert.equal(await page.locator('#save').isEnabled(), true);
    await page.locator('#body').fill('Unsaved draft');
    await page.locator('#new').click();
    assert.equal(await page.locator('#body').inputValue(), 'Unsaved draft');
    await page.getByRole('button', { name: 'Discard draft?', exact: true }).click();
    assert.equal(await page.locator('#body').inputValue(), '');
    await page.locator('#notes button').click();
    await page.locator('#delete').click();
    assert.equal(JSON.parse(storage.get('notes.v1')).length, 1);
    await page.getByRole('button', { name: 'Confirm delete', exact: true }).click();
    await page.getByText('Note deleted.', { exact: true }).waitFor();
    assert.equal(JSON.parse(storage.get('notes.v1')).length, 0);
    await page.locator('#title').fill('Next idea'); await page.locator('#body').fill('Ready for your next application.');
    await page.locator('#save').click(); await page.getByText('Saved on this device.', { exact: true }).waitFor();
    const out = path.join(__dirname, '../build/ui-smoke'); fs.mkdirSync(out, { recursive: true });
    await page.screenshot({ path: path.join(out, 'offline-notes.png'), fullPage: true });
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ passed: true, source: 'mocked-native browser UI only', checks: ['save', 'reload-persistence', 'export', 'export-cancel', 'discard-confirmation', 'delete-confirmation', 'repeat-save'], screenshot: path.join(out, 'offline-notes.png') }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
