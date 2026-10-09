/** Independent host-only validation of compiled bot-mascot-v1 bytes in official WASM. */
import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';import {loadRuntime} from './runtime_loader.mjs';
const root=path.dirname(fileURLToPath(import.meta.url));
const sceneDir=process.env.RIVE_PRODUCT_SCENES||path.join(root,'scenes');
const out=process.env.RIVE_PRODUCT_OUTPUT||path.join(root,'artifacts');
const pkg=JSON.parse(fs.readFileSync(path.join(process.env.RIVE_WASM_DIR,'package.json')));assert.equal(pkg.name,'@rive-app/canvas-advanced');assert.equal(pkg.version,'2.44.1');
const r=await loadRuntime();
const modes=['Idle','Thinking','Working','Queued','WaitingProvider','WaitingUser','Done','Error','Interrupted'];
const states=[...modes,...modes.map(x=>x+'Reduced')];
const report={scope:'Official Rive WASM host import/binding/state tests of actual Kotlin outputs. Not Android/device acceptance.',runtime:pkg.name+'@'+pkg.version,mascots:[]};
function tick(x,n=1){const a=[];for(let j=0;j<n;j++){x.sm.advanceAndApply(1/60);x.art.advance(1/60);for(let i=0;i<x.sm.stateChangedCount();i++)a.push(x.sm.stateChangedNameByIndex(i));}return a;}
for(const filename of fs.readdirSync(sceneDir).filter(x=>x.endsWith('.json')).sort()){
 const id=path.basename(filename,'.json'),scene=JSON.parse(fs.readFileSync(path.join(sceneDir,filename)));assert.equal(scene.contract,'bot-mascot-v1');assert.deepEqual(Object.keys(scene.animations).sort(),[...states].sort());
 const bytes=fs.readFileSync(path.join(out,id+'.riv'));const file=await r.load(new Uint8Array(bytes),undefined,false);assert(file);assert.equal(file.artboardCount(),1);assert.equal(file.viewModelCount(),1);
 const vm=file.viewModelByName('MascotState');assert(vm);assert.equal(vm.instanceCount,1);assert.deepEqual(vm.getInstanceNames(),['Default']);assert.deepEqual(vm.getProperties().map(p=>[p.name,p.type.value??p.type]),[['mode','number'],['reducedMotion','boolean']]);
 const targets=scene.animations.Idle.tracks.map(t=>[t.node,t.property]);
 function instance(){const art=file.defaultArtboard(),vi=vm.defaultInstance();assert.equal(art.animationCount(),18);assert.equal(art.stateMachineCount(),1);assert.equal(file.defaultArtboardViewModel(art).name,'MascotState');const sm=new r.StateMachineInstance(art.stateMachineByName('MascotController'),art);assert.equal(sm.inputCount(),0);sm.bindViewModelInstance(vi);return {art,vi,sm,close(){sm.delete();art.delete();vi.delete();}};}
 function state(x,name){const reduced=name.endsWith('Reduced'),normal=reduced?name.slice(0,-7):name;x.vi.number('mode').value=modes.indexOf(normal);x.vi.boolean('reducedMotion').value=reduced;}
 const pose=x=>targets.map(([n,p])=>{const value=x.art.node(n)[p];assert(Number.isFinite(value));return value;});
 const x=instance();assert.equal(x.vi.number('mode').value,0);assert.equal(x.vi.boolean('reducedMotion').value,false);assert(tick(x,1).includes('Idle'),'Fresh default instance must enter Idle');const transitions=[];
 for(const from of states)for(const to of states){if(from===to)continue;state(x,from);tick(x,2);state(x,to);assert(tick(x,2).includes(to),id+' '+from+'→'+to);transitions.push([from,to]);}
 const motion=[];
 for(const name of states){state(x,name);tick(x,2);const p=pose(x),unique=new Set([JSON.stringify(p)]);for(let f=0;f<180;f++){assert.deepEqual(tick(x),[],name+' must not reenter itself');unique.add(JSON.stringify(pose(x)));}
 if(name.endsWith('Reduced')){assert.equal(unique.size,1,name+' moved');for(const t of scene.animations[name].tracks){assert.equal(t.keys.length,1);assert(Math.abs(x.art.node(t.node)[t.property]-t.keys[0][1])<1e-4,name+' stale target '+t.node+'.'+t.property);}}
 else assert(unique.size>1,name+' did not animate');motion.push({name,distinctPoses:unique.size,noReentryFrames:180});}
 // Check every authored linear segment midpoint independently of controller transition timing.
 const art=file.defaultArtboard();let midpointChecks=0;
 for(const name of states){const ai=new r.LinearAnimationInstance(art.animationByName(name),art);for(const t of scene.animations[name].tracks)for(let k=1;k<t.keys.length;k++){const a=t.keys[k-1],b=t.keys[k];ai.time=(a[0]+b[0])/120;ai.advance(0);ai.apply(1);art.advance(0);assert(Math.abs(art.node(t.node)[t.property]-(a[1]+b[1])/2)<1e-3,id+' midpoint '+name+' '+t.node+'.'+t.property);midpointChecks++;}ai.delete();}art.delete();
 const y=instance();state(y,'ErrorReduced');tick(y,2);const yp=pose(y);state(x,'Thinking');tick(x,80);tick(y,80);assert.deepEqual(pose(y),yp);assert.equal(y.vi.number('mode').value,7);assert.equal(y.vi.boolean('reducedMotion').value,true);
 x.close();y.close();file.delete();
 report.mascots.push({id,bytes:bytes.length,sha256:crypto.createHash('sha256').update(bytes).digest('hex'),animations:18,viewModels:1,properties:2,legacyInputs:0,transitions:transitions.length,normalDirectedTransitions:72,staticReducedStates:9,motion,midpointChecks,independentInstances:true});
}
assert.equal(report.mascots.length,2,'Two original fixtures required');report.passed=true;fs.writeFileSync(path.join(out,'product-runtime-validation.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));
