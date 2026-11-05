// Tous les textes que les joueurs lisent dans les écrans de l'add-on, et seulement eux. Français, tutoiement,
// phrases courtes (design/README.md, « Les mots » et « Écrire sans slop »). Les chiffres arrivent déjà mis en
// forme par nombres.ts : aucun texte ne calcule quoi que ce soit.
//
// TOUT CE FICHIER EST « À VALIDER PAR KR ». Il est commité, jamais présenté comme définitif. La liste, avec l'avant
// (rien : ces écrans sont nouveaux) et l'après, est dans addon/README.md, section « Textes à valider » ; un test
// vérifie que chaque morceau de texte d'ici s'y trouve.
//
// tools/anti_ia.py lit les chaînes de ce fichier comme celles de tout addon/src/**/*.ts.

import type { CityBuildingId, FactoryId, IncidentKindId, MissionId } from "../generated/definitions";
import type { BuyAmount, CityBonusType, MemberRole, RefusalReason, StaffRarity, StaffRole } from "../protocol";
import { COLOR, EXPRESSION_MARK, type Expression } from "./apparence";
import { formatAmount, formatCount } from "./nombres";
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

/** Les identifiants des usines dans l'ordre du jeu (celui de definitions/usines.json, vérifié par un test) : l'ordre de la carte. */
export const FACTORY_IDS: readonly string[] = Object.keys(FACTORY_NAMES);

function isKnownFactory(id: string): id is FactoryId {
  return Object.prototype.hasOwnProperty.call(FACTORY_NAMES, id);
}

/** Un identifiant du service devenu un nom lisible : « usine-neuve » → « Usine neuve ». */
function readableName(id: string): string {
  const spaced = id.replaceAll("-", " ").trim();
  return spaced === "" ? id : `${spaced.charAt(0).toUpperCase()}${spaced.slice(1)}`;
}

/** Le nom d'une usine. Un identifiant que cet add-on ne connaît pas encore devient un nom lisible : « usine-neuve » → « Usine neuve ». */
export function factoryName(id: string): string {
  if (isKnownFactory(id)) return FACTORY_NAMES[id];
  return readableName(id);
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
  /** Le prestige est fait. Fier : c'est l'expression du prestige (docs/design.md). */
  prestige: say("fier", "Prestige accompli, patron !"),
  /** Une ligne de la carte que le joueur n'a pas encore. */
  usinePasEncore: say("oups", "Tu n'as pas encore cette usine."),
  /** Le service n'a pas donné d'origine d'archipel, ou l'île n'est pas dans iles.json : on ne sait pas où aller. */
  ileIntrouvable: say("oups", "Je ne trouve pas cette île."),
  /** L'île est en chantier (elle se redessine après un prestige) et n'est pas prête dans le délai. */
  ilePasPrete: say("oups", "L'île n'est pas encore prête."),
  /** La fin d'une mission dont cet add-on n'a pas les textes. */
  missionInconnue: say("joie", "Bien joué, patron !"),
  /** Un incident vient d'ouvrir dans une usine de l'entreprise. `panique` est l'expression de l'incident à réparer (docs/design.md). */
  incident: say("panique", "Vite, une usine a un souci."),
  /** Le service a clos l'incident que le joueur vient de réparer : l'usine reprend son rythme. */
  usineRepart: say("joie", "L'usine repart !"),
  /** L'événement du service dit que le joueur lui-même a réparé l'usine. */
  repareeParToi: say("joie", "Bien joué, l'usine repart !"),
  /** L'événement du service dit qu'un associé a réparé l'usine. */
  repareeParUnAssocie: say("joie", "Un associé a tout réparé."),
  /** Un candidat de l'agence est embauché. */
  recrue: say("joie", "Nouvelle recrue embauchée !"),
  /** Un membre du personnel a changé d'usine. */
  affectation: say("joie", "Affectation faite."),
  /** Un membre du personnel est renvoyé. */
  renvoi: say("oups", "Membre renvoyé."),
  /** Le service refuse d'affecter ou d'embaucher : plus de poste libre pour ce rôle dans l'usine (EMPLACEMENT_OCCUPE). */
  postesPleins: say("oups", "Plus de poste libre ici."),
  /** Le service refuse d'embaucher un candidat déjà embauché (DEJA_POSSEDE). */
  dejaEmbauche: say("fier", "Déjà embauché."),
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
  SEUIL_NON_ATTEINT: (amount) => missing("Donar", amount, "Le seuil n'est pas atteint."),
  USINE_VERROUILLEE: () => say("oups", "Cette usine est verrouillée."),
  ZONE_VERROUILLEE: () => say("oups", "Cette zone est verrouillée."),
  EMPLACEMENT_OCCUPE: () => say("oups", "Cet emplacement est occupé."),
  ENTREPRISE_PLEINE: () => say("oups", "Ton entreprise est pleine."),
  // À valider par Kr : le refus d'une invitation ou de son acceptation quand l'entreprise de l'invité a déjà commencé.
  ENTREPRISE_EN_COURS: () => say("oups", "Tu as déjà lancé ton entreprise."),
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

/**
 * La bulle d'un refus du service pour une action sur le personnel (embaucher, affecter, renvoyer). Deux codes y disent
 * autre chose que dans le reste du jeu : un emplacement occupé est un poste que l'usine n'a plus, une chose déjà
 * possédée est un candidat déjà embauché. Les autres codes gardent la bulle de `refusalBubble`.
 */
export function staffRefusalBubble(reason: string, amount?: number): Bubble {
  if (reason === "EMPLACEMENT_OCCUPE") return BULLES.postesPleins;
  if (reason === "DEJA_POSSEDE") return BULLES.dejaEmbauche;
  return refusalBubble(reason, amount);
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
  prestige: "Prestige",
  objectifs: "Objectifs",
  personnel: "Personnel",
  /** Le mot d'une tuile verrouillée. */
  bientot: "Bientôt",
  retour: "Retour",
  revenu: (rate: string): string => `Revenu ${rate}`,
  nombreUsines: (count: number): string => (count === 1 ? "1 usine" : `${count} usines`),
  nombreIles: (count: number): string => (count === 1 ? "1 île" : `${count} îles`),
  /** La ligne d'info de la tuile Personnel, et des tuiles d'usine quand on choisit où affecter quelqu'un. */
  nombreMembres: (count: number): string => {
    if (count === 0) return "Aucun membre";
    if (count === 1) return "1 membre";
    return `${count} membres`;
  },
  /** La tuile qui mène aux associés (ASSOCIES_TEXTES). */
  associes: "Associés",
  /** La tuile qui mène à la ville (VILLE_TEXTES). */
  ville: "Ville",
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
    INCIDENT: "Un incident ralentit sa production.",
    SANS_COURANT: "Elle est à l'arrêt, sans courant.",
  } satisfies Record<FactoryStatus, string>,
  niveau: (level: string): string => `Niveau ${level}`,
  revenu: (rate: string): string => `Revenu ${rate}`,
  palier: (level: string): string => `Palier ×2 au niveau ${level}`,
  cout: (amount: string): string => `Coût ${amount} Donar`,
  etape: (step: number, total: number, name: string): string => `Étape ${step} sur ${total} : ${name}`,
  /** L'équipe de l'usine, d'après la liste du personnel du service : combien de membres, et s'il y a un chef. */
  equipe: (membres: string, avecChef: boolean): string => `Équipe : ${membres}, ${avecChef ? "avec chef" : "sans chef"}`,
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

// ---------------------------------------------------------------- les incidents et leur réparation

/** Ce que les joueurs lisent d'une sorte d'incident : son nom (tuile, carte, fiche, message) et la phrase de la fiche. */
export interface IncidentSorteTexts {
  readonly nom: string;
  /** Une phrase de dix mots au plus qui dit ce qui se passe. Elle ne chiffre pas le ralentissement : c'est le service qui le décide. */
  readonly phrase: string;
}

/**
 * Les textes de chaque sorte de definitions/incidents.json, par identifiant. Le service ne rend que l'identifiant de la
 * sorte (Factory.incidentKind) : les mots sont ici. TypeScript refuse une sorte sans textes ou des textes sans sorte, et
 * un test les compare au fichier de données. Aucun chiffre du jeu ici : ni facteur de ralentissement, ni récompense.
 */
export const INCIDENT_TEXTES = {
  /** Une sorte que cet add-on ne connaît pas encore, ou que le service n'a pas donnée : le mot général. */
  inconnue: { nom: USINES_TEXTES.tuile.incident, phrase: FICHE_TEXTES.phrase.INCIDENT },
  sortes: {
    incendie: { nom: "Début d'incendie", phrase: "Le feu a pris, la production ralentit." },
    panne: { nom: "Machine en panne", phrase: "Une machine est en panne, la production ralentit." },
    courroie: { nom: "Courroie cassée", phrase: "Une courroie a cassé, la production ralentit." },
  } satisfies Record<IncidentKindId, IncidentSorteTexts>,
  /** Le bouton de la fiche qui ouvre le mini-jeu de réparation. */
  reparer: "Réparer",
  /** Une usine arrêtée faute de courant : la dernière ligne de la fiche dit quoi faire, et il n'y a aucun bouton. */
  sansCourant: "Il faut une Centrale en marche.",
  /** Le message du tchat quand un incident s'ouvre : l'usine et la sorte, puis où réparer. */
  ouverture: {
    usine: (usine: string, sorte: string): string => `${usine} : ${sorte}`,
    conseil: "Répare-la depuis le menu, dans Usines.",
  },
  /** Le message du tchat quand un incident est réparé : l'usine, puis la récompense telle que le service la donne. */
  reparation: {
    faite: (usine: string): string => `${usine} : réparation faite`,
    recompense: (donar: string): string => `Récompense ${donar} Donar`,
    aucune: "Pas de récompense cette fois.",
    dejaFaite: "Pas de récompense, un associé avait déjà réparé.",
  },
} as const;

function isIncidentKind(kind: string | null | undefined): kind is IncidentKindId {
  return kind !== null && kind !== undefined && Object.prototype.hasOwnProperty.call(INCIDENT_TEXTES.sortes, kind);
}

/** Les sortes qui ont des textes : pour le test qui les compare à definitions/incidents.json. */
export const INCIDENT_TEXTES_KINDS: readonly string[] = Object.keys(INCIDENT_TEXTES.sortes);

/** Les textes d'une sorte d'incident. Une sorte inconnue, ou absente (null), prend le mot général « Incident ». */
export function incidentTexts(kind: string | null | undefined): IncidentSorteTexts {
  return isIncidentKind(kind) ? INCIDENT_TEXTES.sortes[kind] : INCIDENT_TEXTES.inconnue;
}

/** Une étape du mini-jeu de réparation : ce que le joueur lit, ses boutons, et l'indice d'Oooscar quand il se trompe. */
export interface RepairStepTexts {
  /** Une consigne d'une ligne : le fait, puis l'ordre. */
  readonly consigne: string;
  /** Les boutons de l'étape, dans l'ordre où on les montre : deux ou trois, un seul est le bon. */
  readonly choix: readonly string[];
  /**
   * L'indice du bon bouton dans `choix`. Ce n'est pas un texte, mais il change dès qu'on réordonne les boutons : il vit
   * donc à côté d'eux. Il varie d'une étape à l'autre pour que le bon bouton ne soit pas toujours le même.
   */
  readonly bonne: number;
  /** Ce que dit Oooscar quand le joueur se trompe : un indice, six mots au plus. L'étape se rejoue, rien n'est perdu. */
  readonly erreur: Bubble;
}

/**
 * Le mini-jeu de réparation. Trois étapes par sorte, toujours dans le même ordre : le geste de sécurité, l'outil ou la
 * pièce, le geste final. Le nombre d'étapes et leur ordre sont des choix de l'add-on, pas un chiffre d'économie : le
 * service ne juge pas le mini-jeu. À valider par Kr, comme les phrases.
 */
export const REPARATION_TEXTES = {
  titre: "RÉPARATION",
  etape: (step: number, total: number): string => `Étape ${step} sur ${total}`,
  retour: "Retour",
  sortes: {
    incendie: [
      {
        consigne: "Le feu a pris. Choisis le premier geste.",
        choix: ["Jeter un seau d'eau", "Couper l'arrivée", "Ouvrir les fenêtres"],
        bonne: 1,
        erreur: say("oups", "Coupe d'abord ce qui l'alimente."),
      },
      {
        consigne: "Le feu s'étend. Prends le bon outil.",
        choix: ["Un seau d'eau", "Une pelle", "L'extincteur"],
        bonne: 2,
        erreur: say("oups", "Un extincteur éteint plus vite."),
      },
      {
        consigne: "Vise bien avant d'appuyer.",
        choix: ["La base des flammes", "Le haut des flammes", "La fumée"],
        bonne: 0,
        erreur: say("oups", "Vise plus bas, à la base."),
      },
    ],
    panne: [
      {
        consigne: "La machine s'est arrêtée. Choisis le premier geste.",
        choix: ["La relancer d'un coup", "Couper le courant", "Taper dessus"],
        bonne: 1,
        erreur: say("oups", "Coupe le courant avant d'y toucher."),
      },
      {
        consigne: "Le capot est ouvert. Repère la cause.",
        choix: ["Un engrenage grippé", "Une vitre propre", "Un câble tout neuf"],
        bonne: 0,
        erreur: say("oups", "Cherche ce qui est bloqué."),
      },
      {
        consigne: "Remets la machine en route.",
        choix: ["La relancer sans rien faire", "La débrancher pour de bon", "Graisser, puis la relancer"],
        bonne: 2,
        erreur: say("oups", "Graisse avant de relancer."),
      },
    ],
    courroie: [
      {
        consigne: "La courroie a lâché. Choisis le premier geste.",
        choix: ["Arrêter la machine", "Tirer sur la courroie", "Accélérer la machine"],
        bonne: 0,
        erreur: say("oups", "Arrête d'abord la machine."),
      },
      {
        consigne: "Prends la bonne pièce.",
        choix: ["Une corde à linge", "Une ficelle", "Une courroie neuve"],
        bonne: 2,
        erreur: say("oups", "Il faut une courroie neuve."),
      },
      {
        consigne: "Pose la courroie, puis règle-la.",
        choix: ["Serrée à fond", "Tendue sans forcer", "Très lâche"],
        bonne: 1,
        erreur: say("oups", "Ni trop lâche, ni trop serrée."),
      },
    ],
  } satisfies Record<IncidentKindId, readonly RepairStepTexts[]>,
  /** Le mini-jeu d'une sorte que cet add-on ne connaît pas encore, ou que le service n'a pas donnée. */
  generique: [
    {
      consigne: "Une usine a un souci. Choisis le premier geste.",
      choix: ["Courir dans tous les sens", "Couper l'alimentation", "Attendre que ça passe"],
      bonne: 1,
      erreur: say("oups", "Mets l'usine en sécurité d'abord."),
    },
    {
      consigne: "L'usine ralentit. Cherche la cause.",
      choix: ["Inspecter la machine", "Repeindre le mur", "Compter le stock"],
      bonne: 0,
      erreur: say("oups", "Cherche d'abord ce qui cloche."),
    },
    {
      consigne: "Remets l'usine en route.",
      choix: ["Tout débrancher", "Relancer sans réparer", "Réparer, puis relancer"],
      bonne: 2,
      erreur: say("oups", "Répare avant de relancer."),
    },
  ] satisfies readonly RepairStepTexts[],
} as const;

/** Les étapes du mini-jeu d'une sorte d'incident. Une sorte inconnue, ou absente, joue le mini-jeu général. */
export function repairSteps(kind: string | null | undefined): readonly RepairStepTexts[] {
  return isIncidentKind(kind) ? REPARATION_TEXTES.sortes[kind] : REPARATION_TEXTES.generique;
}

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

// ---------------------------------------------------------------- le personnel

export const PERSONNEL_TEXTES = {
  titre: "PERSONNEL",
  phrase: "Ton équipe, usine par usine.",
  vide: "Personne dans ton équipe pour l'instant.",
  /** Le nom du groupe des membres qui n'ont pas d'usine. */
  sansUsine: "Sans usine",
  /** Les rôles du contrat (Staff.role), tels que le joueur les lit. */
  roles: {
    SALARIE: "Salarié",
    CHEF: "Chef",
    DIRECTEUR: "Directeur",
  } satisfies Record<StaffRole, string>,
  /** Les raretés du contrat (Staff.rarity). Le nom de la troisième est à confirmer par Kr : la charte bannit le mot du game design. */
  raretes: {
    COMMUN: "Commun",
    RARE: "Rare",
    EPIQUE: "Exceptionnel",
  } satisfies Record<StaffRarity, string>,
  /** Rareté et rôle, en tête de la fiche d'un membre ou d'un candidat. */
  sorte: (rarete: string, role: string): string => `${rarete} · ${role}`,
  /** La ligne d'info d'un membre ou d'un candidat : rareté, rôle, bonus de production du service. */
  ligne: (rarete: string, role: string, bonus: string): string => `${rarete} · ${role} · +${bonus} %`,
  retour: "Retour",
  /** La tuile qui mène à l'agence. */
  agence: "Agence",
  agenceDetail: "Les candidats du jour",
  /** L'équipe d'une usine. */
  equipe: {
    titre: "ÉQUIPE",
  },
  /** La fiche d'un membre. */
  membre: {
    titre: "MEMBRE",
    bonus: (percent: string): string => `Bonus +${percent} % de production`,
    salaire: (amount: string): string => `Salaire ${amount} Donar/h`,
    apparence: (skin: string): string => `Apparence ${skin}`,
    usine: (name: string): string => `Usine ${name}`,
    sansUsine: "Pas d'usine pour l'instant",
    affecter: "Affecter à une usine",
    renvoyer: "Renvoyer",
    retour: "Retour",
  },
  /** Le choix de l'usine d'un membre. */
  affecter: {
    titre: "AFFECTATION",
    phrase: "Choisis son usine.",
    aucune: "Aucune autre usine.",
    retour: "Retour",
  },
  /** La confirmation d'un renvoi. */
  renvoi: {
    titre: "RENVOI",
    phrase: "Cette personne quitte ton entreprise.",
    renvoyer: "Renvoyer",
    garder: "Garder",
  },
} as const;

export const AGENCE_TEXTES = {
  titre: "AGENCE",
  phrase: "Choisis qui rejoint ton équipe.",
  vide: "Aucun candidat aujourd'hui.",
  /** La marque d'un candidat que l'entreprise a déjà embauché. */
  dejaEmbauche: "Déjà embauché",
  retour: "Retour",
  /** La fiche d'un candidat. */
  candidat: {
    titre: "CANDIDAT",
    cout: (amount: string): string => `Embauche ${amount} Donar`,
    salaire: (percent: string): string => `Salaire ${percent} % du revenu de l'usine`,
    embaucher: "Embaucher",
    retour: "Retour",
  },
  /** Le choix de l'usine d'un candidat qu'on embauche. */
  embauche: {
    titre: "EMBAUCHE",
    phrase: "Choisis son usine, ou aucune.",
    sansUsine: "Sans usine",
    sansUsineDetail: "Rejoint l'équipe sans poste",
    retour: "Retour",
  },
} as const;

/** Les noms des époques, par numéro d'époque (docs/glossaire.md) : l'indice 0 est l'époque 1. */
const EPOQUES = ["Artisanale", "Vapeur", "Moderne", "Futuriste"] as const;

/** Le nom d'une époque, ou une chaîne vide pour une époque que cet add-on ne connaît pas encore. */
export function eraName(era: number): string {
  return EPOQUES[era - 1] ?? "";
}

export const PRESTIGE_TEXTES = {
  titre: "PRESTIGE",
  /** La ligne d'info de la tuile du menu : le numéro de la course, tel que le service le donne. */
  tuile: (course: string): string => `Course ${course}`,
  /** L'époque d'une course : « Époque 2 : Vapeur ». Sans nom connu, le numéro seul. */
  epoque: (era: string, name: string): string => (name === "" ? `Époque ${era}` : `Époque ${era} : ${name}`),
  /** Où en est l'entreprise : sa course et son époque. */
  course: (course: string, epoque: string): string => `Course ${course} · ${epoque}`,
  /** Ce que donnera la course suivante, d'après le champ « next » de l'aperçu. */
  suivante: {
    epoque: (era: string, name: string): string =>
      name === "" ? `Course suivante : époque ${era}` : `Course suivante : époque ${era}, ${name}`,
    etoile: "Course suivante : une étoile de plus",
  },
  /** L'écran d'avant : l'aperçu du service, puis ce qui repart et ce qui reste. */
  avant: {
    principe: "Tu revends tes usines et tu reçois des Lingots.",
    pret: "Le seuil est atteint.",
    pasEncore: "Le seuil n'est pas atteint.",
    seuil: (donar: string): string => `Seuil ${donar} Donar`,
    total: (lingots: string): string => `Lingots en tout ${lingots}`,
    taPart: (lingots: string): string => `Ta part ${lingots} Lingots`,
    unAssocie: (lingots: string): string => `Un associé ${lingots} Lingots`,
    doubler: (lingots: string): string => `Avec deux fois plus de Donar : ${lingots} Lingots`,
    repartTitre: "REPART À ZÉRO",
    repart: ["Ton Donar et le niveau de tes usines.", "La Carrière redémarre, les autres usines retombent en ruine."],
    resteTitre: "RESTE",
    reste: "Tes Lingots, tes Gemmes, ton entreprise et ses associés.",
    confirmer: "Prestige !",
    retour: "Retour",
  },
  /** L'écran d'après : l'état rendu par le service une fois le prestige fait. */
  apres: {
    etoiles: (stars: string): string => `Étoiles ${stars}`,
    lingots: (amount: string): string => `Tu as ${amount} Lingots`,
    suite: "La Carrière tourne. Remets les autres usines en état.",
    usines: "Voir les usines",
    retour: "Retour",
  },
} as const;

export const CARTE_TEXTES = {
  titre: "CARTE",
  phrase: "Choisis une île pour t'y rendre.",
  ilePrincipale: "Île principale",
  /** La ligne d'info de l'île principale tant que l'entreprise n'a pas sa première zone : ce qui la débloque. */
  ilePrincipaleVerrouillee: "Achète la première zone.",
  /** La ligne d'une usine que l'entreprise n'a pas encore : son nom reste caché. */
  inconnue: "?????",
  /** La ligne d'info d'une usine de l'entreprise, d'après son état dans le service. */
  etat: {
    entiere: (level: string): string => `Entière · niv. ${level}`,
    enRuine: "En ruine",
    enTravaux: (step: number, total: number): string => `En travaux ${step}/${total}`,
    /** Une usine active avec un incident : la sorte, puis le niveau. */
    incident: (sorte: string, level: string): string => `${sorte} · niv. ${level}`,
    sansCourant: (level: string): string => `Sans courant · niv. ${level}`,
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

// ---------------------------------------------------------------- les associés

/**
 * L'entreprise partagée : la liste des associés, l'invitation, les parts. Les parts, les noms et les rôles arrivent du
 * service tels quels. Aucun maximum d'associés n'est écrit ici : c'est le service qui refuse une entreprise pleine.
 * Les bulles de refus ont leur choix de mots dans ui/associes.ts (associesRefusalBubble), selon l'action.
 */
export const ASSOCIES_TEXTES = {
  titre: "ASSOCIÉS",
  /** Les rôles du contrat (Member.role), tels que le joueur les lit. */
  roles: {
    PDG: "PDG",
    ASSOCIE: "Associé",
  } satisfies Record<MemberRole, string>,
  /** Un membre dans la liste : rôle, nom, part en pourcent telle que le service la donne. */
  ligne: (role: string, nom: string, part: string): string => `${role} · ${nom} · ${part} %`,
  /** Marque la ligne du joueur lui-même. */
  toi: (ligne: string): string => `${ligne} (toi)`,
  /** Le joueur est seul : il peut inviter jusqu'à ce que le service refuse. */
  seul: "Tu es seul dans l'entreprise. Invite des joueurs, jusqu'à ce que le service refuse.",
  inviter: "Inviter",
  parts: "Parts",
  retour: "Retour",
  /** La ligne d'info de la tuile du menu. */
  tuile: {
    seul: "Invite des joueurs",
    nombre: (count: number): string => `${count} associés`,
  },
  /** Une invitation reçue : sa ligne dans la liste, sa fenêtre, et la ligne envoyée dans le tchat. */
  invitation: {
    titre: "INVITATION",
    texte: (nom: string, entreprise: string): string => `${nom} t'invite dans ${entreprise}`,
    consequence: "Tu quittes ton entreprise actuelle. Le PDG fixera ta part.",
    accepter: "Accepter",
    plusTard: "Plus tard",
  },
  /** Le choix du joueur à inviter. */
  choix: {
    titre: "INVITER",
    phrase: "Choisis un joueur connecté.",
    aucun: "Aucun autre joueur connecté.",
    retour: "Retour",
  },
  /** Le formulaire des parts : un champ par membre. */
  formulaire: {
    titre: "PARTS",
    champ: (nom: string): string => `Part de ${nom}, en %`,
    indice: "Un nombre entier",
    envoyer: "Enregistrer",
  },
  bulles: {
    /** Le service a pris l'invitation. */
    invitationEnvoyee: say("joie", "Invitation envoyée."),
    /** Le service a fixé les parts. */
    partsEnregistrees: say("joie", "Parts enregistrées."),
    /** Le joueur est entré dans l'entreprise qui l'invitait. */
    bienvenue: say("joie", "Bienvenue dans l'entreprise."),
    /** Une invitation arrive (tchat). */
    invitationRecue: say("joie", "Tu as une invitation."),
    /** Les parts ont changé (tchat, annonce du service). */
    partsChangees: say("joie", "Les parts ont changé."),
    /** Un champ du formulaire n'est pas un pourcentage entier : rien n'est envoyé. */
    partsInvalides: say("oups", "Écris un pourcentage entier."),
    /** Le service refuse les parts (400) : leur total n'est pas celui qu'il attend. */
    partsTotal: say("oups", "Les parts doivent faire 100."),
    /** Le service ne trouve plus l'invitation (400) : elle a expiré, ou une autre l'a remplacée. */
    invitationExpiree: say("oups", "Cette invitation a expiré."),
    /** Le service ne connaît pas le joueur invité (400). */
    joueurInconnu: say("oups", "Je ne connais pas ce joueur."),
    /** Inviter un joueur qui est déjà dans l'entreprise (DEJA_POSSEDE). */
    dejaAssocie: say("oups", "Il est déjà associé."),
    /** Accepter l'invitation de l'entreprise où l'on est déjà (DEJA_POSSEDE). */
    tuEsDejaAssocie: say("oups", "Tu es déjà associé ici."),
    /** Inviter un joueur qui a déjà lancé sa propre entreprise (ENTREPRISE_EN_COURS). */
    ilALance: say("oups", "Il a déjà lancé son entreprise."),
    /** Accepter l'invitation d'une entreprise qui s'est remplie depuis (ENTREPRISE_PLEINE). */
    entreprisePleine: say("oups", "Cette entreprise est pleine."),
  },
} as const;

// ---------------------------------------------------------------- la ville

/**
 * Les noms des bâtiments du catalogue de definitions/ville.json (clé « id »). Les types générés ne gardent que les
 * identifiants : cette table les recopie, et un test la compare au fichier de données pour qu'elle ne dérive pas.
 */
export const BATIMENT_NOMS: Readonly<Record<CityBuildingId, string>> = {
  mairie: "Mairie",
  route: "Route",
  maison: "Maison",
  immeuble: "Immeuble",
  commerce: "Commerce",
  garage: "Garage",
};

/** Les identifiants des bâtiments dans l'ordre du catalogue. */
export const BATIMENT_IDS: readonly string[] = Object.keys(BATIMENT_NOMS);

function isKnownBuilding(id: string): id is CityBuildingId {
  return Object.prototype.hasOwnProperty.call(BATIMENT_NOMS, id);
}

/** Le nom d'un bâtiment. Un type que cet add-on ne connaît pas encore devient un nom lisible, comme une usine. */
export function batimentName(id: string): string {
  return isKnownBuilding(id) ? BATIMENT_NOMS[id] : readableName(id);
}

/** « 2 usines actives » ; « 1 usine active » au singulier. */
function usinesActives(usines: string): string {
  return usines === "1" ? "1 usine active" : `${usines} usines actives`;
}

export const VILLE_TEXTES = {
  titre: "VILLE",
  retour: "Retour",
  /** La ligne d'info de la tuile du menu, et de l'île principale sur la carte. */
  mairie: (niveau: string): string => `Mairie niveau ${niveau}`,
  /** La tuile du menu : fermée tant que le service ne donne pas de ville, ouverte avant la première zone. */
  tuile: {
    verrouillee: (usines: string): string => `Il faut ${usinesActives(usines)}.`,
    premiere: (usines: string): string => `Première zone : ${usinesActives(usines)}`,
  },
  zone: {
    aucune: "Tu n'as pas encore de zone.",
    titre: "Prochaine zone",
    numero: (index: string): string => `Zone ${index}`,
    prix: (prix: string): string => `${prix} Lingots`,
    mairieRequise: (niveau: string): string => `Mairie niveau ${niveau} requise`,
    usinesRequises: (usines: string): string => (usines === "1" ? "1 usine active requise" : `${usines} usines actives requises`),
    acheter: (index: string): string => `Acheter la zone ${index}`,
    toutes: "Toutes les zones sont à toi.",
  },
  /** Ce que les bâtiments donnent ensemble, d'après le service. */
  bonus: {
    titre: "Bonus",
    aucun: "Aucun bonus pour l'instant.",
    logement: (loges: string, places: string): string => `Personnel logé ${loges} sur ${places}`,
    /** Le bonus d'un bâtiment, tel que le service le donne (mis en forme, jamais calculé) : la même ligne sur l'enseigne et dans les écrans. */
    types: {
      LOGEMENTS: (places: number): string => `Logements ${formatCount(places)}`,
      PRODUCTION_DONAR: (pourcent: number): string => `Donar +${formatAmount(pourcent)} %`,
      LIVRAISON_VITESSE: (pourcent: number): string => `Livraison +${formatAmount(pourcent)} %`,
    },
  },
  batiments: {
    bouton: "Bâtiments",
    nombre: (nombre: string): string => (nombre === "1" ? "1 bâtiment" : `${nombre} bâtiments`),
    vide: "Aucun bâtiment pour l'instant.",
    ligne: (nom: string, niveau: string): string => `${nom} niveau ${niveau}`,
  },
  fiche: {
    niveau: (niveau: string, maximum: string): string => `Niveau ${niveau} sur ${maximum}`,
    suivant: (prix: string): string => `Niveau suivant : ${prix} Lingots`,
    maximum: "Niveau maximum.",
    ameliorer: "Améliorer",
  },
  /** L'enseigne d'un bâtiment sur l'île principale : son nom, son niveau, puis son bonus. */
  enseigne: {
    niveau: (niveau: string): string => `Niveau ${niveau}`,
  },
  construire: {
    titre: "CONSTRUIRE",
    bouton: "Construire",
    phrase: "Choisis un bâtiment à poser.",
    detail: (prix: string, bonus: string): string => `${prix} Lingots · ${bonus}`,
    detailSansBonus: (prix: string): string => `${prix} Lingots`,
  },
  poser: {
    titre: "POSER",
    parcelle: (x: string, z: string): string => `Parcelle ${x}, ${z}`,
    ailleurs: "Place-toi sur l'île principale.",
    inconnue: "Je ne sais pas où tu te trouves.",
    prix: (prix: string): string => `Prix ${prix} Lingots`,
    /** Où est la porte d'un bâtiment posé avec cette rotation. */
    porte: {
      0: "Porte au sud",
      90: "Porte à l'ouest",
      180: "Porte au nord",
      270: "Porte à l'est",
    },
    /** Le sens d'une route posée avec cette rotation. */
    sens: {
      0: "Sens nord-sud",
      90: "Sens est-ouest",
      180: "Sens nord-sud",
      270: "Sens est-ouest",
    },
    poser: "Poser ici",
    tourner: "Tourner",
  },
  bulles: {
    zoneAchetee: say("joie", "Nouvelle zone achetée !"),
    batimentPose: say("joie", "Bâtiment posé."),
    batimentAmeliore: say("joie", "Bâtiment amélioré !"),
    /** Le joueur appuie sur « Poser ici » ailleurs que sur l'île principale. */
    pasSurLIle: say("oups", "Va sur l'île principale."),
    /** La position du joueur n'est pas lisible (monde pas démarré). */
    positionInconnue: say("oups", "Je ne te trouve pas."),
    /** ZONE_VERROUILLEE en achetant la première zone : il manque des usines actives. */
    premiereZone: say("oups", "Il te faut plus d'usines actives."),
    /** ZONE_VERROUILLEE en achetant une autre zone, ou en améliorant un bâtiment : la mairie doit monter. */
    mairieBasse: say("oups", "Monte d'abord la mairie."),
    /** ZONE_VERROUILLEE en posant un bâtiment : la parcelle est dans une zone fermée, ou la mairie est trop basse. */
    parcelleFermee: say("oups", "Zone fermée ou mairie trop basse."),
    /** EMPLACEMENT_OCCUPE en posant un bâtiment. */
    emplacementOccupe: say("oups", "Un bâtiment est déjà là."),
    /** DEJA_POSSEDE en posant un bâtiment : l'entreprise en a déjà autant que le catalogue le permet. */
    maximumAtteint: say("oups", "Maximum atteint pour ce bâtiment."),
    /** DEJA_POSSEDE en achetant une zone que l'entreprise a déjà. */
    zoneDejaPossedee: say("fier", "Cette zone est déjà à toi."),
    /** DEJA_POSSEDE en améliorant un bâtiment déjà à son dernier niveau. */
    niveauMax: say("fier", "Déjà au niveau maximum."),
  },
} as const;

/**
 * Le bonus d'un bâtiment écrit en une ligne (« Logements 2 », « Donar +0,5 % »), ou undefined quand il n'en donne pas
 * (la mairie, une route). La valeur est celle du service, mise en forme : rien n'est calculé.
 */
export function bonusLine(type: CityBonusType | null | undefined, bonus: number | null | undefined): string | undefined {
  if (type === null || type === undefined || bonus === null || bonus === undefined) return undefined;
  return VILLE_TEXTES.bonus.types[type](bonus);
}
