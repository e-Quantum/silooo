// Tous les textes que les joueurs lisent dans les écrans de l'add-on, et seulement eux. Français, tutoiement,
// phrases courtes (design/README.md, « Les mots » et « Écrire sans slop »). Les chiffres arrivent déjà mis en
// forme par nombres.ts : aucun texte ne calcule quoi que ce soit.
//
// TOUT CE FICHIER EST « À VALIDER PAR KR ». Il est commité, jamais présenté comme définitif. La liste, avec l'avant
// (rien : ces écrans sont nouveaux) et l'après, est dans addon/README.md, section « Textes à valider » ; un test
// vérifie que chaque morceau de texte d'ici s'y trouve.
//
// tools/anti_ia.py lit les chaînes de ce fichier comme celles de tout addon/src/**/*.ts.

import type { FactoryId, MissionId } from "../generated/definitions";
import type { BuyAmount, RefusalReason } from "../protocol";
import { COLOR, EXPRESSION_MARK, type Expression } from "./apparence";
import { formatAmount } from "./nombres";
import type { FactoryStatus } from "./usines";

// ---------------------------------------------------------------- noms des usines

/**
 * Les noms des usines. Ils vivent dans definitions/usines.json (clé « nom ») mais les types générés ne gardent que
 * les identifiants : cette table les recopie, et un test la compare au fichier de données pour qu'elle ne dérive pas.
 */
export const FACTORY_NAMES: Readonly<Record<FactoryId, string>> = {
  carriere: "Carrière",
  scierie: "Scierie",
  ferme: "Ferme",
  "mine-profonde": "Mine profonde",
  fonderie: "Fonderie",
  "atelier-outils": "Atelier d'outils",
  briqueterie: "Briqueterie",
  elevage: "Élevage",
  centrale: "Centrale",
  verrerie: "Verrerie",
  menuiserie: "Menuiserie",
  conserverie: "Conserverie",
  "usine-textile": "Usine textile",
  "usine-robots": "Usine de robots",
  cristallerie: "Cristallerie",
};

function isKnownFactory(id: string): id is FactoryId {
  return Object.prototype.hasOwnProperty.call(FACTORY_NAMES, id);
}

/** Le nom d'une usine. Un identifiant que cet add-on ne connaît pas encore devient un nom lisible : « usine-neuve » → « Usine neuve ». */
export function factoryName(id: string): string {
  if (isKnownFactory(id)) return FACTORY_NAMES[id];
  const spaced = id.replaceAll("-", " ").trim();
  return spaced === "" ? id : `${spaced.charAt(0).toUpperCase()}${spaced.slice(1)}`;
}

// ---------------------------------------------------------------- les bulles d'Oooscar

/** Ce que dit Oooscar : six mots au plus, une des cinq expressions. */
export interface Bubble {
  readonly expression: Expression;
  readonly text: string;
}

function say(expression: Expression, text: string): Bubble {
  return { expression, text };
}

/** Le nom de la mascotte, pour la ligne de la bulle. */
const OOOSCAR = "Oooscar";

/** Une bulle écrite sur une ligne : le nom, le repère de l'expression, puis la phrase. */
export function renderBubble(bubble: Bubble): string {
  return `${COLOR.muted}${OOOSCAR} ${EXPRESSION_MARK[bubble.expression]}${COLOR.reset} ${bubble.text}`;
}

export const BULLES = {
  /** Une tuile verrouillée, une route que le service n'a pas encore écrite. */
  bientot: say("oups", "Pas encore disponible."),
  /** Le service ne répond pas : réseau coupé, délai dépassé, ou aucun état reçu. */
  serviceInjoignable: say("panique", "Le service ne répond pas."),
  /** Une erreur technique du service, hors du jeu. */
  problemeService: say("panique", "Problème côté service."),
  /** Un code de refus que cet add-on ne connaît pas encore. */
  refusInconnu: say("oups", "Impossible pour l'instant."),
  collecte: say("joie", "Production encaissée !"),
  collecteHorsLigne: say("dodo", "Gagné pendant ton absence."),
  montee: say("joie", "Usine améliorée !"),
  etape: say("joie", "Étape terminée !"),
  /** La fin d'une mission dont cet add-on n'a pas les textes. */
  missionInconnue: say("joie", "Bien joué, patron !"),
} as const satisfies Record<string, Bubble>;

function missing(what: string, amount: number | undefined, fallback: string): Bubble {
  return say("oups", amount === undefined ? fallback : `Il te manque ${formatAmount(amount)} ${what}.`);
}

/** Une bulle par code `reason` de `Refused` (409). Le tableau est complet : TypeScript refuse un code oublié. */
const REFUSAL_BUBBLES: Readonly<Record<RefusalReason, (amount: number | undefined) => Bubble>> = {
  PAS_ASSEZ_DE_DONAR: (amount) => missing("Donar", amount, "Il te manque du Donar."),
  PAS_ASSEZ_DE_LINGOTS: (amount) => missing("Lingots", amount, "Il te manque des Lingots."),
  PAS_ASSEZ_DE_GEMMES: (amount) => missing("Gemmes", amount, "Il te manque des Gemmes."),
  PAS_LE_PDG: () => say("oups", "Seul le PDG peut le faire."),
  SEUIL_NON_ATTEINT: () => say("oups", "Le seuil n'est pas atteint."),
  USINE_VERROUILLEE: () => say("oups", "Cette usine est verrouillée."),
  ZONE_VERROUILLEE: () => say("oups", "Cette zone est verrouillée."),
  EMPLACEMENT_OCCUPE: () => say("oups", "Cet emplacement est occupé."),
  ENTREPRISE_PLEINE: () => say("oups", "Ton entreprise est pleine."),
  DEJA_POSSEDE: () => say("fier", "Tu l'as déjà."),
  GRADE_PRECEDENT_REQUIS: () => say("oups", "Il faut d'abord le grade précédent."),
  CONFLIT_REESSAYER: () => say("oups", "Ça a bougé. Réessaie."),
};

function isRefusalReason(reason: string): reason is RefusalReason {
  return Object.prototype.hasOwnProperty.call(REFUSAL_BUBBLES, reason);
}

/** La bulle d'un refus du service. `amount` est le `missing` du contrat, quand c'est une somme. */
export function refusalBubble(reason: string, amount?: number): Bubble {
  return isRefusalReason(reason) ? REFUSAL_BUBBLES[reason](amount) : BULLES.refusInconnu;
}

/** Les codes de refus qui ont une bulle : pour le test qui les compare à l'OpenAPI. */
export const REFUSAL_BUBBLE_REASONS: readonly string[] = Object.keys(REFUSAL_BUBBLES);

/** La bulle d'un échec technique : réseau coupé, route pas encore écrite, erreur du service. */
export function failureBubble(failure: "network" | "error" | "unavailable", reason: string): Bubble {
  if (failure === "network" || failure === "unavailable") return BULLES.serviceInjoignable;
  if (reason === "PAS_FAIT") return BULLES.bientot;
  return BULLES.problemeService;
}

// ---------------------------------------------------------------- le HUD

export const HUD_TEXTES = {
  /** La seule ligne du HUD quand le service ne répond pas. */
  horsLigne: "Service hors ligne",
  /** La consigne du haut quand aucune mission n'est en cours : le seuil de prestige que donne le service. */
  consigne: (seuil: string): string => `Prestige à ${seuil} Donar`,
  /** La consigne du haut pendant une mission : sa consigne, puis l'avancement tel que le service le donne. */
  mission: (consigne: string, progress: string, target: string): string => `${consigne} : ${progress}/${target}`,
} as const;

// ---------------------------------------------------------------- les missions

/** Ce que les joueurs lisent d'une mission : son titre, sa consigne (celle du HUD) et la bulle d'Oooscar à sa fin. */
export interface MissionTexts {
  readonly titre: string;
  readonly consigne: string;
  readonly fin: Bubble;
}

/**
 * Les textes de chaque mission de definitions/missions.json, par identifiant. Le service ne rend que l'identifiant,
 * l'avancement et les Lingots (api/silooo-core.openapi.yaml, getMissions) : les mots sont ici. TypeScript refuse un
 * identifiant sans textes ou des textes sans identifiant, et un test les compare au fichier de données.
 *
 * Bienvenue à la mairie : le joueur encaisse une première fois (le service compte un collect qui rapporte du Donar).
 */
export const MISSION_TEXTES: Readonly<Record<MissionId, MissionTexts>> = {
  "bienvenue-mairie": {
    titre: "Bienvenue à la mairie",
    consigne: "Encaisse au Silo",
    fin: say("joie", "Premier encaissement, bien joué !"),
  },
};

function hasMissionTexts(id: string): id is MissionId {
  return Object.prototype.hasOwnProperty.call(MISSION_TEXTES, id);
}

/** Les identifiants qui ont des textes : pour le test qui les compare à definitions/missions.json. */
export const MISSION_TEXTES_IDS: readonly string[] = Object.keys(MISSION_TEXTES);

/** Les textes d'une mission, ou undefined si le service en connaît une que cet add-on ne connaît pas encore. */
export function missionTexts(id: string): MissionTexts | undefined {
  return hasMissionTexts(id) ? MISSION_TEXTES[id] : undefined;
}

/** Le titre d'une mission. Un identifiant sans textes devient un titre lisible : « mission-neuve » → « Mission neuve ». */
export function missionTitle(id: string): string {
  const known = missionTexts(id);
  if (known !== undefined) return known.titre;
  const spaced = id.replaceAll("-", " ").trim();
  return spaced === "" ? id : `${spaced.charAt(0).toUpperCase()}${spaced.slice(1)}`;
}

/** Ce que dit Oooscar quand une mission se termine. Une mission sans textes a une bulle de repli. */
export function missionEndBubble(id: string): Bubble {
  return missionTexts(id)?.fin ?? BULLES.missionInconnue;
}

// ---------------------------------------------------------------- l'objet qui ouvre le menu

export const BOUSSOLE_TEXTES = {
  /** Le nom de l'objet dans l'inventaire. */
  nom: "Menu",
} as const;

// ---------------------------------------------------------------- les écrans

export const MENU_TEXTES = {
  titre: "MENU",
  silo: "Silo",
  usines: "Usines",
  carte: "Carte",
  objectifs: "Objectifs",
  amis: "Amis",
  /** Le mot d'une tuile verrouillée. */
  bientot: "Bientôt",
  retour: "Retour",
  revenu: (rate: string): string => `Revenu ${rate}`,
  nombreUsines: (count: number): string => (count === 1 ? "1 usine" : `${count} usines`),
} as const;

export const USINES_TEXTES = {
  titre: "USINES",
  vide: "Aucune usine pour l'instant.",
  page: (current: number, total: number): string => `Page ${current} sur ${total}`,
  precedent: "Précédent",
  suite: "Suite",
  /** La ligne d'info d'une tuile d'usine, selon sa situation. */
  tuile: {
    active: (level: string, rate: string): string => `Niv. ${level} · ${rate}`,
    enRuine: "En ruine",
    remise: (step: number, total: number): string => `Remise en état ${step}/${total}`,
    incident: "Incident",
    sansCourant: "Sans courant",
  },
} as const;

export const FICHE_TEXTES = {
  /** Le titre de la fenêtre : la catégorie de la fiche. */
  titre: "USINE",
  /** La situation de l'usine, en capitales, au-dessus de son nom (design/composants/Fiche.md). */
  sorte: {
    ACTIVE: "ACTIVE",
    EN_RUINE: "EN RUINE",
    REMISE_EN_ETAT: "REMISE EN ÉTAT",
    INCIDENT: "INCIDENT",
    SANS_COURANT: "SANS COURANT",
  } satisfies Record<FactoryStatus, string>,
  /** Une phrase de dix mots au plus qui dit où en est l'usine. */
  phrase: {
    ACTIVE: "Elle livre sa production au Silo.",
    EN_RUINE: "Elle est en ruine. Remets-la en état.",
    REMISE_EN_ETAT: "Les travaux avancent, étape par étape.",
    INCIDENT: "Un incident bloque sa production.",
    SANS_COURANT: "Elle est à l'arrêt, sans courant.",
  } satisfies Record<FactoryStatus, string>,
  niveau: (level: string): string => `Niveau ${level}`,
  revenu: (rate: string): string => `Revenu ${rate}`,
  palier: (level: string): string => `Palier ×2 au niveau ${level}`,
  cout: (amount: string): string => `Coût ${amount} Donar`,
  etape: (step: number, total: number, name: string): string => `Étape ${step} sur ${total} : ${name}`,
  /** Les boutons d'achat, dans l'ordre du contrat : un verbe, une quantité. */
  monter: {
    "1": "Monter de 1",
    "10": "Monter de 10",
    palier: "Monter au palier",
    max: "Monter au max",
  } satisfies Record<BuyAmount, string>,
  /** Les quatre étapes de remise en état (docs/game-design.md) : le nom de l'étape et le verbe du bouton. */
  etapes: [
    { nom: "Dialogue", bouton: "Parler" },
    { nom: "Tâches", bouton: "Réparer" },
    { nom: "Livraison", bouton: "Livrer" },
    { nom: "Recrutement", bouton: "Recruter" },
  ],
  retour: "Retour",
} as const;

export const OBJECTIFS_TEXTES = {
  titre: "OBJECTIFS",
  /** Au-dessus de la mission en cours, en capitales (design/composants/Fiche.md). */
  enCours: "EN COURS",
  terminees: "TERMINÉES",
  /** L'avancement tel que le service le donne : actions faites, actions à faire. */
  avancement: (progress: string, target: string): string => `Avancement ${progress}/${target}`,
  recompense: (lingots: string): string => `Récompense ${lingots} Lingots`,
  /** Quand le service dit qu'il n'y a plus de mission en cours. */
  plusDeMission: "Plus de mission pour l'instant.",
  aucuneTerminee: "Aucune mission terminée.",
  /** La ligne d'info de la tuile du menu. */
  tuile: {
    enCours: (progress: string, target: string): string => `Mission ${progress}/${target}`,
    plusDeMission: "Plus de mission",
  },
  retour: "Retour",
} as const;

export const SILO_TEXTES = {
  titre: "SILO",
  phrase: "Encaisse pour recevoir ta production.",
  donar: (amount: string): string => `Donar ${amount}`,
  revenu: (rate: string): string => `Revenu ${rate}`,
  multiplicateur: (multiplier: string): string => `Multiplicateur ${multiplier}`,
  encaisse: (amount: string): string => `Encaissé ${amount} Donar`,
  horsLigne: (duration: string): string => `Hors ligne ${duration}`,
  encaisser: "Encaisser",
  retour: "Retour",
} as const;

// ---------------------------------------------------------------- la cinématique d'arrivée

export const CINEMATIQUE_TEXTES = {
  /** Qui parle, en tête de chaque sous-titre (design/composants/Cinematique.md). */
  qui: OOOSCAR,
  /** Une phrase de huit mots au plus par plan, trois secondes à l'écran au moins. Le joueur hérite d'une vieille carrière. */
  plans: [
    "Bienvenue chez toi, patron.",
    "Tu hérites d'une vieille carrière abandonnée.",
    "Les murs tiennent encore, à peu près.",
    "Remettons-la en état, je t'accompagne.",
  ],
  /** La consigne pour passer, dans la bande du bas. */
  passer: "Reste accroupi pour passer.",
} as const;
