import fs from 'node:fs';import assert from 'node:assert/strict';import crypto from 'node:crypto';
import {loadRuntime} from './runtime_loader.mjs';
const r=await loadRuntime();
const report={scope:'Independent official Rive WASM 2.44.1 host import/state/transform tests. No Rive CLI compilation, Android execution or production-security claim.',mascots:[]};
function advance(x,n){const changes=[];for(let j=0;j<n;j++){x.sm.advanceAndApply(1/60);x.art.advance(1/60);for(let i=0;i<x.sm.stateChangedCount();i++)changes.push(x.sm.stateChangedNameByIndex(i));}return changes;}
for(const id of ['miga','tallo']){
 const scene=JSON.parse(fs.readFileSync(`scenes/${id}.json`));
 const bytes=fs.readFileSync(`artifacts/${id}.riv`);const f=await r.load(new Uint8Array(bytes),undefined,false);assert(f);assert.equal(f.artboardCount(),1);
 const names=id==='miga'?['Body','FootL','FootR']:['Stem','LeafL','LeafR'];
 function instance(){const art=f.defaultArtboard();assert.equal(art.animationCount(),3);assert.equal(art.stateMachineCount(),1);const sm=new r.StateMachineInstance(art.stateMachineByIndex(0),art);assert.equal(sm.inputCount(),2);assert.equal(sm.input(0).name,'mode');assert.equal(sm.input(1).name,'reducedMotion');return {art,sm,mode:sm.input(0).asNumber(),reduced:sm.input(1).asBool(),close(){sm.delete();art.delete();}};}
 function pose(x){return names.map(name=>{const n=x.art.node(name);assert(n);const p=[n.x,n.y,n.rotation,n.scaleX,n.scaleY];assert(p.every(Number.isFinite));return p;});}
 function state(x,name){x.mode.value=name==='Idle'?0:1;x.reduced.value=name==='Reduced';}
 const motion=[];
 for(const name of ['Idle','Active','Reduced']){const x=instance();state(x,name);const entered=advance(x,1);assert(entered.includes(name));const p=pose(x);advance(x,name==='Reduced'?180:21);if(name==='Reduced')assert.deepEqual(pose(x),p);else assert.notDeepEqual(pose(x),p);motion.push({name,entered:true,selectedTransformsMove:name!=='Reduced',constantFrames:name==='Reduced'?180:null});x.close();}
 const x=instance();advance(x,1);const transitions=[];
 for(const from of ['Idle','Active','Reduced'])for(const to of ['Idle','Active','Reduced']){if(from===to)continue;state(x,from);advance(x,2);state(x,to);const changes=advance(x,2);assert(changes.includes(to),`${from}->${to} ${changes}`);transitions.push({from,to,passed:true});}
 state(x,'Active');advance(x,1);assert.equal(advance(x,120).length,0,'AnyState must not restart same state every frame');
 // Compare active motion after a complete cycle to exclude entry-only movement.
 const afterCycle=pose(x);advance(x,17);assert.notDeepEqual(pose(x),afterCycle);
 state(x,'Reduced');advance(x,2);
 for(const track of scene.animations.Reduced.tracks){const value=x.art.node(track.node)[track.property];assert(Math.abs(value-track.keys[0][1])<1e-6,'exact neutral reset '+track.node);}
 const neutral=pose(x);x.mode.value=0;for(let i=0;i<90;i++){assert.equal(advance(x,1).length,0);assert.deepEqual(pose(x),neutral);}x.mode.value=1;for(let i=0;i<90;i++){advance(x,1);assert.deepEqual(pose(x),neutral);}
 // Test linear interpolation numerically, independently of state transitions.
 const aa=f.defaultArtboard();const ai=new r.LinearAnimationInstance(aa.animationByName('Active'),aa);
 for(const track of scene.animations.Active.tracks){if(track.keys.length<2)continue;const [a,b]=track.keys;ai.time=(a[0]+b[0])/120;ai.advance(0);ai.apply(1);aa.advance(0);assert(Math.abs(aa.node(track.node)[track.property]-(a[1]+b[1])/2)<1e-4,'linear midpoint '+track.node);}
 ai.delete();aa.delete();
 const y=instance();state(y,'Reduced');advance(y,2);const p=pose(y);state(x,'Idle');advance(x,40);advance(y,40);assert.deepEqual(pose(y),p);assert.equal(y.reduced.value,true);x.close();y.close();f.delete();
 report.mascots.push({id,bytes:bytes.length,sha256:crypto.createHash('sha256').update(bytes).digest('hex'),animationCount:3,stateMachines:1,stateInputs:2,motion,transitions,independentInstances:true,sameStateNotRetriggered:true,postCycleMovement:true,neutralReset:true,modeChangeWhileReducedStatic:true,linearMidpoints:true});
}
const good=fs.readFileSync('artifacts/miga.riv');const wrongMajor=Buffer.from(good);wrongMajor[4]=8;
const invalids=[['badMagic',Buffer.from([1,2,3,4,5])],['headerTruncated',good.subarray(0,6)],['wrongMajor',wrongMajor],['bodyTruncated',good.subarray(0,91)]];
report.invalid=[];
for(const [name,bytes]of invalids){let rejected=false;try{const f=await r.load(new Uint8Array(bytes),undefined,false);rejected=!f;if(f)f.delete();}catch{rejected=true;}assert(rejected,name);report.invalid.push({name,rejected});}
report.passed=true;fs.writeFileSync('artifacts/runtime-validation.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({passed:true,mascots:report.mascots.map(x=>({id:x.id,bytes:x.bytes,transitions:x.transitions.length})),invalidRejected:report.invalid.length}));
