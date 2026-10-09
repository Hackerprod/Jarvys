import fs from 'node:fs';import assert from 'node:assert/strict';import crypto from 'node:crypto';import path from 'node:path';import {fileURLToPath,pathToFileURL} from 'node:url';
const base=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const runtimeDir=process.env.RIVE_WASM_DIR || path.join(base,'node_modules/@rive-app/canvas-advanced');
const {default:Rive}=await import(pathToFileURL(path.join(runtimeDir,'canvas_advanced.mjs')));
const rive=await Rive({wasmBinary:fs.readFileSync(path.join(runtimeDir,'rive.wasm'))});
fs.mkdirSync(path.join(base,'artifacts'),{recursive:true});
const specs=[{id:'lumen',names:['Idle','Thinking','Working','WaitingUser','Done','Error','Interrupted','Queued','WaitingProvider'],suffix:'Reduced',nodes:['Lantern','Left eye','Right eye','Mouth','Left fin','Right fin','Antenna bulb','Jet outer']},{id:'brisa',names:['idle','thinking','working','waitingUser','done','error','interrupted','queued','waitingProvider'],suffix:'_static',nodes:['Brisa','Head','EyeL','EyeR','Crest','WingL','WingR']}];
const report={scope:'Actual compiled .riv bytes imported independently by official @rive-app/canvas-advanced 2.44.1 WASM in Node; no rendering, Android device execution, or performance claim.',utc:new Date().toISOString(),mascots:[]};
for(const spec of specs){
 const bytes=fs.readFileSync(`${base}/sources/${spec.id}/build/${spec.id}.riv`); assert.equal(bytes.subarray(0,4).toString(),'RIVE');
 const file=await rive.load(new Uint8Array(bytes));assert.equal(file.artboardCount(),1);assert.equal(file.viewModelCount(),1);
 function instance(){const art=file.artboardByName('Mascot');assert.equal(art.animationCount(),18);assert.equal(art.stateMachineCount(),1);const vmi=file.viewModelByName('MascotState').instance();const sm=new rive.StateMachineInstance(art.stateMachineByIndex(0),art);sm.bindViewModelInstance(vmi);return {art,vmi,sm,close(){sm.delete();art.delete();vmi.delete();}};}
 function advance(x,n){const changes=[];for(let f=0;f<n;f++){x.sm.advanceAndApply(1/60);x.art.advance(1/60);for(let i=0;i<x.sm.stateChangedCount();i++)changes.push(x.sm.stateChangedNameByIndex(i));}return changes;}
 function pose(x){return spec.nodes.map(name=>{const n=x.art.node(name);assert(n,`missing ${name}`);return {name,x:n.x,y:n.y,rotation:n.rotation,scaleX:n.scaleX,scaleY:n.scaleY};});}
 const x=instance();const transitions=[];advance(x,180);
 for(let from=0;from<9;from++){x.vmi.boolean('reducedMotion').value=false;x.vmi.number('mode').value=from;advance(x,180);for(let to=0;to<9;to++){if(to===from)continue;
  x.vmi.number('mode').value=to;const changes=advance(x,30);assert(changes.includes(spec.names[to]),`${spec.id} ${from}->${to}: ${changes}`);assert.equal(x.vmi.number('mode').value,to);transitions.push({from,to,entered:spec.names[to],passed:true});
  x.vmi.number('mode').value=from;advance(x,180);
 }}
 const toggles=[];
 for(let mode=0;mode<9;mode++){
  x.vmi.boolean('reducedMotion').value=false;x.vmi.number('mode').value=mode;advance(x,20);
  x.vmi.boolean('reducedMotion').value=true;const enter=advance(x,30);assert(enter.includes(spec.names[mode]+spec.suffix),`${spec.id} reduced mode ${mode}`);const a=pose(x);advance(x,180);assert.deepEqual(pose(x),a,`${spec.id} reduced mode ${mode} moved`);
  x.vmi.boolean('reducedMotion').value=false;const leave=advance(x,30);assert(leave.includes(spec.names[mode]),`${spec.id} resumed mode ${mode}`);toggles.push({mode,entered:spec.names[mode]+spec.suffix,staticFrames:180,returned:spec.names[mode],passed:true});
 }
 // Two instances share immutable bytes but not their mode or live pose.
 const y=instance();x.vmi.number('mode').value=2;x.vmi.boolean('reducedMotion').value=false;y.vmi.number('mode').value=6;y.vmi.boolean('reducedMotion').value=true;advance(x,30);advance(y,30);assert.equal(x.vmi.number('mode').value,2);assert.equal(y.vmi.number('mode').value,6);const before=pose(y);x.vmi.number('mode').value=5;advance(x,90);advance(y,90);assert.deepEqual(pose(y),before);assert.equal(y.vmi.number('mode').value,6);
 y.close();x.close();file.delete();
 report.mascots.push({id:spec.id,rivBytes:bytes.length,sha256:crypto.createHash('sha256').update(bytes).digest('hex'),artboards:1,viewModels:1,stateMachines:1,timelines:18,transitions,toggles,independentInstances:true,passed:true});
}
let invalidRejected=false;try{const bad=await rive.load(new Uint8Array([1,2,3,4,5]));invalidRejected=!bad;if(bad)bad.delete();}catch(e){invalidRejected=true;}assert(invalidRejected);report.invalidBinaryRejected=invalidRejected;report.passed=true;
fs.writeFileSync(`${base}/artifacts/live-runtime-validation.json`,JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({passed:true,mascots:report.mascots.map(x=>({id:x.id,rivBytes:x.rivBytes,transitions:x.transitions.length,reducedMotionToggles:x.toggles.length,independentInstances:x.independentInstances})),invalidBinaryRejected:invalidRejected}));
