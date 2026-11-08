#!/usr/bin/env node
// Vérifie le pack de ressources (resource-pack/) : JSON valides, manifeste cohérent avec celui de l'add-on, copies des
// textures de design/ identiques octet pour octet, fichiers cités qui existent, entité d'Oooscar bien reliée à son
// modèle, ses animations et son contrôleur de rendu, préfixes du HUD identiques à ceux de l'add-on, et modèle et
// texture d'Oooscar conformes à tools/generer_oooscar.mjs. Aucun chiffre du jeu ici : le script ne lit que des fichiers.
//
// Usage : node tools/verifier_pack.mjs [dossier du pack]
//   dossier du pack   par défaut resource-pack/ du dépôt. Pour un autre dossier (les essais de tools/tests/run.sh),
//                     design/ et addon/ restent ceux du dépôt et la comparaison avec le générateur est sautée.
// Sortie : une ligne « chemin: message » par erreur, code 1 s'il y en a.

import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const racine = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const argument = process.argv.slice(2).find((a) => !a.startsWith("--"));
const pack = resolve(argument ?? join(racine, "resource-pack"));
const estLePackDuDepot = pack === join(racine, "resource-pack") || pack === resolve(racine, "resource-pack");

const erreurs = [];
const erreur = (chemin, message) => erreurs.push(`${chemin}: ${message}`);
const chemin = (fichier) => relative(pack, fichier).split("\\").join("/");

function lireJson(fichier) {
  try {
    return JSON.parse(readFileSync(fichier, "utf8").replace(/^\uFEFF/, ""));
  } catch (e) {
    erreur(chemin(fichier), `JSON invalide : ${e.message}`);
    return null;
  }
}
function fichiersDe(dossier, filtre = () => true) {
  if (!existsSync(dossier)) return [];
  const sortie = [];
  for (const nom of readdirSync(dossier).sort()) {
    const complet = join(dossier, nom);
    if (statSync(complet).isDirectory()) sortie.push(...fichiersDe(complet, filtre));
    else if (filtre(nom)) sortie.push(complet);
  }
  return sortie;
}
function pngsDirects(dossier) {
  if (!existsSync(dossier)) return [];
  return readdirSync(dossier).filter((n) => n.endsWith(".png") && statSync(join(dossier, n)).isFile()).sort();
}
/** Largeur et hauteur d'un PNG, lues dans son en-tête ; null si ce n'est pas un PNG. */
function tailleDuPng(fichier) {
  const tete = readFileSync(fichier);
  if (tete.length < 24 || tete.readUInt32BE(0) !== 0x89504e47) return null;
  return { largeur: tete.readUInt32BE(16), hauteur: tete.readUInt32BE(20) };
}

if (!existsSync(pack)) {
  console.error(`${argument ?? "resource-pack"}: le dossier du pack n'existe pas`);
  process.exit(1);
}

// ------------------------------------------------------------------ 1. tous les JSON se lisent
const documents = new Map();
for (const f of fichiersDe(pack, (n) => n.endsWith(".json"))) {
  const doc = lireJson(f);
  if (doc !== null) documents.set(chemin(f), doc);
}

// ------------------------------------------------------------------ 2. le manifeste
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const manifeste = documents.get("manifest.json");
const manifesteAddon = lireJson(join(racine, "addon/manifest.json"));
const clesDeTexte = new Map();
const langue = join(pack, "texts/fr_FR.lang");
if (existsSync(langue)) {
  for (const ligne of readFileSync(langue, "utf8").split(/\r?\n/)) {
    const m = /^([^#=\s][^=]*)=(.*)$/.exec(ligne);
    if (m) clesDeTexte.set(m[1].trim(), m[2].trim());
  }
}
if (!manifeste) {
  if (!documents.has("manifest.json") && !existsSync(join(pack, "manifest.json"))) erreur("manifest.json", "absent");
} else {
  const tete = manifeste.header ?? {};
  const module = (manifeste.modules ?? [])[0] ?? {};
  if (manifeste.format_version !== 2) erreur("manifest.json", "format_version doit valoir 2");
  if (module.type !== "resources") erreur("manifest.json", `le module doit être de type resources (trouvé : ${module.type})`);
  for (const [nom, valeur] of [["header.uuid", tete.uuid], ["modules[0].uuid", module.uuid]]) {
    if (typeof valeur !== "string" || !UUID.test(valeur)) erreur("manifest.json", `${nom} n'est pas un uuid`);
  }
  if (tete.uuid && tete.uuid === module.uuid) erreur("manifest.json", "header.uuid et modules[0].uuid doivent être différents");
  if (manifesteAddon) {
    const pris = [manifesteAddon.header?.uuid, ...(manifesteAddon.modules ?? []).map((m) => m.uuid)];
    for (const [nom, valeur] of [["header.uuid", tete.uuid], ["modules[0].uuid", module.uuid]]) {
      if (valeur && pris.includes(valeur)) erreur("manifest.json", `${nom} est déjà pris dans addon/manifest.json`);
    }
    if (JSON.stringify(tete.min_engine_version) !== JSON.stringify(manifesteAddon.header?.min_engine_version)) {
      erreur("manifest.json", "min_engine_version doit être celle de addon/manifest.json");
    }
  }
  if (!Array.isArray(tete.version) || tete.version.length !== 3) erreur("manifest.json", "header.version doit être [x, y, z]");
  if (JSON.stringify(tete.version) !== JSON.stringify(module.version)) erreur("manifest.json", "header.version et modules[0].version doivent être égales");
  for (const [nom, valeur] of [["name", tete.name], ["description", tete.description]]) {
    if (typeof valeur !== "string" || valeur.trim() === "") {
      erreur("manifest.json", `header.${nom} est vide`);
    } else if (/^pack\./.test(valeur) && !clesDeTexte.get(valeur)) {
      erreur("manifest.json", `header.${nom} cite ${valeur}, absent de texts/fr_FR.lang`);
    }
  }
}
// Un client Bedrock garde en cache le pack qu'il a déjà téléchargé, par version : un pack changé sous la même version
// n'arrive pas aux joueurs, qui voient alors l'ancien HUD (les titres « silooo:cinema| » bruts, par exemple). Tout
// changement de resource-pack/ depuis origin/main doit donc monter header.version. Sauté sans git ou sans origin/main.
if (estLePackDuDepot && manifeste && Array.isArray(manifeste.header?.version)) {
  const git = (...args) => spawnSync("git", ["-C", racine, ...args], { encoding: "utf8" });
  const base = git("merge-base", "HEAD", "origin/main");
  const baseSha = base.status === 0 ? base.stdout.trim() : "";
  if (baseSha) {
    const changes = git("diff", "--name-only", baseSha, "--", "resource-pack");
    const avant = git("show", `${baseSha}:resource-pack/manifest.json`);
    if (changes.status === 0 && changes.stdout.trim() !== "" && avant.status === 0) {
      let versionAvant = null;
      try {
        versionAvant = JSON.parse(avant.stdout.replace(/^﻿/, "")).header?.version ?? null;
      } catch {
        versionAvant = null;
      }
      const plusGrande = (a, b) => {
        for (let i = 0; i < 3; i++) if ((a[i] ?? 0) !== (b[i] ?? 0)) return (a[i] ?? 0) > (b[i] ?? 0);
        return false;
      };
      if (Array.isArray(versionAvant) && !plusGrande(manifeste.header.version, versionAvant)) {
        erreur("manifest.json", `resource-pack/ a changé depuis origin/main : header.version doit dépasser ${versionAvant.join(".")}, sinon les clients gardent l'ancien pack en cache`);
      }
    }
  }
}
const langues = documents.get("texts/languages.json");
if (!Array.isArray(langues) || !langues.includes("fr_FR")) erreur("texts/languages.json", "doit être une liste qui contient fr_FR");
else for (const l of langues) if (!existsSync(join(pack, `texts/${l}.lang`))) erreur("texts/languages.json", `texts/${l}.lang est absent`);

// ------------------------------------------------------------------ 3. les copies de design/ sont identiques
// design/ reste la source ; le pack n'en garde que des copies, jamais une retouche.
const COPIES = [
  ["design/textures", "textures/ui/silooo"],
  ["design/icones", "textures/ui/silooo/icones"],
  ["design/mascotte", "textures/ui/silooo/oooscar"],
  ["design/blocs", "textures/blocks/silooo"],
];
for (const [source, destination] of COPIES) {
  const dossierSource = join(racine, source);
  const dossierDestination = join(pack, destination);
  const sources = pngsDirects(dossierSource);
  if (sources.length === 0) erreur(source, "aucun PNG à copier : le dossier est vide ou absent");
  for (const nom of sources) {
    const copie = join(dossierDestination, nom);
    if (!existsSync(copie)) erreur(`${destination}/${nom}`, `absent (copie de ${source}/${nom})`);
    else if (!readFileSync(copie).equals(readFileSync(join(dossierSource, nom)))) {
      erreur(`${destination}/${nom}`, `diffère de ${source}/${nom} : design/ est la source, recopie le fichier sans le retoucher`);
    }
  }
  for (const nom of pngsDirects(dossierDestination)) {
    if (!sources.includes(nom)) erreur(`${destination}/${nom}`, `n'a pas de source dans ${source}/ : retire-le ou ajoute-le à design/`);
  }
}
{
  const icone = join(pack, "pack_icon.png");
  const source = join(racine, "design/logo/silooo-logo-128.png");
  if (!existsSync(icone)) erreur("pack_icon.png", "absent");
  else if (!readFileSync(icone).equals(readFileSync(source))) erreur("pack_icon.png", "diffère de design/logo/silooo-logo-128.png");
}

// ------------------------------------------------------------------ 4. les fichiers cités existent
const EXTENSIONS_IMAGE = [".png", ".tga", ".jpg"];
// Textures du pack vanilla citées exprès (faces des blocs Silooo qui restent vanilla). Chacune a été vue dans
// bedrock-samples, tag v1.26.60.5, resource_pack/textures/blocks/. Toute autre texture doit être dans le pack.
const TEXTURES_VANILLA = new Set(["textures/blocks/planks_spruce", "textures/blocks/log_big_oak_top"]);
function textureExiste(nom) {
  return TEXTURES_VANILLA.has(nom) || EXTENSIONS_IMAGE.some((e) => existsSync(join(pack, nom + e)));
}
for (const [nom, doc] of documents) {
  const texte = JSON.stringify(doc);
  for (const m of texte.matchAll(/"(textures\/[^"]+)"/g)) {
    if (!textureExiste(m[1])) erreur(nom, `cite ${m[1]}, qui n'existe pas dans le pack`);
  }
}
const defs = documents.get("ui/_ui_defs.json");
for (const fichier of defs?.ui_defs ?? []) {
  if (!existsSync(join(pack, fichier))) erreur("ui/_ui_defs.json", `cite ${fichier}, qui n'existe pas`);
}

// ------------------------------------------------------------------ 5. les préfixes du HUD sont ceux de l'add-on
{
  const hudAddon = join(racine, "addon/src/ui/hud.ts");
  const source = existsSync(hudAddon) ? readFileSync(hudAddon, "utf8") : "";
  const prefixes = ["HUD_TITLE_PREFIX", "HUD_SUBTITLE_PREFIX"].map((nom) => {
    const m = new RegExp(`${nom}\\s*=\\s*"([^"]+)"`).exec(source);
    if (!m) erreur("addon/src/ui/hud.ts", `${nom} introuvable : le pack ne sait plus quel préfixe masquer`);
    return m?.[1];
  });
  const ecrans = ["ui/hud_screen.json", "ui/silooo/hud.json"].map((n) => [n, existsSync(join(pack, n)) ? readFileSync(join(pack, n), "utf8") : null]);
  for (const [nom, contenu] of ecrans) {
    if (contenu === null) erreur(nom, "absent");
    else for (const prefixe of prefixes) if (prefixe && !contenu.includes(prefixe)) erreur(nom, `ne cite pas le préfixe « ${prefixe} » de addon/src/ui/hud.ts`);
  }
}

// ------------------------------------------------------------------ 6. les entités : modèle, animations, rendu, texture
const geometries = new Map();
for (const [nom, doc] of documents) {
  if (!nom.startsWith("models/")) continue;
  for (const g of doc["minecraft:geometry"] ?? []) if (g.description?.identifier) geometries.set(g.description.identifier, { nom, g });
}
const animations = new Set();
for (const [nom, doc] of documents) if (nom.startsWith("animations/")) for (const id of Object.keys(doc.animations ?? {})) animations.add(id);
// Une entité peut aussi citer un contrôleur d'animation dans « animations » (le salarié, par exemple).
for (const [nom, doc] of documents) if (nom.startsWith("animation_controllers/")) for (const id of Object.keys(doc.animation_controllers ?? {})) animations.add(id);
const controleurs = new Set();
for (const [nom, doc] of documents) if (nom.startsWith("render_controllers/")) for (const id of Object.keys(doc.render_controllers ?? {})) controleurs.add(id);

for (const [nom, doc] of documents) {
  if (!nom.startsWith("entity/")) continue;
  const d = doc["minecraft:client_entity"]?.description;
  if (!d) {
    erreur(nom, "pas de minecraft:client_entity.description");
    continue;
  }
  for (const id of Object.values(d.geometry ?? {})) if (!geometries.has(id)) erreur(nom, `la géométrie ${id} n'est définie dans aucun modèle`);
  for (const id of Object.values(d.animations ?? {})) if (!animations.has(id)) erreur(nom, `l'animation ${id} n'est définie dans aucun fichier d'animations ni de contrôleurs`);
  for (const id of d.render_controllers ?? []) if (!controleurs.has(typeof id === "string" ? id : Object.keys(id)[0])) erreur(nom, `le contrôleur ${JSON.stringify(id)} n'est défini nulle part`);
  const utilisees = new Set((d.scripts?.animate ?? []).map((e) => (typeof e === "string" ? e : Object.keys(e)[0])));
  for (const court of utilisees) if (!(court in (d.animations ?? {}))) erreur(nom, `scripts.animate cite ${court}, absent de animations`);
  // La texture de l'entité a la taille que son modèle annonce.
  for (const texture of Object.values(d.textures ?? {})) {
    for (const geo of Object.values(d.geometry ?? {})) {
      const modele = geometries.get(geo);
      const fichier = join(pack, `${texture}.png`);
      if (!modele || !existsSync(fichier)) continue;
      const taille = tailleDuPng(fichier);
      const { texture_width: l, texture_height: h } = modele.g.description;
      if (!taille) erreur(`${texture}.png`, "n'est pas un PNG");
      else if (taille.largeur !== l || taille.hauteur !== h) erreur(`${texture}.png`, `fait ${taille.largeur}x${taille.hauteur}, le modèle ${modele.nom} annonce ${l}x${h}`);
    }
  }
}
// Chaque face d'un cube reste dans la texture annoncée par son modèle.
for (const [id, { nom, g }] of geometries) {
  const { texture_width: l, texture_height: h } = g.description;
  for (const bone of g.bones ?? []) {
    for (const [i, cube] of (bone.cubes ?? []).entries()) {
      if (typeof cube.uv !== "object" || Array.isArray(cube.uv)) continue;
      for (const [face, { uv, uv_size: t }] of Object.entries(cube.uv)) {
        if (uv[0] < 0 || uv[1] < 0 || uv[0] + t[0] > l || uv[1] + t[1] > h) erreur(nom, `${id}, os ${bone.name}, cube ${i}, face ${face} : sort de la texture (${l}x${h})`);
      }
    }
  }
}

// ------------------------------------------------------------------ 7. Les assets générés restent identiques aux sources
if (estLePackDuDepot) {
  // Les structures de la Carrière sont contrôlées à part (tools/carriere/structure.py --verifier, tools/verifier.sh).
  for (const outil of ["generer_oooscar.mjs", "generer_atelier.mjs", "generer_glyphes.mjs"]) {
    const resultat = spawnSync(process.execPath, [join(racine, "tools", outil), "--verifier"], { encoding: "utf8" });
    if (resultat.status !== 0) for (const ligne of `${resultat.stdout}${resultat.stderr}`.split(/\r?\n/).filter(Boolean)) erreur(`tools/${outil}`, ligne);
  }
}

if (erreurs.length > 0) {
  for (const e of erreurs) console.log(e);
  console.log(`${erreurs.length} erreur(s) dans ${argument ?? "resource-pack"}.`);
  process.exit(1);
}
console.log(`Pack de ressources : ${documents.size} fichier(s) JSON, ${fichiersDe(pack, (n) => n.endsWith(".png")).length} PNG, copies de design/ identiques, entités reliées.`);
