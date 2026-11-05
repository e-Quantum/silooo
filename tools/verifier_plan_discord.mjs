#!/usr/bin/env node
// Vérifie le plan du serveur Discord (core/src/main/resources/discord/plan-serveur.json, ADR 0007) contre son
// schéma, puis ce que le schéma ne sait pas dire : identifiants et noms uniques, rôles cités qui existent, un
// seul emoji par salon et jamais dans le nom, couleurs prises dans design/tokens.json, salon du journal présent.
// Le service refait les mêmes contrôles au démarrage (ServerPlan.check) ; ici, Kr les a avant de pousser.
//
// Usage : node tools/verifier_plan_discord.mjs [plan.json]
//   plan.json  le plan à vérifier (par défaut : celui du dépôt) ; le schéma et les jetons sont toujours ceux du dépôt.
// Sortie : une ligne « fichier: chemin JSON: message » par erreur, code 1 s'il y en a.

import { existsSync, readFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const racine = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const DOSSIER = join(racine, "core", "src", "main", "resources", "discord");
const PLAN = join(DOSSIER, "plan-serveur.json");
const SCHEMA = join(DOSSIER, "plan-serveur.schema.json");
const TOKENS = join(racine, "design", "tokens.json");

const JOURNAL = "journal-du-bot";
const SEPARATEUR = "・";
const PHASES = ["attente", "beta", "ouvert"];
const NOM_ECRIT = /^[\p{Ll}0-9][\p{Ll}0-9-]*$/u;
const COULEUR = /^#[0-9a-f]{6}$/;
// Ce que Discord et anti_ia.py tiennent pour un emoji : les pictogrammes, plus le sélecteur de variante et le lieur.
const EMOJI = /[\p{Extended_Pictographic}\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}\u{200D}]/u;

function nomAffiche(chemin) {
  const rel = relative(racine, chemin).split("\\").join("/");
  return rel.startsWith("..") ? chemin : rel;
}

function lire(chemin, erreurs) {
  if (!existsSync(chemin)) {
    erreurs.push(`${nomAffiche(chemin)}: $: fichier introuvable`);
    return null;
  }
  try {
    return JSON.parse(readFileSync(chemin, "utf8"));
  } catch (e) {
    erreurs.push(`${nomAffiche(chemin)}: $: JSON illisible (${e.message})`);
    return null;
  }
}

/** Les couleurs de design/tokens.json, en minuscules, où qu'elles soient dans le fichier. */
function couleursDesJetons(jetons) {
  const couleurs = new Set();
  const parcourir = (valeur) => {
    if (typeof valeur === "string") {
      if (COULEUR.test(valeur.toLowerCase())) couleurs.add(valeur.toLowerCase());
    } else if (Array.isArray(valeur)) {
      valeur.forEach(parcourir);
    } else if (valeur && typeof valeur === "object") {
      Object.values(valeur).forEach(parcourir);
    }
  };
  parcourir(jetons);
  return couleurs;
}

/** Un seul graphème, et un emoji : « 🏗️ » (deux points de code) passe, « 🏗️🏗️ » et « a » non. */
function unSeulEmoji(texte) {
  const segments = [...new Intl.Segmenter("fr", { granularity: "grapheme" }).segment(texte)];
  return segments.length === 1 && EMOJI.test(texte);
}

function unique(erreurs, fichier, chemin, quoi, valeurs) {
  const vus = new Map();
  valeurs.forEach((valeur, i) => {
    if (vus.has(valeur)) erreurs.push(`${fichier}: ${chemin}[${i}]: ${quoi} « ${valeur} » est en double (déjà en [${vus.get(valeur)}])`);
    else vus.set(valeur, i);
  });
}

function controler(plan, fichier, couleurs, erreurs) {
  const roles = plan.roles ?? [];
  const categories = plan.categories ?? [];
  const salons = categories.flatMap((c) => c.salons ?? []);
  const nomDiscord = (s) => (s.type === "vocal" ? `${s.emoji} ${s.nom}` : `${s.emoji}${SEPARATEUR}${s.nom}`);

  unique(erreurs, fichier, "roles", "le rôle", roles.map((r) => r.id));
  unique(erreurs, fichier, "roles", "le nom de rôle", roles.map((r) => r.nom));
  unique(erreurs, fichier, "categories", "la catégorie", categories.map((c) => c.id));
  unique(erreurs, fichier, "categories", "le nom de catégorie", categories.map((c) => c.nom));
  unique(erreurs, fichier, "salons", "le salon", salons.map((s) => s.id));
  unique(erreurs, fichier, "salons", "le nom Discord", salons.map(nomDiscord));

  const idsRoles = new Set(roles.map((r) => r.id));
  roles.forEach((role, i) => {
    if (role.couleur !== undefined && !couleurs.has(role.couleur)) {
      erreurs.push(`${fichier}: roles[${i}].couleur: « ${role.couleur} » n'est pas une couleur de la palette (design/tokens.json)`);
    }
  });

  categories.forEach((categorie, ci) => {
    const chemin = `categories[${ci}]`;
    (categorie.visible ?? []).forEach((id) => {
      if (!idsRoles.has(id)) erreurs.push(`${fichier}: ${chemin}.visible: le rôle « ${id} » n'existe pas`);
    });
    const phaseCategorie = PHASES.indexOf(categorie.phase ?? "attente");
    (categorie.salons ?? []).forEach((salon, si) => {
      const ici = `${chemin}.salons[${si}]`;
      for (const cle of ["ecriture", "visible"]) {
        (salon[cle] ?? []).forEach((id) => {
          if (!idsRoles.has(id)) erreurs.push(`${fichier}: ${ici}.${cle}: le rôle « ${id} » n'existe pas`);
        });
      }
      if (PHASES.indexOf(salon.phase ?? "attente") < phaseCategorie) {
        erreurs.push(`${fichier}: ${ici}.phase: le salon apparaît avant sa catégorie « ${categorie.id} »`);
      }
      if (typeof salon.emoji === "string" && !unSeulEmoji(salon.emoji)) {
        erreurs.push(`${fichier}: ${ici}.emoji: un seul emoji, et rien d'autre (« ${salon.emoji} »)`);
      }
      if (typeof salon.nom === "string") {
        if (EMOJI.test(salon.nom)) erreurs.push(`${fichier}: ${ici}.nom: contient un emoji ; il va dans la clé emoji`);
        else if (salon.type !== "vocal" && !NOM_ECRIT.test(salon.nom)) {
          erreurs.push(`${fichier}: ${ici}.nom: un salon écrit s'appelle en minuscules, sans espace (Discord le réécrirait)`);
        }
      }
      if (salon.verrouille && salon.type !== "vocal") erreurs.push(`${fichier}: ${ici}.verrouille: seul un vocal se verrouille`);
    });
  });

  const journal = salons.find((s) => s.id === JOURNAL);
  if (!journal) erreurs.push(`${fichier}: categories: aucun salon « ${JOURNAL} », où le bot écrit ses comptes rendus`);
  else if (journal.type !== "texte") erreurs.push(`${fichier}: categories: le salon « ${JOURNAL} » doit être un salon texte`);
}

function main() {
  const args = process.argv.slice(2);
  if (args.length > 1 || (args[0] ?? "").startsWith("--")) {
    console.error("Usage : node tools/verifier_plan_discord.mjs [plan.json]");
    process.exit(2);
  }
  const cheminPlan = resolve(args[0] ?? PLAN);
  const fichier = nomAffiche(cheminPlan);
  const erreurs = [];

  const schema = lire(SCHEMA, erreurs);
  const jetons = lire(TOKENS, erreurs);
  const plan = lire(cheminPlan, erreurs);
  if (schema && jetons && plan) {
    const ajv = new Ajv2020({ allErrors: true, strict: true, strictRequired: false });
    addFormats(ajv);
    const valider = ajv.compile(schema);
    if (!valider(plan)) {
      for (const e of valider.errors) {
        const chemin = e.instancePath ? e.instancePath.slice(1).split("/").join(".") : "$";
        const detail = e.keyword === "additionalProperties" ? `${e.message} (${e.params.additionalProperty})` : e.message;
        erreurs.push(`${fichier}: ${chemin}: ${detail}`);
      }
    }
    const couleurs = couleursDesJetons(jetons);
    for (const couleur of schema.$defs?.couleur?.enum ?? []) {
      if (!couleurs.has(couleur)) erreurs.push(`${nomAffiche(SCHEMA)}: $defs.couleur: « ${couleur} » n'est pas dans design/tokens.json`);
    }
    controler(plan, fichier, couleurs, erreurs);
  }

  if (erreurs.length > 0) {
    erreurs.forEach((e) => console.error(e));
    process.exit(1);
  }
  console.log(`${fichier} : plan du serveur Discord valide.`);
}

main();
