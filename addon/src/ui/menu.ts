// Les écrans du menu et le parcours de l'un à l'autre.
//
//   MENU ─┬─ SILO ─────────── Encaisser
//         ├─ USINES ── fiche ─ Monter de 1 / de 10 / au palier / au max   (usine en ruine : l'étape suivante)
//         └─ Carte, Objectifs, Amis : verrouillés, un mot « Bientôt »
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
import { BUY_AMOUNTS, RESTORE_STEP_MAX, type BuyAmount, type Factory, type Xuid } from "../protocol";
import { describeError, type Clock, type Logger } from "../runtime";
import type { Actions, ActionOutcome } from "./actions";
import { COLOR, colored, ICON } from "./apparence";
import { counterValue } from "./compteur";
import type { CompanyView, ViewStore } from "./etat";
import type { ActionFormSpec, FormAnswer, FormPresenter } from "./formulaires";
import { formatAmount, formatCount, formatDuration, formatMultiplier, formatRate } from "./nombres";
import {
  BULLES,
  FICHE_TEXTES,
  MENU_TEXTES,
  SILO_TEXTES,
  USINES_TEXTES,
  factoryName,
  failureBubble,
  refusalBubble,
  renderBubble,
  type Bubble,
} from "./textes";
import { canBuyLevels, factoryStatus, nextRestoreStep, type FactoryStatus } from "./usines";

// ---------------------------------------------------------------- vocabulaire des écrans

/** Où le joueur se trouve. Chaque écran sait quel est son parent : pas de pile à tenir. */
export type Route =
  | { readonly screen: "main" }
  | { readonly screen: "silo" }
  | { readonly screen: "usines"; readonly page: number }
  | { readonly screen: "fiche"; readonly factoryId: string; readonly page: number };

/** Ce que fait un bouton. */
export type Target =
  | { readonly kind: "go"; readonly route: Route }
  /** Une tuile verrouillée : l'écran reste, Oooscar dit que ce n'est pas encore là. */
  | { readonly kind: "soon" }
  | { readonly kind: "collect" }
  | { readonly kind: "buy"; readonly factoryId: string; readonly amount: BuyAmount }
  | { readonly kind: "restore"; readonly factoryId: string; readonly step: number }
  /** Ferme le menu. */
  | { readonly kind: "end" };

/** Un écran montré : la fenêtre, et la cible de chaque bouton (même ordre que `spec.buttons`). */
export interface Screen {
  readonly spec: ActionFormSpec;
  readonly targets: readonly Target[];
}

/** Ce qu'Oooscar dit en haut d'un écran après une action, avec les lignes de chiffres qui vont avec. */
export interface Notice {
  readonly bubble: Bubble;
  readonly lines?: readonly string[];
}

const MAIN: Route = { screen: "main" };

function go(route: Route): Target {
  return { kind: "go", route };
}

/** Un bouton à deux lignes : le nom, puis ce qu'il faut savoir avant d'appuyer. */
function button(label: string, detail?: string): string {
  return detail === undefined ? label : `${label}\n${colored(COLOR.muted, detail)}`;
}

/**
 * Le corps d'un écran : la bulle d'Oooscar et ses chiffres, une ligne vide, puis les lignes de l'écran (une
 * chaîne vide y fait une ligne vide). Aucune ligne vide ne reste au bout.
 */
function bodyOf(notice: Notice | undefined, lines: readonly string[]): string {
  const top = notice === undefined ? [] : [renderBubble(notice.bubble), ...(notice.lines ?? [])];
  const all = top.length > 0 && lines.length > 0 ? [...top, "", ...lines] : [...top, ...lines];
  while (all.length > 0 && all[all.length - 1] === "") all.pop();
  return all.join("\n");
}

interface Entry {
  readonly text: string;
  readonly target: Target;
}

function screenOf(title: string, body: string, entries: readonly Entry[]): Screen {
  return {
    spec: { title, body, buttons: entries.map((entry) => entry.text) },
    targets: entries.map((entry) => entry.target),
  };
}

// ---------------------------------------------------------------- menu principal

function lockedTile(label: string): Entry {
  return {
    text: `${COLOR.locked}${ICON.cadenas} ${label}\n${MENU_TEXTES.bientot}${COLOR.reset}`,
    target: { kind: "soon" },
  };
}

export function mainScreen(view: CompanyView, notice?: Notice): Screen {
  const tiles: Entry[] = [
    {
      text: button(`${ICON.silo} ${MENU_TEXTES.silo}`, MENU_TEXTES.revenu(formatRate(view.state.ratePerHour))),
      target: go({ screen: "silo" }),
    },
    {
      text: button(`${ICON.usine} ${MENU_TEXTES.usines}`, MENU_TEXTES.nombreUsines(view.state.factories.length)),
      target: go({ screen: "usines", page: 0 }),
    },
    lockedTile(MENU_TEXTES.carte),
    lockedTile(MENU_TEXTES.objectifs),
    lockedTile(MENU_TEXTES.amis),
  ];
  return screenOf(MENU_TEXTES.titre, bodyOf(notice, [`${COLOR.bold}${view.state.name}${COLOR.reset}`]), tiles);
}

// ---------------------------------------------------------------- les usines

/** Le repère d'une usine selon sa situation : une icône et une couleur, jamais la couleur seule. */
const STATUS_MARK: Record<FactoryStatus, string> = {
  ACTIVE: colored(COLOR.success, ICON.usine),
  EN_RUINE: colored(COLOR.blocked, ICON.cadenas),
  REMISE_EN_ETAT: colored(COLOR.paper, ICON.engrenage),
  INCIDENT: colored(COLOR.incident, ICON.flamme),
  SANS_COURANT: colored(COLOR.blocked, ICON.eclair),
};

/** La couleur du mot de situation sur la fiche. */
const STATUS_COLOR: Record<FactoryStatus, string> = {
  ACTIVE: COLOR.success,
  EN_RUINE: COLOR.blocked,
  REMISE_EN_ETAT: COLOR.paper,
  INCIDENT: COLOR.incident,
  SANS_COURANT: COLOR.blocked,
};

/** La ligne d'info d'une tuile d'usine : tout vient de la fiche du service. */
function tileDetail(factory: Factory, status: FactoryStatus): string {
  const tuile = USINES_TEXTES.tuile;
  switch (status) {
    case "ACTIVE":
      return tuile.active(formatCount(factory.level), formatRate(factory.ratePerHour));
    case "EN_RUINE":
      return tuile.enRuine;
    case "REMISE_EN_ETAT":
      return tuile.remise(factory.restoreStep ?? 0, RESTORE_STEP_MAX);
    case "INCIDENT":
      return tuile.incident;
    case "SANS_COURANT":
      return tuile.sansCourant;
  }
}

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
  entries.push({ text: FICHE_TEXTES.retour, target: go({ screen: "usines", page }) });

  const lines = [
    colored(STATUS_COLOR[status], FICHE_TEXTES.sorte[status]),
    `${COLOR.bold}${factoryName(factoryId)}${COLOR.reset}`,
    FICHE_TEXTES.phrase[status],
    "",
    ...infos,
  ];
  return screenOf(FICHE_TEXTES.titre, bodyOf(notice, lines), entries);
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

/** La bulle (et les chiffres) qui suivent le résultat d'une action. Rien quand l'action a été ignorée. */
export function noticeOf(outcome: ActionOutcome): Notice | undefined {
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
    case "refused":
      return { bubble: refusalBubble(outcome.reason, outcome.missing) };
    case "failed":
      return { bubble: failureBubble(outcome.failure, outcome.reason) };
    case "busy":
      return undefined;
  }
}

// ---------------------------------------------------------------- le parcours

/** Ce que le menu lit d'un joueur : son nom, d'où vient son identifiant (identity.ts). */
export interface MenuPlayer {
  readonly name: string;
}

export interface MenuDeps<P extends MenuPlayer> {
  readonly views: Pick<ViewStore, "get">;
  readonly actions: Actions;
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
  open(player: P): Promise<void>;
}

function parentOf(route: Route): Route {
  switch (route.screen) {
    case "fiche":
      return { screen: "usines", page: route.page };
    case "usines":
    case "silo":
    case "main":
      return MAIN;
  }
}

export function createMenu<P extends MenuPlayer>(deps: MenuDeps<P>): Menu<P> {
  const { views, actions, presenter, clock, wait, log } = deps;
  const tilesPerScreen = deps.tilesPerScreen ?? config.ui.tilesPerScreen;
  const busyRetries = deps.busyRetries ?? config.ui.formBusyRetries;
  const busyRetrySeconds = deps.busyRetrySeconds ?? config.ui.formBusyRetrySeconds;
  const inMenu = new Set<string>();

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

  function screenFor(route: Route, view: CompanyView, notice: Notice | undefined): Screen | undefined {
    switch (route.screen) {
      case "main":
        return mainScreen(view, notice);
      case "silo":
        return siloScreen(view, clock.now(), notice);
      case "usines":
        return usinesScreen(view, route.page, tilesPerScreen, notice);
      case "fiche":
        return ficheScreen(view, route.factoryId, route.page, notice);
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
        return { bubble: BULLES.bientot };
      case "go":
      case "end":
        return undefined;
    }
  }

  async function walk(player: P): Promise<void> {
    const xuid = playerXuid(player);
    let route: Route = MAIN;
    let notice: Notice | undefined;
    for (;;) {
      const view = views.get(xuid);
      if (view === undefined) {
        await present(player, unavailableScreen().spec);
        return;
      }
      const screen = screenFor(route, view, notice);
      if (screen === undefined) {
        // L'usine n'existe plus côté service : retour au parent, la bulle reste.
        route = parentOf(route);
        continue;
      }
      const answer = await present(player, screen.spec);
      if (answer.kind !== "selected") return;
      const target = screen.targets[answer.index];
      if (target === undefined || target.kind === "end") return;
      notice = undefined;
      if (target.kind === "go") route = target.route;
      else notice = await act(xuid, target);
    }
  }

  return {
    async open(player) {
      const xuid = playerXuid(player);
      if (inMenu.has(xuid)) return;
      inMenu.add(xuid);
      try {
        await walk(player);
      } catch (error) {
        log.warn(`menu de ${player.name} : ${describeError(error)}`);
      } finally {
        inMenu.delete(xuid);
      }
    },
  };
}
