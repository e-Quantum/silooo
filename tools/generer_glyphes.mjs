#!/usr/bin/env node
// Écrit la planche de glyphes du pack de ressources (resource-pack/font/glyph_<page>.png) à partir des images de
// design/ que design/glyphes.json range dans ses cases. Bedrock dessine alors le caractère U+<page><case> de la plage
// privée d'Unicode avec cette image, dans le tchat, les formulaires et le HUD.
//
//   node tools/generer_glyphes.mjs              écrit la planche
//   node tools/generer_glyphes.mjs --verifier   ne touche à rien ; code 1 si la planche ne correspond plus au script
//
// La planche fait 16 cases sur 16, de `taille` pixels chacune. Une icône de 16 pixels est agrandie sans lissage, à un
// multiple entier (design/README.md, Icônes). Une illustration plus grande (Oooscar) est réduite par moyenne des pixels,
// proportions gardées, centrée en largeur et posée en bas de sa case. Comparaison pixel par pixel, comme les autres
// générateurs : le codage zlib change d'une version de Node à l'autre.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { encoderPng, lirePng } from "./lib/png.mjs";

const RACINE = join(dirname(fileURLToPath(import.meta.url)), "..");
const plan = JSON.parse(readFileSync(join(RACINE, "design/glyphes.json"), "utf8"));
const TAILLE = plan.taille;
const COTE = TAILLE * 16;
const SORTIE = join(RACINE, `resource-pack/font/glyph_${plan.page}.png`);
const NOM_SORTIE = `resource-pack/font/glyph_${plan.page}.png`;

if (!/^[E-F][0-9A-F]$/.test(plan.page)) throw new Error(`design/glyphes.json : page ${plan.page} hors de la plage privée E0-F8`);
const cases = new Set();
for (const { nom, case: numero } of plan.glyphes) {
  if (!Number.isInteger(numero) || numero < 0 || numero > 255) throw new Error(`design/glyphes.json : ${nom}, case ${numero} hors de 0-255`);
  if (cases.has(numero)) throw new Error(`design/glyphes.json : la case ${numero} est prise deux fois`);
  cases.add(numero);
}

/** Copie une image dans la case `numero` de la planche, agrandie ou réduite pour y tenir. */
function poser(planche, image, numero) {
  const x0 = (numero % 16) * TAILLE;
  const y0 = Math.floor(numero / 16) * TAILLE;
  const { largeur, hauteur, pixels } = image;
  if (largeur <= TAILLE && hauteur <= TAILLE) {
    // Agrandissement sans lissage, au plus grand multiple entier qui tient.
    const k = Math.floor(TAILLE / Math.max(largeur, hauteur));
    const dx = x0 + Math.floor((TAILLE - largeur * k) / 2);
    const dy = y0 + (TAILLE - hauteur * k);
    for (let y = 0; y < hauteur * k; y++) {
      for (let x = 0; x < largeur * k; x++) {
        const source = (Math.floor(y / k) * largeur + Math.floor(x / k)) * 4;
        planche.set(pixels.subarray(source, source + 4), ((dy + y) * COTE + dx + x) * 4);
      }
    }
    return;
  }
  // Réduction par moyenne, couleurs pondérées par l'opacité pour ne pas assombrir les bords.
  const echelle = Math.max(largeur, hauteur) / TAILLE;
  const l = Math.round(largeur / echelle);
  const h = Math.round(hauteur / echelle);
  const dx = x0 + Math.floor((TAILLE - l) / 2);
  const dy = y0 + (TAILLE - h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < l; x++) {
      const sx0 = Math.floor((x * largeur) / l);
      const sx1 = Math.max(sx0 + 1, Math.floor(((x + 1) * largeur) / l));
      const sy0 = Math.floor((y * hauteur) / h);
      const sy1 = Math.max(sy0 + 1, Math.floor(((y + 1) * hauteur) / h));
      let r = 0;
      let g = 0;
      let b = 0;
      let a = 0;
      let n = 0;
      for (let sy = sy0; sy < sy1; sy++) {
        for (let sx = sx0; sx < sx1; sx++) {
          const p = (sy * largeur + sx) * 4;
          const alpha = pixels[p + 3];
          r += pixels[p] * alpha;
          g += pixels[p + 1] * alpha;
          b += pixels[p + 2] * alpha;
          a += alpha;
          n += 1;
        }
      }
      const cible = ((dy + y) * COTE + dx + x) * 4;
      if (a === 0) continue;
      planche[cible] = Math.round(r / a);
      planche[cible + 1] = Math.round(g / a);
      planche[cible + 2] = Math.round(b / a);
      planche[cible + 3] = Math.round(a / n);
    }
  }
}

const planche = new Uint8Array(COTE * COTE * 4);
for (const { source, case: numero } of plan.glyphes) poser(planche, lirePng(readFileSync(join(RACINE, "design", source))), numero);

if (process.argv.includes("--verifier")) {
  let ecart = "";
  if (!existsSync(SORTIE)) ecart = `${NOM_SORTIE} est absent`;
  else {
    const lue = lirePng(readFileSync(SORTIE));
    if (lue.largeur !== COTE || lue.hauteur !== COTE || Buffer.compare(Buffer.from(lue.pixels), Buffer.from(planche)) !== 0) {
      ecart = `${NOM_SORTIE} n'a pas les pixels que le script compose`;
    }
  }
  if (ecart) {
    console.error(`${ecart} : relance node tools/generer_glyphes.mjs`);
    process.exit(1);
  }
  console.log(`Glyphes : ${NOM_SORTIE} conforme à design/glyphes.json.`);
} else {
  mkdirSync(dirname(SORTIE), { recursive: true });
  writeFileSync(SORTIE, encoderPng(planche, COTE, COTE));
  console.log(`Glyphes : ${plan.glyphes.length} images dans ${NOM_SORTIE} (${COTE} sur ${COTE}, cases de ${TAILLE}).`);
}
