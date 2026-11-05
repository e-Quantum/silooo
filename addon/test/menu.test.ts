import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { Balances, CompanyState, CompanyUpdated, Factory, MissionState } from "../src/protocol";
import { createActions, type ActionOutcome, type PreviewOutcome } from "../src/ui/actions";
import { COLOR } from "../src/ui/apparence";
import { counterValue } from "../src/ui/compteur";
import { createViewStore, type CompanyView } from "../src/ui/etat";
import { createMissions } from "../src/ui/missions";
import type { TravelOutcome } from "../src/monde/voyage";
import {
  apresScreen,
  carteScreen,
  createMenu,
  ficheScreen,
  mainScreen,
  noticeOf,
  objectifsScreen,
  prestigeScreen,
  siloScreen,
  travelNotice,
  unavailableScreen,
  usinesScreen,
  type MenuDeps,
} from "../src/ui/menu";
import { formatAmount, formatCount, formatRate } from "../src/ui/nombres";
import {
  BULLES,
  CARTE_TEXTES,
  FACTORY_IDS,
  FACTORY_NAMES,
  FICHE_TEXTES,
  MENU_TEXTES,
  MISSION_TEXTES,
  OBJECTIFS_TEXTES,
  PRESTIGE_TEXTES,
  refusalBubble,
  SILO_TEXTES,
  USINES_TEXTES,
} from "../src/ui/textes";
import { balances, companyState, factory, mission, missionState, ok, prestigePreview, recordingLogger } from "./helpers";
import {
  PLAYER,
  XUID,
  bare,
  busy,
  close,
  failure,
  fakeClock,
  fakeCompany,
  fakeMissionsApi,
  fakePresenter,
  plain,
  press,
  refused,
  sequentialIds,
  storeWith,
  unreachable,
  viewsWith,
  type Reply,
  type Script,
} from "./ui-helpers";

const IDS = Object.keys(FACTORY_NAMES);

function viewOf(state: Partial<CompanyState> = {}, clock = fakeClock(0)): CompanyView {
  const view = viewsWith(state, clock).views.get(XUID);
  if (view === undefined) throw new Error("vue absente");
  return view;
}

function many(count: number): Factory[] {
  return IDS.slice(0, count).map((factoryId, i) => factory({ factoryId, level: i + 1 }));
}

const titleOf = (spec: { title: string }) => spec.title;

// ---------------------------------------------------------------- les écrans

describe("menu principal", () => {
  const screen = mainScreen(viewOf({ name: "Les Forges", ratePerHour: 7_200, factories: many(3) }), missionState());

  it("huit tuiles : Silo, Usines, Carte, Objectifs, Prestige, Personnel et Associés actifs, Amis verrouillé", () => {
    assert.equal(screen.spec.title, MENU_TEXTES.titre);
    assert.equal(screen.spec.buttons.length, 8);
    assert.deepEqual(screen.targets.map((t) => (t.kind === "go" ? t.route.screen : t.kind)), [
      "silo",
      "usines",
      "carte",
      "objectifs",
      "prestige",
      "personnel",
      "associes",
      "soon",
    ]);
  });

  it("les tuiles actives disent ce que le service a dit : revenu et nombre d'usines", () => {
    const buttons = screen.spec.buttons.map(bare);
    assert.match(buttons[0] ?? "", new RegExp(`Silo\\n${MENU_TEXTES.revenu(formatRate(7_200))}`));
    assert.match(buttons[1] ?? "", /Usines\n3 usines/);
  });

  it("la tuile verrouillée porte un cadenas et un mot, en gris", () => {
    const text = screen.spec.buttons[7] ?? "";
    assert.ok(text.startsWith(COLOR.locked), text);
    assert.ok(bare(text).includes("[X]"));
    assert.ok(bare(text).endsWith(MENU_TEXTES.bientot));
    assert.match(bare(text), /Amis/);
    for (const index of [0, 1, 2, 3, 4, 5, 6]) {
      assert.ok(!(screen.spec.buttons[index] ?? "").startsWith(COLOR.locked), `tuile ${index}`);
    }
  });

  it("le corps porte le nom de l'entreprise tel que le service le donne", () => {
    assert.equal(bare(screen.spec.body), "Les Forges");
  });

  it("une bulle d'Oooscar passe avant le nom", () => {
    const withNotice = mainScreen(viewOf(), undefined, { bubble: BULLES.bientot });
    assert.match(bare(withNotice.spec.body), /^Oooscar \[oups\] Pas encore disponible\.\n\nEntreprise de test$/);
  });

  it("une seule usine s'écrit au singulier", () => {
    assert.match(bare(mainScreen(viewOf({ factories: many(1) }), undefined).spec.buttons[1] ?? ""), /1 usine$/);
  });

  it("tient dans les huit tuiles d'un écran", () => {
    assert.ok(screen.spec.buttons.length <= 8);
  });
});

describe("écran des usines", () => {
  const view = viewOf({ factories: many(10) });

  it("une tuile par usine, le nom et la ligne d'info du service, puis Suite et Retour", () => {
    const screen = usinesScreen(view, 0, 4);
    assert.equal(screen.spec.title, USINES_TEXTES.titre);
    assert.equal(screen.spec.buttons.length, 6);
    const names = screen.spec.buttons.slice(0, 4).map((b) => bare(b).split("\n")[0] ?? "");
    assert.deepEqual(names, IDS.slice(0, 4).map((id) => `[U] ${FACTORY_NAMES[id as keyof typeof FACTORY_NAMES]}`));
    assert.match(bare(screen.spec.buttons[0] ?? ""), /\nNiv\. 1 · 2\/s$/);
    assert.match(bare(screen.spec.buttons[3] ?? ""), /\nNiv\. 4 · 2\/s$/);
    assert.deepEqual(screen.spec.buttons.slice(4).map(bare), [USINES_TEXTES.suite, MENU_TEXTES.retour]);
    assert.deepEqual(screen.targets.slice(0, 4).map((t) => t.kind === "go" && t.route.screen === "fiche" && t.route.factoryId), IDS.slice(0, 4));
    assert.deepEqual(screen.targets[4], { kind: "go", route: { screen: "usines", page: 1 } });
    assert.deepEqual(screen.targets[5], { kind: "go", route: { screen: "main" } });
  });

  it("pages : Précédent et Suite aux bons endroits, page indiquée, jamais plus de tuiles que demandé", () => {
    const labels = (page: number) => usinesScreen(view, page, 4).spec.buttons.map((b) => bare(b).split("\n")[0]);
    assert.equal(labels(0).filter((l) => l === USINES_TEXTES.precedent).length, 0);
    assert.ok(labels(1).includes(USINES_TEXTES.precedent) && labels(1).includes(USINES_TEXTES.suite));
    assert.ok(labels(2).includes(USINES_TEXTES.precedent) && !labels(2).includes(USINES_TEXTES.suite));
    assert.equal(labels(2).length, 2 + 2);
    assert.match(bare(usinesScreen(view, 1, 4).spec.body), new RegExp(USINES_TEXTES.page(2, 3)));
    for (const page of [0, 1, 2]) {
      assert.ok(usinesScreen(view, page, 4).spec.buttons.length <= 4 + 3);
    }
  });

  it("une page hors limites revient dans les limites", () => {
    assert.equal(usinesScreen(view, 99, 4).spec.body, usinesScreen(view, 2, 4).spec.body);
    assert.equal(usinesScreen(view, -3, 4).spec.body, usinesScreen(view, 0, 4).spec.body);
    assert.equal(usinesScreen(view, 0, 0).spec.buttons.length, 1 + 1 + 1);
  });

  it("une seule page : pas de numéro de page, pas de Suite", () => {
    const screen = usinesScreen(viewOf({ factories: many(3) }), 0, 8);
    assert.equal(screen.spec.body, "");
    assert.deepEqual(screen.spec.buttons.slice(3).map(bare), [MENU_TEXTES.retour]);
  });

  it("aucune usine : une phrase et le retour", () => {
    const screen = usinesScreen(viewOf({ factories: [] }), 0, 8);
    assert.equal(screen.spec.body, USINES_TEXTES.vide);
    assert.deepEqual(screen.spec.buttons, [MENU_TEXTES.retour]);
  });

  it("la ligne d'info suit la situation de l'usine : repère, couleur et mot", () => {
    const screen = usinesScreen(
      viewOf({
        factories: [
          factory({ factoryId: "carriere", state: "EN_RUINE" }),
          factory({ factoryId: "scierie", state: "REMISE_EN_ETAT", restoreStep: 2 }),
          factory({ factoryId: "ferme", incidentId: 4 }),
          factory({ factoryId: "centrale", stoppedNoPower: true }),
          factory({ factoryId: "fonderie" }),
        ],
      }),
      0,
      8,
    );
    const lines = screen.spec.buttons.slice(0, 5).map(bare);
    assert.deepEqual(lines, [
      "[X] Carrière\nEn ruine",
      "[T] Scierie\nRemise en état 2/4",
      "[!] Ferme\nIncident",
      "[E] Centrale\nSans courant",
      "[U] Fonderie\nNiv. 3 · 2/s",
    ]);
    // L'état n'est jamais porté par la couleur seule : un repère en texte précède chaque nom.
    assert.ok(screen.spec.buttons[0]?.includes(COLOR.blocked));
    assert.ok(screen.spec.buttons[2]?.includes(COLOR.incident));
    assert.ok(screen.spec.buttons[4]?.includes(COLOR.success));
  });
});

describe("fiche d'une usine active", () => {
  const view = viewOf({
    factories: [factory({ factoryId: "mine-profonde", level: 12, ratePerHour: 36_000, nextCost: 1_250, nextMilestone: 25 })],
  });
  const screen = ficheScreen(view, "mine-profonde", 1);

  it("suit l'ordre de la fiche : situation, nom, phrase, puis trois infos tirées des champs du service", () => {
    assert.ok(screen);
    assert.equal(screen.spec.title, FICHE_TEXTES.titre);
    assert.deepEqual(bare(screen.spec.body).split("\n"), [
      "ACTIVE",
      "Mine profonde",
      FICHE_TEXTES.phrase.ACTIVE,
      "",
      `Niveau ${formatCount(12)}`,
      `Revenu ${formatRate(36_000)}`,
      `Palier ×2 au niveau ${formatCount(25)}`,
    ]);
  });

  it("quatre boutons de montée dans l'ordre du contrat, puis Retour vers la bonne page", () => {
    assert.ok(screen);
    assert.deepEqual(
      screen.targets.map((t) => (t.kind === "buy" ? t.amount : t.kind)),
      ["1", "10", "palier", "max", "go"],
    );
    assert.deepEqual(screen.targets[4], { kind: "go", route: { screen: "usines", page: 1 } });
    assert.deepEqual(screen.spec.buttons.map((b) => bare(b).split("\n")[0]), [
      FICHE_TEXTES.monter["1"],
      FICHE_TEXTES.monter["10"],
      FICHE_TEXTES.monter.palier,
      FICHE_TEXTES.monter.max,
      FICHE_TEXTES.retour,
    ]);
  });

  it("le coût du service est sur le bouton « de 1 » ; les autres n'affichent aucun prix, le contrat n'en donne pas", () => {
    assert.ok(screen);
    assert.equal(bare(screen.spec.buttons[0] ?? ""), `${FICHE_TEXTES.monter["1"]}\n${FICHE_TEXTES.cout(formatAmount(1_250))}`);
    for (const index of [1, 2, 3]) assert.doesNotMatch(screen.spec.buttons[index] ?? "", /\n|Donar/);
  });

  it("chaque bouton vise la bonne usine", () => {
    assert.ok(screen);
    for (const target of screen.targets.filter((t) => t.kind === "buy")) {
      assert.equal(target.kind === "buy" && target.factoryId, "mine-profonde");
    }
  });

  it("une usine inconnue du service : pas de fiche", () => {
    assert.equal(ficheScreen(view, "n-existe-pas", 0), undefined);
  });

  it("un incident garde la montée possible et change la situation et la phrase", () => {
    const incident = ficheScreen(viewOf({ factories: [factory({ incidentId: 9 })] }), "carriere", 0);
    assert.match(bare(incident?.spec.body ?? ""), new RegExp(`^${FICHE_TEXTES.sorte.INCIDENT}\\nCarrière\\n${FICHE_TEXTES.phrase.INCIDENT}`));
    assert.equal(incident?.targets.filter((t) => t.kind === "buy").length, 4);
    const sansCourant = ficheScreen(viewOf({ factories: [factory({ stoppedNoPower: true })] }), "carriere", 0);
    assert.match(bare(sansCourant?.spec.body ?? ""), new RegExp(FICHE_TEXTES.sorte.SANS_COURANT));
  });

  it("une bulle d'Oooscar passe en tête", () => {
    const withNotice = ficheScreen(view, "mine-profonde", 0, { bubble: BULLES.montee });
    assert.match(bare(withNotice?.spec.body ?? ""), /^Oooscar \[joie\] Usine améliorée !\n\nACTIVE\nMine profonde/);
  });
});

describe("fiche d'une usine en ruine ou en remise en état", () => {
  const step = (restore: Partial<Factory>) =>
    ficheScreen(viewOf({ factories: [factory({ factoryId: "scierie", ...restore })] }), "scierie", 0);

  it("une ruine propose la première étape, par un seul verbe", () => {
    const screen = step({ state: "EN_RUINE", level: 0 });
    assert.deepEqual(screen?.spec.buttons.map(bare), [FICHE_TEXTES.etapes[0].bouton, FICHE_TEXTES.retour]);
    assert.deepEqual(screen?.targets[0], { kind: "restore", factoryId: "scierie", step: 1 });
    assert.match(bare(screen?.spec.body ?? ""), /EN RUINE\nScierie\n.*\n\nÉtape 1 sur 4 : Dialogue$/);
  });

  it("une remise en état propose l'étape qui suit la dernière faite", () => {
    const screen = step({ state: "REMISE_EN_ETAT", restoreStep: 2 });
    assert.deepEqual(screen?.spec.buttons.map(bare), ["Livrer", FICHE_TEXTES.retour]);
    assert.deepEqual(screen?.targets[0], { kind: "restore", factoryId: "scierie", step: 3 });
    assert.match(bare(screen?.spec.body ?? ""), /Étape 3 sur 4 : Livraison$/);
  });

  it("les quatre étapes ont chacune leur verbe", () => {
    const verbs = [1, 2, 3, 4].map((done) => {
      const screen = step({ state: "REMISE_EN_ETAT", restoreStep: done - 1 });
      return bare(screen?.spec.buttons[0] ?? "");
    });
    assert.deepEqual(verbs, ["Parler", "Réparer", "Livrer", "Recruter"]);
  });

  it("aucun bouton de montée, aucun niveau, aucun revenu", () => {
    const screen = step({ state: "EN_RUINE", level: 0, ratePerHour: 0 });
    assert.ok(screen);
    assert.ok(!screen.targets.some((t) => t.kind === "buy"));
    assert.doesNotMatch(bare(screen.spec.body), /Niveau|Revenu|Palier/);
  });

  it("quatre étapes faites mais encore en remise en état : rien à demander, seul Retour reste", () => {
    const screen = step({ state: "REMISE_EN_ETAT", restoreStep: 4 });
    assert.deepEqual(screen?.spec.buttons.map(bare), [FICHE_TEXTES.retour]);
  });
});

describe("écran du Silo", () => {
  it("montre le compteur à l'instant demandé, le revenu et le multiplicateur du service", () => {
    const view = viewOf({ donar: 1_000, ratePerHour: 3_600, multiplier: 2.5 });
    const at = (nowMs: number) => bare(siloScreen(view, nowMs).spec.body);
    assert.deepEqual(at(0).split("\n"), [
      SILO_TEXTES.phrase,
      "",
      SILO_TEXTES.donar(formatAmount(1_000)),
      SILO_TEXTES.revenu(formatRate(3_600)),
      SILO_TEXTES.multiplicateur("×2,5"),
    ]);
    assert.match(at(60_000), new RegExp(SILO_TEXTES.donar(formatAmount(counterValue(view.anchor, 60_000)))));
    assert.notEqual(at(60_000), at(0));
  });

  it("deux boutons : Encaisser, puis Retour vers le menu", () => {
    const screen = siloScreen(viewOf(), 0);
    assert.deepEqual(screen.spec.buttons, [SILO_TEXTES.encaisser, SILO_TEXTES.retour]);
    assert.deepEqual(screen.targets, [{ kind: "collect" }, { kind: "go", route: { screen: "main" } }]);
  });

  it("la bulle et les chiffres de l'encaissement passent avant le reste", () => {
    const notice = noticeOf({ kind: "collected", collected: 1_500, offlineHours: 0 });
    const body = bare(siloScreen(viewOf(), 0, notice).spec.body);
    assert.ok(body.startsWith(`Oooscar [joie] Production encaissée !\n${SILO_TEXTES.encaisse(formatAmount(1_500))}\n\nEncaisse pour`));
  });
});

describe("noticeOf", () => {
  const n = (outcome: ActionOutcome) => noticeOf(outcome);

  it("un encaissement : la bulle, les Donar reçus et, hors ligne, la durée", () => {
    assert.deepEqual(n({ kind: "collected", collected: 1_500, offlineHours: 0 }), {
      bubble: BULLES.collecte,
      lines: [SILO_TEXTES.encaisse(formatAmount(1_500))],
    });
    assert.deepEqual(n({ kind: "collected", collected: 90_000, offlineHours: 8 }), {
      bubble: BULLES.collecteHorsLigne,
      lines: [SILO_TEXTES.encaisse(formatAmount(90_000)), "Hors ligne 8 h"],
    });
  });

  it("une montée et une étape réussies", () => {
    assert.deepEqual(n({ kind: "levelsBought" }), { bubble: BULLES.montee });
    assert.deepEqual(n({ kind: "stepDone" }), { bubble: BULLES.etape });
  });

  it("un refus du jeu : la bulle du code, avec la somme qui manque", () => {
    assert.equal(n({ kind: "refused", reason: "PAS_ASSEZ_DE_DONAR", missing: 120 })?.bubble.text, "Il te manque 120 Donar.");
    assert.equal(n({ kind: "refused", reason: "PAS_LE_PDG" })?.bubble.text, "Seul le PDG peut le faire.");
    assert.equal(n({ kind: "refused", reason: "CODE_DU_FUTUR" })?.bubble, BULLES.refusInconnu);
  });

  it("un échec technique : service injoignable, route pas encore écrite, panne du service", () => {
    assert.equal(n({ kind: "failed", failure: "network", reason: "RESEAU" })?.bubble, BULLES.serviceInjoignable);
    assert.equal(n({ kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" })?.bubble, BULLES.serviceInjoignable);
    assert.equal(n({ kind: "failed", failure: "error", reason: "PAS_FAIT" })?.bubble, BULLES.bientot);
    assert.equal(n({ kind: "failed", failure: "error", reason: "ERREUR_INTERNE" })?.bubble, BULLES.problemeService);
  });

  it("une action ignorée parce qu'une autre tourne : pas de bulle", () => {
    assert.equal(n({ kind: "busy" }), undefined);
  });
});

describe("écran sans réponse du service", () => {
  it("une bulle de panique et un seul bouton qui ferme", () => {
    const screen = unavailableScreen();
    assert.match(bare(screen.spec.body), /Oooscar \[panique\] Le service ne répond pas\./);
    assert.deepEqual(screen.targets, [{ kind: "end" }]);
  });
});

// ---------------------------------------------------------------- le parcours

const ACTIVE = factory({ factoryId: "carriere", level: 3, ratePerHour: 7_200, nextCost: 150, nextMilestone: 25 });

function after(level: number, version = 2, extra: Partial<Factory> = {}): CompanyUpdated {
  return {
    state: companyState({ version, donar: 40, factories: [factory({ factoryId: "carriere", level, nextCost: 200, ...extra })] }),
    balances: balances(),
  };
}

/** Le menu tel que main.ts le monte : les missions relues après chaque encaissement, et à l'ouverture des objectifs. */
type Travel = NonNullable<MenuDeps<typeof PLAYER>["travel"]>;

function setup(
  replies: readonly Reply[],
  script: Script = {},
  state: Partial<CompanyState> = { factories: [ACTIVE] },
  missionAnswers: Parameters<typeof fakeMissionsApi>[0] = undefined,
  extras: { readonly travel?: Travel; readonly tilesPerScreen?: number } = {},
) {
  const clock = fakeClock(0);
  const store = storeWith(state);
  const views = createViewStore({ players: store, clock });
  const api = fakeCompany(script);
  const missionsApi = fakeMissionsApi(missionAnswers);
  const log = recordingLogger();
  const missions = createMissions({ api: missionsApi, players: store, say: () => undefined, log });
  const actions = createActions({ api, views, newRequestId: sequentialIds(), log, afterCollect: missions.refresh });
  const presenter = fakePresenter(replies);
  const waits: number[] = [];
  const menu = createMenu({
    views,
    missions,
    actions,
    presenter,
    clock,
    wait: async (seconds) => void waits.push(seconds),
    log,
    tilesPerScreen: extras.tilesPerScreen ?? 8,
    busyRetries: 3,
    busyRetrySeconds: 0.5,
    ...(extras.travel === undefined ? {} : { travel: extras.travel }),
  });
  return { menu, presenter, api, views, log, waits, store, missions, missionsApi };
}

describe("parcours : menu, Usines, fiche, montée", () => {
  it("succès : la fiche revient avec le nouveau niveau et la bulle de joie", async () => {
    const { menu, presenter, api } = setup(
      [press("Usines"), press("Carrière"), press("Monter de 1"), close],
      { buyLevels: [ok(after(4))] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "USINES", "USINE", "USINE"]);
    assert.match(bare(presenter.shown[2]?.body ?? ""), /Niveau 3/);
    assert.match(bare(presenter.shown[3]?.body ?? ""), /^Oooscar \[joie\] Usine améliorée !\n\nACTIVE\nCarrière/);
    assert.match(bare(presenter.shown[3]?.body ?? ""), /Niveau 4/);
    assert.deepEqual(api.calls, [
      { op: "buyLevels", companyId: 7, factoryId: "carriere", action: { requestId: "id-1", actor: XUID }, amount: "1" },
    ]);
  });

  it("chaque bouton de montée envoie sa quantité", async () => {
    const { menu, api } = setup(
      [
        press("Usines"),
        press("Carrière"),
        press("Monter de 10"),
        press("Monter au palier"),
        press("Monter au max"),
        close,
      ],
      { buyLevels: [ok(after(13, 2)), ok(after(25, 3)), ok(after(40, 4))] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(
      api.calls.map((call) => (call.op === "buyLevels" ? call.amount : call.op)),
      ["10", "palier", "max"],
    );
    assert.deepEqual(new Set(api.calls.map((call) => ("action" in call ? call.action.requestId : ""))).size, 3);
  });

  it("refus PAS_ASSEZ_DE_DONAR : la fiche revient inchangée avec ce qui manque", async () => {
    const { menu, presenter, views } = setup(
      [press("Usines"), press("Carrière"), press("Monter de 1"), close],
      { buyLevels: [refused("PAS_ASSEZ_DE_DONAR", 120)] },
    );
    await menu.open(PLAYER);
    assert.match(bare(presenter.shown[3]?.body ?? ""), /^Oooscar \[oups\] Il te manque 120 Donar\.\n\nACTIVE/);
    assert.match(bare(presenter.shown[3]?.body ?? ""), /Niveau 3/);
    assert.equal(views.get(XUID)?.state.version, 1);
  });

  it("CONFLIT_REESSAYER : l'état est rechargé, l'action rejouée avec le même requestId, le joueur ne voit que le succès", async () => {
    const { menu, presenter, api } = setup(
      [press("Usines"), press("Carrière"), press("Monter de 1"), close],
      {
        buyLevels: [refused("CONFLIT_REESSAYER"), ok(after(4, 6))],
        getCompany: [ok(companyState({ version: 5, factories: [ACTIVE] }))],
      },
    );
    await menu.open(PLAYER);
    assert.deepEqual(
      api.calls.map((call) => call.op),
      ["buyLevels", "getCompany", "buyLevels"],
    );
    const ids = api.calls.flatMap((call) => (call.op === "buyLevels" ? [call.action.requestId] : []));
    assert.equal(ids[0], ids[1]);
    assert.match(bare(presenter.shown[3]?.body ?? ""), /Usine améliorée !/);
    assert.equal(presenter.shown.length, 4);
  });

  it("service injoignable : la fiche revient avec la panique d'Oooscar", async () => {
    const { menu, presenter } = setup(
      [press("Usines"), press("Carrière"), press("Monter de 1"), close],
      { buyLevels: [unreachable()] },
    );
    await menu.open(PLAYER);
    assert.match(bare(presenter.shown[3]?.body ?? ""), /^Oooscar \[panique\] Le service ne répond pas\./);
  });

  it("route pas encore écrite : « Pas encore disponible »", async () => {
    const { menu, presenter } = setup(
      [press("Usines"), press("Carrière"), press("Monter de 1"), close],
      { buyLevels: [failure(501, "PAS_FAIT")] },
    );
    await menu.open(PLAYER);
    assert.match(bare(presenter.shown[3]?.body ?? ""), /\[oups\] Pas encore disponible\./);
  });

  it("Retour remonte d'un écran à la fois, jusqu'au menu", async () => {
    const { menu, presenter } = setup([
      press("Usines"),
      press("Carrière"),
      press("Retour"),
      press("Retour"),
      close,
    ]);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "USINES", "USINE", "USINES", "MENU"]);
  });

  it("Retour de la fiche revient sur la page des usines d'où l'on vient", async () => {
    const fifth = FACTORY_NAMES[IDS[4] as keyof typeof FACTORY_NAMES];
    const { menu, presenter } = setupSmallPages([press("Usines"), press("Suite"), press(fifth), press("Retour"), close]);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "USINES", "USINES", "USINE", "USINES"]);
    assert.match(bare(presenter.shown[4]?.body ?? ""), /Page 2 sur 3/);
  });
});

function setupSmallPages(replies: readonly Reply[]) {
  const clock = fakeClock(0);
  const store = storeWith({ factories: many(10) });
  const views = createViewStore({ players: store, clock });
  const log = recordingLogger();
  const presenter = fakePresenter(replies);
  const actions = createActions({ api: fakeCompany(), views, newRequestId: sequentialIds(), log });
  const missions = createMissions({ api: fakeMissionsApi(), players: store, say: () => undefined, log });
  const menu = createMenu({ views, missions, actions, presenter, clock, wait: async () => undefined, log, tilesPerScreen: 4 });
  return { menu, presenter };
}

describe("parcours : remise en état", () => {
  const RUINE = { factories: [factory({ factoryId: "carriere", state: "EN_RUINE" as const, level: 0, ratePerHour: 0 })] };

  it("une ruine : le bouton Parler envoie l'étape 1, la fiche montre ensuite l'étape 2", async () => {
    const { menu, presenter, api } = setup(
      [press("Usines"), press("Carrière"), press("Parler"), close],
      {
        restoreStep: [
          ok({
            state: companyState({
              version: 2,
              factories: [factory({ factoryId: "carriere", state: "REMISE_EN_ETAT", restoreStep: 1, level: 0 })],
            }),
            balances: balances(),
          }),
        ],
      },
      RUINE,
    );
    await menu.open(PLAYER);
    assert.deepEqual(api.calls, [
      { op: "restoreStep", companyId: 7, factoryId: "carriere", action: { requestId: "id-1", actor: XUID }, step: 1 },
    ]);
    const last = bare(presenter.shown[3]?.body ?? "");
    assert.match(last, /^Oooscar \[joie\] Étape terminée !/);
    assert.match(last, /REMISE EN ÉTAT/);
    assert.match(last, /Étape 2 sur 4 : Tâches$/);
    assert.ok(presenter.shown[3]?.buttons.map(bare).includes("Réparer"));
  });

  it("quand l'usine devient active, les boutons de montée apparaissent", async () => {
    const { menu, presenter } = setup(
      [press("Usines"), press("Carrière"), press("Recruter"), close],
      { restoreStep: [ok(after(1, 5))] },
      { factories: [factory({ factoryId: "carriere", state: "REMISE_EN_ETAT", restoreStep: 3, level: 0 })] },
    );
    await menu.open(PLAYER);
    assert.ok(presenter.shown[3]?.buttons.map(bare).some((b) => b.startsWith("Monter de 1")));
  });
});

describe("parcours : Silo et encaissement", () => {
  it("Silo puis Encaisser : l'écran revient avec la bulle et les Donar reçus", async () => {
    const { menu, presenter, api } = setup(
      [press("Silo"), press("Encaisser"), close],
      { collect: [ok({ collected: 1_500, offlineHours: 0, state: companyState({ version: 2, donar: 1_540 }) })] },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "SILO", "SILO"]);
    const body = bare(presenter.shown[2]?.body ?? "");
    assert.ok(body.startsWith(`Oooscar [joie] Production encaissée !\n${SILO_TEXTES.encaisse(formatAmount(1_500))}\n\n`));
    assert.deepEqual(api.calls, [{ op: "collect", companyId: 7, action: { requestId: "id-1", actor: XUID } }]);
  });

  it("deux touches depuis le menu principal, pas une de plus avant l'appel au service", async () => {
    const { menu, presenter, api } = setup(
      [press("Silo"), press("Encaisser"), close],
      { collect: [ok({ collected: 1, offlineHours: 0, state: companyState({ version: 2 }) })] },
    );
    const original = api.collect.bind(api);
    let screensSeen = -1;
    api.collect = (companyId, action) => {
      screensSeen = presenter.shown.length;
      return original(companyId, action);
    };
    await menu.open(PLAYER);
    assert.equal(screensSeen, 2);
  });

  it("l'encaissement d'une absence : Oooscar dort et dit la durée", async () => {
    const { menu, presenter } = setup(
      [press("Silo"), press("Encaisser"), close],
      { collect: [ok({ collected: 90_000, offlineHours: 8, state: companyState({ version: 2 }) })] },
    );
    await menu.open(PLAYER);
    const body = bare(presenter.shown[2]?.body ?? "");
    assert.match(body, /\[dodo\] Gagné pendant ton absence\./);
    assert.match(body, /Hors ligne 8 h/);
  });

  it("Retour du Silo ramène au menu", async () => {
    const { menu, presenter } = setup([press("Silo"), press("Retour"), close]);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "SILO", "MENU"]);
  });
});

describe("parcours : tuiles verrouillées", () => {
  it("Amis : le menu reste, Oooscar dit que ce n'est pas encore disponible, aucun appel au service", async () => {
    const { menu, presenter, api } = setup([press("Amis"), close]);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "MENU"]);
    assert.match(bare(presenter.shown[1]?.body ?? ""), /^Oooscar \[oups\] Pas encore disponible\./);
    assert.deepEqual(api.calls, []);
  });

  it("la bulle ne reste qu'un écran", async () => {
    const { menu, presenter } = setup([press("Amis"), press("Usines"), press("Retour"), close]);
    await menu.open(PLAYER);
    assert.doesNotMatch(bare(presenter.shown[3]?.body ?? ""), /Oooscar/);
  });
});

describe("parcours : fermeture, écran occupé, joueur parti", () => {
  it("fermer un écran ferme le menu", async () => {
    const { menu, presenter } = setup([press("Usines"), close]);
    await menu.open(PLAYER);
    assert.equal(presenter.shown.length, 2);
  });

  it("un bouton qui n'existe pas ferme le menu sans bruit", async () => {
    const { menu, log } = setup([() => ({ kind: "selected", index: 99 })]);
    await menu.open(PLAYER);
    assert.deepEqual(log.all(), []);
  });

  it("écran occupé (le tchat est ouvert) : réessaie après la pause réglée, puis montre l'écran", async () => {
    const { menu, presenter, waits } = setup([busy, busy, press("Usines"), close]);
    await menu.open(PLAYER);
    assert.deepEqual(waits, [0.5, 0.5]);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "MENU", "MENU", "USINES"]);
  });

  it("écran occupé trop longtemps : abandonne après les essais réglés, le dit au journal, ne lève pas", async () => {
    const { menu, presenter, waits, log } = setup([busy, busy, busy, busy]);
    await menu.open(PLAYER);
    assert.equal(presenter.shown.length, 4);
    assert.equal(waits.length, 3);
    assert.match(log.infos.join("\n"), /menu de Alex : écran occupé, abandon/);
  });

  it("un joueur sans réponse de join voit « le service ne répond pas » et rien d'autre", async () => {
    const { menu, presenter, api } = setup([press("Retour")]);
    await menu.open({ name: "Inconnu" });
    assert.equal(presenter.shown.length, 1);
    assert.match(bare(presenter.shown[0]?.body ?? ""), /Le service ne répond pas\./);
    assert.deepEqual(api.calls, []);
  });

  it("le présentateur qui lève (joueur parti) est journalisé, rien ne remonte, le menu peut se rouvrir", async () => {
    const { menu, presenter, log } = setup([
      () => {
        throw new Error("joueur parti");
      },
      close,
    ]);
    await menu.open(PLAYER);
    assert.match(log.warns.join("\n"), /menu de Alex : Error: joueur parti/);
    await menu.open(PLAYER);
    assert.equal(presenter.shown.length, 2);
  });

  it("un menu déjà ouvert pour le joueur : le second appel est ignoré", async () => {
    let release: (() => void) | undefined;
    const { menu, presenter } = setup([
      () =>
        new Promise((resolve) => {
          release = () => resolve({ kind: "closed" });
        }),
    ]);
    const first = menu.open(PLAYER);
    await menu.open(PLAYER);
    assert.equal(presenter.shown.length, 1);
    release?.();
    await first;
  });

  it("l'usine disparaît côté service pendant le parcours : retour à la liste, la bulle est gardée", async () => {
    const { menu, presenter } = setup(
      [press("Usines"), press("Carrière"), press("Monter de 1"), close],
      { buyLevels: [ok({ state: companyState({ version: 2, factories: [] }), balances: balances() })] },
    );
    await menu.open(PLAYER);
    assert.equal(presenter.shown[3]?.title, "USINES");
    assert.match(bare(presenter.shown[3]?.body ?? ""), /Usine améliorée !/);
  });

  it("le texte brut de chaque écran montré ne contient ni emoji ni point de suspension", async () => {
    const { menu, presenter } = setup(
      [press("Silo"), press("Retour"), press("Usines"), press("Carrière"), close],
    );
    await menu.open(PLAYER);
    for (const spec of presenter.shown) {
      assert.doesNotMatch(plain(spec), /\p{Extended_Pictographic}|\.\.\.|…/u);
    }
  });
});

// ---------------------------------------------------------------- les objectifs

describe("menu principal, tuile Objectifs", () => {
  const tile = (missions: MissionState | undefined): string => bare(mainScreen(viewOf(), missions).spec.buttons[3] ?? "");

  it("est active : une icône, le nom du jeu, et la ligne d'info de la mission en cours telle que le service la donne", () => {
    assert.equal(tile(missionState()), "[O] Objectifs\nMission 0/1");
    assert.equal(tile(missionState({ current: mission({ progress: 3, target: 12 }) })), "[O] Objectifs\nMission 3/12");
    assert.equal(
      tile(missionState({ current: mission({ progress: 1_250, target: 20_000 }) })),
      `[O] Objectifs\nMission ${formatCount(1_250)}/${formatCount(20_000)}`,
    );
  });

  it("n'a rien de verrouillé : ni cadenas, ni gris, ni « Bientôt »", () => {
    const text = mainScreen(viewOf(), missionState()).spec.buttons[3] ?? "";
    assert.ok(!text.startsWith(COLOR.locked));
    assert.ok(!bare(text).includes("[X]"));
    assert.ok(!bare(text).includes(MENU_TEXTES.bientot));
  });

  it("toutes les missions terminées : le service n'en donne plus, la tuile le dit", () => {
    assert.equal(tile({ current: null, completed: ["bienvenue-mairie"] }), "[O] Objectifs\nPlus de mission");
  });

  it("rien n'a pu être lu : la tuile reste active, sans ligne d'info inventée", () => {
    assert.equal(tile(undefined), "[O] Objectifs");
    const screen = mainScreen(viewOf(), undefined);
    assert.deepEqual(screen.targets[3], { kind: "go", route: { screen: "objectifs" } });
  });

  it("la ligne d'info est en gris, comme celle des autres tuiles", () => {
    const text = mainScreen(viewOf(), missionState()).spec.buttons[3] ?? "";
    assert.ok(text.includes(`${COLOR.muted}Mission 0/1`), text);
  });
});

describe("écran des objectifs", () => {
  const body = (missions: MissionState | undefined, notice?: Parameters<typeof objectifsScreen>[1]): string =>
    bare(objectifsScreen(missions, notice).spec.body);

  it("la mission en cours : titre, consigne, avancement et récompense du service, puis les missions terminées", () => {
    const screen = objectifsScreen(missionState());
    assert.equal(screen.spec.title, OBJECTIFS_TEXTES.titre);
    assert.equal(
      bare(screen.spec.body),
      [
        "EN COURS",
        "Bienvenue à la mairie",
        "Encaisse au Silo",
        "Avancement 0/1",
        "Récompense 10 Lingots",
        "",
        "TERMINÉES",
        "Aucune mission terminée.",
      ].join("\n"),
    );
  });

  it("un seul bouton, Retour, qui ramène au menu", () => {
    const screen = objectifsScreen(missionState());
    assert.deepEqual(screen.spec.buttons, [OBJECTIFS_TEXTES.retour]);
    assert.deepEqual(screen.targets, [{ kind: "go", route: { screen: "main" } }]);
  });

  it("l'avancement et la récompense sont ceux du service, mis en forme et jamais recalculés", () => {
    const text = body(missionState({ current: mission({ progress: 2, target: 5, rewardLingots: 1_250 }) }));
    assert.match(text, /^Avancement 2\/5$/m);
    assert.ok(text.split("\n").includes(`Récompense ${formatCount(1_250)} Lingots`), text);
    const autre = body(missionState({ current: mission({ progress: 0, target: 1, rewardLingots: 0 }) }));
    assert.match(autre, /^Récompense 0 Lingots$/m);
  });

  it("la récompense a la couleur des Lingots, le titre est en gras", () => {
    const brut = objectifsScreen(missionState()).spec.body;
    assert.ok(brut.includes(`${COLOR.lingots}Récompense 10 Lingots${COLOR.reset}`), brut);
    assert.ok(brut.includes(`${COLOR.bold}Bienvenue à la mairie${COLOR.reset}`), brut);
  });

  it("les missions terminées sont listées par leur titre, dans l'ordre du service, avec un repère de réussite", () => {
    const text = objectifsScreen({ current: null, completed: ["bienvenue-mairie", "mission-du-futur"] }).spec.body;
    const lignes = bare(text).split("\n");
    assert.deepEqual(lignes.slice(-2), ["[O] Bienvenue à la mairie", "[O] Mission du futur"]);
    assert.ok(text.includes(`${COLOR.success}[O]${COLOR.reset} Bienvenue à la mairie`), text);
    assert.ok(!text.includes(OBJECTIFS_TEXTES.aucuneTerminee));
  });

  it("plus de mission en cours : le service le dit, l'écran ne montre ni consigne ni récompense", () => {
    const text = body({ current: null, completed: ["bienvenue-mairie"] });
    assert.ok(text.startsWith(OBJECTIFS_TEXTES.plusDeMission));
    assert.ok(!text.includes("EN COURS"));
    assert.ok(!text.includes("Récompense"));
    assert.ok(!text.includes("Avancement"));
  });

  it("une mission que cet add-on ne connaît pas : un titre lisible tiré de l'identifiant, pas de consigne inventée", () => {
    const text = body(missionState({ current: mission({ id: "visite-du-port", progress: 1, target: 4, rewardLingots: 7 }) }));
    assert.equal(text.split("\n")[1], "Visite du port");
    assert.equal(text.split("\n")[2], "Avancement 1/4");
    assert.match(text, /^Récompense 7 Lingots$/m);
  });

  it("rien n'a pu être lu : Oooscar panique, aucun chiffre n'est inventé", () => {
    const text = body(undefined);
    assert.equal(text, "Oooscar [panique] Le service ne répond pas.");
    assert.doesNotMatch(text, /\d/);
  });

  it("une bulle d'Oooscar passe avant les lignes de l'écran", () => {
    assert.match(body(missionState(), { bubble: BULLES.bientot }), /^Oooscar \[oups\] Pas encore disponible\.\n\nEN COURS\n/);
  });

  it("chaque mission des textes a un titre et une consigne qui s'affichent", () => {
    for (const [id, textes] of Object.entries(MISSION_TEXTES)) {
      const text = body(missionState({ current: mission({ id }) }));
      assert.ok(text.includes(textes.titre), id);
      assert.ok(text.includes(textes.consigne), id);
    }
  });
});

describe("parcours : objectifs", () => {
  it("Objectifs lit les missions au service avant de montrer l'écran, et Retour revient au menu", async () => {
    const { menu, presenter, missionsApi } = setup([press("Objectifs"), press("Retour"), close]);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "OBJECTIFS", "MENU"]);
    assert.deepEqual(missionsApi.calls, [XUID]);
    assert.match(bare(presenter.shown[1]?.body ?? ""), /^EN COURS\nBienvenue à la mairie\nEncaisse au Silo\nAvancement 0\/1\nRécompense 10 Lingots/);
  });

  it("la tuile du menu montre la lecture faite en ouvrant les objectifs", async () => {
    const { menu, presenter } = setup([press("Objectifs"), press("Retour"), close]);
    await menu.open(PLAYER);
    assert.equal(bare(presenter.shown[0]?.buttons[3] ?? ""), "[O] Objectifs");
    assert.equal(bare(presenter.shown[2]?.buttons[3] ?? ""), "[O] Objectifs\nMission 0/1");
  });

  it("chaque ouverture relit : l'avancement du service est celui de la dernière lecture", async () => {
    const { menu, presenter, missionsApi } = setup(
      [press("Objectifs"), press("Retour"), press("Objectifs"), close],
      {},
      undefined,
      [ok(missionState({ current: mission({ progress: 1, target: 3 }) })), ok(missionState({ current: mission({ progress: 2, target: 3 }) }))],
    );
    await menu.open(PLAYER);
    assert.equal(missionsApi.calls.length, 2);
    assert.match(bare(presenter.shown[1]?.body ?? ""), /Avancement 1\/3/);
    assert.match(bare(presenter.shown[3]?.body ?? ""), /Avancement 2\/3/);
  });

  it("le service ne répond pas et rien n'était connu : Oooscar panique, le menu reste utilisable", async () => {
    const { menu, presenter, log } = setup([press("Objectifs"), press("Retour"), close], {}, undefined, [unreachable()]);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "OBJECTIFS", "MENU"]);
    assert.equal(bare(presenter.shown[1]?.body ?? ""), "Oooscar [panique] Le service ne répond pas.");
    assert.match(log.warns.join("\n"), /missions de .* : /);
  });

  it("le service ne répond plus mais une lecture existe : l'écran garde la dernière lecture", async () => {
    const { menu, presenter } = setup(
      [press("Objectifs"), press("Retour"), press("Objectifs"), close],
      {},
      undefined,
      [ok(missionState({ current: mission({ progress: 1, target: 3 }) })), unreachable()],
    );
    await menu.open(PLAYER);
    assert.match(bare(presenter.shown[3]?.body ?? ""), /Avancement 1\/3/);
  });

  it("un encaissement relit les missions, et la tuile le montre sans que l'add-on dise « mission faite »", async () => {
    const { menu, presenter, missionsApi } = setup(
      [press("Silo"), press("Encaisser"), press("Retour"), close],
      { collect: [ok({ collected: 1_500, offlineHours: 0, state: companyState({ version: 2, donar: 1_540 }) })] },
      undefined,
      [ok({ current: null, completed: ["bienvenue-mairie"] })],
    );
    await menu.open(PLAYER);
    assert.deepEqual(missionsApi.calls, [XUID]);
    const ecrans = presenter.shown.map((spec) => bare(`${spec.title}\n${spec.body}\n${spec.buttons.join("\n")}`));
    assert.ok(ecrans[3]?.includes("[O] Objectifs\nPlus de mission"), ecrans[3]);
    for (const texte of ecrans) assert.doesNotMatch(texte, /mission (faite|terminée|accomplie)/i);
    assert.match(bare(presenter.shown[2]?.body ?? ""), /^Oooscar \[joie\] Production encaissée !/);
  });

  it("un encaissement qui n'aboutit pas ne relit rien", async () => {
    const { menu, missionsApi } = setup([press("Silo"), press("Encaisser"), close], { collect: [unreachable()] });
    await menu.open(PLAYER);
    assert.deepEqual(missionsApi.calls, []);
  });
});

// ---------------------------------------------------------------- la carte

function viewWith(state: Partial<CompanyState> = {}, soldes: Partial<Balances> = {}): CompanyView {
  const view = viewsWith(state, fakeClock(0), soldes).views.get(XUID);
  if (view === undefined) throw new Error("vue absente");
  return view;
}

describe("menu principal, tuiles Carte et Prestige", () => {
  const screen = mainScreen(viewOf({ factories: many(3), courseNumber: 4 }), missionState());

  it("la tuile Carte est active : le nombre d'îles est celui des usines de l'entreprise", () => {
    assert.equal(bare(screen.spec.buttons[2] ?? ""), "[C] Carte\n3 îles");
    assert.equal(bare(mainScreen(viewOf({ factories: many(1) }), undefined).spec.buttons[2] ?? ""), "[C] Carte\n1 île");
    assert.deepEqual(screen.targets[2], { kind: "go", route: { screen: "carte", page: 0 } });
  });

  it("la tuile Prestige est active : le numéro de la course est celui du service", () => {
    assert.equal(bare(screen.spec.buttons[4] ?? ""), "[P] Prestige\nCourse 4");
    assert.deepEqual(screen.targets[4], { kind: "go", route: { screen: "prestige" } });
  });

  it("ni l'une ni l'autre n'est grisée ni marquée « Bientôt »", () => {
    for (const index of [2, 4]) {
      const text = screen.spec.buttons[index] ?? "";
      assert.ok(!text.startsWith(COLOR.locked), text);
      assert.ok(!bare(text).includes(MENU_TEXTES.bientot), text);
      assert.ok(!bare(text).includes("[X]"), text);
    }
  });
});

describe("écran de la carte", () => {
  const view = viewOf({
    factories: [
      factory({ factoryId: "carriere", level: 12 }),
      factory({ factoryId: "scierie", state: "REMISE_EN_ETAT", level: 0, restoreStep: 2 }),
      factory({ factoryId: "ferme", state: "EN_RUINE", level: 0, ratePerHour: 0 }),
    ],
  });
  const screen = carteScreen(view, 0, 20);

  it("la phrase, puis l'île principale verrouillée, une ligne par usine dans l'ordre du jeu, et Retour", () => {
    assert.equal(screen.spec.title, CARTE_TEXTES.titre);
    assert.equal(screen.spec.body, CARTE_TEXTES.phrase);
    assert.equal(screen.spec.buttons.length, 1 + FACTORY_IDS.length + 1);
    assert.equal(bare(screen.spec.buttons.at(-1) ?? ""), CARTE_TEXTES.retour);
    assert.deepEqual(screen.targets.at(-1), { kind: "go", route: { screen: "main" } });
  });

  it("l'île principale n'est pas construite : cadenas, gris, « Bientôt », et Oooscar dit « Pas encore disponible »", () => {
    const text = screen.spec.buttons[0] ?? "";
    assert.ok(text.startsWith(COLOR.locked), text);
    assert.equal(bare(text), `[X] ${CARTE_TEXTES.ilePrincipale}\n${MENU_TEXTES.bientot}`);
    assert.deepEqual(screen.targets[0], { kind: "soon" });
  });

  it("une usine de l'entreprise : son nom et son état d'après le service, et la route de son île", () => {
    assert.equal(bare(screen.spec.buttons[1] ?? ""), `[U] Carrière\n${CARTE_TEXTES.etat.entiere("12")}`);
    assert.equal(bare(screen.spec.buttons[2] ?? ""), `[T] Scierie\n${CARTE_TEXTES.etat.enTravaux(2, 4)}`);
    assert.equal(bare(screen.spec.buttons[3] ?? ""), `[X] Ferme\n${CARTE_TEXTES.etat.enRuine}`);
    assert.deepEqual(screen.targets[1], { kind: "travel", factoryId: "carriere" });
    assert.deepEqual(screen.targets[2], { kind: "travel", factoryId: "scierie" });
    assert.deepEqual(screen.targets[3], { kind: "travel", factoryId: "ferme" });
  });

  it("une usine que le joueur n'a pas : « ????? » en gris avec un cadenas, son nom reste caché", () => {
    const hidden = screen.spec.buttons.slice(4, 1 + FACTORY_IDS.length);
    assert.equal(hidden.length, FACTORY_IDS.length - 3);
    for (const text of hidden) {
      assert.ok(text.startsWith(COLOR.locked), text);
      assert.equal(bare(text), `[X] ${CARTE_TEXTES.inconnue}`);
    }
    for (const target of screen.targets.slice(4, 1 + FACTORY_IDS.length)) {
      assert.deepEqual(target, { kind: "soon", bubble: BULLES.usinePasEncore });
    }
    const all = plain(screen.spec);
    for (const id of FACTORY_IDS.slice(3)) {
      assert.ok(!all.includes(FACTORY_NAMES[id as keyof typeof FACTORY_NAMES]), id);
    }
  });

  it("l'ordre est celui du jeu, pas celui du service", () => {
    const reversed = carteScreen(viewOf({ factories: [factory({ factoryId: "ferme" }), factory({ factoryId: "carriere" })] }), 0, 20);
    const names = reversed.spec.buttons.slice(1, 4).map((text) => bare(text).split("\n")[0]);
    assert.deepEqual(names, ["[U] Carrière", `[X] ${CARTE_TEXTES.inconnue}`, "[U] Ferme"]);
  });

  it("une usine du service que l'add-on ne connaît pas encore vient après les autres, avec un nom lisible", () => {
    const next = carteScreen(viewOf({ factories: [factory({ factoryId: "usine-du-futur" })] }), 0, 40);
    assert.equal(next.spec.buttons.length, 1 + FACTORY_IDS.length + 1 + 1);
    assert.equal(bare(next.spec.buttons[1 + FACTORY_IDS.length] ?? "").split("\n")[0], "[U] Usine du futur");
    assert.deepEqual(next.targets[1 + FACTORY_IDS.length], { kind: "travel", factoryId: "usine-du-futur" });
  });

  it("une entreprise sans usine : tout est caché, rien ne mène nulle part", () => {
    const empty = carteScreen(viewOf({ factories: [] }), 0, 40);
    assert.ok(empty.targets.every((target) => target.kind !== "travel"));
  });

  it("pages : Précédent et Suite aux bons endroits, page indiquée, île principale en tête de la première seulement", () => {
    const labels = (page: number) => carteScreen(view, page, 4).spec.buttons.map((b) => bare(b).split("\n")[0]);
    assert.equal(labels(0)[0], `[X] ${CARTE_TEXTES.ilePrincipale}`);
    assert.deepEqual(labels(0).slice(4), [USINES_TEXTES.suite, CARTE_TEXTES.retour]);
    assert.ok(labels(1).includes(USINES_TEXTES.precedent) && labels(1).includes(USINES_TEXTES.suite));
    assert.ok(!labels(1).includes(`[X] ${CARTE_TEXTES.ilePrincipale}`));
    assert.deepEqual(labels(3).slice(-2), [USINES_TEXTES.precedent, CARTE_TEXTES.retour]);
    assert.match(bare(carteScreen(view, 1, 4).spec.body), new RegExp(USINES_TEXTES.page(2, 4)));
    assert.deepEqual(carteScreen(view, 0, 4).targets[4], { kind: "go", route: { screen: "carte", page: 1 } });
    assert.deepEqual(carteScreen(view, 1, 4).targets[4], { kind: "go", route: { screen: "carte", page: 0 } });
  });

  it("une page hors limites revient dans les limites", () => {
    assert.equal(carteScreen(view, 99, 4).spec.body, carteScreen(view, 3, 4).spec.body);
    assert.equal(carteScreen(view, -3, 4).spec.body, carteScreen(view, 0, 4).spec.body);
  });

  it("une bulle d'Oooscar passe avant la phrase", () => {
    const withNotice = carteScreen(view, 0, 20, { bubble: BULLES.ilePasPrete });
    assert.match(bare(withNotice.spec.body), /^Oooscar \[oups\] L'île n'est pas encore prête\.\n\nChoisis une île/);
  });

  it("tient dans les huit tuiles d'un écran, plus la navigation", () => {
    for (let page = 0; page < 4; page += 1) {
      assert.ok(carteScreen(view, page, 8).spec.buttons.length <= 8 + 3);
    }
  });
});

describe("travelNotice", () => {
  it("l'île introuvable et l'île pas prête ont chacune leur bulle", () => {
    assert.deepEqual(travelNotice("unavailable"), { bubble: BULLES.ileIntrouvable });
    assert.deepEqual(travelNotice("waiting"), { bubble: BULLES.ilePasPrete });
  });

  it("arrivé ou parti : pas de bulle, le menu se ferme", () => {
    assert.equal(travelNotice("arrived"), undefined);
    assert.equal(travelNotice("gone"), undefined);
  });
});

describe("parcours : carte et voyage", () => {
  const TWO = { factories: [ACTIVE, factory({ factoryId: "scierie", state: "EN_RUINE", level: 0, ratePerHour: 0 })] };

  function travelling(outcome: TravelOutcome | Error) {
    const calls: Array<{ readonly player: { readonly name: string }; readonly state: CompanyState; readonly factoryId: string }> = [];
    const travel: Travel = async (player, state, factoryId) => {
      calls.push({ player, state, factoryId });
      if (outcome instanceof Error) throw outcome;
      return outcome;
    };
    return { calls, travel };
  }

  it("arrivé : le joueur est sur son île, le menu se ferme sans autre écran ni appel au service", async () => {
    const { calls, travel } = travelling("arrived");
    const { menu, presenter, api } = setup([press("Carte"), press("Carrière")], {}, TWO, undefined, { travel });
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "CARTE"]);
    assert.equal(calls.length, 1);
    assert.equal(calls[0]?.factoryId, "carriere");
    assert.equal(calls[0]?.player, PLAYER);
    assert.equal(calls[0]?.state.companyId, 7);
    assert.deepEqual(api.calls, []);
  });

  it("l'état donné au voyage est celui que le service a rendu en dernier", async () => {
    const { calls, travel } = travelling("arrived");
    const { menu, views } = setup([press("Carte"), press("Carrière")], {}, TWO, undefined, { travel });
    await menu.open(PLAYER);
    assert.equal(calls[0]?.state, views.get(XUID)?.state);
  });

  it("une usine en ruine mène aussi à son île : le monde la dessine en ruine", async () => {
    const { calls, travel } = travelling("arrived");
    const { menu } = setup([press("Carte"), press("Scierie")], {}, TWO, undefined, { travel });
    await menu.open(PLAYER);
    assert.equal(calls[0]?.factoryId, "scierie");
  });

  it("joueur parti pendant l'attente : le menu se ferme sans bulle", async () => {
    const { travel } = travelling("gone");
    const { menu, presenter } = setup([press("Carte"), press("Carrière")], {}, TWO, undefined, { travel });
    await menu.open(PLAYER);
    assert.equal(presenter.shown.length, 2);
  });

  it("île pas prête : la carte revient avec la bulle d'Oooscar, et le joueur peut réessayer", async () => {
    const { calls, travel } = travelling("waiting");
    const { menu, presenter } = setup([press("Carte"), press("Carrière"), press("Carrière"), close], {}, TWO, undefined, { travel });
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "CARTE", "CARTE", "CARTE"]);
    assert.match(bare(presenter.shown[2]?.body ?? ""), /^Oooscar \[oups\] L'île n'est pas encore prête\./);
    assert.equal(calls.length, 2);
  });

  it("île introuvable : la carte revient avec la bulle d'Oooscar", async () => {
    const { travel } = travelling("unavailable");
    const { menu, presenter } = setup([press("Carte"), press("Carrière"), close], {}, TWO, undefined, { travel });
    await menu.open(PLAYER);
    assert.match(bare(presenter.shown[2]?.body ?? ""), /^Oooscar \[oups\] Je ne trouve pas cette île\./);
  });

  it("sans monde (le voyage n'est pas branché) : « Pas encore disponible » et la carte reste", async () => {
    const { menu, presenter, api } = setup([press("Carte"), press("Carrière"), close], {}, TWO);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "CARTE", "CARTE"]);
    assert.match(bare(presenter.shown[2]?.body ?? ""), /^Oooscar \[oups\] Pas encore disponible\./);
    assert.deepEqual(api.calls, []);
  });

  it("l'île principale et les usines cachées : une bulle, aucun voyage", async () => {
    const { calls, travel } = travelling("arrived");
    const { menu, presenter } = setup(
      [press("Carte"), press("Île principale"), press("?????"), close],
      {},
      { factories: [ACTIVE] },
      undefined,
      { travel },
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "CARTE", "CARTE", "CARTE"]);
    assert.match(bare(presenter.shown[2]?.body ?? ""), /^Oooscar \[oups\] Pas encore disponible\./);
    assert.match(bare(presenter.shown[3]?.body ?? ""), /^Oooscar \[oups\] Tu n'as pas encore cette usine\./);
    assert.deepEqual(calls, []);
  });

  it("Retour ramène au menu ; la bulle ne reste qu'un écran", async () => {
    const { menu, presenter } = setup([press("Carte"), press("Île principale"), press("Retour"), close]);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "CARTE", "CARTE", "MENU"]);
    assert.doesNotMatch(bare(presenter.shown[3]?.body ?? ""), /Oooscar/);
  });

  it("les pages de la carte : Suite puis Précédent", async () => {
    const { menu, presenter } = setup([press("Carte"), press("Suite"), press("Précédent"), close], {}, TWO, undefined, { tilesPerScreen: 4 });
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "CARTE", "CARTE", "CARTE"]);
    assert.match(bare(presenter.shown[2]?.body ?? ""), /Page 2 sur 4/);
    assert.match(bare(presenter.shown[3]?.body ?? ""), /Page 1 sur 4/);
  });

  it("le voyage qui lève est journalisé, rien ne remonte, le menu peut se rouvrir", async () => {
    const { travel } = travelling(new Error("monde en panne"));
    const { menu, log, presenter } = setup([press("Carte"), press("Carrière"), close], {}, TWO, undefined, { travel });
    await menu.open(PLAYER);
    assert.match(log.warns.join("\n"), /menu de Alex : Error: monde en panne/);
    await menu.open(PLAYER);
    assert.equal(presenter.shown.length, 3);
  });

  it("le texte brut de la carte ne contient ni emoji ni point de suspension", async () => {
    const { menu, presenter } = setup([press("Carte"), press("Île principale"), close], {}, TWO);
    await menu.open(PLAYER);
    for (const spec of presenter.shown) {
      assert.doesNotMatch(plain(spec), /\p{Extended_Pictographic}|\.\.\.|…/u);
    }
  });
});

// ---------------------------------------------------------------- le prestige

describe("écran du prestige : avant", () => {
  const view = viewOf({ era: 1, courseNumber: 3, threshold: 2_500_000 });
  const preview = (overrides: Parameters<typeof prestigePreview>[0] = {}): PreviewOutcome => ({
    kind: "preview",
    preview: prestigePreview(overrides),
  });
  const body = (lookup: PreviewOutcome | undefined, notice?: Parameters<typeof prestigeScreen>[3]) =>
    bare(prestigeScreen(view, lookup, XUID, notice).spec.body);

  it("permis : où en est la course, ce que le service annonce, ce qui repart et ce qui reste", () => {
    const screen = prestigeScreen(view, preview(), XUID);
    const avant = PRESTIGE_TEXTES.avant;
    assert.equal(screen.spec.title, PRESTIGE_TEXTES.titre);
    assert.equal(
      bare(screen.spec.body),
      [
        "Course 3 · Époque 1 : Artisanale",
        "",
        avant.pret,
        avant.principe,
        avant.total(formatCount(1_200)),
        avant.doubler(formatCount(1_700)),
        "",
        "Course suivante : époque 2, Vapeur",
        "",
        avant.repartTitre,
        ...avant.repart,
        "",
        avant.resteTitre,
        avant.reste,
      ].join("\n"),
    );
  });

  it("permis : « Prestige ! » confirme, « Retour » ramène au menu", () => {
    const screen = prestigeScreen(view, preview(), XUID);
    assert.deepEqual(screen.spec.buttons.map(bare), [PRESTIGE_TEXTES.avant.confirmer, PRESTIGE_TEXTES.avant.retour]);
    assert.deepEqual(screen.targets, [{ kind: "prestige" }, { kind: "go", route: { screen: "main" } }]);
  });

  it("les Lingots sont ceux du service, mis en forme, jamais recalculés", () => {
    const text = body(preview({ lingotsTotal: 1_234_567, lingotsIfDoubled: 2_000_000 }));
    assert.ok(text.includes(PRESTIGE_TEXTES.avant.total(formatCount(1_234_567))), text);
    assert.ok(text.includes(PRESTIGE_TEXTES.avant.doubler(formatCount(2_000_000))), text);
  });

  it("sans Lingots du doublé : la ligne n'existe pas", () => {
    const { lingotsIfDoubled: _omitted, ...rest } = prestigePreview();
    assert.doesNotMatch(body({ kind: "preview", preview: rest }), /deux fois plus/);
  });

  it("seul dans l'entreprise : pas de part à montrer", () => {
    assert.doesNotMatch(body(preview()), /Ta part|Un associé/);
  });

  it("plusieurs membres : la part de chaque membre, la sienne reconnue à son identifiant, l'ordre du service gardé", () => {
    const text = body(
      preview({
        shares: [
          { xuid: "2535400000000002", lingots: 500 },
          { xuid: XUID, lingots: 700 },
        ],
      }),
    );
    assert.ok(text.includes(PRESTIGE_TEXTES.avant.unAssocie(formatCount(500))), text);
    assert.ok(text.includes(PRESTIGE_TEXTES.avant.taPart(formatCount(700))), text);
    assert.ok(text.indexOf("Un associé") < text.indexOf("Ta part"), "l'ordre du service est gardé");
  });

  it("la course suivante suit le champ « next » du service : une époque, ou une étoile", () => {
    assert.match(body(preview({ next: "EPOQUE_2" })), /Course suivante : époque 2, Vapeur/);
    assert.match(body(preview({ next: "EPOQUE_3" })), /Course suivante : époque 3, Moderne/);
    assert.match(body(preview({ next: "EPOQUE_4" })), /Course suivante : époque 4, Futuriste/);
    assert.ok(body(preview({ next: "ETOILE" })).includes(PRESTIGE_TEXTES.suivante.etoile));
  });

  it("pas permis : le seuil du service, aucun bouton de confirmation, aucun Lingot annoncé", () => {
    const screen = prestigeScreen(view, preview({ allowed: false, lingotsTotal: 0, lingotsIfDoubled: 0, shares: [] }), XUID);
    const text = bare(screen.spec.body);
    assert.ok(text.includes(PRESTIGE_TEXTES.avant.pasEncore), text);
    assert.ok(text.includes(PRESTIGE_TEXTES.avant.seuil(formatAmount(2_500_000))), text);
    assert.doesNotMatch(text, /Lingots en tout|deux fois plus|Ta part/);
    assert.deepEqual(screen.spec.buttons.map(bare), [PRESTIGE_TEXTES.avant.retour]);
    assert.deepEqual(screen.targets, [{ kind: "go", route: { screen: "main" } }]);
    assert.ok(screen.spec.body.includes(`${COLOR.blocked}${PRESTIGE_TEXTES.avant.pasEncore}`));
  });

  it("l'autorisation est en couleur ET en mots, jamais la couleur seule", () => {
    const screen = prestigeScreen(view, preview(), XUID);
    assert.ok(screen.spec.body.includes(`${COLOR.success}${PRESTIGE_TEXTES.avant.pret}`));
  });

  it("le numéro de course et le nom de l'époque viennent de l'état du service", () => {
    const later = prestigeScreen(viewOf({ era: 3, courseNumber: 12 }), preview({ next: "EPOQUE_4" }), XUID);
    assert.match(bare(later.spec.body), /^Course 12 · Époque 3 : Moderne\n/);
  });

  it("sans aperçu lu : Oooscar dit que le service ne répond pas, il ne reste que « Retour »", () => {
    const screen = prestigeScreen(view, undefined, XUID);
    assert.equal(bare(screen.spec.body), `Oooscar [panique] ${BULLES.serviceInjoignable.text}`);
    assert.deepEqual(screen.spec.buttons.map(bare), [PRESTIGE_TEXTES.avant.retour]);
  });

  it("aperçu en échec : la bulle de l'échec, rien d'inventé", () => {
    const failed = (failure: "network" | "error" | "unavailable", reason: string): PreviewOutcome => ({ kind: "failed", failure, reason });
    assert.match(body(failed("network", "RESEAU")), /^Oooscar \[panique\]/);
    assert.ok(body(failed("error", "ERREUR_INTERNE")).includes(BULLES.problemeService.text));
    assert.ok(body(failed("error", "PAS_FAIT")).includes(BULLES.bientot.text));
    assert.doesNotMatch(body(failed("network", "RESEAU")), /Lingots|Seuil/);
  });

  it("une bulle d'Oooscar passe avant tout, y compris sur un aperçu lu", () => {
    const text = body(preview(), { bubble: refusalBubble("PAS_LE_PDG") });
    assert.match(text, /^Oooscar \[oups\] Seul le PDG peut le faire\.\n\nCourse 3/);
  });
});

describe("écran du prestige : après", () => {
  const apres = PRESTIGE_TEXTES.apres;
  const view = viewWith({ era: 2, courseNumber: 4, stars: 0, donar: 0, factories: many(2) }, { lingots: 1_200 });
  const screen = apresScreen(view);

  it("l'état que le service vient de rendre : course, époque, Lingots du solde, Donar remis à zéro, la suite", () => {
    assert.equal(screen.spec.title, PRESTIGE_TEXTES.titre);
    assert.equal(
      bare(screen.spec.body),
      ["Course 4 · Époque 2 : Vapeur", apres.lingots(formatCount(1_200)), SILO_TEXTES.donar(formatAmount(0)), "", apres.suite].join("\n"),
    );
  });

  it("les étoiles n'apparaissent que si l'entreprise en a", () => {
    assert.doesNotMatch(bare(screen.spec.body), /Étoiles/);
    const starred = apresScreen(viewWith({ stars: 2, era: 4, courseNumber: 20 }, { lingots: 5 }));
    assert.ok(bare(starred.spec.body).includes(apres.etoiles("2")));
  });

  it("deux boutons : voir les usines, ou retour au menu", () => {
    assert.deepEqual(screen.spec.buttons.map(bare), [apres.usines, apres.retour]);
    assert.deepEqual(screen.targets, [
      { kind: "go", route: { screen: "usines", page: 0 } },
      { kind: "go", route: { screen: "main" } },
    ]);
  });

  it("la bulle de fierté d'Oooscar passe avant le corps", () => {
    const withNotice = apresScreen(view, { bubble: BULLES.prestige });
    assert.match(bare(withNotice.spec.body), /^Oooscar \[fier\] Prestige accompli, patron !\n\nCourse 4/);
  });
});

describe("noticeOf : le prestige", () => {
  it("un prestige réussi : la bulle de fierté", () => {
    assert.deepEqual(noticeOf({ kind: "prestiged" }), { bubble: BULLES.prestige });
  });

  it("le refus PAS_LE_PDG : seul le PDG peut le faire", () => {
    assert.equal(noticeOf({ kind: "refused", reason: "PAS_LE_PDG" })?.bubble.text, "Seul le PDG peut le faire.");
  });

  it("le refus SEUIL_NON_ATTEINT : les Donar qui manquent si le service les donne, sinon la phrase du seuil", () => {
    assert.equal(noticeOf({ kind: "refused", reason: "SEUIL_NON_ATTEINT", missing: 4_000 })?.bubble.text, `Il te manque ${formatAmount(4_000)} Donar.`);
    assert.equal(noticeOf({ kind: "refused", reason: "SEUIL_NON_ATTEINT" })?.bubble.text, "Le seuil n'est pas atteint.");
  });
});

describe("parcours : prestige", () => {
  const STATE = { factories: [ACTIVE], era: 1, courseNumber: 2, threshold: 5_000, donar: 9_000 };

  /** Ce que le service rend après le prestige : la course suivante, la Carrière seule debout, les Lingots versés. */
  function prestiged(version = 2): CompanyUpdated {
    return {
      state: companyState({
        version,
        era: 2,
        courseNumber: 3,
        donar: 0,
        donarEarned: 0,
        factories: [
          factory({ factoryId: "carriere", level: 1 }),
          factory({ factoryId: "scierie", state: "EN_RUINE", level: 0, ratePerHour: 0 }),
        ],
      }),
      balances: balances({ lingots: 1_200 }),
    };
  }

  it("avant puis après : l'aperçu, la confirmation, l'état rafraîchi et les îles qui suivent", async () => {
    const { menu, presenter, api, views } = setup(
      [press("Prestige"), press("Prestige !"), press("Voir les usines"), close],
      { prestigePreview: [ok(prestigePreview())], prestige: [ok(prestiged())] },
      STATE,
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "PRESTIGE", "PRESTIGE", "USINES"]);
    assert.match(bare(presenter.shown[1]?.body ?? ""), /^Course 2 · Époque 1 : Artisanale/);
    assert.match(bare(presenter.shown[1]?.body ?? ""), /Lingots en tout 1.200/);
    assert.match(bare(presenter.shown[2]?.body ?? ""), /^Oooscar \[fier\] Prestige accompli, patron !\n\nCourse 3 · Époque 2 : Vapeur/);
    assert.match(bare(presenter.shown[2]?.body ?? ""), /Tu as 1.200 Lingots/);
    assert.ok(presenter.shown[3]?.buttons.map(bare).includes("[X] Scierie\nEn ruine"));
    assert.deepEqual(api.calls, [
      { op: "prestigePreview", companyId: 7 },
      { op: "prestige", companyId: 7, action: { requestId: "id-1", actor: XUID } },
    ]);
    assert.equal(views.get(XUID)?.state.version, 2);
    assert.equal(views.get(XUID)?.balances.lingots, 1_200);
  });

  it("après le prestige, le menu montre la course suivante", async () => {
    const { menu, presenter } = setup(
      [press("Prestige"), press("Prestige !"), press("Retour"), close],
      { prestigePreview: [ok(prestigePreview())], prestige: [ok(prestiged())] },
      STATE,
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "PRESTIGE", "PRESTIGE", "MENU"]);
    assert.equal(bare(presenter.shown[0]?.buttons[4] ?? ""), "[P] Prestige\nCourse 2");
    assert.equal(bare(presenter.shown[3]?.buttons[4] ?? ""), "[P] Prestige\nCourse 3");
  });

  it("l'aperçu est relu à chaque ouverture de l'écran", async () => {
    const { menu, presenter, api } = setup(
      [press("Prestige"), press("Retour"), press("Prestige"), close],
      { prestigePreview: [ok(prestigePreview()), ok(prestigePreview({ lingotsTotal: 1_900 }))] },
      STATE,
    );
    await menu.open(PLAYER);
    assert.deepEqual(api.calls.map((call) => call.op), ["prestigePreview", "prestigePreview"]);
    assert.match(bare(presenter.shown[1]?.body ?? ""), /Lingots en tout 1.200/);
    assert.match(bare(presenter.shown[3]?.body ?? ""), /Lingots en tout 1.900/);
  });

  it("refus PAS_LE_PDG : l'écran revient avec la bulle, l'aperçu relu, rien n'a changé", async () => {
    const { menu, presenter, api, views } = setup(
      [press("Prestige"), press("Prestige !"), close],
      { prestigePreview: [ok(prestigePreview()), ok(prestigePreview())], prestige: [refused("PAS_LE_PDG")] },
      STATE,
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "PRESTIGE", "PRESTIGE"]);
    assert.match(bare(presenter.shown[2]?.body ?? ""), /^Oooscar \[oups\] Seul le PDG peut le faire\.\n\nCourse 2/);
    assert.deepEqual(api.calls.map((call) => call.op), ["prestigePreview", "prestige", "prestigePreview"]);
    assert.equal(views.get(XUID)?.state.version, 1);
  });

  it("refus SEUIL_NON_ATTEINT : les Donar qui manquent, et l'aperçu relu retire le bouton de confirmation", async () => {
    const stale = prestigePreview();
    const now = prestigePreview({ allowed: false, lingotsTotal: 0, lingotsIfDoubled: 0, shares: [] });
    const { menu, presenter } = setup(
      [press("Prestige"), press("Prestige !"), close],
      { prestigePreview: [ok(stale), ok(now)], prestige: [refused("SEUIL_NON_ATTEINT", 4_000)] },
      STATE,
    );
    await menu.open(PLAYER);
    const body = bare(presenter.shown[2]?.body ?? "");
    assert.ok(body.startsWith(`Oooscar [oups] Il te manque ${formatAmount(4_000)} Donar.`), body);
    assert.ok(body.includes(PRESTIGE_TEXTES.avant.pasEncore), body);
    assert.deepEqual(presenter.shown[2]?.buttons.map(bare), [PRESTIGE_TEXTES.avant.retour]);
  });

  it("CONFLIT_REESSAYER : l'état est rechargé, le prestige rejoué une fois avec le même requestId, le joueur ne voit que le succès", async () => {
    const { menu, presenter, api } = setup(
      [press("Prestige"), press("Prestige !"), close],
      {
        prestigePreview: [ok(prestigePreview())],
        prestige: [refused("CONFLIT_REESSAYER"), ok(prestiged(6))],
        getCompany: [ok(companyState({ ...STATE, version: 5 }))],
      },
      STATE,
    );
    await menu.open(PLAYER);
    assert.deepEqual(api.calls.map((call) => call.op), ["prestigePreview", "prestige", "getCompany", "prestige"]);
    const ids = api.calls.flatMap((call) => (call.op === "prestige" ? [call.action.requestId] : []));
    assert.equal(ids.length, 2);
    assert.equal(ids[0], ids[1]);
    assert.match(bare(presenter.shown[2]?.body ?? ""), /Prestige accompli/);
    assert.equal(presenter.shown.length, 3);
  });

  it("service injoignable pendant le prestige : l'écran revient avec la panique d'Oooscar, rien n'est affiché comme fait", async () => {
    const { menu, presenter, views } = setup(
      [press("Prestige"), press("Prestige !"), close],
      { prestigePreview: [ok(prestigePreview()), unreachable()], prestige: [unreachable()] },
      STATE,
    );
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "PRESTIGE", "PRESTIGE"]);
    assert.match(bare(presenter.shown[2]?.body ?? ""), /^Oooscar \[panique\]/);
    assert.deepEqual(presenter.shown[2]?.buttons.map(bare), [PRESTIGE_TEXTES.avant.retour]);
    assert.equal(views.get(XUID)?.state.version, 1);
  });

  it("aperçu injoignable à l'ouverture : la panique d'Oooscar, « Retour » seul, aucun prestige possible", async () => {
    const { menu, presenter, api } = setup([press("Prestige"), press("Retour"), close], { prestigePreview: [unreachable()] }, STATE);
    await menu.open(PLAYER);
    assert.deepEqual(presenter.shown.map(titleOf), ["MENU", "PRESTIGE", "MENU"]);
    assert.match(bare(presenter.shown[1]?.body ?? ""), /^Oooscar \[panique\]/);
    assert.deepEqual(presenter.shown[1]?.buttons.map(bare), [PRESTIGE_TEXTES.avant.retour]);
    assert.deepEqual(api.calls.map((call) => call.op), ["prestigePreview"]);
  });

  it("le texte brut des écrans du prestige ne contient ni emoji ni point de suspension", async () => {
    const { menu, presenter } = setup(
      [press("Prestige"), press("Prestige !"), press("Retour"), close],
      { prestigePreview: [ok(prestigePreview())], prestige: [ok(prestiged())] },
      STATE,
    );
    await menu.open(PLAYER);
    for (const spec of presenter.shown) {
      assert.doesNotMatch(plain(spec), /\p{Extended_Pictographic}|\.\.\.|…/u);
    }
  });
});
