#!/usr/bin/env node
// Génère le QR code de la pop-up Discord de la salle d'attente : un PNG à pixels nets (un module = 8 pixels, aucun
// lissage, encre fonte-900 sur papier) qui mène à https://silooo.com/discord/.
//
// Usage : node tools/generer_qr_discord.mjs [adresse] [sortie.png]
//   adresse   par défaut https://silooo.com/discord/
//   sortie    par défaut resource-pack-attente/textures/ui/silooo_discord/qr.png
//
// L'encodeur est le paquet npm `qrcode-generator` (sans dépendance), qui n'est pas dans le dépôt : installe-le où tu
// veux (`npm i --prefix /tmp/qr qrcode-generator@1.4.4`) et donne son dossier dans QRCODE_GENERATOR
// (`QRCODE_GENERATOR=/tmp/qr/node_modules/qrcode-generator`). Le PNG commité est la référence : relancer le script
// avec la même adresse redonne le même fichier.

import { createRequire } from "node:module";
import { deflateSync } from "node:zlib";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const racine = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const adresse = process.argv[2] ?? "https://silooo.com/discord/";
const sortie = resolve(process.argv[3] ?? join(racine, "resource-pack-attente/textures/ui/silooo_discord/qr.png"));

const MODULE = 8; // pixels par module
const MARGE = 4; // modules blancs autour : le minimum de la norme
const ENCRE = [0x1e, 0x1c, 0x1a]; // fonte-900
const PAPIER = [0xff, 0xf6, 0xde]; // papier

const exiger = createRequire(import.meta.url);
const qrcode = exiger(process.env.QRCODE_GENERATOR ?? "qrcode-generator");
const qr = qrcode(0, "M");
qr.addData(adresse);
qr.make();
const n = qr.getModuleCount();
const cote = (n + 2 * MARGE) * MODULE;

const brut = Buffer.alloc((cote * 3 + 1) * cote); // un octet de filtre (0) par ligne, puis les pixels RVB
for (let y = 0; y < cote; y += 1) {
  const ligne = y * (cote * 3 + 1);
  for (let x = 0; x < cote; x += 1) {
    const mx = Math.floor(x / MODULE) - MARGE;
    const my = Math.floor(y / MODULE) - MARGE;
    const noir = mx >= 0 && my >= 0 && mx < n && my < n && qr.isDark(my, mx);
    const couleur = noir ? ENCRE : PAPIER;
    brut.set(couleur, ligne + 1 + x * 3);
  }
}

const table = new Uint32Array(256).map((_, i) => {
  let c = i;
  for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
function crc32(octets) {
  let c = 0xffffffff;
  for (const o of octets) c = table[(c ^ o) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function morceau(type, donnees) {
  const tete = Buffer.alloc(4);
  tete.writeUInt32BE(donnees.length);
  const corps = Buffer.concat([Buffer.from(type, "latin1"), donnees]);
  const somme = Buffer.alloc(4);
  somme.writeUInt32BE(crc32(corps));
  return Buffer.concat([tete, corps, somme]);
}
const entete = Buffer.alloc(13);
entete.writeUInt32BE(cote, 0);
entete.writeUInt32BE(cote, 4);
entete.set([8, 2, 0, 0, 0], 8); // 8 bits, RVB, sans entrelacement
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  morceau("IHDR", entete),
  morceau("IDAT", deflateSync(brut, { level: 9 })),
  morceau("IEND", Buffer.alloc(0)),
]);

mkdirSync(dirname(sortie), { recursive: true });
writeFileSync(sortie, png);
console.log(`${sortie} : ${cote} × ${cote} px, ${n} × ${n} modules, ${adresse}`);
