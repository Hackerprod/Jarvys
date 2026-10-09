/** Host-only fresh-instance/first-frame checks; no Android or Full-suite claim.
 * Usage: node validate_initialization.mjs SCENE_JSON COMPILED_RIV; set RIVE_WASM_DIR.
 * This script reads existing actual Kotlin outputs and never compiles a fixture.
 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { loadRuntime } from './runtime_loader.mjs';

const root = path.dirname(fileURLToPath(import.meta.url));
const rivPath = process.argv[3];
assert(rivPath, 'Usage: node validate_initialization.mjs SCENE_JSON COMPILED_RIV');
const scenePath = process.argv[2] || path.join(root, 'jarvys-main.scene.json');
assert(process.env.RIVE_WASM_DIR, 'Set RIVE_WASM_DIR to the existing official package');
const pkg = JSON.parse(fs.readFileSync(path.join(process.env.RIVE_WASM_DIR, 'package.json')));
assert.equal(pkg.name, '@rive-app/canvas-advanced');
assert.equal(pkg.version, '2.44.1');
const runtime = await loadRuntime();
const modes = ['Idle', 'Thinking', 'Working', 'Queued', 'WaitingProvider', 'WaitingUser', 'Done', 'Error', 'Interrupted'];
const results = [];

function tick(entry) {
  entry.sm.advanceAndApply(1 / 60);
  entry.art.advance(1 / 60);
  return Array.from({ length: entry.sm.stateChangedCount() },
    (_, index) => entry.sm.stateChangedNameByIndex(index));
}

for (const id of ['jarvys']) {
  const scene = JSON.parse(fs.readFileSync(scenePath));
  const file = await runtime.load(new Uint8Array(fs.readFileSync(rivPath)), undefined, false);
  assert(file);
  const vm = file.viewModelByName('MascotState');
  const entries = [];
  function create() {
    const entry = {};
    entries.push(entry);
    entry.art = file.defaultArtboard();
    entry.vi = vm.defaultInstance();
    entry.sm = new runtime.StateMachineInstance(entry.art.stateMachineByName('MascotController'), entry.art);
    entry.sm.bindViewModelInstance(entry.vi);
    return entry;
  }
  try {
    const initial = create();
    assert.equal(initial.vi.number('mode').value, 0);
    assert.equal(initial.vi.boolean('reducedMotion').value, false);
    assert.equal(tick(initial).at(-1), 'Idle');
    const reducedInitialization = [];
    for (const [mode, name] of modes.entries()) {
      const entry = create();
      const state = `${name}Reduced`;
      entry.vi.number('mode').value = mode;
      entry.vi.boolean('reducedMotion').value = true;
      assert.equal(tick(entry).at(-1), state);
      const tracks = scene.animations[state].tracks;
      const pose = () => tracks.map(track => entry.art.node(track.node)[track.property]);
      const first = pose();
      first.forEach((value, index) => {
        assert(Math.abs(value - tracks[index].keys[0][1]) < 1e-4,
          `${id}: incorrect first-frame reduced target`);
      });
      for (let frame = 1; frame < 180; frame++) {
        assert.deepEqual(tick(entry), []);
        assert.deepEqual(pose(), first);
      }
      reducedInitialization.push({ state, firstFrameApplied: true, staticFrames: 180 });
    }
    results.push({ id, freshDefaultIdle: true, reducedInitialization, passed: true });
  } finally {
    for (const entry of entries) {
      entry.sm?.delete();
      entry.art?.delete();
      entry.vi?.delete();
    }
    file.delete();
  }
}
console.log(JSON.stringify({ scope: 'Host-only first applied frame; not Android', results, passed: true }, null, 2));
