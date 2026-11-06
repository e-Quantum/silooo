// Les écrans du menu et le parcours de l'un à l'autre.
//
//   MENU ─┬─ SILO ─────────── Encaisser
//         ├─ USINES ── fiche ─ Monter de 1 / de 10 / au palier / au max   (usine en ruine : l'étape suivante)
//         │                  └ Réparer (usine en incident) : le mini-jeu de reparation.ts, puis resolveIncident
//         ├─ CARTE ────────── une île par usine ; choisir une île de l'entreprise y téléporte (le menu se ferme)
//         ├─ OBJECTIFS ────── la mission en cours et les missions terminées (relues à l'ouverture)
//         ├─ PRESTIGE ─────── l'aperçu du service (relu à l'ouverture) ─ Prestige ! ─ l'écran d'après
//         ├─ PERSONNEL ────── les équipes, un membre à la fois ; AGENCE : les candidats du jour (relus à l'ouverture)
//         │                   (ces écrans sont dans personnel.ts)
//         │   └ ASSOCIÉS ──── les membres de l'entreprise ; Inviter, Parts (le PDG), les invitations reçues
//         │                   (ces écrans sont dans associes.ts ; le menu principal n'a que huit places, Associés est sous Personnel)
//         ├─ BOUTIQUE ─────── les Gemmes du service ; Grades, Pilier, Cosmétiques par catégorie, Boost collectif : un article,
//         │                   Acheter ou Offrir (relus à l'ouverture) ; PASS : la saison, les paliers, Réclamer, Passer premium
//         │                   (ces écrans sont dans boutique.ts et pass.ts ; le rappel du vote du jour dans votes.ts)
//         ├─ VILLE ────────── mairie, bonus, prochaine zone ; Bâtiments, Construire (ces écrans sont dans ville.ts)
//
// Les écrans sont des fonctions pures : une vue de l'entreprise (ce que le service a dit) entre, un écran sort
// (titre, corps, boutons, et ce que chaque bouton fait). Rien n'y est calculé : chaque chiffre montré est un champ
// de la réponse du service, mis en forme par nombres.ts. Le parcours (`createMenu`) montre un écran, lit la réponse
// du joueur, appelle `Actions` quand un bouton en demande une, et revient sur l'écran avec la bulle d'Oooscar.
//
// Deux touches suffisent pour encaisser : Silo, puis Encaisser. Monter une usine en demande trois : Usines, la
// tuile, le bouton.

import { playerXuid } from "../identity";
import { config } from "../config";
import { isRejectedRequest } from "./associes";
import {
  BUY_AMOUNTS,
  PASS_PREMIUM_ITEM_ID,
  RESTORE_STEP_MAX,
  type CompanyState,
  type Factory,
  type MissionState,
  type PrestigePreview,
  type Xuid,
} from "../protocol";
import { ILES, VILLE } from "../monde/definitions";
import type { Pos, Xz } from "../monde/geometrie";
import { parcelAt, type ParcelReading } from "../monde/ville";
import type { TravelOutcome } from "../monde/voyage";
import { describeError, type Clock, type Logger } from "../runtime";
import type { Actions, ActionOutcome, CandidatesOutcome, CatalogOutcome, PassOutcome, PreviewOutcome, VotesOutcome } from "./actions";
import { COLOR, colored, ICON } from "./apparence";
import {
  associesNotice,
  associesScreen,
  invitationScreen,
  inviterScreen,
  membersOf,
  partsForm,
  readShares,
  type OnlinePlayer,
} from "./associes";
import {
  affectsCompany,
  articleParent,
  articleScreen,
  boutiqueNotice,
  boutiqueScreen,
  boutiqueTile,
  offrirScreen,
  pioneerLine,
  purchaseContext,
  rayonScreen,
  shelfParent,
} from "./boutique";
import { counterValue } from "./compteur";
import {
  MAIN,
  STATUS_MARK,
  bodyOf,
  button,
  go,
  screenOf,
  tileDetail,
  type Entry,
  type Notice,
  type Route,
  type Screen,
  type Target,
} from "./ecran";
import type { CompanyView, ViewStore } from "./etat";
import type { ActionFormSpec, FormAnswer, FormPresenter, ModalFormSpec, ModalAnswer } from "./formulaires";
import type { IlesDefinitions, VilleDefinitions } from "../generated/definitions";
import type { Invitations } from "./invitations";
import type { Missions } from "./missions";
import { formatAmount, formatCount, formatDuration, formatMultiplier, formatRate } from "./nombres";
import { passNotice, palierScreen, paliersScreen, passScreen } from "./pass";
import { affecterScreen, agenceScreen, candidatScreen, embaucheScreen, equipeScreen, membreScreen, personnelScreen, renvoiScreen, teamLine } from "./personnel";
import { playRepair } from "./reparation";
import { batimentScreen, batimentsScreen, construireScreen, ilePrincipaleTile, poserScreen, villeNotice, villeScreen, villeTile } from "./ville";
import {
  ASSOCIES_TEXTES,
  BOUTIQUE_TEXTES,
  BULLES,
  CARTE_TEXTES,
  FACTORY_IDS,
  FICHE_TEXTES,
  INCIDENT_TEXTES,
  MENU_TEXTES,
  OBJECTIFS_TEXTES,
  PASS_TEXTES,
  PRESTIGE_TEXTES,
  SILO_TEXTES,
  USINES_TEXTES,
  VILLE_TEXTES,
  eraName,
  factoryName,
  failureBubble,
  incidentTexts,
  missionTexts,
  missionTitle,
  refusalBubble,
  renderBubble,
  staffRefusalBubble,
} from "./textes";
import { canBuyLevels, factoryStatus, nextRestoreStep, type FactoryStatus } from "./usines";

// Le vocabulaire des écrans (routes, cibles, boutons) vit dans ecran.ts ; le menu le redonne à qui l'importait d'ici.
export type { Notice, Route, Screen, Target } from "./ecran";

// ---------------------------------------------------------------- menu principal

/**
 * La tuile Objectifs : la ligne d'info dit où en est la mission en cours, d'après la dernière lecture du service.
 * Tant que rien n'a pu être lu, la tuile n'a pas de ligne d'info ; l'écran, lui, relit à l'ouverture.
 */
function objectifsTile(missions: MissionState | undefined): Entry {
  let detail: string | undefined;
  if (missions !== undefined) {
    detail =
      missions.current === null
        ? OBJECTIFS_TEXTES.tuile.plusDeMission
        : OBJECTIFS_TEXTES.tuile.enCours(formatCount(missions.current.progress), formatCount(missions.current.target));
  }
  return { text: button(`${ICON.objectifs} ${MENU_TEXTES.objectifs}`, detail), target: go({ screen: "objectifs" }) };
}

export function mainScreen(view: CompanyView, missions: MissionState | undefined, notice?: Notice, cosmetics: readonly string[] = []): Screen {
  const tiles: Entry[] = [
    {
      text: button(`${ICON.silo} ${MENU_TEXTES.silo}`, MENU_TEXTES.revenu(formatRate(view.state.ratePerHour))),
      target: go({ screen: "silo" }),
    },
    {
      text: button(`${ICON.usine} ${MENU_TEXTES.usines}`, MENU_TEXTES.nombreUsines(view.state.factories.length)),
      target: go({ screen: "usines", page: 0 }),
    },
    {
      text: button(`${ICON.carte} ${MENU_TEXTES.carte}`, MENU_TEXTES.nombreIles(view.state.factories.length)),
      target: go({ screen: "carte", page: 0 }),
    },
    objectifsTile(missions),
    {
      text: button(`${ICON.prestige} ${MENU_TEXTES.prestige}`, PRESTIGE_TEXTES.tuile(formatCount(view.state.courseNumber))),
      target: go({ screen: "prestige" }),
    },
    {
      text: button(`${ICON.personnel} ${MENU_TEXTES.personnel}`, MENU_TEXTES.nombreMembres((view.state.staff ?? []).length)),
      target: go({ screen: "personnel", page: 0 }),
    },
    boutiqueTile(view),
    villeTile(view),
  ];
  // Sous le nom de l'entreprise : le titre Pionnier, quand `join` dit que le joueur le possède.
  const title = pioneerLine(cosmetics);
  const lines = [`${COLOR.bold}${view.state.name}${COLOR.reset}`, ...(title === undefined ? [] : [title])];
  return screenOf(MENU_TEXTES.titre, bodyOf(notice, lines), tiles);
}

// ---------------------------------------------------------------- les usines

/** La couleur du mot de situation sur la fiche. */
const STATUS_COLOR: Record<FactoryStatus, string> = {
  ACTIVE: COLOR.success,
  EN_RUINE: COLOR.blocked,
  REMISE_EN_ETAT: COLOR.paper,
  INCIDENT: COLOR.incident,
  SANS_COURANT: COLOR.blocked,
};

export function usinesScreen(view: CompanyView, page: number, tilesPerScreen: number, notice?: Notice): Screen {
  const factories = view.state.factories;
  const perPage = Math.max(1, Math.floor(tilesPerScreen));
  const pages = Math.max(1, Math.ceil(factories.length / perPage));
  const current = Math.min(Math.max(0, Math.floor(page)), pages - 1);

  const entries: Entry[] = factories.slice(current * perPage, (current + 1) * perPage).map((factory) => {
    const status = factoryStatus(factory);
    return {
      text: button(`${STATUS_MARK[status]} ${factoryName(factory.factoryId)}`, tileDetail(factory, status)),
      target: go({ screen: "fiche", factoryId: factory.factoryId, page: current }),
    };
  });
  if (current > 0) entries.push({ text: USINES_TEXTES.precedent, target: go({ screen: "usines", page: current - 1 }) });
  if (current < pages - 1) entries.push({ text: USINES_TEXTES.suite, target: go({ screen: "usines", page: current + 1 }) });
  entries.push({ text: MENU_TEXTES.retour, target: go(MAIN) });

  const lines: string[] = [];
  if (factories.length === 0) lines.push(USINES_TEXTES.vide);
  if (pages > 1) lines.push(colored(COLOR.muted, USINES_TEXTES.page(current + 1, pages)));
  return screenOf(USINES_TEXTES.titre, bodyOf(notice, lines), entries);
}

/**
 * La fiche d'une usine, dans l'ordre de design/composants/Fiche.md : situation, nom, phrase, trois infos au plus,
 * puis les actions. Rend undefined si le service ne connaît plus cette usine.
 */
export function ficheScreen(view: CompanyView, factoryId: string, page: number, notice?: Notice): Screen | undefined {
  const factory = view.state.factories.find((candidate) => candidate.factoryId === factoryId);
  if (factory === undefined) return undefined;
  const status = factoryStatus(factory);
  const step = nextRestoreStep(factory);
  // Une usine en incident : la sorte (son nom, sa phrase) vient de la fiche du service ; une sorte inconnue garde le mot « Incident ».
  const incident = status === "INCIDENT" ? incidentTexts(factory.incidentKind) : undefined;
  const incidentId = factory.incidentId;

  const infos: string[] = [];
  if (canBuyLevels(factory)) {
    infos.push(
      FICHE_TEXTES.niveau(formatCount(factory.level)),
      FICHE_TEXTES.revenu(formatRate(factory.ratePerHour)),
      FICHE_TEXTES.palier(formatCount(factory.nextMilestone)),
    );
  } else if (step !== undefined) {
    infos.push(FICHE_TEXTES.etape(step, RESTORE_STEP_MAX, FICHE_TEXTES.etapes[step - 1]?.nom ?? ""));
  }

  const entries: Entry[] = [];
  // Réparer passe avant la montée : c'est l'action qui rend à l'usine son rythme. Sans courant, il n'y a rien à appeler.
  if (incident !== undefined && incidentId !== undefined && incidentId !== null) {
    entries.push({
      text: INCIDENT_TEXTES.reparer,
      target: { kind: "repair", factoryId, incidentId, incidentKind: factory.incidentKind ?? null },
    });
  }
  if (canBuyLevels(factory)) {
    for (const amount of BUY_AMOUNTS) {
      entries.push({
        text: amount === "1" ? button(FICHE_TEXTES.monter[amount], FICHE_TEXTES.cout(formatAmount(factory.nextCost))) : FICHE_TEXTES.monter[amount],
        target: { kind: "buy", factoryId, amount },
      });
    }
  } else if (step !== undefined) {
    entries.push({ text: FICHE_TEXTES.etapes[step - 1]?.bouton ?? "", target: { kind: "restore", factoryId, step } });
  }
  if (factory.state === "ACTIVE") {
    entries.push({ text: SILO_TEXTES.encaisser, target: { kind: "collect" } });
    entries.push({ text: MENU_TEXTES.personnel, target: go({ screen: "equipe", factoryId, page: 0 }) });
  }
  entries.push({ text: FICHE_TEXTES.retour, target: go({ screen: "usines", page }) });

  const lines = [
    colored(STATUS_COLOR[status], incident === undefined ? FICHE_TEXTES.sorte[status] : incident.nom.toUpperCase()),
    `${COLOR.bold}${factoryName(factoryId)}${COLOR.reset}`,
    incident === undefined ? FICHE_TEXTES.phrase[status] : incident.phrase,
    "",
    ...infos,
  ];
  // La ligne d'équipe vient de la liste du personnel du service ; sans personne dans l'usine, elle n'existe pas.
  const team = teamLine(view, factoryId);
  if (team !== undefined) lines.push(team);
  // Faute de courant, la dernière ligne dit quoi faire (design/composants/Fiche.md) : une Centrale en marche.
  if (factory.stoppedNoPower === true && (status === "SANS_COURANT" || status === "INCIDENT")) {
    lines.push(colored(COLOR.blocked, INCIDENT_TEXTES.sansCourant));
  }
  return screenOf(FICHE_TEXTES.titre, bodyOf(notice, lines), entries);
}

// ---------------------------------------------------------------- les objectifs

/**
 * Les objectifs, dans l'ordre de la fiche : la mission en cours (titre, consigne, avancement et récompense tels que
 * le service les donne), puis les missions terminées. Sans lecture du service, Oooscar le dit et rien n'est inventé.
 */
export function objectifsScreen(missions: MissionState | undefined, notice?: Notice): Screen {
  const entries: Entry[] = [{ text: OBJECTIFS_TEXTES.retour, target: go(MAIN) }];
  if (missions === undefined) {
    return screenOf(OBJECTIFS_TEXTES.titre, bodyOf(notice ?? { bubble: BULLES.serviceInjoignable }, []), entries);
  }

  const lines: string[] = [];
  const current = missions.current;
  if (current === null) {
    lines.push(OBJECTIFS_TEXTES.plusDeMission);
  } else {
    lines.push(colored(COLOR.paper, OBJECTIFS_TEXTES.enCours), `${COLOR.bold}${missionTitle(current.id)}${COLOR.reset}`);
    const texts = missionTexts(current.id);
    if (texts !== undefined) lines.push(texts.consigne);
    lines.push(
      OBJECTIFS_TEXTES.avancement(formatCount(current.progress), formatCount(current.target)),
      colored(COLOR.lingots, OBJECTIFS_TEXTES.recompense(formatCount(current.rewardLingots))),
    );
  }
  lines.push("", colored(COLOR.paper, OBJECTIFS_TEXTES.terminees));
  if (missions.completed.length === 0) lines.push(OBJECTIFS_TEXTES.aucuneTerminee);
  for (const id of missions.completed) lines.push(`${colored(COLOR.success, ICON.objectifs)} ${missionTitle(id)}`);
  return screenOf(OBJECTIFS_TEXTES.titre, bodyOf(notice, lines), entries);
}

// ---------------------------------------------------------------- la carte

/** La ligne d'info d'une usine de l'entreprise sur la carte : entière, en incident, sans courant ou en ruine, d'après l'état du service. */
function carteDetail(factory: Factory, status: FactoryStatus): string {
  const etat = CARTE_TEXTES.etat;
  switch (factory.state) {
    case "ACTIVE":
      if (status === "INCIDENT") return etat.incident(incidentTexts(factory.incidentKind).nom, formatCount(factory.level));
      if (status === "SANS_COURANT") return etat.sansCourant(formatCount(factory.level));
      return etat.entiere(formatCount(factory.level));
    case "EN_RUINE":
      return etat.enRuine;
    case "REMISE_EN_ETAT":
      return etat.enTravaux(factory.restoreStep ?? 0, RESTORE_STEP_MAX);
  }
}

/**
 * La carte des îles : l'île principale (verrouillée tant que l'entreprise n'a pas sa première zone, puis un voyage à la
 * mairie), puis une ligne par usine dans l'ordre du jeu. Une usine de l'entreprise mène à son île ; une usine qu'elle n'a pas reste cachée (« ????? »).
 * Une usine de l'entreprise que cet add-on ne connaît pas encore vient après les autres.
 */
export function carteScreen(view: CompanyView, page: number, tilesPerScreen: number, notice?: Notice): Screen {
  const owned = new Map(view.state.factories.map((factory) => [factory.factoryId, factory]));
  const ids = [...FACTORY_IDS, ...view.state.factories.map((factory) => factory.factoryId).filter((id) => !FACTORY_IDS.includes(id))];
  const rows: Entry[] = [ilePrincipaleTile(view, CARTE_TEXTES.ilePrincipale, CARTE_TEXTES.ilePrincipaleVerrouillee)];
  for (const id of ids) {
    const factory = owned.get(id);
    if (factory === undefined) {
      rows.push({
        text: `${COLOR.locked}${ICON.cadenas} ${CARTE_TEXTES.inconnue}${COLOR.reset}`,
        target: { kind: "soon", bubble: BULLES.usinePasEncore },
      });
    } else {
      const status = factoryStatus(factory);
      rows.push({
        text: button(`${STATUS_MARK[status]} ${factoryName(id)}`, carteDetail(factory, status)),
        target: { kind: "travel", factoryId: id },
      });
    }
  }

  const perPage = Math.max(1, Math.floor(tilesPerScreen));
  const pages = Math.max(1, Math.ceil(rows.length / perPage));
  const current = Math.min(Math.max(0, Math.floor(page)), pages - 1);
  const entries = rows.slice(current * perPage, (current + 1) * perPage);
  if (current > 0) entries.push({ text: USINES_TEXTES.precedent, target: go({ screen: "carte", page: current - 1 }) });
  if (current < pages - 1) entries.push({ text: USINES_TEXTES.suite, target: go({ screen: "carte", page: current + 1 }) });
  entries.push({ text: CARTE_TEXTES.retour, target: go(MAIN) });

  const lines: string[] = [CARTE_TEXTES.phrase];
  if (pages > 1) lines.push(colored(COLOR.muted, USINES_TEXTES.page(current + 1, pages)));
  return screenOf(CARTE_TEXTES.titre, bodyOf(notice, lines), entries);
}

// ---------------------------------------------------------------- le prestige

/** « Époque 2 : Vapeur », d'après le numéro d'époque du service. */
function eraLabel(era: number): string {
  return PRESTIGE_TEXTES.epoque(formatCount(era), eraName(era));
}

/** Ce que donnera la course suivante : une époque, ou une étoile. `next` est un champ de l'aperçu du service. */
function nextLine(next: PrestigePreview["next"]): string {
  const era = /^EPOQUE_([0-9]+)$/.exec(next);
  if (era === null) return PRESTIGE_TEXTES.suivante.etoile;
  const number = Number(era[1]);
  return PRESTIGE_TEXTES.suivante.epoque(formatCount(number), eraName(number));
}

/** Les parts de chaque membre, telles que le service les donne. Une entreprise d'une seule personne n'a pas de part à montrer. */
function shareLines(preview: PrestigePreview, xuid: Xuid): string[] {
  if (preview.shares.length < 2) return [];
  return preview.shares.map((share) =>
    colored(
      COLOR.lingots,
      share.xuid === xuid ? PRESTIGE_TEXTES.avant.taPart(formatCount(share.lingots)) : PRESTIGE_TEXTES.avant.unAssocie(formatCount(share.lingots)),
    ),
  );
}

/**
 * L'écran d'avant le prestige : où en est la course, ce que le service dit que le prestige rapporterait (autorisé ou non,
 * Lingots, part de chaque membre, ce que donnerait deux fois plus de Donar), la course suivante, puis ce qui repart à
 * zéro et ce qui reste. Le bouton « Prestige ! » n'existe que si le service dit que c'est permis. Sans aperçu (lecture
 * pas faite ou échouée), Oooscar le dit et il ne reste que « Retour » : rien n'est inventé.
 */
export function prestigeScreen(view: CompanyView, lookup: PreviewOutcome | undefined, xuid: Xuid, notice?: Notice): Screen {
  const back: Entry = { text: PRESTIGE_TEXTES.avant.retour, target: go(MAIN) };
  if (lookup?.kind !== "preview") {
    const bubble = lookup === undefined ? BULLES.serviceInjoignable : failureBubble(lookup.failure, lookup.reason);
    return screenOf(PRESTIGE_TEXTES.titre, bodyOf(notice ?? { bubble }, []), [back]);
  }

  const { preview } = lookup;
  const avant = PRESTIGE_TEXTES.avant;
  const lines = [colored(COLOR.muted, PRESTIGE_TEXTES.course(formatCount(view.state.courseNumber), eraLabel(view.state.era))), ""];
  if (preview.allowed) {
    lines.push(colored(COLOR.success, avant.pret), avant.principe, colored(COLOR.lingots, avant.total(formatCount(preview.lingotsTotal))));
    lines.push(...shareLines(preview, xuid));
    if (preview.lingotsIfDoubled !== undefined) lines.push(colored(COLOR.muted, avant.doubler(formatCount(preview.lingotsIfDoubled))));
  } else {
    lines.push(colored(COLOR.blocked, avant.pasEncore), avant.seuil(formatAmount(view.state.threshold)));
  }
  lines.push(
    "",
    nextLine(preview.next),
    "",
    colored(COLOR.paper, avant.repartTitre),
    ...avant.repart,
    "",
    colored(COLOR.paper, avant.resteTitre),
    avant.reste,
  );

  const entries: Entry[] = [];
  if (preview.allowed) entries.push({ text: avant.confirmer, target: { kind: "prestige" } });
  entries.push(back);
  return screenOf(PRESTIGE_TEXTES.titre, bodyOf(notice, lines), entries);
}

/**
 * L'écran d'après le prestige : l'état que le service vient de rendre (époque, course, étoiles, Lingots, Donar), lu dans
 * la vue du joueur. Les îles changent de visage au relevé suivant du monde (monde/synchro.ts).
 */
export function apresScreen(view: CompanyView, notice?: Notice): Screen {
  const { state, balances } = view;
  const apres = PRESTIGE_TEXTES.apres;
  const lines = [`${COLOR.bold}${PRESTIGE_TEXTES.course(formatCount(state.courseNumber), eraLabel(state.era))}${COLOR.reset}`];
  if (state.stars > 0) lines.push(apres.etoiles(formatCount(state.stars)));
  lines.push(colored(COLOR.lingots, apres.lingots(formatCount(balances.lingots))), SILO_TEXTES.donar(formatAmount(state.donar)), "", apres.suite);
  const entries: Entry[] = [
    { text: apres.usines, target: go({ screen: "usines", page: 0 }) },
    { text: apres.retour, target: go(MAIN) },
  ];
  return screenOf(PRESTIGE_TEXTES.titre, bodyOf(notice, lines), entries);
}

// ---------------------------------------------------------------- le silo

export function siloScreen(view: CompanyView, nowMs: number, notice?: Notice): Screen {
  const lines = [
    SILO_TEXTES.phrase,
    "",
    SILO_TEXTES.donar(formatAmount(counterValue(view.anchor, nowMs))),
    SILO_TEXTES.revenu(formatRate(view.state.ratePerHour)),
    SILO_TEXTES.multiplicateur(formatMultiplier(view.state.multiplier)),
  ];
  const entries: Entry[] = [
    { text: SILO_TEXTES.encaisser, target: { kind: "collect" } },
    { text: SILO_TEXTES.retour, target: go(MAIN) },
  ];
  return screenOf(SILO_TEXTES.titre, bodyOf(notice, lines), entries);
}

// ---------------------------------------------------------------- le service ne répond pas

/** Quand le service n'a rien dit du joueur : une bulle et un bouton qui ferme. */
export function unavailableScreen(): Screen {
  return screenOf(MENU_TEXTES.titre, renderBubble(BULLES.serviceInjoignable), [{ text: MENU_TEXTES.retour, target: { kind: "end" } }]);
}

// ---------------------------------------------------------------- ce que dit Oooscar après une action

/**
 * La bulle (et les chiffres) qui suivent le résultat d'une action. Rien quand l'action a été ignorée. `staff` dit que
 * l'action touche le personnel : deux refus y ont des mots à eux (plus de poste libre, déjà embauché).
 */
export function noticeOf(outcome: ActionOutcome, staff = false): Notice | undefined {
  switch (outcome.kind) {
    case "collected": {
      const lines = [SILO_TEXTES.encaisse(formatAmount(outcome.collected))];
      if (outcome.offlineHours > 0) lines.push(SILO_TEXTES.horsLigne(formatDuration(outcome.offlineHours)));
      return { bubble: outcome.offlineHours > 0 ? BULLES.collecteHorsLigne : BULLES.collecte, lines };
    }
    case "levelsBought":
      return { bubble: BULLES.montee };
    case "stepDone":
      return { bubble: BULLES.etape };
    case "incidentResolved":
      return { bubble: BULLES.usineRepart };
    case "prestiged":
      return { bubble: BULLES.prestige };
    case "hired":
      return { bubble: BULLES.recrue };
    case "assigned":
      return { bubble: BULLES.affectation };
    case "fired":
      return { bubble: BULLES.renvoi };
    case "invited":
      return { bubble: ASSOCIES_TEXTES.bulles.invitationEnvoyee };
    case "sharesSet":
      return { bubble: ASSOCIES_TEXTES.bulles.partsEnregistrees };
    case "joined":
      return { bubble: ASSOCIES_TEXTES.bulles.bienvenue };
    case "zoneBought":
      return { bubble: VILLE_TEXTES.bulles.zoneAchetee };
    case "buildingPlaced":
      return { bubble: VILLE_TEXTES.bulles.batimentPose };
    case "buildingUpgraded":
      return { bubble: VILLE_TEXTES.bulles.batimentAmeliore };
    case "itemBought":
      return { bubble: BOUTIQUE_TEXTES.bulles.achete };
    case "passClaimed":
      return { bubble: PASS_TEXTES.bulles.palierReclame };
    case "refused":
      return { bubble: staff ? staffRefusalBubble(outcome.reason, outcome.missing) : refusalBubble(outcome.reason, outcome.missing) };
    case "failed":
      return { bubble: failureBubble(outcome.failure, outcome.reason) };
    case "busy":
      return undefined;
  }
}

// ---------------------------------------------------------------- le parcours

/** La bulle d'Oooscar quand le voyage n'a pas eu lieu. `arrived` et `gone` n'en ont pas : le menu se ferme. */
export function travelNotice(outcome: TravelOutcome): Notice | undefined {
  switch (outcome) {
    case "unavailable":
      return { bubble: BULLES.ileIntrouvable };
    case "waiting":
      return { bubble: BULLES.ilePasPrete };
    case "arrived":
    case "gone":
      return undefined;
  }
}

/** Ce que le menu lit d'un joueur : son nom, d'où vient son identifiant (identity.ts). */
export interface MenuPlayer {
  readonly name: string;
}

export interface MenuDeps<P extends MenuPlayer> {
  readonly views: Pick<ViewStore, "get">;
  /** Les missions : lues pour la tuile Objectifs, relues quand le joueur ouvre l'écran des objectifs. */
  readonly missions: Pick<Missions, "get" | "refresh">;
  readonly actions: Actions;
  /** Les invitations reçues (ui/invitations.ts) : listées dans l'écran des associés, retirées quand le joueur y répond. Absentes, il n'y en a aucune. */
  readonly invitations?: Pick<Invitations, "list" | "remove" | "clear" | "opened">;
  /** Les joueurs connectés que le service connaît (le choix de « Inviter »). Absent, la liste est vide. */
  readonly online?: () => readonly OnlinePlayer[];
  /**
   * Téléporte le joueur sur l'île d'une usine de son entreprise (monde/voyage.ts), d'après l'état que le service a
   * donné. Absent quand le monde n'a pas démarré : la carte répond alors « Pas encore disponible ».
   */
  readonly travel?: (player: P, state: CompanyState, factoryId: string) => Promise<TravelOutcome>;
  /** Téléporte le joueur à la mairie de l'île principale (monde/voyage.ts, createCityTravel). Absent : la carte répond « Pas encore disponible ». */
  readonly travelCity?: (player: P, state: CompanyState) => Promise<TravelOutcome>;
  /**
   * Où se tient le joueur dans le monde des joueurs (monde/arrivee.ts, `ArrivalWorld.locate`). Sert à lire la parcelle de
   * « Poser ici ». Absent, ou sans réponse : la pose dit qu'elle ne sait pas où est le joueur.
   */
  readonly where?: (player: P) => Pos | undefined;
  /** La dernière origine d'archipel connue d'une entreprise (monde/synchro.ts), pour une réponse du service qui ne la redonne pas. */
  readonly originOf?: (companyId: number) => Xz | undefined;
  /** Les chiffres du monde et de la ville. Absents : definitions/iles.json et definitions/ville.json. */
  readonly defs?: IlesDefinitions;
  readonly ville?: VilleDefinitions;
  /** Les cosmétiques que `join` a donnés au joueur (le titre Pionnier sous le nom de l'entreprise). Absent : aucun. */
  readonly cosmetics?: (xuid: Xuid) => readonly string[];
  readonly presenter: FormPresenter<P>;
  readonly clock: Clock;
  /** Attend ce nombre de secondes sur l'horloge du serveur (runtime.ts, pause). */
  readonly wait: (seconds: number) => Promise<void>;
  readonly log: Logger;
  readonly tilesPerScreen?: number;
  readonly busyRetries?: number;
  readonly busyRetrySeconds?: number;
}

export interface Menu<P> {
  /**
   * Ouvre le menu principal et suit le joueur d'écran en écran jusqu'à ce qu'il ferme. Un joueur qui a déjà un
   * menu ouvert est ignoré. Ne lève pas.
   */
  open(player: P, initial?: Route): Promise<void>;
}

function parentOf(route: Route): Route {
  switch (route.screen) {
    case "fiche":
      return { screen: "usines", page: route.page };
    case "equipe":
    case "membre":
    case "agence":
      return { screen: "personnel", page: 0 };
    case "affecter":
    case "renvoi":
      return { screen: "membre", staffId: route.staffId };
    case "candidat":
      return { screen: "agence" };
    case "embauche":
      return { screen: "candidat", slot: route.slot };
    case "associes":
      return { screen: "personnel", page: 0 };
    case "inviter":
    case "invitation":
      return { screen: "associes" };
    case "rayon":
      return shelfParent(route.rayon);
    case "article":
      return articleParent(route.shelf, route.page);
    case "offrir":
      return { screen: "article", itemId: route.itemId, shelf: route.shelf, page: route.page };
    case "pass":
      return { screen: "boutique" };
    case "paliers":
      return { screen: "pass" };
    case "palier":
      return { screen: "paliers", page: route.page };
    case "batiments":
    case "construire":
      return { screen: "ville" };
    case "batiment":
      return { screen: "batiments", page: route.page };
    case "poser":
      return { screen: "construire" };
    case "ville":
    case "boutique":
    case "personnel":
    case "usines":
    case "silo":
    case "objectifs":
    case "prestige":
    case "apres":
    case "carte":
    case "main":
      return MAIN;
  }
}

/** Ce que le menu a lu du service pour ses écrans, par joueur et par ouverture : relu à chaque entrée dans l'écran. */
interface Reads {
  preview: PreviewOutcome | undefined;
  candidates: CandidatesOutcome | undefined;
  /** La parcelle où se tient le joueur, lue en entrant dans l'écran de pose. */
  parcel: ParcelReading | undefined;
  /** Le catalogue de la boutique, le pass et les votes du jour, relus à l'entrée dans la boutique. */
  catalog: CatalogOutcome | undefined;
  pass: PassOutcome | undefined;
  votes: VotesOutcome | undefined;
}

/** Une relecture ratée garde la dernière lecture réussie : l'écran reste, et la bulle d'Oooscar dit que ce n'est pas frais. */
function keepGood<T extends { readonly kind: string }>(previous: T | undefined, fresh: T): T {
  return fresh.kind === "failed" && previous !== undefined && previous.kind !== "failed" ? previous : fresh;
}

export function createMenu<P extends MenuPlayer>(deps: MenuDeps<P>): Menu<P> {
  const { views, missions, actions, presenter, clock, wait, log } = deps;
  const tilesPerScreen = deps.tilesPerScreen ?? config.ui.tilesPerScreen;
  const busyRetries = deps.busyRetries ?? config.ui.formBusyRetries;
  const busyRetrySeconds = deps.busyRetrySeconds ?? config.ui.formBusyRetrySeconds;
  const inMenu = new Set<string>();
  const defs = deps.defs ?? ILES;
  const ville = deps.ville ?? VILLE;

  /** La parcelle où se tient le joueur. Rien quand sa position ou l'origine de l'archipel n'est pas connue. */
  function readParcel(player: P, state: CompanyState): ParcelReading | undefined {
    const at = deps.where?.(player);
    const origin = state.island ?? deps.originOf?.(state.companyId);
    if (at === undefined || origin === undefined) return undefined;
    return parcelAt(at, origin, defs, ville);
  }

  /** Montre l'écran. Si Bedrock le refuse parce que le joueur a un autre écran ouvert, on réessaie un peu, puis on renonce. */
  async function present(player: P, spec: ActionFormSpec): Promise<FormAnswer> {
    for (let attempt = 0; ; attempt += 1) {
      const answer = await presenter.show(player, spec);
      if (answer.kind !== "busy") return answer;
      if (attempt >= busyRetries) {
        log.info(`menu de ${player.name} : écran occupé, abandon`);
        return { kind: "closed" };
      }
      await wait(busyRetrySeconds);
    }
  }

  /** Montre le formulaire des parts, avec les mêmes règles que `present`. */
  async function askForm(player: P, spec: ModalFormSpec): Promise<ModalAnswer> {
    for (let attempt = 0; ; attempt += 1) {
      const answer = await presenter.ask(player, spec);
      if (answer.kind !== "busy") return answer;
      if (attempt >= busyRetries) {
        log.info(`formulaire de ${player.name} : écran occupé, abandon`);
        return { kind: "closed" };
      }
      await wait(busyRetrySeconds);
    }
  }

  /**
   * Le formulaire des parts : un champ par membre, avec la part du service. Les champs sont lus tels quels (un entier par
   * champ) et envoyés ; le total est jugé par le service. Rend la bulle d'après sa réponse, ou rien si le joueur ferme.
   */
  async function editShares(player: P, xuid: Xuid, view: CompanyView): Promise<Notice | undefined> {
    const members = membersOf(view);
    const answer = await askForm(player, partsForm(members));
    if (answer.kind !== "submitted") return undefined;
    const shares = readShares(members, answer.values);
    if (shares === undefined) return { bubble: ASSOCIES_TEXTES.bulles.partsInvalides };
    const outcome = await actions.setShares(xuid, shares);
    return associesNotice(outcome, "setShares") ?? noticeOf(outcome);
  }

  function screenFor(
    route: Route,
    xuid: Xuid,
    view: CompanyView,
    notice: Notice | undefined,
    reads: Reads,
  ): Screen | undefined {
    switch (route.screen) {
      case "main":
        return mainScreen(view, missions.get(xuid), notice, deps.cosmetics?.(xuid) ?? []);
      case "silo":
        return siloScreen(view, clock.now(), notice);
      case "objectifs":
        return objectifsScreen(missions.get(xuid), notice);
      case "prestige":
        return prestigeScreen(view, reads.preview, xuid, notice);
      case "apres":
        return apresScreen(view, notice);
      case "carte":
        return carteScreen(view, route.page, tilesPerScreen, notice);
      case "usines":
        return usinesScreen(view, route.page, tilesPerScreen, notice);
      case "fiche":
        return ficheScreen(view, route.factoryId, route.page, notice);
      case "personnel":
        return personnelScreen(view, route.page, tilesPerScreen, notice);
      case "equipe":
        return equipeScreen(view, route.factoryId, route.page, tilesPerScreen, notice);
      case "membre":
        return membreScreen(view, route.staffId, notice);
      case "affecter":
        return affecterScreen(view, route.staffId, route.page, tilesPerScreen, notice);
      case "renvoi":
        return renvoiScreen(view, route.staffId, notice);
      case "agence":
        return agenceScreen(reads.candidates, notice);
      case "candidat":
        return candidatScreen(reads.candidates, route.slot, notice);
      case "embauche":
        return embaucheScreen(view, reads.candidates, route.slot, route.page, tilesPerScreen, notice);
      case "associes":
        return associesScreen(view, xuid, deps.invitations?.list(xuid) ?? [], notice);
      case "inviter":
        return inviterScreen(deps.online?.() ?? [], xuid, route.page, tilesPerScreen, notice);
      case "invitation":
        return invitationScreen(deps.invitations?.list(xuid) ?? [], route.companyId, notice);
      case "ville":
        return villeScreen(view, notice);
      case "batiments":
        return batimentsScreen(view, route.page, tilesPerScreen, notice);
      case "batiment":
        return batimentScreen(view, route.buildingId, route.page, notice);
      case "construire":
        return construireScreen(notice, ville);
      case "poser":
        return poserScreen(route.buildingType, route.rotation, reads.parcel, notice, ville);
      case "boutique":
        return boutiqueScreen(view, reads.catalog, reads.pass, reads.votes, notice);
      case "rayon":
        return rayonScreen(reads.catalog, route.rayon, route.page, tilesPerScreen, notice);
      case "article":
        return articleScreen(view, reads.catalog, route.itemId, route.shelf, route.page, notice);
      case "offrir":
        return offrirScreen(reads.catalog, deps.online?.() ?? [], xuid, route.itemId, route.shelf, route.page, route.choix, tilesPerScreen, notice);
      case "pass":
        return passScreen(reads.pass, reads.catalog, notice);
      case "paliers":
        return paliersScreen(reads.pass, route.page, tilesPerScreen, notice);
      case "palier":
        return palierScreen(reads.pass, route.tier, route.page, notice);
    }
  }

  async function act(xuid: Xuid, target: Target): Promise<Notice | undefined> {
    switch (target.kind) {
      case "collect":
        return noticeOf(await actions.collect(xuid));
      case "buy":
        return noticeOf(await actions.buyLevels(xuid, target.factoryId, target.amount));
      case "restore":
        return noticeOf(await actions.restoreStep(xuid, target.factoryId, target.step));
      case "soon":
        return { bubble: target.bubble ?? BULLES.bientot };
      case "hire":
      case "assign":
      case "fire":
      case "repair":
      case "invite":
      case "parts":
      case "accept":
      case "prestige":
      case "travel":
      case "travelCity":
      case "buyZone":
      case "upgrade":
      case "place":
      case "buyItem":
      case "giftItem":
      case "claim":
      case "go":
      case "end":
        return undefined;
    }
  }

  async function walk(player: P, initial: Route): Promise<void> {
    const xuid = playerXuid(player);
    let route: Route = initial;
    let notice: Notice | undefined;
    /** Ce que le menu a lu du service pour ses écrans : l'aperçu du prestige, les candidats de l'agence. */
    const reads: Reads = { preview: undefined, candidates: undefined, parcel: undefined, catalog: undefined, pass: undefined, votes: undefined };
    /** Relit ce que l'écran d'arrivée montre et que le service peut avoir changé depuis la dernière fois. */
    async function enter(next: Route): Promise<Notice | undefined> {
      // Les missions avancent côté service : l'écran des objectifs les relit à chaque ouverture.
      if (next.screen === "objectifs") await missions.refresh(xuid);
      // Le prestige aussi : l'aperçu dépend de la production du moment.
      if (next.screen === "prestige") reads.preview = await actions.preview(xuid);
      // Les candidats du jour aussi : un candidat embauché ailleurs, ou une nouvelle liste.
      if (next.screen === "agence") reads.candidates = await actions.candidates(xuid);
      // La ville aussi : un associé a pu acheter une zone ou poser un bâtiment. La relecture ne coûte rien à l'écran : si elle échoue, la dernière ville connue reste.
      if (next.screen === "ville") {
        const refreshed = await actions.refresh(xuid);
        if (refreshed.kind === "failed") return { bubble: failureBubble(refreshed.failure, refreshed.reason) };
      }
      // La parcelle où se tient le joueur : lue à l'ouverture de l'écran de pose, et à chaque quart de tour.
      if (next.screen === "poser") {
        const current = views.get(xuid)?.state;
        reads.parcel = current === undefined ? undefined : readParcel(player, current);
      }
      // La boutique : le catalogue (ce que le joueur possède, le prochain grade), le pass (les paliers à réclamer) et les votes du
      // jour (le rappel) sont relus ensemble. Seul l'échec du catalogue est dit : sans pass ni votes, la boutique manque d'une ligne.
      if (next.screen === "boutique") {
        const [catalog, pass, votes] = await Promise.all([actions.catalog(xuid), actions.pass(xuid), actions.votes(xuid)]);
        reads.catalog = keepGood(reads.catalog, catalog);
        reads.pass = keepGood(reads.pass, pass);
        reads.votes = votes;
        if (catalog.kind === "failed") return { bubble: failureBubble(catalog.failure, catalog.reason) };
      }
      // Le pass aussi : les points montent avec le prestige, les commandes et les missions.
      if (next.screen === "pass") {
        const pass = await actions.pass(xuid);
        reads.pass = keepGood(reads.pass, pass);
        if (pass.kind === "failed") return { bubble: failureBubble(pass.failure, pass.reason) };
      }
      // Les associés aussi : un joueur a pu accepter, ou le PDG changer les parts, sans que ce joueur ait agi.
      if (next.screen === "associes") {
        deps.invitations?.opened(xuid);
        const refreshed = await actions.refresh(xuid);
        // La relecture a échoué : l'écran montre la dernière liste connue, et Oooscar dit pourquoi elle n'est pas fraîche.
        if (refreshed.kind === "failed") return { bubble: failureBubble(refreshed.failure, refreshed.reason) };
      }
      return undefined;
    }
    notice = await enter(route);
    for (;;) {
      const view = views.get(xuid);
      if (view === undefined) {
        await present(player, unavailableScreen().spec);
        return;
      }
      const screen = screenFor(route, xuid, view, notice, reads);
      if (screen === undefined) {
        // L'usine, le membre ou le candidat n'existe plus côté service : retour au parent, la bulle reste. Si la relecture du
        // parent échoue à son tour, c'est elle que dit Oooscar.
        route = parentOf(route);
        notice = (await enter(route)) ?? notice;
        continue;
      }
      const answer = await present(player, screen.spec);
      if (answer.kind !== "selected") return;
      const target = screen.targets[answer.index];
      if (target === undefined || target.kind === "end") return;
      notice = undefined;
      if (target.kind === "go") {
        route = target.route;
        notice = await enter(route);
      } else if (target.kind === "invite") {
        const outcome = await actions.invite(xuid, target.targetXuid);
        notice = associesNotice(outcome, "invite") ?? noticeOf(outcome);
        if (outcome.kind === "invited") route = { screen: "associes" };
      } else if (target.kind === "parts") {
        notice = await editShares(player, xuid, view);
      } else if (target.kind === "accept") {
        const outcome = await actions.acceptInvite(xuid, target.companyId);
        notice = associesNotice(outcome, "acceptInvite") ?? noticeOf(outcome);
        if (outcome.kind === "joined") {
          // Le joueur est dans la nouvelle entreprise ; le service périme ses autres invitations.
          deps.invitations?.clear(xuid);
          route = { screen: "associes" };
        } else if (isRejectedRequest(outcome) || (outcome.kind === "refused" && outcome.reason === "DEJA_POSSEDE")) {
          // Le service ne connaît plus cette invitation, ou le joueur est déjà dans cette entreprise : elle ne sert plus.
          deps.invitations?.remove(xuid, target.companyId);
          route = { screen: "associes" };
        }
      } else if (target.kind === "prestige") {
        const outcome = await actions.prestige(xuid);
        notice = noticeOf(outcome);
        if (outcome.kind === "prestiged") route = { screen: "apres" };
        else if (outcome.kind !== "busy") reads.preview = await actions.preview(xuid);
      } else if (target.kind === "hire") {
        const outcome = await actions.hire(xuid, target.slot, target.factoryId);
        notice = noticeOf(outcome, true);
        // Embauché, le candidat porte son `staffId` : l'agence est relue pour le montrer. Refusé, le joueur reste sur le choix de l'usine.
        if (outcome.kind === "hired") {
          route = { screen: "agence" };
          await enter(route);
        }
      } else if (target.kind === "assign") {
        const outcome = await actions.assignStaff(xuid, target.staffId, target.factoryId);
        notice = noticeOf(outcome, true);
        if (outcome.kind === "assigned") route = { screen: "membre", staffId: target.staffId };
      } else if (target.kind === "fire") {
        const outcome = await actions.fireStaff(xuid, target.staffId);
        notice = noticeOf(outcome, true);
        if (outcome.kind === "fired") route = { screen: "personnel", page: 0 };
      } else if (target.kind === "repair") {
        // Le mini-jeu a ses propres écrans. Réussi, le service clôt l'incident et rend l'état d'aujourd'hui ; la fiche revient.
        const played = await playRepair({ show: (spec) => present(player, spec) }, target.factoryId, target.incidentKind);
        if (played === "closed") return;
        if (played === "done") notice = noticeOf(await actions.resolveIncident(xuid, target.incidentId));
      } else if (target.kind === "buyZone") {
        const outcome = await actions.buyZone(xuid, target.zoneIndex);
        notice = villeNotice(outcome, "buyZone", target.zoneIndex) ?? noticeOf(outcome);
      } else if (target.kind === "upgrade") {
        const outcome = await actions.upgradeBuilding(xuid, target.buildingId);
        notice = villeNotice(outcome, "upgradeBuilding") ?? noticeOf(outcome);
      } else if (target.kind === "place") {
        // La parcelle est celle où le joueur se tient maintenant, pas celle que l'écran a montrée en s'ouvrant.
        const current = views.get(xuid)?.state ?? view.state;
        const parcel = readParcel(player, current);
        reads.parcel = parcel;
        if (parcel === undefined) {
          notice = { bubble: VILLE_TEXTES.bulles.positionInconnue };
        } else if (parcel.kind === "off") {
          notice = { bubble: VILLE_TEXTES.bulles.pasSurLIle };
        } else {
          const outcome = await actions.placeBuilding(xuid, target.buildingType, parcel.x, parcel.z, target.rotation);
          notice = villeNotice(outcome, "placeBuilding") ?? noticeOf(outcome);
          if (outcome.kind === "buildingPlaced") route = { screen: "ville" };
        }
      } else if (target.kind === "buyItem") {
        const context = purchaseContext(reads.catalog, target.itemId, false);
        const outcome = await actions.buyItem(xuid, target.itemId);
        notice = boutiqueNotice(outcome, context) ?? noticeOf(outcome);
        if (outcome.kind === "itemBought") {
          // Ce que le joueur possède a changé (et le prochain grade) : le catalogue est relu. La piste premium aussi, et le revenu
          // quand l'article multiplie la production (grade, Pilier, boost).
          reads.catalog = keepGood(reads.catalog, await actions.catalog(xuid));
          if (target.itemId === PASS_PREMIUM_ITEM_ID) reads.pass = keepGood(reads.pass, await actions.pass(xuid));
          if (affectsCompany(target.itemId)) await actions.refresh(xuid);
        }
      } else if (target.kind === "giftItem") {
        const context = purchaseContext(reads.catalog, target.itemId, true);
        const outcome = await actions.buyItem(xuid, target.itemId, target.forXuid);
        notice = boutiqueNotice(outcome, context) ?? noticeOf(outcome);
        // Offert, le joueur revient sur la fiche de l'article : ses Gemmes sont celles du service.
        if (outcome.kind === "itemBought" && route.screen === "offrir") {
          route = { screen: "article", itemId: route.itemId, shelf: route.shelf, page: route.page };
        }
      } else if (target.kind === "claim") {
        const outcome = await actions.claimPass(xuid, target.tier, target.track);
        notice = passNotice(outcome) ?? noticeOf(outcome);
        // Réclamé, ou refusé parce que la lecture était périmée : le pass est relu pour que le palier montre l'état du service.
        if (outcome.kind === "passClaimed" || outcome.kind === "refused") reads.pass = keepGood(reads.pass, await actions.pass(xuid));
      } else if (target.kind === "travelCity") {
        if (deps.travelCity === undefined) {
          notice = { bubble: BULLES.bientot };
        } else {
          const outcome = await deps.travelCity(player, views.get(xuid)?.state ?? view.state);
          if (outcome === "arrived" || outcome === "gone") return;
          notice = travelNotice(outcome);
        }
      } else if (target.kind === "travel") {
        if (deps.travel === undefined) {
          notice = { bubble: BULLES.bientot };
        } else {
          const outcome = await deps.travel(player, views.get(xuid)?.state ?? view.state, target.factoryId);
          // Arrivé, le joueur est sur son île : le menu se ferme. Parti, il n'y a plus personne à qui parler.
          if (outcome === "arrived" || outcome === "gone") return;
          notice = travelNotice(outcome);
        }
      } else {
        notice = await act(xuid, target);
      }
    }
  }

  return {
    async open(player, initial = MAIN) {
      // Le Player Bedrock peut devenir invalide pendant form.show : ne plus relire son nom ensuite.
      let name: string;
      let xuid: Xuid;
      try {
        name = player.name;
        xuid = playerXuid({ name });
      } catch {
        return;
      }
      if (inMenu.has(xuid)) return;
      inMenu.add(xuid);
      try {
        await walk(player, initial);
      } catch (error) {
        log.warn(`menu de ${name} : ${describeError(error)}`);
      } finally {
        inMenu.delete(xuid);
      }
    },
  };
}
