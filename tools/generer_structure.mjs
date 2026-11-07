#!/usr/bin/env node
// Le format NBT little-endian suit l'exemple officiel Microsoft structure_blocks_sample_behavior_pack.
// La construction est la nôtre ; seuls le format et la version de palette viennent de l'exemple officiel.
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const defs=JSON.parse(readFileSync(resolve(root,'definitions/iles.json')));
const factory=defs.usines.find(x=>x.id===defs.apparition.usine), radius=factory.rayon??defs.plateforme.rayon;
const bottom=-(defs.plateforme.couches.length-1), top=defs.atelier.hauteurNettoyage;
const size=[radius*2+1,top-bottom+1,radius*2+1], count=size.reduce((a,b)=>a*b,1);
const palette=[], lookup=new Map(), indices=new Array(count).fill(0);
function entry(name,states={}) {const key=JSON.stringify([name,states]);if(!lookup.has(key)){lookup.set(key,palette.length);palette.push({name,states});}return lookup.get(key);}
entry('minecraft:air');
function put(f,r,h,name,states={}) {
 const [x,y,z]=[radius-f,h-bottom,radius-r];
 if(![x,y,z].every((n,i)=>Number.isInteger(n)&&n>=0&&n<size[i]))throw Error(`hors structure : ${f},${r},${h}`);
 indices[x*size[1]*size[2]+y*size[2]+z]=entry(name,states);
}
for(const [i,layer] of defs.plateforme.couches.entries()) {
 const r=radius-layer.retrait;
 for(let z=-r;z<=r;z++){const half=Math.floor(Math.sqrt(Math.max(0,r*r+r-z*z)));for(let x=-half;x<=half;x++)put(x,z,-i,defs.materiaux[layer.materiau]);}
}
for(const volume of defs.atelier.volumes)for(let f=volume.de.avant;f<=volume.a.avant;f++)for(let r=volume.de.droite;r<=volume.a.droite;r++)for(let h=volume.de.haut;h<=volume.a.haut;h++)put(f,r,h,volume.bloc);
for(const machine of defs.atelier.machines)put(machine.position.avant,machine.position.droite,machine.position.haut,machine.bloc,
 ['broyeur','convoyeur'].includes(machine.role)?{'silooo:actif':false}:{});
const sign=defs.atelier.enseigne;
put(sign.support.avant,sign.support.droite,sign.support.haut,defs.materiaux.planches);
put(sign.position.avant,sign.position.droite,sign.position.haut,defs.materiaux.enseigne,{facing_direction:4});
const byte=n=>Buffer.from([n&255]);
const int=n=>{const b=Buffer.alloc(4);b.writeInt32LE(n);return b;};
const str=s=>{const v=Buffer.from(s),b=Buffer.alloc(2);b.writeUInt16LE(v.length);return Buffer.concat([b,v]);};
const named=(type,name,value)=>Buffer.concat([byte(type),str(name),value]);
const compound=children=>Buffer.concat([...children,byte(0)]);
const list=(type,items)=>Buffer.concat([byte(type),int(items.length),...items]);
const stateValue=(name,value)=>typeof value==='string'?named(8,name,str(value)):typeof value==='boolean'?named(1,name,byte(value?1:0)):named(3,name,int(value));
const file=named(10,'',compound([
 named(3,'format_version',int(1)),named(9,'size',list(3,size.map(int))),
 named(10,'structure',compound([
  named(9,'block_indices',list(9,[list(3,indices.map(int)),list(3,new Array(count).fill(-1).map(int))])),
  named(9,'entities',list(10,[])),
  named(10,'palette',compound([named(10,'default',compound([
   named(9,'block_palette',list(10,palette.map(p=>compound([
    named(8,'name',str(p.name)),named(10,'states',compound(Object.entries(p.states).map(([k,v])=>stateValue(k,v)))),named(3,'version',int(17959425))
   ])))),named(10,'block_position_data',compound([]))
  ]))]))
 ])),named(9,'structure_world_origin',list(3,[0,0,0].map(int)))
]));
const [namespace,id]=defs.atelier.structure.split(':');
const path=resolve(root,`addon/pack/structures/${namespace}/${id}.mcstructure`);
if(process.argv.includes('--verifier')) {
 if(!existsSync(path)||!readFileSync(path).equals(file)){console.error('La structure Carrière doit être régénérée.');process.exit(1);}
}else{mkdirSync(dirname(path),{recursive:true});writeFileSync(path,file);}
console.log(`Structure Carrière : ${size.join(' × ')}, ${palette.length} permutations, ${file.length} octets.`);
