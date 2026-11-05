#!/usr/bin/env node
// Génère les types TypeScript de l'add-on à partir de definitions/ : un fichier unique,
// addon/src/generated/definitions.ts, qu'on n'édite jamais à la main.
//
// Le dossier est parcouru, pas listé : tout <nom>.json qui a son <nom>.schema.json donne ses types, donc
// un futur definitions/monde.json est repris sans toucher à ce script. Aucun chiffre du jeu ici : on lit
// les schémas (la forme) et, pour les identifiants, les fichiers de données (les valeurs).
//
// Ce que le fichier contient :
//   - un type par schéma : <Nom>Definitions pour la racine, <Nom><Def> pour chaque entrée de $defs ;
//   - une union d'identifiants pour chaque tableau d'objets à « id » de la racine (FactoryId, ...).
//
// Usage : node tools/generer_types.mjs [dossier] [--schemas dossier] [--sortie fichier] [--verifier]
//   dossier    définitions à lire (par défaut : definitions/ du dépôt)
//   --schemas  dossier des schémas (par défaut : <dossier>/schemas)
//   --sortie   fichier à écrire (par défaut : addon/src/generated/definitions.ts)
//   --verifier n'écrit rien ; code 1 si le fichier de sortie diffère de ce qui serait généré
// Un mot-clé de schéma que le convertisseur ne connaît pas fait échouer le script : mieux vaut un échec
// net qu'un type qui laisse passer ce que le schéma interdit.

import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const racine = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const SORTIE_PAR_DEFAUT = join(racine, "addon", "src", "generated", "definitions.ts");

// Noms d'usage des unions d'identifiants, par « fichier.tableau ». Tout autre tableau d'objets à id prend
// le nom <Fichier><Tableau>Id, ce qui suffit à un nouveau fichier sans toucher à cette table.
const NOMS_IDENTIFIANTS = {
  "usines.usines": "FactoryId",
  "boutique.grades": "ShopGradeId",
  "boutique.abonnements": "ShopSubscriptionId",
  "missions.missions": "MissionId",
  "incidents.sortes": "IncidentKindId",
};

// ---------------------------------------------------------------- arguments

function lireArguments(argv) {
  const options = { dossier: null, schemas: null, sortie: null, verifier: false };
  for (let i = 0; i < argv.length; i++) {
    const argument = argv[i];
    if (argument === "--verifier") {
      options.verifier = true;
    } else if (argument === "--schemas" || argument === "--sortie") {
      const valeur = argv[++i];
      if (!valeur) throw new Error(`${argument} demande une valeur`);
      options[argument.slice(2)] = valeur;
    } else if (argument.startsWith("--")) {
      throw new Error(`option inconnue : ${argument}`);
    } else if (options.dossier === null) {
      options.dossier = argument;
    } else {
      throw new Error(`argument en trop : ${argument}`);
    }
  }
  return options;
}

// ---------------------------------------------------------------- mots-clés de schéma

// Sans effet sur le type.
const ANNOTATIONS = new Set(["$schema", "$id", "$comment", "title", "description", "default", "examples", "deprecated"]);
// Des contraintes de valeur : le type TypeScript ne les exprime pas, le validateur s'en charge.
const CONTRAINTES = new Set([
  "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
  "minLength", "maxLength", "pattern", "format",
  "minItems", "maxItems", "uniqueItems",
  "minProperties", "maxProperties", "dependentRequired",
]);
// Ce que le convertisseur sait lire.
const STRUCTURE = new Set([
  "type", "enum", "const", "properties", "required", "additionalProperties", "items", "$ref", "$defs", "anyOf", "oneOf",
]);

class ErreurSchema extends Error {}

function echec(chemin, message) {
  throw new ErreurSchema(`${chemin}: ${message}`);
}

/** Une branche d'anyOf qui ne porte que des contraintes (« required », par exemple) ne dit rien du type. */
function sansType(schema) {
  if (schema === null || typeof schema !== "object") return false;
  return Object.keys(schema).every((cle) => ANNOTATIONS.has(cle) || CONTRAINTES.has(cle) || cle === "required");
}

// ---------------------------------------------------------------- noms

/** « boutique » donne Boutique, « mon-monde » donne MonMonde, « identifiantGrade » donne IdentifiantGrade. */
function pascal(texte) {
  const mots = texte.split(/[^A-Za-z0-9]+/).filter(Boolean);
  if (mots.length === 0) throw new Error(`nom inutilisable : « ${texte} »`);
  const nom = mots.map((mot) => mot[0].toUpperCase() + mot.slice(1)).join("");
  return /^[0-9]/.test(nom) ? `_${nom}` : nom;
}

function cleDePropriete(nom) {
  return /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(nom) ? nom : JSON.stringify(nom);
}

function commentaire(texte, indentation) {
  if (typeof texte !== "string" || texte.trim() === "") return "";
  const ligne = texte.replace(/\s+/g, " ").trim().replaceAll("*/", "*\\/");
  return `${indentation}/** ${ligne} */\n`;
}

// ---------------------------------------------------------------- conversion schéma vers TypeScript

/**
 * Convertit un schéma en texte de type. `contexte` porte le schéma racine, le préfixe des noms et la table
 * des unions d'identifiants. Rend { code, union } : `union` dit s'il faut des parenthèses dans un tableau.
 */
function convertir(schema, contexte, chemin, indentation) {
  if (schema === true || (schema !== null && typeof schema === "object" && Object.keys(schema).length === 0)) {
    return { code: "unknown", union: false };
  }
  if (schema === null || typeof schema !== "object" || Array.isArray(schema)) {
    echec(chemin, "un schéma doit être un objet");
  }
  for (const cle of Object.keys(schema)) {
    if (ANNOTATIONS.has(cle) || CONTRAINTES.has(cle) || STRUCTURE.has(cle)) continue;
    echec(chemin, `mot-clé de schéma non pris en charge : « ${cle} »`);
  }

  if ("$ref" in schema) {
    for (const cle of ["type", "enum", "const", "properties", "items", "anyOf", "oneOf", "additionalProperties"]) {
      if (cle in schema) echec(chemin, `« ${cle} » à côté d'un $ref n'est pas pris en charge`);
    }
    return { code: nomDeReference(schema.$ref, contexte, chemin), union: false };
  }

  if ("const" in schema) return { code: litteral(schema.const, chemin), union: false };
  if ("enum" in schema) {
    if (!Array.isArray(schema.enum) || schema.enum.length === 0) echec(chemin, "enum vide ou mal formée");
    const valeurs = schema.enum.map((valeur) => litteral(valeur, chemin));
    return { code: valeurs.join(" | "), union: valeurs.length > 1 };
  }

  for (const cle of ["anyOf", "oneOf"]) {
    if (!(cle in schema)) continue;
    const branches = schema[cle];
    if (!Array.isArray(branches) || branches.length === 0) echec(chemin, `« ${cle} » vide ou mal formé`);
    if (branches.every(sansType)) continue; // des contraintes seules : le type vient du reste du schéma
    if (branches.some(sansType)) echec(chemin, `« ${cle} » mêle des branches typées et des contraintes`);
    if ("type" in schema || "properties" in schema) echec(chemin, `« ${cle} » à côté de « type » n'est pas pris en charge`);
    const types = branches.map((branche, i) => convertir(branche, contexte, `${chemin}/${cle}/${i}`, indentation));
    const codes = types.map((t) => (t.union ? `(${t.code})` : t.code));
    return { code: codes.join(" | "), union: codes.length > 1 };
  }

  let types = schema.type;
  if (types === undefined) types = "properties" in schema ? "object" : null;
  if (types === null) return { code: "unknown", union: false };
  if (Array.isArray(types)) {
    if (types.length === 0) echec(chemin, "« type » vide");
    const convertis = types.map((type) => convertirType(type, schema, contexte, chemin, indentation));
    return { code: convertis.map((t) => (t.union ? `(${t.code})` : t.code)).join(" | "), union: convertis.length > 1 };
  }
  return convertirType(types, schema, contexte, chemin, indentation);
}

function convertirType(type, schema, contexte, chemin, indentation) {
  switch (type) {
    case "string":
      return { code: "string", union: false };
    case "number":
    case "integer":
      return { code: "number", union: false };
    case "boolean":
      return { code: "boolean", union: false };
    case "null":
      return { code: "null", union: false };
    case "array": {
      if (!("items" in schema)) return { code: "readonly unknown[]", union: false };
      const element = convertir(schema.items, contexte, `${chemin}/items`, indentation);
      return { code: `readonly ${element.union ? `(${element.code})` : element.code}[]`, union: false };
    }
    case "object":
      return convertirObjet(schema, contexte, chemin, indentation);
    default:
      return echec(chemin, `type non pris en charge : « ${type} »`);
  }
}

function convertirObjet(schema, contexte, chemin, indentation) {
  const proprietes = schema.properties ?? {};
  const noms = Object.keys(proprietes);
  const obligatoires = new Set(schema.required ?? []);
  for (const nom of obligatoires) {
    if (!(nom in proprietes)) echec(chemin, `« required » cite « ${nom} », que « properties » ne décrit pas`);
  }
  const supplement = schema.additionalProperties;
  const ouvert = supplement === true;
  const supplementType = supplement !== undefined && supplement !== true && supplement !== false;
  if (supplementType && noms.length > 0) {
    echec(chemin, "« additionalProperties » typé avec « properties » dans le même objet n'est pas pris en charge");
  }

  if (supplementType) {
    const valeur = convertir(supplement, contexte, `${chemin}/additionalProperties`, indentation);
    return { code: `{ readonly [cle: string]: ${valeur.code} }`, union: false };
  }

  const interne = `${indentation}  `;
  const surcharge = contexte.identifiants.get(schema) ?? null;
  const lignes = noms.map((nom) => {
    const propriete = proprietes[nom];
    const code =
      nom === "id" && surcharge
        ? surcharge
        : convertir(propriete, contexte, `${chemin}/properties/${nom}`, interne).code;
    const doc = propriete !== null && typeof propriete === "object" ? commentaire(propriete.description, interne) : "";
    return `${doc}${interne}readonly ${cleDePropriete(nom)}${obligatoires.has(nom) ? "" : "?"}: ${code};\n`;
  });
  if (ouvert) lignes.push(`${interne}readonly [cle: string]: unknown;\n`);
  if (lignes.length === 0) return { code: "Record<string, never>", union: false };
  return { code: `{\n${lignes.join("")}${indentation}}`, union: false };
}

function litteral(valeur, chemin) {
  if (valeur === null || ["string", "number", "boolean"].includes(typeof valeur)) return JSON.stringify(valeur);
  return echec(chemin, "une constante ou un élément d'enum doit être une chaîne, un nombre, un booléen ou null");
}

function nomDeReference(reference, contexte, chemin) {
  const correspondance = /^#\/\$defs\/([^/]+)$/.exec(String(reference));
  if (!correspondance) echec(chemin, `$ref non pris en charge : « ${reference} » (seul #/$defs/<nom> l'est)`);
  const definition = correspondance[1];
  if (!(definition in (contexte.racine.$defs ?? {}))) echec(chemin, `$ref vers « ${definition} », absent de $defs`);
  return `${contexte.prefixe}${pascal(definition)}`;
}

/** Suit un $ref local pour atteindre le schéma visé (utile pour repérer les tableaux d'objets à id). */
function resoudre(schema, schemaRacine) {
  if (schema !== null && typeof schema === "object" && typeof schema.$ref === "string") {
    const correspondance = /^#\/\$defs\/([^/]+)$/.exec(schema.$ref);
    return correspondance ? (schemaRacine.$defs?.[correspondance[1]] ?? schema) : schema;
  }
  return schema;
}

// ---------------------------------------------------------------- identifiants

/**
 * Les tableaux de la racine dont les éléments sont des objets à « id » : { propriete, element }.
 * Le schéma de l'élément sert de clé pour typer la propriété « id » avec l'union.
 */
function tableauxAIdentifiant(schemaRacine) {
  const trouves = [];
  for (const [propriete, brut] of Object.entries(schemaRacine.properties ?? {})) {
    const tableau = resoudre(brut, schemaRacine);
    if (!tableau || tableau.type !== "array" || !tableau.items) continue;
    const element = resoudre(tableau.items, schemaRacine);
    if (element && typeof element === "object" && element.properties && "id" in element.properties) {
      trouves.push({ propriete, element });
    }
  }
  return trouves;
}

function valeursDIdentifiants(donnees, propriete, fichier) {
  const liste = donnees?.[propriete];
  if (!Array.isArray(liste)) echec(fichier, `« ${propriete} » devrait être un tableau`);
  const vus = new Set();
  liste.forEach((entree, i) => {
    const id = entree?.id;
    if (typeof id !== "string" || id === "") echec(fichier, `${propriete}[${i}].id devrait être une chaîne non vide`);
    vus.add(id);
  });
  return [...vus];
}

// ---------------------------------------------------------------- un fichier de définitions

function nomAffiche(chemin) {
  const rel = relative(process.cwd(), chemin);
  return (rel && !rel.startsWith("..") ? rel : chemin).replaceAll("\\", "/");
}

function lireJson(chemin) {
  try {
    return JSON.parse(readFileSync(chemin, "utf8").replace(/^﻿/, ""));
  } catch (e) {
    throw new ErreurSchema(`${nomAffiche(chemin)}: JSON illisible : ${e.message}`);
  }
}

function genererFichier(nom, fichierDonnees, fichierSchema, nomsPris) {
  const donnees = lireJson(fichierDonnees);
  const schema = lireJson(fichierSchema);
  const affiche = nomAffiche(fichierSchema);
  if (schema === null || typeof schema !== "object" || (schema.type !== "object" && !schema.properties)) {
    echec(affiche, "le schéma racine doit décrire un objet");
  }
  const prefixe = pascal(nom);
  const contexte = { racine: schema, prefixe, identifiants: new Map() };

  const sections = [];
  const reserver = (nomType) => {
    if (nomsPris.has(nomType)) echec(affiche, `le nom de type « ${nomType} » est déjà pris`);
    nomsPris.add(nomType);
    return nomType;
  };

  // Unions d'identifiants. Un schéma d'élément partagé par deux tableaux n'est pas typé : l'union serait fausse.
  const unions = [];
  const surcharges = new Map();
  for (const { propriete, element } of tableauxAIdentifiant(schema)) {
    const nomUnion = reserver(NOMS_IDENTIFIANTS[`${nom}.${propriete}`] ?? `${prefixe}${pascal(propriete)}Id`);
    const valeurs = valeursDIdentifiants(donnees, propriete, nomAffiche(fichierDonnees));
    unions.push({ nomUnion, propriete, valeurs });
    surcharges.set(element, surcharges.has(element) ? null : nomUnion);
  }
  for (const [element, nomUnion] of surcharges) if (nomUnion) contexte.identifiants.set(element, nomUnion);

  for (const { nomUnion, propriete, valeurs } of unions) {
    const corps = valeurs.length === 0 ? " never" : `\n${valeurs.map((v) => `  | ${JSON.stringify(v)}`).join("\n")}`;
    sections.push(`/** Identifiants de ${nom}.json, tableau « ${propriete} ». */\nexport type ${nomUnion} =${corps};\n`);
  }

  const racineNom = reserver(`${prefixe}Definitions`);
  const doc = commentaire(schema.description ?? schema.title, "");
  sections.push(`${doc}export type ${racineNom} = ${convertir(schema, contexte, affiche, "").code};\n`);

  for (const [definition, sousSchema] of Object.entries(schema.$defs ?? {})) {
    const nomType = reserver(`${prefixe}${pascal(definition)}`);
    const code = convertir(sousSchema, contexte, `${affiche}#/$defs/${definition}`, "").code;
    const docDef = sousSchema !== null && typeof sousSchema === "object" ? commentaire(sousSchema.description, "") : "";
    sections.push(`${docDef}export type ${nomType} = ${code};\n`);
  }
  return `// ---- ${nom}.json\n\n${sections.join("\n")}`;
}

// ---------------------------------------------------------------- l'ensemble

const ENTETE = [
  "// Fichier généré par tools/generer_types.mjs à partir de definitions/ : ne pas éditer à la main.",
  "// Pour le refaire : node tools/generer_types.mjs",
  "// Pour vérifier qu'il est à jour : node tools/generer_types.mjs --verifier",
  "",
].join("\n");

function generer(dossier, dossierSchemas) {
  if (!existsSync(dossier)) throw new Error(`dossier introuvable : ${nomAffiche(dossier)}`);
  const noms = readdirSync(dossier, { withFileTypes: true })
    .filter((entree) => entree.isFile() && entree.name.endsWith(".json"))
    .map((entree) => entree.name.slice(0, -".json".length))
    .sort();
  if (noms.length === 0) throw new Error(`aucun fichier .json dans ${nomAffiche(dossier)}`);

  const nomsPris = new Set();
  const blocs = noms.map((nom) => {
    const fichierSchema = join(dossierSchemas, `${nom}.schema.json`);
    if (!existsSync(fichierSchema)) {
      throw new ErreurSchema(`${nomAffiche(join(dossier, `${nom}.json`))}: pas de schéma ${nomAffiche(fichierSchema)}`);
    }
    return genererFichier(nom, join(dossier, `${nom}.json`), fichierSchema, nomsPris);
  });
  return `${ENTETE}\n${blocs.join("\n")}`;
}

function main() {
  const options = lireArguments(process.argv.slice(2));
  const dossier = resolve(options.dossier ?? join(racine, "definitions"));
  const dossierSchemas = resolve(options.schemas ?? join(dossier, "schemas"));
  const sortie = resolve(options.sortie ?? SORTIE_PAR_DEFAUT);
  const texte = generer(dossier, dossierSchemas);

  const existant = existsSync(sortie) ? readFileSync(sortie, "utf8").replaceAll("\r\n", "\n") : null;
  if (options.verifier) {
    if (existant === null) {
      console.error(`${nomAffiche(sortie)}: fichier absent. Lance : node tools/generer_types.mjs`);
      process.exit(1);
    }
    if (existant !== texte) {
      const attendues = texte.split("\n");
      const presentes = existant.split("\n");
      let ligne = 0;
      while (ligne < attendues.length && attendues[ligne] === presentes[ligne]) ligne++;
      console.error(`${nomAffiche(sortie)}:${ligne + 1}: périmé, il ne correspond plus à definitions/.`);
      console.error(`  attendu : ${attendues[ligne] ?? "(fin du fichier)"}`);
      console.error(`  présent : ${presentes[ligne] ?? "(fin du fichier)"}`);
      console.error("Lance : node tools/generer_types.mjs");
      process.exit(1);
    }
    console.log(`Types de l'add-on à jour : ${nomAffiche(sortie)}.`);
    return;
  }

  if (existant === texte) {
    console.log(`Types de l'add-on déjà à jour : ${nomAffiche(sortie)}.`);
    return;
  }
  mkdirSync(dirname(sortie), { recursive: true });
  writeFileSync(sortie, texte, "utf8");
  console.log(`Types de l'add-on écrits : ${nomAffiche(sortie)}.`);
}

try {
  main();
} catch (e) {
  console.error(`generer_types: ${e.message}`);
  process.exit(1);
}
