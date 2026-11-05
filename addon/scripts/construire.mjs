#!/usr/bin/env node
// Construit le pack de comportement : esbuild regroupe src/main.ts en UN fichier de scripts, le
// manifeste est copié à côté, puis le tout est zippé en dist/silooo-addon.mcpack.
//
//   dist/pack/manifest.json
//   dist/pack/scripts/main.js      <- contrat avec deploy/ : ce dossier, ce nom, ce chemin
//   dist/silooo-addon.mcpack       <- le même contenu en zip
//
// Usage : node scripts/construire.mjs   (ou npm run build, qui vérifie d'abord les types)

import { build } from "esbuild";
import { zipSync } from "fflate";
import { copyFileSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const racine = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const dist = join(racine, "dist");
const pack = join(dist, "pack");
const sortieScript = join(pack, "scripts", "main.js");
const mcpack = join(dist, "silooo-addon.mcpack");

/** Les seuls modules que le script compilé a le droit d'importer : ceux que Bedrock fournit. */
const MODULES_BEDROCK = /^@minecraft\/(server|server-ui|server-admin|server-net)$/;

function fichiersDe(dossier) {
  const liste = [];
  for (const nom of readdirSync(dossier).sort()) {
    const chemin = join(dossier, nom);
    if (statSync(chemin).isDirectory()) liste.push(...fichiersDe(chemin));
    else liste.push(chemin);
  }
  return liste;
}

/** Le script doit être autonome : esbuild a tout regroupé, sauf les modules de Bedrock. */
function verifierImports(source) {
  const trouves = new Set();
  for (const m of source.matchAll(/(?:^|\n)\s*(?:import|export)\s[^;]*?\sfrom\s*["']([^"']+)["']/g)) trouves.add(m[1]);
  for (const m of source.matchAll(/(?:^|\n)\s*import\s*["']([^"']+)["']/g)) trouves.add(m[1]);
  for (const m of source.matchAll(/\bimport\(\s*["']([^"']+)["']\s*\)/g)) trouves.add(m[1]);
  const interdits = [...trouves].filter((nom) => !MODULES_BEDROCK.test(nom));
  if (interdits.length > 0) {
    throw new Error(`le script compilé importe autre chose que les modules de Bedrock : ${interdits.join(", ")}`);
  }
  if (/\brequire\s*\(/.test(source)) throw new Error("le script compilé appelle require()");
  return [...trouves].sort();
}

async function main() {
  rmSync(dist, { recursive: true, force: true });
  mkdirSync(join(pack, "scripts"), { recursive: true });

  await build({
    absWorkingDir: racine,
    entryPoints: ["src/main.ts"],
    outfile: sortieScript,
    bundle: true,
    format: "esm",
    target: "es2023",
    platform: "neutral",
    external: ["@minecraft/*"],
    minify: false,
    legalComments: "none",
    tsconfig: "tsconfig.json",
    logLevel: "warning",
  });
  const imports = verifierImports(readFileSync(sortieScript, "utf8"));

  copyFileSync(join(racine, "manifest.json"), join(pack, "manifest.json"));

  // Un zip dont l'ordre et les dates ne changent pas : deux constructions du même code donnent le même fichier.
  const entrees = {};
  for (const fichier of fichiersDe(pack)) {
    const nom = relative(pack, fichier).replaceAll("\\", "/");
    entrees[nom] = [readFileSync(fichier), { level: 9, mtime: new Date(2000, 0, 1) }];
  }
  writeFileSync(mcpack, zipSync(entrees));

  const octets = (fichier) => statSync(fichier).size;
  console.log(`dist/pack/scripts/main.js : ${octets(sortieScript)} octets, imports : ${imports.join(", ") || "aucun"}`);
  console.log(`dist/silooo-addon.mcpack : ${octets(mcpack)} octets, ${Object.keys(entrees).length} fichier(s)`);
}

main().catch((erreur) => {
  console.error(`construire: ${erreur instanceof Error ? erreur.message : erreur}`);
  process.exit(1);
});
