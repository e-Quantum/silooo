import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { CINEMATIQUE_TEXTES } from "../src/ui/textes";

const ui=JSON.parse(readFileSync(join(process.cwd(),"../resource-pack/ui/silooo/cinematique.json"),"utf8"));
const controls=ui.scene.controls as Array<Record<string, any>>;
const story=controls.find((control)=>control.recit)?.recit;
const hint=controls.find((control)=>control.consigne)?.consigne;
describe("récit au-dessus de l'inventaire",()=>{
 it("sépare le récit, la consigne et la zone de la barre rapide",()=>{
  assert.ok(story&&hint);
  const storyBottom=-story.offset[1], hintBottom=-hint.offset[1], hintTop=hintBottom+hint.size[1];
  assert.ok(hintBottom>=24,"la consigne reste au-dessus de la barre rapide");
  assert.ok(storyBottom>=hintTop+3,"le récit ne chevauche pas la consigne");
  assert.equal(story.anchor_to,"bottom_middle");
  const background=story.controls.find((control:any)=>control.fond)?.fond;
  assert.equal(background.texture,"textures/ui/silooo/panneau-fonte");
 });
 it("réserve assez de place au nom et aux phrases à petite résolution",()=>{
  const label=story.controls.find((control:any)=>control.parole)?.parole;
  for(const viewportWidth of [225,320,480]){
   const usableWidth=viewportWidth*0.9-24;
   for(const phrase of CINEMATIQUE_TEXTES.plans){
    // Six unités par caractère majorent les glyphes de la police normale ; neuf par ligne.
    const lines=1+Math.ceil(phrase.length*6/usableWidth);
    assert.ok(lines*9<=label.size[1],`${viewportWidth}: ${phrase}`);
   }
  }
  assert.ok(label.size[1]<=story.size[1]);
  assert.equal(label.font_size,"normal");
 });
});
