#!/usr/bin/env node
// Assets du chantier Carrière, suivant le générateur existant d'Oooscar : palette Atelier, PNG et modèles natifs.
// Les dessins nouveaux sont à valider par Kr. Aucun chiffre d'économie n'est produit ici.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { deflateSync } from "node:zlib";
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const check = process.argv.includes("--verifier");
const palette = JSON.parse(readFileSync(resolve(root, "design/tokens.json"))).color.tokens;
const color = (name) => { const hex = palette.find((entry) => entry.name === name)?.value; if (!hex) throw Error(name); return [...hex.slice(1).match(/../g).map((part) => parseInt(part, 16)), 255]; };
const ink=color("fonte-900"), steel=color("fonte-500"), light=color("acier-100"), blue=color("email-bleu"), gold=color("laiton"), paper=color("papier"), green=color("email-vert"), red=color("email-rouge"), skin=color("papier-ombre");
// Tons des planches de la charte (design/decor/planches.png), qui ne sont pas des jetons de couleur.
const decor=(hex)=>[...hex.slice(1).match(/../g).map((part)=>parseInt(part,16)),255];
const bois_clair=decor("#9a6c43"), bois=decor("#6b4a2e"), bois_sombre=decor("#46301e");
let failures=0;
function output(path, value) {
  const file=resolve(root,path), data=Buffer.isBuffer(value)?value:Buffer.from(JSON.stringify(value,null,2)+"\n");
  if(check) { if(!existsSync(file)||!readFileSync(file).equals(data)){console.error(`${path}: doit être régénéré`);failures++;} }
  else {mkdirSync(dirname(file),{recursive:true});writeFileSync(file,data);}
}
function crc(buffer) {let value=0xffffffff;for(const byte of buffer){value^=byte;for(let i=0;i<8;i++)value=(value>>>1)^((value&1)?0xedb88320:0);}return(value^0xffffffff)>>>0;}
function png(width,height,paint) {
  const bytes=Buffer.alloc(height*(width*4+1));
  for(let y=0;y<height;y++)for(let x=0;x<width;x++)Buffer.from(paint(x,y)).copy(bytes,y*(width*4+1)+1+x*4);
  const chunk=(type,data)=>{const name=Buffer.from(type),size=Buffer.alloc(4),sum=Buffer.alloc(4);size.writeUInt32BE(data.length);sum.writeUInt32BE(crc(Buffer.concat([name,data])));return Buffer.concat([size,name,data,sum]);};
  const header=Buffer.alloc(13);header.writeUInt32BE(width);header.writeUInt32BE(height,4);header[8]=8;header[9]=6;
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk("IHDR",header),chunk("IDAT",deflateSync(bytes)),chunk("IEND",Buffer.alloc(0))]);
}
function blockTexture(kind,active=false) {
  return png(16,active?64:16,(x,absoluteY)=>{
    const y=absoluteY%16,phase=Math.floor(absoluteY/16);
    if(x===0||x===15||y===0||y===15)return ink;
    if((x===2||x===13)&&(y===2||y===13))return light;
    if(kind==="convoyeur") {
      if(y<3||y>12)return steel;
      const movingX = (x - (active ? phase * 2 : 0) + 16) % 16;
      if(movingX>4&&movingX<10&&y>5&&y<10)return (movingX+y)%3===0?light:steel;
      if(((x+(active?phase*2:0))%8===0)||((x+(active?phase*2:0)+y)%8===0&&y>5&&y<11))return gold;
      return ink;
    }
    if(kind==="broyeur") {
      if(x>3&&x<12&&y>3&&y<10)return (x+(active?phase:0))%3===0?light:ink;
      if(x>10&&x<14&&y>10&&y<14)return active?green:red;
      if(y===3||y===11)return gold;
      return steel;
    }
    if(kind==="bureau_administration") {
      // Le bureau du contremaître, époque 1 : planches (tons de design/decor/planches.png), un registre, un encrier.
      if(x>3&&x<11&&y>3&&y<10)return (y===5||y===7)&&x>4&&x<10?skin:paper;
      if(x>11&&x<14&&y>4&&y<8)return y===5?ink:gold;
      if(y%5===4)return bois_sombre;
      return ((x*3+y)%11===0)?bois:bois_clair;
    }
    if(x<3||x>12||y<3||y>12)return gold;
    return ((x*7+y*11)%9<3)?light:steel;
  });
}
const names=["broyeur","convoyeur","bac_pierre","bureau_administration"];
const terrain={resource_pack_name:"silooo",texture_name:"atlas.terrain",padding:8,num_mip_levels:4,texture_data:{}};
for(const name of names)for(const active of [false,...(["broyeur","convoyeur"].includes(name)?[true]:[])]){
  const key=name+(active?"_actif":""),data=blockTexture(name,active);
  output(`design/blocs/${key}.png`,data);output(`resource-pack/textures/blocks/silooo/${key}.png`,data);
  terrain.texture_data[`silooo_${key}`]={textures:`textures/blocks/silooo/${key}`};
}
// Ces trois fichiers sont partagés avec les blocs de la Carrière de l'époque 1 (docs/carriere.md) : le générateur
// ne réécrit que ses propres entrées, placées en tête, et garde les autres telles qu'elles sont, dans leur ordre.
function existant(path, vide) { const file=resolve(root,path); return existsSync(file)?JSON.parse(readFileSync(file,"utf8")):vide; }
const atelierCle=(cle)=>names.some((name)=>cle===`silooo:${name}`||cle===`silooo_${name}`||cle===`silooo_${name}_actif`);
const autresTextures=Object.fromEntries(Object.entries(existant("resource-pack/textures/terrain_texture.json",{texture_data:{}}).texture_data??{}).filter(([cle])=>!atelierCle(cle)));
output("resource-pack/textures/terrain_texture.json",{...terrain,texture_data:{...terrain.texture_data,...autresTextures}});
const autresFlipbooks=existant("resource-pack/textures/flipbook_textures.json",[]).filter((entree)=>!atelierCle(entree.atlas_tile));
output("resource-pack/textures/flipbook_textures.json",[...["broyeur","convoyeur"].map((name)=>({flipbook_texture:`textures/blocks/silooo/${name}_actif`,atlas_tile:`silooo_${name}_actif`,ticks_per_frame:3,frames:[0,1,2,3],blend_frames:false})),...autresFlipbooks]);
const autresSons=Object.fromEntries(Object.entries(existant("resource-pack/blocks.json",{})).filter(([cle])=>cle!=="format_version"&&!atelierCle(cle)));
output("resource-pack/blocks.json",{format_version:"1.19.30",...Object.fromEntries(names.map((name)=>[`silooo:${name}`,{sound:name==="bureau_administration"?"wood":"metal"}])),...autresSons});
const faces=["north","south","east","west","up","down"];
function cube(origin,size,material="*",uv=[0,0]) {return{origin,size,uv:Object.fromEntries(faces.map((face)=>[face,{uv,uv_size:[16,16],material_instance:material}]))};}
const models={
  broyeur:[cube([-7,0,-7],[14,4,14]),cube([-6,4,-6],[12,8,12]),cube([-8,12,-8],[16,4,3]),cube([-8,12,5],[16,4,3]),cube([-8,12,-5],[3,4,10]),cube([5,12,-5],[3,4,10])],
  convoyeur:[cube([-8,3,-7],[16,5,14]),cube([-8,8,-8],[16,2,2]),cube([-8,8,6],[16,2,2]),cube([-7,0,-6],[3,3,3]),cube([4,0,3],[3,3,3])],
  bac_pierre:[cube([-8,0,-8],[16,3,16]),cube([-8,3,-8],[16,8,2]),cube([-8,3,6],[16,8,2]),cube([-8,3,-6],[2,8,12]),cube([6,3,-6],[2,8,12]),cube([-5,3,-5],[10,9,10])],
  bureau_administration:[cube([-8,10,-8],[16,3,16]),cube([-7,0,-7],[2,10,2]),cube([5,0,-7],[2,10,2]),cube([-7,0,5],[2,10,2]),cube([5,0,5],[2,10,2]),cube([-6,2,0],[5,8,6]),cube([-4,13,1],[8,3,3])],
};
for(const name of names){
  const running=["broyeur","convoyeur"].includes(name);
  output(`resource-pack/models/blocks/${name}.geo.json`,{format_version:"1.12.0","minecraft:geometry":[{description:{identifier:`geometry.silooo_${name}`,texture_width:16,texture_height:16,visible_bounds_width:2,visible_bounds_height:2,visible_bounds_offset:[0,0.5,0]},bones:[{name:"root",pivot:[0,0,0],cubes:models[name]}]}]});
  const components={"minecraft:geometry":{identifier:`geometry.silooo_${name}`},"minecraft:material_instances":{"*":{texture:`silooo_${name}`,render_method:"opaque"}},"minecraft:destructible_by_mining":false,"minecraft:destructible_by_explosion":false,"minecraft:light_dampening":0,"silooo:atelier":{}};
  if(name==="convoyeur")components["minecraft:collision_box"]={origin:[-8,0,-8],size:[16,10,16]};
  const definition={description:{identifier:`silooo:${name}`,...(running?{states:{"silooo:actif":[false,true]}}:{})},components};
  if(running)definition.permutations=[{condition:"q.block_state('silooo:actif')",components:{"minecraft:material_instances":{"*":{texture:`silooo_${name}_actif`,render_method:"opaque"}}}}];
  output(`addon/pack/blocks/${name}.json`,{format_version:"1.21.90","minecraft:block":definition});
}
// Un salarié ordinaire de production, représenté sans créer d'embauche ni de bonus dans le service.
// Son apparence est celle des salariés de la Carrière de l'époque 1 (modèle, skin commun, gestes : docs/carriere.md) :
// il pioche quand l'usine tourne, il se repose quand elle est arrêtée. Le comportement reste celui de la 0.1.4.
output("addon/pack/entities/ouvrier.json",{format_version:"1.21.0","minecraft:entity":{description:{identifier:"silooo:ouvrier",is_spawnable:false,is_summonable:true,properties:{"silooo:travaille":{type:"bool",default:false,client_sync:true}}},components:{"minecraft:type_family":{family:["silooo_ouvrier","npc"]},"minecraft:collision_box":{width:0.6,height:2.1},"minecraft:health":{value:1,max:1},"minecraft:physics":{},"minecraft:movement":{value:0},"minecraft:pushable":{is_pushable:false,is_pushable_by_piston:false},"minecraft:damage_sensor":{triggers:[{cause:"all",deals_damage:false}]},"minecraft:interact":{interactions:[{interact_text:"action.interact.silooo.personnel",on_interact:{event:"silooo:parler",target:"self"}}]}},events:{"silooo:parler":{set_property:{"silooo:travaille":"query.property('silooo:travaille')"}}}}});
output("resource-pack/entity/ouvrier.entity.json",{format_version:"1.10.0","minecraft:client_entity":{description:{identifier:"silooo:ouvrier",materials:{default:"entity_alphatest"},textures:{default:"textures/entity/salarie_commun"},geometry:{default:"geometry.silooo.salarie"},animations:{piocher:"animation.silooo_salarie.piocher",repos:"animation.silooo_salarie.inactif"},scripts:{animate:[{piocher:"q.property('silooo:travaille')"},{repos:"!q.property('silooo:travaille')"}]},sound_effects:{pioche:"silooo.carriere.pioche"},particle_effects:{eclats:"silooo:eclats_pierre"},render_controllers:["controller.render.silooo_ouvrier"]}}});
output("resource-pack/render_controllers/ouvrier.render_controllers.json",{format_version:"1.8.0",render_controllers:{"controller.render.silooo_ouvrier":{geometry:"Geometry.default",materials:[{"*":"Material.default"}],textures:["Texture.default"],part_visibility:[{"charge":false}]}}});
// Les pierres sont un décor animé : aucun objet ramassable ni quantité de stock.
const transport=JSON.parse(readFileSync(resolve(root,"definitions/iles.json"))).atelier.transport;
// Les tons de la tuile de pierre de la charte (design/decor/pierre.png) : un éclat de carrière, lumière en haut à gauche.
const pale=color("fonte-300");
const stone=png(16,16,(x,y)=>{ if(x+y<6)return light; if(x+y>22)return steel; return ((x*7+y*11)%9<2)?light:((x*5+y*3)%11<2)?steel:pale; });
output("design/production/pierre.png",stone);output("resource-pack/textures/entity/pierre.png",stone);
// Un caillou irrégulier d'un quart de bloc, posé sur la toile : deux pavés décalés plutôt qu'un cube.
const cargoBones=[{name:"root",pivot:[0,0,0],cubes:[cube([-2,0,-2],[4,2,4]),cube([-1,2,-2],[3,1,3]),cube([1,0,1],[2,1,2])]}];
output("resource-pack/models/entity/production.geo.json",{format_version:"1.12.0","minecraft:geometry":[{description:{identifier:"geometry.silooo_production",texture_width:16,texture_height:16,visible_bounds_width:1,visible_bounds_height:2,visible_bounds_offset:[0,0.5,0]},bones:cargoBones}]});
output("addon/pack/entities/production.json",{format_version:"1.21.0","minecraft:entity":{description:{identifier:"silooo:production",is_spawnable:false,is_summonable:true,properties:{"silooo:actif":{type:"bool",default:false,client_sync:true}}},components:{"minecraft:type_family":{family:["silooo_production"]},"minecraft:collision_box":{width:0.05,height:0.05},"minecraft:health":{value:1,max:1},"minecraft:physics":{has_gravity:false,has_collision:false},"minecraft:pushable":{is_pushable:false,is_pushable_by_piston:false},"minecraft:damage_sensor":{triggers:[{cause:"all",deals_damage:false}]}}}});
output("resource-pack/entity/production.entity.json",{format_version:"1.10.0","minecraft:client_entity":{description:{identifier:"silooo:production",materials:{default:"entity_alphatest"},textures:{default:"textures/entity/pierre"},geometry:{default:"geometry.silooo_production"},animations:{transport:"animation.silooo_production.transport"},scripts:{animate:[{transport:"q.property('silooo:actif')"}]},render_controllers:["controller.render.silooo_production"]}}});
output("resource-pack/animations/production.animation.json",{format_version:"1.8.0",animations:{"animation.silooo_production.transport":{loop:true,animation_length:1,bones:{root:{position:{"0.0":[0,0,0],"0.5":{pre:[0,0,0],post:[0,1,0]},"1.0":{pre:[0,1,0],post:[0,0,0]}}}}}}});
output("resource-pack/render_controllers/production.render_controllers.json",{format_version:"1.8.0",render_controllers:{"controller.render.silooo_production":{geometry:"Geometry.default",materials:[{"*":"Material.default"}],textures:["Texture.default"]}}});
console.log(`Atelier : ${check?"contrôle":"génération"} terminé, ${failures} erreur(s).`);if(failures)process.exit(1);
