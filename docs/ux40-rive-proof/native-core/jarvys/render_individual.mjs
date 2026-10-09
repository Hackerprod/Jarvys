import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {pathToFileURL} from 'node:url';
const scriptDir=path.dirname(new URL(import.meta.url).pathname);
assert(process.env.JARVYS_PROOF_OUTPUT,'Set JARVYS_PROOF_OUTPUT to a writable output directory');
assert(process.env.JARVYS_RIV_FILE,'Set JARVYS_RIV_FILE to the compiled production asset');
const root=path.resolve(process.env.JARVYS_PROOF_OUTPUT);fs.mkdirSync(root,{recursive:true});
const sourcePath=process.env.JARVYS_SCENE_SOURCE||path.join(scriptDir,'jarvys-main.scene.json');
assert(process.env.CANVAS_DIR,'Set CANVAS_DIR to an installed @napi-rs/canvas package');
const canvasDir=path.resolve(process.env.CANVAS_DIR);
assert(process.env.RIVE_WASM_DIR,'Set RIVE_WASM_DIR to the installed official runtime');
const wasmDir=path.resolve(process.env.RIVE_WASM_DIR);
const pkg=JSON.parse(fs.readFileSync(path.join(wasmDir,'package.json')));
assert.equal(pkg.name,'@rive-app/canvas-advanced');assert.equal(pkg.version,'2.44.1');
const {createCanvas,Path2D,DOMMatrix,ImageData}=await import(pathToFileURL(path.join(canvasDir,'index.js')).href);
Object.assign(globalThis,{Path2D,DOMMatrix,ImageData});
const {default:Rive}=await import(pathToFileURL(path.join(wasmDir,'canvas_advanced.mjs')).href);
const r=await Rive({wasmBinary:fs.readFileSync(path.join(wasmDir,'rive.wasm'))});
const sourceBytes=fs.readFileSync(sourcePath),rivBytes=fs.readFileSync(process.env.JARVYS_RIV_FILE);
const sourceSha256=crypto.createHash('sha256').update(sourceBytes).digest('hex');
const rivSha256=crypto.createHash('sha256').update(rivBytes).digest('hex');
assert.equal(sourceSha256,'983546e91329e6ab22e1191fbfb0f3c240384c8eabbacc62a1d96951e39e236b','Wrong frozen Jarvys source');
assert.equal(rivSha256,'514c6cfb16008eb6ecfd13c136ab6032c2ebd615c11f2325491fa762fc73981a','Wrong frozen Jarvys Rive binary');
const file=await r.load(new Uint8Array(rivBytes),undefined,false);assert(file);
const vm=file.viewModelByName('MascotState');assert(vm);
const scene=JSON.parse(sourceBytes.toString('utf8'));
const modes=['Idle','Thinking','Working','Queued','WaitingProvider','WaitingUser','Done','Error','Interrupted'];
function instance(mode,reduced=false){
 const art=file.defaultArtboard(),vi=vm.defaultInstance(),sm=new r.StateMachineInstance(art.stateMachineByName('MascotController'),art);
 sm.bindViewModelInstance(vi);vi.number('mode').value=mode;vi.boolean('reducedMotion').value=reduced;
 // One zero-time pass applies the requested state before its first sampled frame.
 sm.advanceAndApply(0);art.advance(0);
 return {art,vi,sm,advance(frames){for(let i=0;i<frames;i++){sm.advanceAndApply(1/60);art.advance(1/60);}},close(){sm.delete();art.delete();vi.delete();}};
}
function render(x,size,filename){
 const canvas=createCanvas(size,size),renderer=r.makeRenderer(canvas);
 renderer.clear();renderer.save();renderer.scale(size/256,size/256);x.art.draw(renderer);renderer.restore();r.resolveAnimationFrame();
 fs.writeFileSync(filename,canvas.toBuffer('image/png'));renderer.delete();
}
fs.mkdirSync(path.join(root,'runtime-frames'),{recursive:true});
let captures=0;const checks=[];
for(let mode=0;mode<modes.length;mode++){
 const name=modes[mode],animation=scene.animations[name],dir=path.join(root,'runtime-frames',name);
 fs.mkdirSync(dir,{recursive:true});const frames=animation.duration/3;assert(Number.isInteger(frames));const x=instance(mode);
 for(let f=0;f<frames;f++){
  for(const size of [256,22,30,34]){render(x,size,path.join(dir,`${size}-${String(f).padStart(3,'0')}.png`));captures++;}
  x.advance(3);
 }
 // A single post-end tick clamps float32 timeline endpoints before strict hold checks.
 if(!animation.loop)x.advance(1);
 for(const size of [256,22,30,34]){render(x,size,path.join(dir,`${size}-final.png`));captures++;}
 x.advance(180);for(const size of [256,22,30,34]){render(x,size,path.join(dir,`${size}-after-3s.png`));captures++;}x.close();
 const reduced=instance(mode,true);for(const size of [256,22,30,34]){render(reduced,size,path.join(dir,`${size}-reduced.png`));captures++;}
 reduced.advance(240);render(reduced,256,path.join(dir,'256-reduced-after-4s.png'));captures++;reduced.close();
 checks.push({mode,name,durationSeconds:animation.duration/60,renderFrames:frames,fps:20,loop:animation.loop});
 console.log(name,frames,'frames at 20 fps');
}
file.delete();
fs.writeFileSync(path.join(root,'runtime-render-report.json'),JSON.stringify({runtime:'Official Rive Canvas2D WASM 2.44.1, cached host adapter; not Android',sourceSha256,rivSha256,captures,sizes:[256,22,30,34],renderTimes:'Starts at t=0; each frame advances exactly 3/60 s; one-shot settled endpoint sampled at duration + 1/60 s',states:checks},null,2)+'\n');
