/** Official Canvas2D WASM with a host adapter; no Android or browser claim. */
import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';import {fileURLToPath,pathToFileURL} from 'node:url';import {loadRuntime} from './runtime_loader.mjs';
const root=path.dirname(fileURLToPath(import.meta.url)),out=process.env.RIVE_PRODUCT_OUTPUT||path.join(root,'artifacts'),sceneDir=process.env.RIVE_PRODUCT_SCENES||path.join(root,'scenes');
const canvasDir=process.env.CANVAS_DIR;assert(canvasDir);const {createCanvas,Path2D,DOMMatrix,ImageData}=await import(pathToFileURL(path.join(canvasDir,'index.js')).href);globalThis.Path2D=Path2D;globalThis.DOMMatrix=DOMMatrix;globalThis.ImageData=ImageData;
const r=await loadRuntime(),modes=['Idle','Thinking','Working','Queued','WaitingProvider','WaitingUser','Done','Error','Interrupted'];fs.mkdirSync(path.join(out,'captures'),{recursive:true});const report={scope:'Official Rive Canvas2D WASM 2.44.1 and host canvas adapter, not Android',captures:[]};
for(const name of fs.readdirSync(sceneDir).filter(x=>x.endsWith('.json')).sort()){
 const id=path.basename(name,'.json'),file=await r.load(new Uint8Array(fs.readFileSync(path.join(out,id+'.riv'))),undefined,false);assert(file);const vm=file.viewModelByName('MascotState');
 for(let mode=0;mode<9;mode++)for(const reduced of [false,true])for(const frames of (reduced?[1,181]:[1,31])){
  const canvas=createCanvas(256,256),art=file.defaultArtboard(),vi=vm.defaultInstance(),sm=new r.StateMachineInstance(art.stateMachineByName('MascotController'),art);sm.bindViewModelInstance(vi);vi.number('mode').value=mode;vi.boolean('reducedMotion').value=reduced;
  for(let f=0;f<frames;f++){sm.advanceAndApply(1/60);art.advance(1/60);}
  const renderer=r.makeRenderer(canvas);renderer.clear();renderer.save();art.draw(renderer);renderer.restore();r.resolveAnimationFrame();const state=modes[mode]+(reduced?'Reduced':''),filename=`${id}-${state}-${frames}.png`;fs.writeFileSync(path.join(out,'captures',filename),canvas.toBuffer('image/png'));report.captures.push({id,state,mode,reduced,frames,filename});renderer.delete();sm.delete();art.delete();vi.delete();
 }
 file.delete();
}
assert.equal(report.captures.length,72);report.passed=true;fs.writeFileSync(path.join(out,'product-render-results.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({passed:true,captures:report.captures.length}));
