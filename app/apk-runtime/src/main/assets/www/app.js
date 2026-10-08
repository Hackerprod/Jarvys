(function () {
  'use strict';
  var records = [];
  var selected = null;
  var ready = false;
  var busy = false;
  var dirty = false;
  var confirmDiscard = false;
  var confirmDelete = false;
  var title = document.getElementById('title');
  var body = document.getElementById('body');
  var status = document.getElementById('status');
  var list = document.getElementById('notes');
  var buttons = ['save', 'export', 'delete', 'new'].map(function (id) { return document.getElementById(id); });
  function controls() { buttons.forEach(function (button) { button.disabled = busy || !ready; }); title.disabled = body.disabled = busy || !ready; }
  function noteStatus(message) { status.textContent = message; }
  function resetConfirmations() {
    confirmDiscard = confirmDelete = false;
    document.getElementById('new').textContent = 'New note';
    document.getElementById('delete').textContent = 'Delete';
  }
  function clearEditor() { selected = null; title.value = ''; body.value = ''; dirty = false; resetConfirmations(); render(); }
  function render() {
    list.textContent = '';
    records.forEach(function (note) {
      var li = document.createElement('li');
      var button = document.createElement('button');
      button.type = 'button'; button.textContent = note.title || 'Untitled note';
      button.setAttribute('aria-current', note.id === selected ? 'true' : 'false');
      button.disabled = busy;
      button.addEventListener('click', function () {
        if (dirty) { noteStatus('Save this note before switching.'); return; }
        selected = note.id; title.value = note.title; body.value = note.body; resetConfirmations(); render(); noteStatus('Saved on this device.');
      });
      li.appendChild(button); list.appendChild(li);
    });
  }
  function change() { dirty = true; resetConfirmations(); noteStatus('Unsaved changes.'); }
  title.addEventListener('input', change); body.addEventListener('input', change);
  async function work(action) {
    if (!ready || busy) return;
    busy = true; controls(); render();
    try { await action(); }
    catch (error) { noteStatus((error.code ? error.code + ': ' : '') + error.message); }
    finally { busy = false; controls(); render(); }
  }
  document.getElementById('new').addEventListener('click', function () {
    if (dirty && !confirmDiscard) {
      confirmDiscard = true; document.getElementById('new').textContent = 'Discard draft?';
      noteStatus('Tap Discard draft? to start over without saving these changes.'); return;
    }
    clearEditor(); title.focus(); noteStatus('A fresh page.');
  });
  document.getElementById('save').addEventListener('click', function () { work(async function () {
    if (!title.value.trim() && !body.value.trim()) { noteStatus('Write a title or note first.'); return; }
    if (!selected && records.length >= 20) { noteStatus('This sample supports 20 notes. Export or delete one first.'); return; }
    var id = selected || Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
    var next = records.filter(function (note) { return note.id !== id; });
    next.unshift({ id: id, title: title.value.trim(), body: body.value });
    await Jarvys.storage.set('notes.v1', JSON.stringify(next));
    records = next; selected = id; dirty = false; resetConfirmations(); noteStatus('Saved on this device.');
  }); });
  document.getElementById('delete').addEventListener('click', function () { work(async function () {
    if (!selected) { noteStatus('Select a saved note to delete.'); return; }
    if (!confirmDelete) {
      confirmDelete = true; document.getElementById('delete').textContent = 'Confirm delete';
      noteStatus('Tap Confirm delete to permanently remove this saved note.'); return;
    }
    var next = records.filter(function (note) { return note.id !== selected; });
    await Jarvys.storage.set('notes.v1', JSON.stringify(next));
    records = next; clearEditor(); noteStatus('Note deleted.');
  }); });
  document.getElementById('export').addEventListener('click', function () { work(async function () {
    if (!title.value.trim() && !body.value.trim()) { noteStatus('Write or select a note first.'); return; }
    var filename = title.value.replace(/[^a-zA-Z0-9 _-]/g, '').trim().slice(0, 60) || 'note';
    await Jarvys.export.text({ filename: filename + '.txt', text: title.value + '\n\n' + body.value, mimeType: 'text/plain' });
    noteStatus('Export saved to the location you chose.');
  }); });
  controls();
  Jarvys.storage.get('notes.v1').then(function (saved) {
    if (saved !== null) {
      var parsed = JSON.parse(saved);
      if (!Array.isArray(parsed) || parsed.length > 20 || parsed.some(function (n) {
        return !n || typeof n.id !== 'string' || typeof n.title !== 'string' || typeof n.body !== 'string';
      })) throw new Error('Saved data is invalid; it has not been changed.');
      records = parsed;
    }
    ready = true; controls(); render(); noteStatus(records.length ? 'Choose a note, or start a new one.' : 'Write your first note.');
  }).catch(function (error) { noteStatus(error.message); });
}());
