import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { CompanyState, CompanyUpdated, Factory } from "../src/protocol";
import { createActions } from "../src/ui/actions";
import { createViewStore } from "../src/ui/etat";
import { createMenu } from "../src/ui/menu";
import { createMissions } from "../src/ui/missions";
import { BULLES, INCIDENT_TEXTES, refusalBubble, repairSteps } from "../src/ui/textes";
import { balances, companyState, factory, ok, recordingLogger } from "./helpers";
import {
  PLAYER,
  XUID,
  bare,
  close,
  fakeClock,
  fakeCompany,
  fakeMissionsApi,
  fakePresenter,
  press,
  refused,
  sequentialIds,
  storeWith,
  unreachable,
  type Reply,
  type Script,
} from "./ui-helpers";

/** Une usine en incident, comme la fiche du service la donne. */
const EN_INCIDENT = (kind: string | null = "incendie", sansCle = false): Partial<CompanyState> => ({
  factories: [factory({ factoryId: "carriere", incidentId: 31, ...(sansCle ? {} : { incidentKind: kind }) })],
});

/** L'état que le service rend une fois l'incident clos. */
function repaired(version = 2): CompanyUpdated {
  const clean: Factory = factory({ factoryId: "carriere", incidentId: null, incidentKind: null });
  return { state: companyState({ version, factories: [clean] }), balances: balances() };
}

/** Le bouton juste de l'étape `step` de la sorte `kind`. */
const juste = (kind: string | null | undefined, step: number): Reply => () => ({
  kind: "selected",
  index: repairSteps(kind)[step]?.bonne ?? -1,
});

/** Un bouton faux de l'étape `step` : le premier qui n'est pas le bon. */
const faux = (kind: string | null | undefined, step: number): Reply => () => ({
  kind: "selected",
  index: repairSteps(kind)[step]?.bonne === 0 ? 1 : 0,
});

function setup(replies: readonly Reply[], script: Script, state: Partial<CompanyState>) {
  const clock = fakeClock(0);
  const store = storeWith(state);
  const views = createViewStore({ players: store, clock });
  const api = fakeCompany(script);
  const log = recordingLogger();
  const missions = createMissions({ api: fakeMissionsApi(), players: store, say: () => undefined, log });
  const actions = createActions({ api, views, newRequestId: sequentialIds(), log });
  const presenter = fakePresenter(replies);
  const menu = createMenu({ views, missions, actions, presenter, clock, wait: async () => undefined, log, tilesPerScreen: 8 });
  return { menu, presenter, api, views, log };
}

const titles = (shown: ReadonlyArray<{ title: string }>): string[] => shown.map((spec) => spec.title);

describe("parcours : Réparer, mini-jeu, resolveIncident", () => {
  it("trois bonnes réponses : le service clôt l'incident, la fiche revient avec l'état rendu et la bulle de joie", async () => {
    const { menu, presenter, api, views } = setup(
      [press("Usines"), press("Carrière"), press("Réparer"), juste("incendie", 0), juste("incendie", 1), juste("incendie", 2), close],
      { resolveIncident: [ok(repaired())] },
      EN_INCIDENT(),
    );
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter.shown), ["MENU", "USINES", "USINE", "RÉPARATION", "RÉPARATION", "RÉPARATION", "USINE"]);
    assert.deepEqual(api.calls, [{ op: "resolveIncident", companyId: 7, incidentId: 31, action: { requestId: "id-1", actor: XUID } }]);
    const fiche = presenter.shown[6];
    assert.match(bare(fiche?.body ?? ""), /^Oooscar \[joie\] L'usine repart !\n\nACTIVE\nCarrière/);
    assert.ok(!(fiche?.buttons ?? []).some((button) => button.includes(INCIDENT_TEXTES.reparer)));
    assert.equal(views.get(XUID)?.state.version, 2);
    assert.equal(views.get(XUID)?.state.factories[0]?.incidentId, null);
  });

  it("l'état du service est celui que l'on voit : rien n'est calculé dans l'add-on", async () => {
    const rendu = repaired(5);
    rendu.state.factories[0] = factory({ factoryId: "carriere", level: 41, ratePerHour: 123_456, incidentId: null });
    const { menu, presenter } = setup(
      [press("Usines"), press("Carrière"), press("Réparer"), juste("panne", 0), juste("panne", 1), juste("panne", 2), close],
      { resolveIncident: [ok(rendu)] },
      EN_INCIDENT("panne"),
    );
    await menu.open(PLAYER);
    assert.match(bare(presenter.shown[6]?.body ?? ""), /Niveau 41/);
  });

  it("une mauvaise réponse rejoue l'étape avec l'indice d'Oooscar, sans appeler le service", async () => {
    const { menu, presenter, api } = setup(
      [press("Usines"), press("Carrière"), press("Réparer"), faux("courroie", 0), juste("courroie", 0), juste("courroie", 1), juste("courroie", 2), close],
      { resolveIncident: [ok(repaired())] },
      EN_INCIDENT("courroie"),
    );
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter.shown), ["MENU", "USINES", "USINE", "RÉPARATION", "RÉPARATION", "RÉPARATION", "RÉPARATION", "USINE"]);
    assert.doesNotMatch(bare(presenter.shown[3]?.body ?? ""), /Oooscar/);
    assert.match(bare(presenter.shown[4]?.body ?? ""), /^Oooscar \[oups\] /);
    assert.match(bare(presenter.shown[4]?.body ?? ""), /Étape 1 sur 3/);
    assert.equal(api.calls.length, 1);
  });

  it("Retour dans le mini-jeu : la fiche revient, l'incident reste ouvert, rien n'est demandé", async () => {
    const { menu, presenter, api, views } = setup(
      [press("Usines"), press("Carrière"), press("Réparer"), juste("incendie", 0), press("Retour"), close],
      {},
      EN_INCIDENT(),
    );
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter.shown), ["MENU", "USINES", "USINE", "RÉPARATION", "RÉPARATION", "USINE"]);
    assert.deepEqual(api.calls, []);
    assert.equal(views.get(XUID)?.state.factories[0]?.incidentId, 31);
    assert.ok((presenter.shown[5]?.buttons ?? []).some((button) => button.includes(INCIDENT_TEXTES.reparer)));
  });

  it("fermer la fenêtre en plein jeu ferme le menu, sans rien demander", async () => {
    const { menu, presenter, api } = setup([press("Usines"), press("Carrière"), press("Réparer"), close], {}, EN_INCIDENT());
    await menu.open(PLAYER);
    assert.deepEqual(titles(presenter.shown), ["MENU", "USINES", "USINE", "RÉPARATION"]);
    assert.deepEqual(api.calls, []);
  });
});

describe("parcours : la clôture refusée ou impossible", () => {
  it("un refus du service (409) : la bulle du code, la fiche garde son bouton Réparer", async () => {
    const { menu, presenter, views } = setup(
      [press("Usines"), press("Carrière"), press("Réparer"), juste("incendie", 0), juste("incendie", 1), juste("incendie", 2), close],
      { resolveIncident: [refused("PAS_LE_PDG")] },
      EN_INCIDENT(),
    );
    await menu.open(PLAYER);
    const fiche = presenter.shown[6];
    assert.ok(bare(fiche?.body ?? "").startsWith(`Oooscar [oups] ${refusalBubble("PAS_LE_PDG").text}`));
    assert.ok((fiche?.buttons ?? []).some((button) => button.includes(INCIDENT_TEXTES.reparer)));
    assert.equal(views.get(XUID)?.state.factories[0]?.incidentId, 31);
  });

  it("le service injoignable : Oooscar le dit, l'incident reste ouvert et on peut rejouer", async () => {
    const { menu, presenter, api } = setup(
      [
        press("Usines"),
        press("Carrière"),
        press("Réparer"),
        juste("incendie", 0),
        juste("incendie", 1),
        juste("incendie", 2),
        press("Réparer"),
        press("Retour"),
        close,
      ],
      { resolveIncident: [unreachable()] },
      EN_INCIDENT(),
    );
    await menu.open(PLAYER);
    assert.ok(bare(presenter.shown[6]?.body ?? "").startsWith(`Oooscar [panique] ${BULLES.serviceInjoignable.text}`));
    assert.equal(presenter.shown[7]?.title, "RÉPARATION");
    assert.equal(api.calls.length, 1);
  });

  it("CONFLIT_REESSAYER : l'état est relu, la demande rejouée une fois avec le même requestId", async () => {
    const { menu, presenter, api } = setup(
      [press("Usines"), press("Carrière"), press("Réparer"), juste("panne", 0), juste("panne", 1), juste("panne", 2), close],
      {
        resolveIncident: [refused("CONFLIT_REESSAYER"), ok(repaired(6))],
        getCompany: [ok(companyState({ version: 5, ...EN_INCIDENT("panne") }))],
      },
      EN_INCIDENT("panne"),
    );
    await menu.open(PLAYER);
    assert.deepEqual(
      api.calls.map((call) => call.op),
      ["resolveIncident", "getCompany", "resolveIncident"],
    );
    const [premier, rejeu] = api.calls.filter((call) => call.op === "resolveIncident");
    assert.deepEqual(premier, rejeu);
    assert.match(bare(presenter.shown[6]?.body ?? ""), /^Oooscar \[joie\] L'usine repart !/);
  });
});

describe("parcours : une sorte que l'add-on ne connaît pas, ou que le service ne dit pas", () => {
  for (const kind of [null, undefined, "sorte-du-futur"]) {
    it(`incidentKind ${String(kind)} : le mini-jeu général, puis la clôture`, async () => {
      const { menu, presenter, api } = setup(
        [press("Usines"), press("Carrière"), press("Réparer"), juste(kind, 0), juste(kind, 1), juste(kind, 2), close],
        { resolveIncident: [ok(repaired())] },
        EN_INCIDENT(kind ?? null, kind === undefined),
      );
      await menu.open(PLAYER);
      assert.deepEqual(titles(presenter.shown), ["MENU", "USINES", "USINE", "RÉPARATION", "RÉPARATION", "RÉPARATION", "USINE"]);
      assert.equal(bare(presenter.shown[2]?.body ?? "").split("\n")[0], "INCIDENT");
      assert.equal(api.calls.length, 1);
    });
  }
});
