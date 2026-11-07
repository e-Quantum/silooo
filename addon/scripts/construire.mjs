#!/usr/bin/env node
// Construit le pack de comportement : esbuild regroupe src/main.ts en UN fichier de scripts, le
// manifeste est copié à côté, le dossier statique pack/ (entités, par exemple) est copié tel quel, puis le tout est
// zippé en dist/silooo-addon.mcpack.
//
//   dist/pack/manifest.json
//   dist/pack/scripts/main.js      <- contrat avec deploy/ : ce dossier, ce nom, ce chemin
//   dist/pack/entities/*.json      <- copié de addon/pack/ (le dossier statique)
//   dist/silooo-addon.mcpack       <- le même contenu en zip
//   dist/attente/manifest.json     <- le pack de la salle d'attente (src/attente/main.ts, attente.manifest.json),
//   dist/attente/scripts/main.js      que start.sh installe en mode attente
//   dist/silooo-attente.mcpack
//
// Usage : node scripts/construire.mjs [--sortie <dossier>] [--statique <dossier>]
//         (ou npm run build, qui vérifie d'abord les types)
//   --sortie   : construit dans ce dossier, qui doit être absent ou vide, au lieu de dist/ (les tests s'en servent).
//   --statique : copie ce dossier au lieu de pack/ (les tests s'en servent pour les cas d'erreur).

import { build } from "esbuild";
import { zipSync } from "fflate";
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const racine = resolve(dirname(fileURLToPath(import.meta.url)), "..");

/** La valeur d'une option « --nom <dossier> », ou undefined si l'option est absente. */
function lireOption(arguments_, nom) {
  const position = arguments_.indexOf(nom);
  if (position < 0) return undefined;
  const valeur = arguments_[position + 1];
  if (valeur === undefined || valeur.startsWith("--")) throw new Error(`${nom} demande un dossier`);
  return resolve(valeur);
}

const options = process.argv.slice(2);
const sortieChoisie = lireOption(options, "--sortie");
const dist = sortieChoisie ?? join(racine, "dist");
const personnalise = sortieChoisie !== undefined;
const pack = join(dist, "pack");
const sortieScript = join(pack, "scripts", "main.js");
const mcpack = join(dist, "silooo-addon.mcpack");
/** Le dossier statique du pack de comportement : copié tel quel dans dist/pack. */
const statique = lireOption(options, "--statique") ?? join(racine, "pack");

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

/**
 * Copie le dossier statique pack/ dans dist/pack. Un fichier .json doit être du JSON valide ; manifest.json et scripts/
 * viennent de la construction et ne peuvent pas être écrasés par le dossier statique. Rend les chemins copiés.
 */
function copierStatique() {
  if (!existsSync(statique)) return [];
  const copies = [];
  for (const fichier of fichiersDe(statique)) {
    const nom = relative(statique, fichier).replaceAll("\\", "/");
    if (nom === "manifest.json" || nom.startsWith("scripts/")) {
      throw new Error(`pack/${nom} : manifest.json et scripts/ sont produits par la construction, pas par pack/`);
    }
    if (nom.endsWith(".json")) {
      try {
        JSON.parse(readFileSync(fichier, "utf8"));
      } catch (erreur) {
        throw new Error(`pack/${nom} n'est pas du JSON valide : ${erreur instanceof Error ? erreur.message : erreur}`);
      }
    }
    mkdirSync(dirname(join(pack, nom)), { recursive: true });
    copyFileSync(fichier, join(pack, nom));
    copies.push(nom);
  }
  return copies;
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
  if (personnalise) {
    // On n'efface jamais un dossier choisi à la main : il doit être absent ou vide.
    if (existsSync(dist) && readdirSync(dist).length > 0) throw new Error(`--sortie ${dist} existe et n'est pas vide`);
  } else {
    rmSync(dist, { recursive: true, force: true });
  }
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

  // Le pack de la salle d'attente : son propre script et son manifeste, sans le dossier statique (ni entité ni Oooscar).
  const attente = join(dist, "attente");
  const scriptAttente = join(attente, "scripts", "main.js");
  mkdirSync(join(attente, "scripts"), { recursive: true });
  await build({
    absWorkingDir: racine,
    entryPoints: ["src/attente/main.ts"],
    outfile: scriptAttente,
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
  verifierImports(readFileSync(scriptAttente, "utf8"));
  copyFileSync(join(racine, "attente.manifest.json"), join(attente, "manifest.json"));
  const entreesAttente = {};
  for (const fichier of fichiersDe(attente)) {
    entreesAttente[relative(attente, fichier).replaceAll("\\", "/")] = [readFileSync(fichier), { level: 9, mtime: new Date(2000, 0, 1) }];
  }
  writeFileSync(join(dist, "silooo-attente.mcpack"), zipSync(entreesAttente));
  console.log(`dist/attente/scripts/main.js : ${statSync(scriptAttente).size} octets`);

  copyFileSync(join(racine, "manifest.json"), join(pack, "manifest.json"));
  const copies = copierStatique();

  // Un zip dont l'ordre et les dates ne changent pas : deux constructions du même code donnent le même fichier.
  const entrees = {};
  for (const fichier of fichiersDe(pack)) {
    const nom = relative(pack, fichier).replaceAll("\\", "/");
    entrees[nom] = [readFileSync(fichier), { level: 9, mtime: new Date(2000, 0, 1) }];
  }
  writeFileSync(mcpack, zipSync(entrees));

  const octets = (fichier) => statSync(fichier).size;
  console.log(`dist/pack/scripts/main.js : ${octets(sortieScript)} octets, imports : ${imports.join(", ") || "aucun"}`);
  console.log(`dist/pack : ${copies.length} fichier(s) copié(s) de pack/${copies.length > 0 ? " (" + copies.join(", ") + ")" : ""}`);
  console.log(`dist/silooo-addon.mcpack : ${octets(mcpack)} octets, ${Object.keys(entrees).length} fichier(s)`);
}

main().catch((erreur) => {
  console.error(`construire: ${erreur instanceof Error ? erreur.message : erreur}`);
  process.exit(1);
});
