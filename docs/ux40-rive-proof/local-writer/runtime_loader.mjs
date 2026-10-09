import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';

export async function loadRuntime(){
 const dir=path.resolve(process.env.RIVE_WASM_DIR||'node_modules/@rive-app/canvas-advanced');
 const {default:Rive}=await import(pathToFileURL(path.join(dir,'canvas_advanced.mjs')).href);
 return Rive({wasmBinary:fs.readFileSync(path.join(dir,'rive.wasm'))});
}
