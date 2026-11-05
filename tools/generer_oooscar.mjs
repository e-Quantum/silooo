#!/usr/bin/env node
// Dessine Oooscar pour le pack de ressources : le modèle (resource-pack/models/entity/oooscar.geo.json) et sa texture
// (resource-pack/textures/entity/oooscar.png) sortent du même script, pour que chaque cube trouve ses pixels.
//
//   node tools/generer_oooscar.mjs              écrit les deux fichiers
//   node tools/generer_oooscar.mjs --verifier   ne touche à rien ; code 1 si l'un des deux ne correspond plus au script
//
// Les couleurs viennent de design/tokens.json, par leur nom : aucune valeur de couleur n'est écrite ici. La texture est
// comparée pixel par pixel (pas octet par octet : le codage zlib change d'une version de Node à l'autre).
// Le modèle est un petit silo en blocs (docs/design.md, « Oooscar ») : un corps rond approché par trois boîtes, deux
// cerclages gris, une échelle sur son flanc droit, un toit rouge en gradins qui glisse de travers, deux pieds, deux
// bras. Les yeux, les joues et la bouche sont peints sur la face avant. Unités du modèle : un pixel de texture = un
// seizième de bloc. L'avant est la face « north » (vers -z), comme pour tous les mobs de Bedrock.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { deflateSync, inflateSync } from "node:zlib";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const RACINE = join(dirname(fileURLToPath(import.meta.url)), "..");
const SORTIE_MODELE = join(RACINE, "resource-pack/models/entity/oooscar.geo.json");
const SORTIE_TEXTURE = join(RACINE, "resource-pack/textures/entity/oooscar.png");
const IDENTIFIANT = "geometry.silooo_oooscar";
const LARGEUR_TEXTURE = 128;

// ------------------------------------------------------------------ les couleurs de design/tokens.json
const jetons = JSON.parse(readFileSync(join(RACINE, "design/tokens.json"), "utf8")).color.tokens;
function couleur(nom) {
  const jeton = jetons.find((j) => j.name === nom);
  if (!jeton) throw new Error(`jeton de couleur inconnu : ${nom}`);
  const [, r, g, b] = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(jeton.value);
  return [parseInt(r, 16), parseInt(g, 16), parseInt(b, 16), 255];
}

// Un style = trois tons : le dessus et la ligne du haut, le corps, la ligne du bas et le dessous.
const STYLES = {
  tole: { clair: couleur("papier"), base: couleur("email-creme"), sombre: couleur("papier-ombre") },
  cerclage: { clair: couleur("acier-100"), base: couleur("acier-100"), sombre: couleur("fonte-300") },
  toit: { clair: couleur("email-rouge-clair"), base: couleur("email-rouge"), sombre: couleur("email-rouge-sombre") },
  fonte: { clair: couleur("fonte-300"), base: couleur("fonte-500"), sombre: couleur("fonte-900") },
  rail: { clair: couleur("fonte-500"), base: couleur("fonte-700"), sombre: couleur("fonte-900") },
};
const ENCRE = couleur("fonte-900");
const PAPIER = couleur("papier");
const JOUE = couleur("email-rouge-clair");

// ------------------------------------------------------------------ les cubes
// o : coin le plus bas et le plus à gauche ; t : taille ; meme : réutilise les pixels d'un autre cube de même taille.
// La hauteur du corps va de 3 à 16 ; les cerclages sont posés un demi-pixel en dehors, à deux hauteurs.
const CORPS_BAS = 3;
const CORPS_HAUT = 16;
const CORPS_HAUTEUR = CORPS_HAUT - CORPS_BAS;
const CERCLAGE_BAS = 4;
const CERCLAGE_HAUT = 14;
// L'échelle est à droite quand on regarde Oooscar en face, comme sur design/mascotte : c'est -x (en face de lui, on voit +x à
// sa gauche). Seul un vrai client Bedrock le confirme ; s'il est à l'envers, mettre 7 ici (le côté +x) et relancer le script.
const X_ECHELLE = -8;

const OS = [
  { nom: "root", pivot: [0, 0, 0] },
  { nom: "pied_g", parent: "root", pivot: [3, 3, 0] },
  { nom: "pied_d", parent: "root", pivot: [-3, 3, 0] },
  { nom: "corps", parent: "root", pivot: [0, CORPS_BAS, 0] },
  { nom: "echelle", parent: "corps", pivot: [X_ECHELLE, 10, 0] },
  { nom: "toit", parent: "corps", pivot: [0, CORPS_HAUT, 0] },
  { nom: "bras_g", parent: "corps", pivot: [8.5, 13, -4.5] },
  { nom: "bras_d", parent: "corps", pivot: [-8.5, 13, -4.5] },
];

const CUBES = [
  // Pieds : deux dalles sombres sous l'avant du corps.
  { id: "pied_g", os: "pied_g", o: [1, 0, -4], t: [4, 3, 5], style: "fonte" },
  { id: "pied_d", os: "pied_d", o: [-5, 0, -4], t: [4, 3, 5], style: "fonte", meme: "pied_g" },
  // Corps : trois boîtes qui se recouvrent, un octogone vu du dessus (le silo est rond). La boîte du milieu porte la
  // face avant, large de dix pixels ; les deux autres ne dépassent que d'un pixel de chaque côté.
  { id: "corps_a", os: "corps", o: [-7, CORPS_BAS, -5], t: [14, CORPS_HAUTEUR, 10], style: "tole" },
  { id: "corps_b", os: "corps", o: [-5, CORPS_BAS, -7], t: [10, CORPS_HAUTEUR, 14], style: "tole", visage: true },
  { id: "corps_c", os: "corps", o: [-6, CORPS_BAS, -6], t: [12, CORPS_HAUTEUR, 12], style: "tole" },
  // Cerclages : deux anneaux gris, chacun en trois boîtes comme le corps.
  { id: "cerclage_a", os: "corps", o: [-7.5, CERCLAGE_BAS, -5.5], t: [15, 2, 11], style: "cerclage" },
  { id: "cerclage_b", os: "corps", o: [-5.5, CERCLAGE_BAS, -7.5], t: [11, 2, 15], style: "cerclage" },
  { id: "cerclage_c", os: "corps", o: [-6.5, CERCLAGE_BAS, -6.5], t: [13, 2, 13], style: "cerclage" },
  { id: "cerclage_haut_a", os: "corps", o: [-7.5, CERCLAGE_HAUT, -5.5], t: [15, 2, 11], style: "cerclage", meme: "cerclage_a" },
  { id: "cerclage_haut_b", os: "corps", o: [-5.5, CERCLAGE_HAUT, -7.5], t: [11, 2, 15], style: "cerclage", meme: "cerclage_b" },
  { id: "cerclage_haut_c", os: "corps", o: [-6.5, CERCLAGE_HAUT, -6.5], t: [13, 2, 13], style: "cerclage", meme: "cerclage_c" },
  // Échelle sur le flanc droit, deux montants et quatre barreaux (voir X_ECHELLE).
  { id: "montant_av", os: "echelle", o: [X_ECHELLE, 5, -2], t: [1, 10, 1], style: "rail" },
  { id: "montant_ar", os: "echelle", o: [X_ECHELLE, 5, 1], t: [1, 10, 1], style: "rail", meme: "montant_av" },
  ...[6, 8, 10, 12].map((y) => ({ id: `barreau_${y}`, os: "echelle", o: [X_ECHELLE, y, -1], t: [1, 1, 2], style: "fonte", ...(y === 6 ? {} : { meme: "barreau_6" }) })),
  // Toit : trois gradins qui glissent chacun un peu plus vers +x, puis la cheminée. Il est maladroit.
  { id: "toit_1", os: "toit", o: [-8, 16, -8], t: [17, 2, 16], style: "toit" },
  { id: "toit_2", os: "toit", o: [-5, 18, -6], t: [13, 2, 12], style: "toit" },
  { id: "toit_3", os: "toit", o: [-2, 20, -4], t: [9, 2, 8], style: "toit" },
  { id: "cheminee", os: "toit", o: [1, 22, -2], t: [4, 2, 4], style: "fonte" },
  // Bras : deux dalles claires le long du corps, devant l'échelle côté droit.
  { id: "bras_g", os: "bras_g", o: [7.5, 6, -6], t: [2, 7, 3], style: "tole" },
  { id: "bras_d", os: "bras_d", o: [-9.5, 6, -6], t: [2, 7, 3], style: "tole", meme: "bras_g" },
];

// ------------------------------------------------------------------ la texture : un rectangle par face
const FACES = ["north", "south", "east", "west", "up", "down"];

/** Les six rectangles d'un cube, dans un gabarit en croix de L = 2 (sx + sz) sur H = sy + sz pixels. */
function gabarit([sx, sy, sz]) {
  return {
    largeur: 2 * (sx + sz),
    hauteur: sy + sz,
    faces: {
      up: [sz, 0, sx, sz],
      down: [sz + sx, 0, sx, sz],
      east: [0, sz, sz, sy],
      north: [sz, sz, sx, sy],
      west: [sz + sx, sz, sz, sy],
      south: [2 * sz + sx, sz, sx, sy],
    },
  };
}

/** Pose chaque gabarit sur la planche, rangée par rangée, avec un pixel d'écart. */
function empaqueter() {
  const places = new Map();
  let x = 0;
  let y = 0;
  let rangee = 0;
  for (const cube of CUBES) {
    if (cube.meme) continue;
    const g = gabarit(cube.t);
    if (x + g.largeur > LARGEUR_TEXTURE) {
      x = 0;
      y += rangee + 1;
      rangee = 0;
    }
    places.set(cube.id, { x, y, ...g });
    x += g.largeur + 1;
    rangee = Math.max(rangee, g.hauteur);
  }
  const hauteur = Math.max(32, 2 ** Math.ceil(Math.log2(y + rangee)));
  return { places, hauteur };
}

const { places, hauteurTexture } = (() => {
  const { places: p, hauteur } = empaqueter();
  return { places: p, hauteurTexture: hauteur };
})();

function placeDe(cube) {
  const place = places.get(cube.meme ?? cube.id);
  const source = CUBES.find((c) => c.id === (cube.meme ?? cube.id));
  if (cube.meme && cube.t.join() !== source.t.join()) throw new Error(`${cube.id} et ${cube.meme} n'ont pas la même taille`);
  return place;
}

/** Les pixels de la planche, en RGBA, et le rectangle de chaque face du cube. */
function peindre() {
  const pixels = new Uint8Array(LARGEUR_TEXTURE * hauteurTexture * 4);
  const poser = (x, y, c) => pixels.set(c, (y * LARGEUR_TEXTURE + x) * 4);
  for (const cube of CUBES) {
    if (cube.meme) continue;
    const place = places.get(cube.id);
    const style = STYLES[cube.style];
    for (const face of FACES) {
      const [fx, fy, w, h] = place.faces[face];
      const pose = (x, y, c) => {
        if (x >= 0 && y >= 0 && x < w && y < h) poser(place.x + fx + x, place.y + fy + y, c);
      };
      // Le fond de la face : le dessus clair, le dessous sombre, les côtés en trois bandes (clair, corps, sombre).
      for (let y = 0; y < h; y++) {
        for (let x = 0; x < w; x++) {
          let c = style.base;
          if (face === "up") c = style.clair;
          else if (face === "down") c = style.sombre;
          else if (h > 2 && y === 0) c = style.clair;
          else if (h > 1 && y === h - 1) c = style.sombre;
          pose(x, y, c);
        }
      }
      if (cube.visage && face === "north") visage(pose, w, h);
    }
  }
  return pixels;
}

/**
 * Le visage, sur la face avant de la boîte du milieu (dix pixels de large, treize de haut). Les cerclages couvrent les
 * deux premières lignes et les lignes 10 à 11 : tout se joue entre la ligne 2 et la ligne 9.
 */
function visage(pose, w, h) {
  const haut = 2; // première ligne libre sous le cerclage du haut
  void h;
  // Deux yeux carrés : un cadre clair de 4 sur 5, une pupille d'encre de 2 sur 3 au milieu, un point de lumière.
  for (const gauche of [0, w - 4]) {
    for (let y = 0; y < 5; y++) for (let x = 0; x < 4; x++) pose(gauche + x, haut + y, PAPIER);
    for (let y = 1; y < 4; y++) for (let x = 1; x < 3; x++) pose(gauche + x, haut + y, ENCRE);
    pose(gauche + 1, haut + 1, PAPIER);
  }
  // Deux joues, juste sous les yeux.
  for (const gauche of [0, w - 2]) {
    for (let y = 0; y < 2; y++) for (let x = 0; x < 2; x++) pose(gauche + x, haut + 5 + y, JOUE);
  }
  // Une bouche ouverte : un trait d'encre et, dessous, la langue.
  for (let x = 3; x < w - 3; x++) pose(x, haut + 6, ENCRE);
  for (let x = 4; x < w - 4; x++) pose(x, haut + 7, JOUE);
}

// ------------------------------------------------------------------ le modèle
function modele() {
  const nombre = (v) => (Number.isInteger(v) ? v : Number(v.toFixed(3)));
  const bones = OS.map((os) => {
    const bone = { name: os.nom };
    if (os.parent) bone.parent = os.parent;
    bone.pivot = os.pivot;
    const cubes = CUBES.filter((c) => c.os === os.nom).map((cube) => {
      const place = placeDe(cube);
      const uv = {};
      for (const face of FACES) {
        const [fx, fy, w, h] = place.faces[face];
        uv[face] = { uv: [place.x + fx, place.y + fy], uv_size: [w, h] };
      }
      return { origin: cube.o.map(nombre), size: cube.t, uv };
    });
    if (cubes.length) bone.cubes = cubes;
    return bone;
  });
  return {
    format_version: "1.12.0",
    "minecraft:geometry": [
      {
        description: {
          identifier: IDENTIFIANT,
          texture_width: LARGEUR_TEXTURE,
          texture_height: hauteurTexture,
          visible_bounds_width: 3,
          visible_bounds_height: 3,
          visible_bounds_offset: [0, 0.75, 0],
        },
        bones,
      },
    ],
  };
}

// ------------------------------------------------------------------ PNG, écriture et lecture
const TABLE_CRC = new Uint32Array(256).map((_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
function crc32(tampon) {
  let c = 0xffffffff;
  for (const octet of tampon) c = TABLE_CRC[(c ^ octet) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function bloc(type, donnees) {
  const tete = Buffer.alloc(8);
  tete.writeUInt32BE(donnees.length, 0);
  tete.write(type, 4, "ascii");
  const pied = Buffer.alloc(4);
  pied.writeUInt32BE(crc32(Buffer.concat([tete.subarray(4), donnees])), 0);
  return Buffer.concat([tete, donnees, pied]);
}
function encoderPng(pixels, largeur, hauteur) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(largeur, 0);
  ihdr.writeUInt32BE(hauteur, 4);
  ihdr.set([8, 6, 0, 0, 0], 8); // 8 bits, RGBA, sans entrelacement
  const lignes = Buffer.alloc((largeur * 4 + 1) * hauteur);
  for (let y = 0; y < hauteur; y++) {
    lignes[y * (largeur * 4 + 1)] = 0; // filtre « aucun »
    Buffer.from(pixels.buffer, pixels.byteOffset + y * largeur * 4, largeur * 4).copy(lignes, y * (largeur * 4 + 1) + 1);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    bloc("IHDR", ihdr),
    bloc("IDAT", deflateSync(lignes, { level: 9 })),
    bloc("IEND", Buffer.alloc(0)),
  ]);
}
/** Lit un PNG RGBA de 8 bits (ce que ce script écrit) : renvoie sa taille et ses pixels. */
function lirePng(tampon) {
  let p = 8;
  let largeur = 0;
  let hauteur = 0;
  const donnees = [];
  while (p < tampon.length) {
    const longueur = tampon.readUInt32BE(p);
    const type = tampon.toString("ascii", p + 4, p + 8);
    const corps = tampon.subarray(p + 8, p + 8 + longueur);
    if (type === "IHDR") {
      largeur = corps.readUInt32BE(0);
      hauteur = corps.readUInt32BE(4);
      if (corps[8] !== 8 || corps[9] !== 6) throw new Error("PNG attendu en RGBA de 8 bits");
    }
    if (type === "IDAT") donnees.push(corps);
    p += 12 + longueur;
  }
  const brut = inflateSync(Buffer.concat(donnees));
  const pixels = new Uint8Array(largeur * hauteur * 4);
  const pas = largeur * 4;
  for (let y = 0; y < hauteur; y++) {
    const filtre = brut[y * (pas + 1)];
    for (let x = 0; x < pas; x++) {
      const a = x >= 4 ? pixels[y * pas + x - 4] : 0;
      const b = y ? pixels[(y - 1) * pas + x] : 0;
      const c = x >= 4 && y ? pixels[(y - 1) * pas + x - 4] : 0;
      let v = brut[y * (pas + 1) + 1 + x];
      if (filtre === 1) v += a;
      else if (filtre === 2) v += b;
      else if (filtre === 3) v += (a + b) >> 1;
      else if (filtre === 4) {
        const estimation = a + b - c;
        const pa = Math.abs(estimation - a);
        const pb = Math.abs(estimation - b);
        const pc = Math.abs(estimation - c);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      pixels[y * pas + x] = v & 255;
    }
  }
  return { largeur, hauteur, pixels };
}

// ------------------------------------------------------------------ programme
const pixels = peindre();
const texte = `${JSON.stringify(modele(), null, 2)}\n`;

if (process.argv.includes("--verifier")) {
  const ecarts = [];
  if (!existsSync(SORTIE_MODELE)) ecarts.push("resource-pack/models/entity/oooscar.geo.json est absent");
  else if (readFileSync(SORTIE_MODELE, "utf8").replace(/\r\n/g, "\n") !== texte) ecarts.push("resource-pack/models/entity/oooscar.geo.json n'est pas celui que le script écrit");
  if (!existsSync(SORTIE_TEXTURE)) ecarts.push("resource-pack/textures/entity/oooscar.png est absent");
  else {
    const lue = lirePng(readFileSync(SORTIE_TEXTURE));
    if (lue.largeur !== LARGEUR_TEXTURE || lue.hauteur !== hauteurTexture || Buffer.compare(Buffer.from(lue.pixels), Buffer.from(pixels)) !== 0) {
      ecarts.push("resource-pack/textures/entity/oooscar.png n'a pas les pixels que le script dessine");
    }
  }
  if (ecarts.length) {
    for (const e of ecarts) console.error(`${e} : relance node tools/generer_oooscar.mjs`);
    process.exit(1);
  }
  console.log("Oooscar : modèle et texture conformes au script.");
} else {
  for (const sortie of [SORTIE_MODELE, SORTIE_TEXTURE]) mkdirSync(dirname(sortie), { recursive: true });
  writeFileSync(SORTIE_MODELE, texte);
  writeFileSync(SORTIE_TEXTURE, encoderPng(pixels, LARGEUR_TEXTURE, hauteurTexture));
  console.log(`Oooscar : ${CUBES.length} cubes, texture ${LARGEUR_TEXTURE} sur ${hauteurTexture}.`);
}
