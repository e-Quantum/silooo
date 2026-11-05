#!/usr/bin/env node
// Vérifie les fichiers de definitions/ : chaque <nom>.json contre definitions/schemas/<nom>.schema.json,
// puis des contrôles croisés que le schéma ne sait pas exprimer (identifiants uniques, entrées qui
// existent, grades qui montent). Aucun chiffre du jeu ici : le script ne lit que les fichiers.
//
// Usage : node tools/verifier_definitions.mjs [dossier] [--schemas dossier]
//   dossier    définitions à vérifier (par défaut : definitions/ du dépôt)
//   --schemas  dossier des schémas (par défaut : <dossier>/schemas, sinon definitions/schemas du dépôt)
// Sortie : une ligne « fichier: chemin JSON: message » par erreur, code 1 s'il y en a.

import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const racine = resolve(dirname(fileURLToPath(import.meta.url)), "..");

// ---------------------------------------------------------------- arguments

function lireArguments(argv) {
  const options = { dossier: null, schemas: null };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--schemas") {
      options.schemas = argv[++i] ?? null;
      if (!options.schemas) throw new Error("--schemas demande un dossier");
    } else if (argv[i].startsWith("--")) {
      throw new Error(`option inconnue : ${argv[i]}`);
    } else if (options.dossier === null) {
      options.dossier = argv[i];
    } else {
      throw new Error(`argument en trop : ${argv[i]}`);
    }
  }
  return options;
}

// ---------------------------------------------------------------- messages

const erreurs = [];

function nomAffiche(chemin) {
  const rel = relative(process.cwd(), chemin);
  return (rel && !rel.startsWith("..") ? rel : chemin).replaceAll("\\", "/");
}

function signaler(fichier, chemin, message) {
  erreurs.push(`${fichier}: ${chemin}: ${message}`);
}

/** Chemin JSON lisible : ["usines", "3", "id"] devient $.usines[3].id. */
function cheminJson(segments) {
  let chemin = "$";
  for (const segment of segments) {
    chemin += /^\d+$/.test(segment) ? `[${segment}]` : `.${segment}`;
  }
  return chemin;
}

function segmentsDe(instancePath) {
  return instancePath
    .split("/")
    .slice(1)
    .map((s) => s.replaceAll("~1", "/").replaceAll("~0", "~"));
}

const TYPES = {
  string: "une chaîne",
  number: "un nombre",
  integer: "un entier",
  boolean: "un booléen",
  array: "un tableau",
  object: "un objet",
  null: "null",
};

function extrait(valeur) {
  const texte = JSON.stringify(valeur) ?? String(valeur);
  return texte.length > 60 ? `${texte.slice(0, 57)}...` : texte;
}

/** Nom de l'entrée visée (usine, grade, abonnement) quand l'erreur est dans un tableau d'objets à id. */
function etiquette(donnees, segments) {
  let courant = donnees;
  let id = null;
  for (const segment of segments) {
    if (courant === null || typeof courant !== "object") break;
    courant = courant[segment];
    if (courant && typeof courant === "object" && typeof courant.id === "string") id = courant.id;
  }
  return id ? ` (entrée « ${id} »)` : "";
}

function traduire(err, donnees) {
  const segments = segmentsDe(err.instancePath);
  const p = err.params;
  let message;
  switch (err.keyword) {
    case "required":
      segments.push(p.missingProperty);
      return { segments, message: "propriété obligatoire absente" };
    case "additionalProperties":
      segments.push(p.additionalProperty);
      return { segments, message: "propriété inconnue, le schéma n'en prévoit pas" };
    case "type":
      message = `doit être ${TYPES[p.type] ?? p.type}, reçu ${extrait(err.data)}`;
      break;
    case "minimum":
      message = `doit être au moins ${p.limit}, reçu ${extrait(err.data)}`;
      break;
    case "maximum":
      message = `doit être au plus ${p.limit}, reçu ${extrait(err.data)}`;
      break;
    case "exclusiveMinimum":
      message = `doit être strictement supérieur à ${p.limit}, reçu ${extrait(err.data)}`;
      break;
    case "exclusiveMaximum":
      message = `doit être strictement inférieur à ${p.limit}, reçu ${extrait(err.data)}`;
      break;
    case "pattern": {
      const attendu = err.parentSchema?.description ?? `motif ${p.pattern}`;
      message = `format invalide ${extrait(err.data)}. Attendu : ${attendu}`;
      break;
    }
    case "minItems":
      message = `doit contenir au moins ${p.limit} élément(s)`;
      break;
    case "minLength":
      message = `ne doit pas être vide (au moins ${p.limit} caractère)`;
      break;
    case "uniqueItems":
      message = `doublon aux positions ${p.j} et ${p.i}`;
      break;
    case "dependentRequired":
      message = `« ${p.property} » exige aussi « ${p.missingProperty} »`;
      break;
    case "anyOf": {
      const noms = (err.parentSchema?.anyOf ?? []).flatMap((b) => b.required ?? []);
      message = noms.length
        ? `doit porter l'une de ces propriétés : ${noms.join(", ")}`
        : "ne correspond à aucune des formes admises";
      break;
    }
    case "enum":
      message = `doit valoir l'une de ces valeurs : ${p.allowedValues.map(extrait).join(", ")}`;
      break;
    case "const":
      message = `doit valoir ${extrait(p.allowedValue)}`;
      break;
    default:
      message = err.message ?? `règle ${err.keyword} non respectée`;
  }
  return { segments, message: message + etiquette(donnees, segments) };
}

/** Retire les erreurs de branches d'un anyOf : seule l'erreur du anyOf lui-même est utile. */
function sansBranches(errs) {
  const racinesAnyOf = errs.filter((e) => e.keyword === "anyOf").map((e) => `${e.schemaPath}/`);
  return errs.filter((e) => !racinesAnyOf.some((r) => e.schemaPath.startsWith(r)));
}

// ---------------------------------------------------------------- contrôles croisés

const liste = (x) => (Array.isArray(x) ? x : []);
const texte = (x) => (typeof x === "string" ? x : null);

function doublons(fichier, chemin, entrees, nature) {
  const vus = new Map();
  entrees.forEach((entree, i) => {
    const id = texte(entree?.id);
    if (id === null) return;
    if (vus.has(id)) {
      signaler(fichier, `${chemin}[${i}].id`, `identifiant « ${id} » déjà utilisé par ${nature} n° ${vus.get(id)}`);
    } else {
      vus.set(id, i);
    }
  });
}

function croissant(fichier, chemin, valeurs, nom) {
  for (let i = 1; i < valeurs.length; i++) {
    if (typeof valeurs[i] === "number" && typeof valeurs[i - 1] === "number" && valeurs[i] <= valeurs[i - 1]) {
      signaler(fichier, `${chemin}[${i}]`, `${nom} doivent croître : ${valeurs[i]} suit ${valeurs[i - 1]}`);
    }
  }
}

function controlesUsines(fichier, d) {
  const usines = liste(d.usines);
  doublons(fichier, "$.usines", usines, "l'usine");
  // Qui produit quoi : une entrée doit sortir d'une AUTRE usine.
  const producteurs = new Map();
  for (const u of usines) {
    for (const produit of liste(u?.produit)) {
      if (!producteurs.has(produit)) producteurs.set(produit, new Set());
      producteurs.get(produit).add(u?.id);
    }
  }
  usines.forEach((u, i) => {
    liste(u?.entrees).forEach((entree, k) => {
      const autres = [...(producteurs.get(entree) ?? [])].filter((id) => id !== u?.id);
      if (autres.length === 0) {
        signaler(fichier, `$.usines[${i}].entrees[${k}]`,
          `l'entrée « ${entree} » n'est le produit d'aucune autre usine (entrée de « ${u?.id} »)`);
      }
    });
  });
}

function controlesEconomie(fichier, d) {
  const seuils = liste(d.prestige?.seuils);
  seuils.forEach((s, i) => {
    if (s?.course !== i + 1) {
      signaler(fichier, `$.prestige.seuils[${i}].course`, `les courses se suivent de 1 en 1 : ${i + 1} attendu, reçu ${extrait(s?.course)}`);
    }
  });
  croissant(fichier, "$.prestige.seuils", seuils.map((s) => s?.donar), "les seuils de Donar");
  croissant(fichier, "$.ville.zonesPrixLingots", liste(d.ville?.zonesPrixLingots), "les prix des zones");
  croissant(fichier, "$.niveaux.paliersX2.fixes", liste(d.niveaux?.paliersX2?.fixes), "les paliers fixes");
}

function controlesBoutique(fichier, d) {
  const grades = liste(d.grades);
  doublons(fichier, "$.grades", grades, "le grade");
  doublons(fichier, "$.abonnements", liste(d.abonnements), "l'abonnement");
  // « À prendre dans l'ordre » : chaque grade coûte plus que le précédent.
  croissant(fichier, "$.grades", grades.map((g) => g?.gemmes), "les prix des grades (en Gemmes)");
  const ids = new Set(grades.map((g) => g?.id));
  liste(d.abonnements).forEach((a, i) => {
    if (a?.reserveA !== undefined && !ids.has(a.reserveA)) {
      signaler(fichier, `$.abonnements[${i}].reserveA`, `le grade « ${a.reserveA} » n'existe pas dans $.grades`);
    }
  });
  controlesCosmetiques(fichier, d);
}

/** Identifiant de l'article « boost collectif » dans la boutique : une constante du service (ShopRules.BOOST_ID). */
const ARTICLE_BOOST = "boost-collectif";
/** Le titre que le site donne aux pionniers : le service le cherche par cet identifiant (ShopRules.PIONEER_TITLE_ID). */
const TITRE_PIONNIER = "pionnier";

function controlesCosmetiques(fichier, d) {
  const cosmetiques = liste(d.cosmetiques);
  doublons(fichier, "$.cosmetiques", cosmetiques, "le cosmétique");
  // Tous les articles se commandent par le même champ itemId : un identifiant ne peut désigner qu'un article.
  const pris = new Map([[ARTICLE_BOOST, "le boost collectif"]]);
  liste(d.grades).forEach((g) => pris.set(g?.id, "un grade"));
  liste(d.abonnements).forEach((a) => pris.set(a?.id, "un abonnement"));
  const noms = new Map();
  cosmetiques.forEach((c, i) => {
    const id = texte(c?.id);
    if (id !== null && pris.has(id)) {
      signaler(fichier, `$.cosmetiques[${i}].id`, `l'identifiant « ${id} » désigne déjà ${pris.get(id)} : un article, un identifiant`);
    }
    const nom = texte(c?.nom)?.trim().toLowerCase();
    if (nom !== undefined && nom !== null) {
      if (noms.has(nom)) {
        signaler(fichier, `$.cosmetiques[${i}].nom`, `le nom « ${c.nom} » est déjà celui du cosmétique n° ${noms.get(nom)}`);
      } else {
        noms.set(nom, i);
      }
    }
    if (c?.achetable === true && !(c?.prixGemmes >= 1)) {
      signaler(fichier, `$.cosmetiques[${i}].prixGemmes`, "un cosmétique achetable coûte au moins 1 Gemme");
    }
    if (c?.achetable === false && c?.prixGemmes !== 0) {
      signaler(fichier, `$.cosmetiques[${i}].prixGemmes`, "un cosmétique qui n'est pas vendu a le prix 0");
    }
  });
  const pionnier = cosmetiques.find((c) => c?.id === TITRE_PIONNIER);
  if (pionnier === undefined || pionnier.categorie !== "titre" || pionnier.achetable !== false) {
    signaler(fichier, "$.cosmetiques", `le titre « ${TITRE_PIONNIER} » doit exister, de catégorie titre et non achetable : le site le donne aux pionniers`);
  }
}

/** Suit un chemin « a.b.c » dans un objet ; undefined si un maillon manque. */
function valeurAuChemin(objet, chemin) {
  let courant = objet;
  for (const cle of chemin.split(".")) {
    if (courant === null || typeof courant !== "object" || Array.isArray(courant) || !Object.hasOwn(courant, cle)) {
      return undefined;
    }
    courant = courant[cle];
  }
  return courant;
}

function controlesMissions(fichier, d, dossier) {
  const missions = liste(d.missions);
  doublons(fichier, "$.missions", missions, "la mission");
  // Les récompenses sont des références dans economie.json : le chemin doit y mener à un nombre entier de Lingots.
  const fichierEconomie = join(dossier, "economie.json");
  if (!existsSync(fichierEconomie)) return;
  let economie;
  try {
    economie = JSON.parse(readFileSync(fichierEconomie, "utf8").replace(/^﻿/, ""));
  } catch {
    return; // economie.json illisible : sa propre vérification le dit, il n'y a rien à croiser
  }
  missions.forEach((m, i) => {
    const chemin = texte(m?.recompense);
    if (chemin === null) return;
    const valeur = valeurAuChemin(economie, chemin);
    if (valeur === undefined) {
      signaler(fichier, `$.missions[${i}].recompense`, `« ${chemin} » n'existe pas dans economie.json`);
    } else if (!Number.isInteger(valeur) || valeur < 0) {
      signaler(fichier, `$.missions[${i}].recompense`,
        `« ${chemin} » doit mener à un nombre entier de Lingots, 0 au moins ; economie.json y met ${extrait(valeur)}`);
    }
  });
}

function controlesPersonnel(fichier, d) {
  const agence = d.agence ?? {};
  // Une place de la liste du jour, un rôle : les deux tableaux se suivent.
  const places = agence.candidatsParJour;
  const roles = liste(agence.rolesParPlace);
  if (Number.isInteger(places) && roles.length !== places) {
    signaler(fichier, "$.agence.rolesParPlace", `une entrée par place : ${places} attendues (candidatsParJour), ${roles.length} reçues`);
  }
  // Le tirage se fait sans remise : il faut au moins un nom par place.
  if (Number.isInteger(places) && liste(agence.noms).length < places) {
    signaler(fichier, "$.agence.noms", `au moins ${places} noms (candidatsParJour) pour que deux candidats du jour n'en partagent pas un`);
  }
  // Les parts de rareté font 100, le bonus monte avec la rareté, et un membre rapporte plus qu'il ne coûte en salaire.
  const raretes = d.raretes && typeof d.raretes === "object" ? d.raretes : {};
  const ordre = ["COMMUN", "RARE", "EPIQUE"];
  const parts = ordre.map((r) => raretes[r]?.repartitionPourcent);
  if (parts.every((x) => typeof x === "number")) {
    const somme = parts.reduce((a, b) => a + b, 0);
    if (Math.abs(somme - 100) > 1e-9) {
      signaler(fichier, "$.raretes", `les parts repartitionPourcent doivent faire 100, elles font ${somme}`);
    }
  }
  croissant(fichier, "$.raretes", ordre.map((r) => raretes[r]?.bonusProductionPourcent), "les bonus de production (COMMUN, RARE, EPIQUE)");
  croissant(fichier, "$.raretes", ordre.map((r) => raretes[r]?.coutHeuresDeRevenu), "les prix d'embauche (COMMUN, RARE, EPIQUE)");
  // Le nom que les joueurs lisent : deux raretés ne s'appellent pas pareil.
  const nomsRaretes = ordre.map((r) => raretes[r]?.nom).filter((n) => typeof n === "string").map((n) => n.trim().toLowerCase());
  if (new Set(nomsRaretes).size !== nomsRaretes.length) {
    signaler(fichier, "$.raretes", "deux raretés portent le même nom");
  }
  const facteursRoles = d.roles && typeof d.roles === "object" ? d.roles : {};
  for (const r of ordre) {
    const bonus = raretes[r]?.bonusProductionPourcent;
    const salaire = raretes[r]?.salairePourcent;
    if (typeof bonus !== "number" || typeof salaire !== "number") continue;
    for (const [role, facteurs] of Object.entries(facteursRoles)) {
      const facteur = facteurs?.salaireFacteur;
      if (typeof facteur === "number" && salaire * facteur >= bonus) {
        signaler(fichier, `$.raretes.${r}.salairePourcent`,
          `le salaire (${salaire} × ${facteur} pour ${role}) doit rester sous le bonus de production (${bonus}) : sinon embaucher fait perdre de l'argent`);
      }
    }
  }
}

const MINUTES_PAR_JOUR = 24 * 60;

function controlesIncidents(fichier, d, dossier) {
  doublons(fichier, "$.sortes", liste(d.sortes), "la sorte");
  // Les créneaux d'un jour UTC se suivent sans reste : le numéro du créneau fait partie du tirage.
  const creneau = d.frequence?.creneauMinutes;
  if (Number.isInteger(creneau) && creneau >= 1 && MINUTES_PAR_JOUR % creneau !== 0) {
    signaler(fichier, "$.frequence.creneauMinutes", `${creneau} minutes ne divisent pas une journée (${MINUTES_PAR_JOUR} minutes) : les créneaux ne se suivraient pas sans reste`);
  }
  // La colonne factory_incidents.slowdown est un DECIMAL(3,2) : deux décimales au plus.
  liste(d.sortes).forEach((s, i) => {
    const facteur = s?.ralentissement;
    if (typeof facteur === "number" && Math.abs(facteur * 100 - Math.round(facteur * 100)) > 1e-9) {
      signaler(fichier, `$.sortes[${i}].ralentissement`, `${facteur} a plus de deux décimales (colonne factory_incidents.slowdown)`);
    }
  });
  // La Centrale doit exister, et ne pas dépendre elle-même du courant qu'elle produit.
  const fichierUsines = join(dossier, "usines.json");
  if (!existsSync(fichierUsines)) return;
  let usines;
  try {
    usines = liste(JSON.parse(readFileSync(fichierUsines, "utf8").replace(/^﻿/, "")).usines);
  } catch {
    return; // usines.json illisible : sa propre vérification le dit, il n'y a rien à croiser
  }
  const centrale = texte(d.electricite?.usineCentrale);
  if (centrale !== null) {
    const usine = usines.find((u) => u?.id === centrale);
    if (!usine) {
      signaler(fichier, "$.electricite.usineCentrale", `« ${centrale} » n'est pas une usine de usines.json`);
    } else if (usine.electricite === true) {
      signaler(fichier, "$.electricite.usineCentrale", `« ${centrale} » dépend elle-même de l'électricité : sans courant, elle ne pourrait jamais redémarrer`);
    }
  }
  const epoques = usines.map((u) => u?.epoque).filter((e) => Number.isInteger(e));
  const premiere = d.electricite?.aPartirDeEpoque;
  if (Number.isInteger(premiere) && epoques.length > 0 && premiere > Math.max(...epoques)) {
    signaler(fichier, "$.electricite.aPartirDeEpoque", `${premiere} dépasse la dernière époque de usines.json (${Math.max(...epoques)}) : l'électricité ne servirait jamais`);
  }
}

/** Lit un fichier JSON voisin ; null s'il manque ou s'il est illisible (sa propre vérification le dit). */
function lireVoisin(dossier, nom) {
  const fichier = join(dossier, nom);
  if (!existsSync(fichier)) return null;
  try {
    return JSON.parse(readFileSync(fichier, "utf8").replace(/^﻿/, ""));
  } catch {
    return null;
  }
}

function nonDecroissant(fichier, chemin, valeurs, nom) {
  for (let i = 1; i < valeurs.length; i++) {
    if (typeof valeurs[i] === "number" && typeof valeurs[i - 1] === "number" && valeurs[i] < valeurs[i - 1]) {
      signaler(fichier, `${chemin}[${i}]`, `${nom} ne doivent pas baisser : ${valeurs[i]} suit ${valeurs[i - 1]}`);
    }
  }
}

function controlesVille(fichier, d, dossier) {
  const zones = liste(d.zones?.ordre);
  const colonnes = d.zones?.colonnes;
  const taille = d.zones?.tailleChunks;
  const blocsParChunk = d.ile?.blocsParChunk;
  const parcelle = d.ile?.parcelleChunks;

  // Le quadrillage est carré et chaque case porte une zone, une seule : les zones sont une permutation de la grille.
  if (Number.isInteger(colonnes) && zones.length !== colonnes * colonnes) {
    signaler(fichier, "$.zones.ordre", `${colonnes * colonnes} zones attendues (${colonnes} colonnes au carré), ${zones.length} reçues`);
  }
  const cases = new Map();
  zones.forEach((z, i) => {
    if (Number.isInteger(colonnes) && (z?.colonne >= colonnes || z?.rangee >= colonnes)) {
      signaler(fichier, `$.zones.ordre[${i}]`, `la case (${z.colonne}, ${z.rangee}) sort du quadrillage de ${colonnes} colonnes`);
    }
    const cle = `${z?.colonne},${z?.rangee}`;
    if (cases.has(cle)) {
      signaler(fichier, `$.zones.ordre[${i}]`, `la case (${z?.colonne}, ${z?.rangee}) est déjà celle de la zone ${cases.get(cle) + 1}`);
    } else {
      cases.set(cle, i);
    }
  });
  // On étend la ville de proche en proche : chaque zone touche une zone déjà achetée.
  zones.forEach((z, i) => {
    if (i === 0) return;
    const touche = zones.slice(0, i).some((autre) => Math.abs(autre?.colonne - z?.colonne) + Math.abs(autre?.rangee - z?.rangee) === 1);
    if (!touche) {
      signaler(fichier, `$.zones.ordre[${i}]`, `la zone ${i + 1} ne touche aucune des zones précédentes : la ville se bâtirait en îlots`);
    }
  });
  nonDecroissant(fichier, "$.zones.ordre", zones.map((z) => z?.niveauMairieRequis), "les niveaux de mairie des zones");
  if (Number.isInteger(taille) && Number.isInteger(parcelle) && taille % parcelle !== 0) {
    signaler(fichier, "$.ile.parcelleChunks", `${parcelle} chunks ne divisent pas une zone de ${taille} chunks : une parcelle déborderait sur la zone voisine`);
  }

  // La mairie de départ est sur une parcelle entière de la première zone.
  const depart = d.mairie?.emplacementDepart;
  if (depart && Number.isInteger(blocsParChunk) && Number.isInteger(parcelle) && Number.isInteger(taille) && zones.length > 0) {
    const cote = blocsParChunk * parcelle;
    for (const axe of ["x", "z"]) {
      if (Number.isInteger(depart[axe]) && depart[axe] % cote !== 0) {
        signaler(fichier, `$.mairie.emplacementDepart.${axe}`, `${depart[axe]} n'est pas sur une parcelle entière (multiple de ${cote} blocs)`);
      }
    }
    const zoneBlocs = taille * blocsParChunk;
    const premiere = zones[0];
    const dedans = (valeur, rang) => Number.isInteger(valeur) && Number.isInteger(rang) && valeur >= rang * zoneBlocs && valeur < (rang + 1) * zoneBlocs;
    if (!dedans(depart.x, premiere?.colonne) || !dedans(depart.z, premiere?.rangee)) {
      signaler(fichier, "$.mairie.emplacementDepart", "la mairie de départ doit se trouver dans la première zone");
    }
  }

  // Le catalogue.
  const batiments = liste(d.batiments);
  doublons(fichier, "$.batiments", batiments, "le bâtiment");
  const mairie = batiments.find((b) => b?.id === "mairie");
  if (!mairie) {
    signaler(fichier, "$.batiments", "le bâtiment « mairie » manque : les niveaux de mairie viennent de là");
  } else if (mairie.posable !== false) {
    signaler(fichier, `$.batiments[${batiments.indexOf(mairie)}].posable`, "la mairie n'est pas posable par le joueur : elle est posée toute seule à l'achat de la première zone");
  }
  const niveauxMairie = liste(mairie?.niveaux).length;
  const dernierRequis = Math.max(0, ...zones.map((z) => (Number.isInteger(z?.niveauMairieRequis) ? z.niveauMairieRequis : 0)));
  if (mairie && dernierRequis > niveauxMairie) {
    signaler(fichier, "$.zones.ordre", `une zone demande la mairie niveau ${dernierRequis}, mais la mairie n'a que ${niveauxMairie} niveaux`);
  }
  batiments.forEach((b, i) => {
    const chemin = `$.batiments[${i}]`;
    const niveaux = liste(b?.niveaux);
    const avecBonus = niveaux.filter((n) => n?.bonus !== undefined).length;
    if (b?.typeBonus === undefined && avecBonus > 0) {
      signaler(fichier, `${chemin}.niveaux`, `« ${b?.id} » n'a pas de typeBonus, ses niveaux ne portent donc pas de bonus`);
    }
    if (b?.typeBonus !== undefined && avecBonus !== niveaux.length) {
      signaler(fichier, `${chemin}.niveaux`, `« ${b?.id} » a un typeBonus (${b.typeBonus}) : chacun de ses niveaux doit porter un bonus`);
    }
    nonDecroissant(fichier, `${chemin}.niveaux`, niveaux.map((n) => n?.bonus), `les bonus de « ${b?.id} »`);
    nonDecroissant(fichier, `${chemin}.niveaux`, niveaux.map((n) => n?.niveauMairieRequis), `les niveaux de mairie de « ${b?.id} »`);
    niveaux.forEach((n, k) => {
      if (mairie && Number.isInteger(n?.niveauMairieRequis) && n.niveauMairieRequis > niveauxMairie) {
        signaler(fichier, `${chemin}.niveaux[${k}].niveauMairieRequis`, `la mairie niveau ${n.niveauMairieRequis} n'existe pas (${niveauxMairie} niveaux) : ce niveau ne s'achèterait jamais`);
      }
      // Rien ne s'obtient pour rien, sauf le premier niveau de la mairie, posé avec la première zone.
      if (n?.prixLingots === 0 && !(b?.id === "mairie" && k === 0)) {
        signaler(fichier, `${chemin}.niveaux[${k}].prixLingots`, "un niveau gratuit n'a pas de sens : seul le niveau 1 de la mairie, posé avec la première zone, l'est");
      }
    });
  });

  // Les prix des zones sont dans economie.json : une zone, un prix. L'île fait ce que iles.json dit.
  const economie = lireVoisin(dossier, "economie.json");
  const prix = liste(economie?.ville?.zonesPrixLingots);
  if (economie && prix.length !== zones.length) {
    signaler(fichier, "$.zones.ordre", `${zones.length} zones ici, mais ${prix.length} prix dans economie.json (ville.zonesPrixLingots) : une zone, un prix`);
  }
  const iles = lireVoisin(dossier, "iles.json");
  const demiCote = iles?.anneau?.ilePrincipaleDemiCote;
  if (Number.isInteger(demiCote) && [colonnes, taille, blocsParChunk].every(Number.isInteger) && colonnes * taille * blocsParChunk !== 2 * demiCote) {
    signaler(fichier, "$.zones", `la ville fait ${colonnes * taille * blocsParChunk} blocs de côté, mais l'île principale de iles.json en fait ${2 * demiCote}`);
  }
}

function controlesVote(fichier, d) {
  doublons(fichier, "$.sites", liste(d.sites), "le site");
}

const CONTROLES = {
  usines: controlesUsines, economie: controlesEconomie, boutique: controlesBoutique, missions: controlesMissions,
  personnel: controlesPersonnel, incidents: controlesIncidents, ville: controlesVille, vote: controlesVote,
};

// ---------------------------------------------------------------- programme

function main() {
  const options = lireArguments(process.argv.slice(2));
  const dossier = resolve(options.dossier ?? join(racine, "definitions"));
  if (!existsSync(dossier) || !statSync(dossier).isDirectory()) {
    console.error(`${nomAffiche(dossier)}: $: dossier de définitions introuvable`);
    process.exit(1);
  }
  const parDefaut = join(dossier, "schemas");
  const dossierSchemas = resolve(options.schemas ?? (existsSync(parDefaut) ? parDefaut : join(racine, "definitions", "schemas")));

  const ajv = new Ajv2020({ allErrors: true, strict: true, strictRequired: false, verbose: true });
  addFormats(ajv);

  const fichiers = readdirSync(dossier).filter((f) => f.endsWith(".json")).sort();
  // Sur le dépôt lui-même, chaque schéma doit avoir son fichier de données ; un dossier de test
  // n'en contient qu'un, donc l'absence n'y compte pas.
  const attendus = options.dossier === null && existsSync(dossierSchemas)
    ? readdirSync(dossierSchemas).filter((f) => f.endsWith(".schema.json")).map((f) => `${f.slice(0, -".schema.json".length)}.json`)
    : [];
  for (const attendu of attendus) {
    if (!fichiers.includes(attendu)) {
      signaler(nomAffiche(join(dossier, attendu)), "$", "fichier de définitions absent alors que son schéma existe");
    }
  }

  for (const fichier of fichiers) {
    const nom = fichier.slice(0, -".json".length);
    const affiche = nomAffiche(join(dossier, fichier));
    const fichierSchema = join(dossierSchemas, `${nom}.schema.json`);
    if (!existsSync(fichierSchema)) {
      signaler(affiche, "$", `aucun schéma ${nomAffiche(fichierSchema)} pour ce fichier`);
      continue;
    }
    let donnees;
    try {
      donnees = JSON.parse(readFileSync(join(dossier, fichier), "utf8").replace(/^﻿/, ""));
    } catch (e) {
      signaler(affiche, "$", `JSON invalide : ${e.message}`);
      continue;
    }
    let validateur;
    try {
      validateur = ajv.compile(JSON.parse(readFileSync(fichierSchema, "utf8")));
    } catch (e) {
      signaler(nomAffiche(fichierSchema), "$", `schéma inutilisable : ${e.message}`);
      continue;
    }
    if (!validateur(donnees)) {
      for (const err of sansBranches(validateur.errors ?? [])) {
        const { segments, message } = traduire(err, donnees);
        signaler(affiche, cheminJson(segments), message);
      }
    }
    if (donnees !== null && typeof donnees === "object" && CONTROLES[nom]) {
      CONTROLES[nom](affiche, donnees, dossier);
    }
  }

  if (erreurs.length > 0) {
    console.error(erreurs.join("\n"));
    console.error(`\n${erreurs.length} erreur(s) dans les définitions.`);
    process.exit(1);
  }
  console.log(`Définitions valides : ${fichiers.length} fichier(s) vérifié(s) contre leur schéma.`);
}

try {
  main();
} catch (e) {
  console.error(`verifier_definitions: ${e.message}`);
  process.exit(1);
}
