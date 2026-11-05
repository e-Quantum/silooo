#!/usr/bin/env node
// Construit un faux zip de BDS pour tools/tests/deploy-start.sh : le vrai zip de Mojang ne se télécharge pas depuis un
// script (minecraft.net refuse les clients qui ne sont pas des navigateurs) et ne se redistribue pas.
//
//   node tools/tests/faire-zip.mjs <sortie.zip> [--variante <texte>] [--sans-serveur]
//
// Le zip ne contient que des fichiers sans compression (méthode « stored »), écrits ici sans bibliothèque, avec les
// droits Unix dans les attributs externes. Ce que contient un vrai zip de BDS et que start.sh doit retirer à
// l'installation y est : worlds/, config/, server.properties, allowlist.json, permissions.json. --variante change le
// contenu (donc les sommes), pour fabriquer un fichier « corrompu » qui n'a pas les sommes attendues.
import { writeFileSync } from "node:fs";

const args = process.argv.slice(2);
const sortie = args[0];
if (!sortie || sortie.startsWith("--")) {
  console.error("usage : node tools/tests/faire-zip.mjs <sortie.zip> [--variante <texte>] [--sans-serveur]");
  process.exit(64);
}
const iVariante = args.indexOf("--variante");
const variante = iVariante >= 0 ? args[iVariante + 1] ?? "" : "";
const sansServeur = args.includes("--sans-serveur");

const SERVEUR = `#!/usr/bin/env bash
echo "serveur lancé : LD_LIBRARY_PATH=$LD_LIBRARY_PATH dossier=$(basename "$PWD")"
`;

/** @type {{ nom: string, contenu: string, mode: number }[]} */
const entrees = [
  ...(sansServeur ? [] : [{ nom: "bedrock_server", contenu: SERVEUR, mode: 0o644 }]),
  { nom: "libfausse.so", contenu: `bibliothèque factice ${variante}\n`, mode: 0o644 },
  { nom: "server.properties", contenu: "server-name=du zip\n", mode: 0o644 },
  { nom: "allowlist.json", contenu: '["du zip"]\n', mode: 0o644 },
  { nom: "permissions.json", contenu: '["du zip"]\n', mode: 0o644 },
  { nom: "worlds/Bedrock level/level.dat", contenu: "monde du zip\n", mode: 0o644 },
  { nom: "config/ancien.json", contenu: "{}\n", mode: 0o644 },
  { nom: "behavior_packs/vanilla/manifest.json", contenu: "{}\n", mode: 0o644 },
  { nom: "release-notes.txt", contenu: `notes ${variante}\n`, mode: 0o644 },
];

// CRC-32 (polynôme 0xEDB88320), sans dépendance.
const TABLE = new Uint32Array(256).map((_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
function crc32(tampon) {
  let c = 0xffffffff;
  for (const octet of tampon) c = TABLE[(c ^ octet) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

const DOS_HEURE = 0;
const DOS_DATE = ((2026 - 1980) << 9) | (11 << 5) | 5; // 5 novembre 2026, une date fixe : deux zips du même contenu sont identiques

const locaux = [];
const centraux = [];
let decalage = 0;
for (const { nom, contenu, mode } of entrees) {
  const nomOctets = Buffer.from(nom, "utf8");
  const donnees = Buffer.from(contenu, "utf8");
  const crc = crc32(donnees);

  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(10, 4); // version nécessaire
  local.writeUInt16LE(0x0800, 6); // noms en UTF-8
  local.writeUInt16LE(0, 8); // stored
  local.writeUInt16LE(DOS_HEURE, 10);
  local.writeUInt16LE(DOS_DATE, 12);
  local.writeUInt32LE(crc, 14);
  local.writeUInt32LE(donnees.length, 18);
  local.writeUInt32LE(donnees.length, 22);
  local.writeUInt16LE(nomOctets.length, 26);
  local.writeUInt16LE(0, 28);
  locaux.push(local, nomOctets, donnees);

  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50, 0);
  central.writeUInt16LE((3 << 8) | 20, 4); // fait sous Unix : les droits sont dans les attributs externes
  central.writeUInt16LE(10, 6);
  central.writeUInt16LE(0x0800, 8);
  central.writeUInt16LE(0, 10);
  central.writeUInt16LE(DOS_HEURE, 12);
  central.writeUInt16LE(DOS_DATE, 14);
  central.writeUInt32LE(crc, 16);
  central.writeUInt32LE(donnees.length, 20);
  central.writeUInt32LE(donnees.length, 24);
  central.writeUInt16LE(nomOctets.length, 28);
  central.writeUInt16LE(0, 30); // extra
  central.writeUInt16LE(0, 32); // commentaire
  central.writeUInt16LE(0, 34); // disque
  central.writeUInt16LE(0, 36); // attributs internes
  central.writeUInt32LE(((0o100000 | mode) << 16) >>> 0, 38);
  central.writeUInt32LE(decalage, 42);
  centraux.push(central, nomOctets);

  decalage += local.length + nomOctets.length + donnees.length;
}

const annuaire = Buffer.concat(centraux);
const fin = Buffer.alloc(22);
fin.writeUInt32LE(0x06054b50, 0);
fin.writeUInt16LE(0, 4);
fin.writeUInt16LE(0, 6);
fin.writeUInt16LE(entrees.length, 8);
fin.writeUInt16LE(entrees.length, 10);
fin.writeUInt32LE(annuaire.length, 12);
fin.writeUInt32LE(decalage, 16);
fin.writeUInt16LE(0, 20);

writeFileSync(sortie, Buffer.concat([...locaux, annuaire, fin]));
