#!/usr/bin/env node
// Tire l'île de la page d'attente de l'aperçu 3D de la Carrière (l'artefact de l'atelier, « Carrière Silooo, aperçu 3D »).
// L'aperçu embarque ses données dans la page : DATA (les blocs, la palette et ses textures, les entités) et MOD (les
// modèles, animations et particules Bedrock du pack de ressources). Ce script en garde ce que la page d'attente montre,
// la Carrière en marche, sans les sons ni la cinématique, et écrit deux fichiers de core/src/main/resources/site/public/ile/ :
//
//   carriere.json  les données de la scène, lues par carriere.js
//   molang.js      les expressions Molang des animations et des particules, compilées en fonctions JavaScript. L'aperçu
//                  les compile dans le navigateur (new Function), ce que la politique de sécurité du site interdit
//                  (script-src 'self', sans 'unsafe-eval') : elles le sont ici, une fois, dans un fichier du site.
//
// Usage : node tools/site_ile.mjs <apercu.html>
//   <apercu.html>  la page de l'aperçu, enregistrée telle quelle (Artifact, action read)
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';

const [source] = process.argv.slice(2);
if (!source) {
  console.error('Usage : node tools/site_ile.mjs <apercu.html>');
  process.exit(64);
}
const html = readFileSync(source, 'utf8');
const SORTIE = path.join(path.dirname(new URL(import.meta.url).pathname), '..', 'core/src/main/resources/site/public/ile');

/** La valeur JSON d'une ligne « const NOM = {...}; » de la page. */
function constante(nom) {
  const ligne = html.split('\n').find((l) => l.startsWith(`const ${nom} = `));
  if (!ligne) throw new Error(`${source} : pas de « const ${nom} »`);
  return JSON.parse(ligne.slice(`const ${nom} = `.length).replace(/;\s*$/, ''));
}
const DATA = constante('DATA');
const MOD = constante('MOD');

// ---------- Les blocs en marche, et seulement la palette et les textures qu'ils utilisent ----------
const blocs = [];
const utilises = new Map();
for (let i = 0; i < DATA.blocs.length; i += 5) {
  const [x, y, z, , m] = DATA.blocs.slice(i, i + 5);
  if (m < 0) continue;
  if (!utilises.has(m)) utilises.set(m, utilises.size);
  blocs.push(x, y, z, utilises.get(m));
}
const palette = [...utilises.keys()].map((i) => {
  const { id, forme, faces } = DATA.palette[i];
  return { id, forme, faces };
});
const noms = new Set(['sapin', 'planches', 'chene-noir', 'pierre', 'pierres']); // les matériaux des wagonnets et de la charrette
for (const b of palette) for (const n of Object.values(b.faces)) noms.add(n);
const textures = {};
const images = {};
for (const n of noms) {
  if (!DATA.textures[n]) throw new Error(`texture absente : ${n}`);
  textures[n] = DATA.textures[n];
  if ((DATA.images[n] || 1) > 1) images[n] = DATA.images[n];
}

const scene = {
  source: 'Carrière Silooo, aperçu 3D (atelier) : la Carrière en marche',
  taille: DATA.taille,
  sol: DATA.sol,
  palette,
  textures,
  images,
  blocs,
  entites: DATA.entites.marche,
  emetteurs: DATA.emetteurs.marche,
  reperes: DATA.reperes,
  modeles: {
    geos: MOD.geos,
    anims: MOD.anims,
    tex: Object.fromEntries(Object.entries(MOD.tex).filter(([k]) => k !== 'salarie_legendaire')),
    particles: MOD.particles,
    machines: MOD.machines,
    salarieEffets: MOD.salarieEffets,
  },
};

// ---------- Molang : chaque chaîne des animations, des particules et des machines, compilée ----------
// La même traduction que l'aperçu (fonction mol) : query.property('silooo:x') → v.x, query.anim_time → v.t, etc.
function traduire(e) {
  return String(e)
    .replace(/\bquery\.property\('silooo:(\w+)'\)/g, 'v.$1').replace(/\bquery\.all_animations_finished\b/g, 'v.fini')
    .replace(/\b(?:query|q)\.anim_time\b/g, 'v.t').replace(/\bvariable\.particle_age\b/g, 'v.age')
    .replace(/\bvariable\.particle_lifetime\b/g, 'v.life').replace(/\bvariable\.particle_random_(\d)\b/g, 'v.r$1')
    .replace(/\bvariable\.(\w+)\b/g, '(v.$1 || 0)').replace(/\bmath\.(\w+)/g, 'M.$1');
}
const expressions = new Map();
function parcourir(o) {
  if (typeof o === 'string') {
    if (expressions.has(o)) return;
    const js = traduire(o);
    // Une chaîne qui n'est pas une expression (un identifiant « silooo:x », un nom de texture) ne se compile pas : elle est laissée.
    try { new Function('M', 'v', `return (${js});`); } catch { return; }
    // Un nom seul (« pilon_1 », « animation.silooo_x.y », « textures/particle/x ») se compile mais n'est jamais évalué : inutile de l'écrire.
    if (/^[A-Za-z_][\w./]*$/.test(o) && !/^(query|q|variable|math)\./.test(o)) return;
    expressions.set(o, js);
  } else if (Array.isArray(o)) o.forEach(parcourir);
  else if (o && typeof o === 'object') Object.values(o).forEach(parcourir);
}
parcourir(MOD.anims);
parcourir(MOD.particles);
parcourir(MOD.machines);

const lignes = [...expressions].map(([e, js]) => `  ${JSON.stringify(e)}: (M, v) => (${js}),`);
const molang = `// Généré par tools/site_ile.mjs depuis l'aperçu 3D de la Carrière : ne pas éditer à la main.
// Les expressions Molang des animations, particules et machines du pack, compilées une fois pour que le site n'ait
// jamais à évaluer de code (la politique de sécurité interdit eval et new Function).
export default {
${lignes.join('\n')}
};
`;

mkdirSync(SORTIE, { recursive: true });
writeFileSync(path.join(SORTIE, 'carriere.json'), JSON.stringify(scene));
writeFileSync(path.join(SORTIE, 'molang.js'), molang);
console.log(`carriere.json : ${blocs.length / 4} blocs, ${palette.length} blocs de palette, ${Object.keys(textures).length} textures`);
console.log(`molang.js : ${expressions.size} expressions`);
