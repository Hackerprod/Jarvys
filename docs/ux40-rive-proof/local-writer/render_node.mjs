// Official Rive Canvas2D backend with a host-only Skia-backed Canvas adapter.
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {loadRuntime} from './runtime_loader.mjs';
const canvasDir=path.resolve(process.env.CANVAS_DIR||'node_modules/@napi-rs/canvas');
const {createCanvas,Path2D,DOMMatrix,ImageData}=await import(pathToFileURL(path.join(canvasDir,'index.js')).href);
globalThis.Path2D=Path2D;globalThis.DOMMatrix=DOMMatrix;globalThis.ImageData=ImageData;
const r=await loadRuntime();
fs.mkdirSync('artifacts/captures',{recursive:true});
const result={renderer:'Official Rive Canvas2D WASM 2.44.1 with @napi-rs/canvas 1.0.10 host-only adapter; not Android or browser rendering.',captures:[]};
for(const id of ['miga','tallo']){
 const file=await r.load(new Uint8Array(fs.readFileSync(`artifacts/${id}.riv`)),undefined,false);
 for(const [label,mode,reduced,frames] of [['Idle',0,false,1],['Idle',0,false,31],['Active',1,false,1],['Active',1,false,22],['Reduced',1,true,1],['Reduced',1,true,181]]){
  const canvas=createCanvas(256,256);const art=file.defaultArtboard();const sm=new r.StateMachineInstance(art.stateMachineByIndex(0),art);
  sm.input(0).asNumber().value=mode;sm.input(1).asBool().value=reduced;
  for(let i=0;i<frames;i++){sm.advanceAndApply(1/60);art.advance(1/60);}
  const renderer=r.makeRenderer(canvas);renderer.clear();renderer.save();art.draw(renderer);renderer.restore();r.resolveAnimationFrame();
  const filename=`${id}-${label}-${frames}.png`;fs.writeFileSync(`artifacts/captures/${filename}`,canvas.toBuffer('image/png'));
  result.captures.push({id,label,mode,reduced,frames,filename});
  renderer.delete();sm.delete();art.delete();
 }
 file.delete();
}
result.passed=true;fs.writeFileSync('artifacts/render-results.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({passed:true,captures:result.captures.length}));
