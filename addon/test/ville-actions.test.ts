// Les trois actions de la ville : acheter une zone, poser un bâtiment, l'améliorer. Elles passent par la même machinerie
// que les autres actions (requestId, rejeu sur conflit, verrou du joueur) ; on vérifie ici ce qui leur est propre.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { ApiResult } from "../src/http";
import type { CompanyUpdated } from "../src/protocol";
import { createActions } from "../src/ui/actions";
import { createViewStore } from "../src/ui/etat";
import { balances, companyState, ok, recordingLogger } from "./helpers";
import { failure, fakeClock, fakeCompany, refused, sequentialIds, storeWith, unreachable, XUID, type Script } from "./ui-helpers";
import { cityBuilding, cityState, emptyCity, house } from "./ville-helpers";

function setup(script: Script, state: Parameters<typeof storeWith>[0] = { version: 1, city: emptyCity() }) {
  const clock = fakeClock(0);
  const store = storeWith(state, { lingots: 20 });
  const views = createViewStore({ players: store, clock });
  const api = fakeCompany(script);
  const log = recordingLogger();
  const actions = createActions({ api, views, newRequestId: sequentialIds(), log });
  return { actions, api, views, store, log };
}

/** La réponse du service à un achat de zone : l'état au numéro `version`, avec la mairie, et les soldes d'après. */
const afterBuy = (version = 2, lingots = 19): CompanyUpdated => ({
  state: companyState({ version, city: cityState() }),
  balances: balances({ lingots }),
});

describe("acheter une zone", () => {
  it("envoie la zone et l'acteur, range la ville et les soldes rendus", async () => {
    const { actions, api, views } = setup({ buyZone: [ok(afterBuy())] });
    assert.deepEqual(await actions.buyZone(XUID, 1), { kind: "zoneBought" });
    assert.deepEqual(api.calls, [{ op: "buyZone", companyId: 7, zoneIndex: 1, action: { requestId: "id-1", actor: XUID } }]);
    const view = views.get(XUID);
    assert.equal(view?.state.version, 2);
    assert.equal(view?.state.city?.buildings[0]?.buildingType, "mairie");
    assert.equal(view?.balances.lingots, 19);
  });

  it("un refus garde son code, et les Lingots qui manquent", async () => {
    const { actions, views } = setup({ buyZone: [refused("PAS_ASSEZ_DE_LINGOTS", 3)] });
    assert.deepEqual(await actions.buyZone(XUID, 1), { kind: "refused", reason: "PAS_ASSEZ_DE_LINGOTS", missing: 3 });
    assert.equal(views.get(XUID)?.state.version, 1);
    assert.equal(views.get(XUID)?.state.city?.buildings.length, 0);
  });

  it("ZONE_VERROUILLEE et DEJA_POSSEDE sont rendus tels quels, jamais rejoués", async () => {
    for (const reason of ["ZONE_VERROUILLEE", "DEJA_POSSEDE"]) {
      const { actions, api } = setup({ buyZone: [refused(reason), ok(afterBuy())] });
      assert.deepEqual(await actions.buyZone(XUID, 2), { kind: "refused", reason });
      assert.equal(api.calls.length, 1);
    }
  });

  it("CONFLIT_REESSAYER : relit l'état, rejoue une fois avec le même requestId", async () => {
    const { actions, api, views } = setup({
      buyZone: [refused("CONFLIT_REESSAYER"), ok(afterBuy(6))],
      getCompany: [ok(companyState({ version: 5, city: emptyCity() }))],
    });
    assert.deepEqual(await actions.buyZone(XUID, 1), { kind: "zoneBought" });
    assert.deepEqual(
      api.calls.map((call) => call.op),
      ["buyZone", "getCompany", "buyZone"],
    );
    const sends = api.calls.flatMap((call) => (call.op === "buyZone" ? [call.action] : []));
    assert.deepEqual(sends[0], sends[1]);
    assert.equal(views.get(XUID)?.state.version, 6);
  });

  it("un réseau coupé ou une erreur du service sont des échecs, l'état ne bouge pas", async () => {
    const coupe = setup({ buyZone: [unreachable()] });
    assert.deepEqual(await coupe.actions.buyZone(XUID, 1), { kind: "failed", failure: "network", reason: "RESEAU" });
    assert.match(coupe.log.warns.join("\n"), /buyZone : réseau/);
    const erreur = setup({ buyZone: [failure(500, "ERREUR_INTERNE")] });
    assert.deepEqual(await erreur.actions.buyZone(XUID, 1), { kind: "failed", failure: "error", reason: "ERREUR_INTERNE" });
    assert.equal(erreur.views.get(XUID)?.state.version, 1);
  });

  it("un joueur sans réponse de join : indisponible, aucun appel", async () => {
    const { actions, api } = setup({});
    assert.deepEqual(await actions.buyZone("999", 1), { kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" });
    assert.deepEqual(api.calls, []);
  });
});

describe("poser un bâtiment", () => {
  const placed = (): CompanyUpdated => ({
    state: companyState({ version: 3, city: cityState({ buildings: [cityBuilding(), house({ buildingId: 5, x: 208, z: 192, rotation: 90 })] }) }),
    balances: balances({ lingots: 14 }),
  });

  it("envoie le type, la parcelle et la rotation, et range la ville rendue", async () => {
    const { actions, api, views } = setup({ placeBuilding: [ok(placed())] }, { version: 2, city: cityState() });
    assert.deepEqual(await actions.placeBuilding(XUID, "maison", 208, 192, 90), { kind: "buildingPlaced" });
    assert.deepEqual(api.calls, [
      { op: "placeBuilding", companyId: 7, action: { requestId: "id-1", actor: XUID }, buildingType: "maison", x: 208, z: 192, rotation: 90 },
    ]);
    assert.equal(views.get(XUID)?.state.city?.buildings.length, 2);
    assert.equal(views.get(XUID)?.balances.lingots, 14);
  });

  it("EMPLACEMENT_OCCUPE, ZONE_VERROUILLEE, DEJA_POSSEDE et PAS_ASSEZ_DE_LINGOTS sont rendus tels quels", async () => {
    for (const [reason, missing] of [
      ["EMPLACEMENT_OCCUPE", undefined],
      ["ZONE_VERROUILLEE", undefined],
      ["DEJA_POSSEDE", undefined],
      ["PAS_ASSEZ_DE_LINGOTS", 4],
    ] as const) {
      const { actions, api } = setup({ placeBuilding: [refused(reason, missing)] }, { version: 2, city: cityState() });
      const outcome = await actions.placeBuilding(XUID, "maison", 208, 192, 0);
      assert.deepEqual(outcome, missing === undefined ? { kind: "refused", reason } : { kind: "refused", reason, missing });
      assert.equal(api.calls.length, 1);
    }
  });

  it("CONFLIT_REESSAYER : un seul rejeu, avec le même requestId", async () => {
    const { actions, api } = setup(
      {
        placeBuilding: [refused("CONFLIT_REESSAYER"), refused("CONFLIT_REESSAYER")],
        getCompany: [ok(companyState({ version: 4, city: cityState() }))],
      },
      { version: 2, city: cityState() },
    );
    assert.deepEqual(await actions.placeBuilding(XUID, "maison", 208, 192, 0), { kind: "refused", reason: "CONFLIT_REESSAYER" });
    assert.deepEqual(
      api.calls.map((call) => call.op),
      ["placeBuilding", "getCompany", "placeBuilding"],
    );
  });
});

describe("améliorer un bâtiment", () => {
  const upgraded = (): CompanyUpdated => ({
    state: companyState({ version: 3, city: cityState({ mairieLevel: 2, buildings: [cityBuilding({ level: 2 })] }) }),
    balances: balances({ lingots: 12 }),
  });

  it("envoie le bâtiment et l'acteur, et range la ville rendue", async () => {
    const { actions, api, views } = setup({ upgradeBuilding: [ok(upgraded())] }, { version: 2, city: cityState() });
    assert.deepEqual(await actions.upgradeBuilding(XUID, 1), { kind: "buildingUpgraded" });
    assert.deepEqual(api.calls, [{ op: "upgradeBuilding", companyId: 7, buildingId: 1, action: { requestId: "id-1", actor: XUID } }]);
    assert.equal(views.get(XUID)?.state.city?.mairieLevel, 2);
    assert.equal(views.get(XUID)?.balances.lingots, 12);
  });

  it("DEJA_POSSEDE (dernier niveau), ZONE_VERROUILLEE (mairie basse) et le manque de Lingots sont rendus tels quels", async () => {
    for (const [reason, missing] of [
      ["DEJA_POSSEDE", undefined],
      ["ZONE_VERROUILLEE", undefined],
      ["PAS_ASSEZ_DE_LINGOTS", 2],
    ] as const) {
      const { actions } = setup({ upgradeBuilding: [refused(reason, missing)] }, { version: 2, city: cityState() });
      const outcome = await actions.upgradeBuilding(XUID, 1);
      assert.deepEqual(outcome, missing === undefined ? { kind: "refused", reason } : { kind: "refused", reason, missing });
    }
  });

  it("un bâtiment que le service ne connaît pas (400) est un échec « error »", async () => {
    const { actions } = setup({ upgradeBuilding: [failure(400, "REQUETE_INVALIDE")] }, { version: 2, city: cityState() });
    assert.deepEqual(await actions.upgradeBuilding(XUID, 99), { kind: "failed", failure: "error", reason: "REQUETE_INVALIDE" });
  });
});

describe("la ville respecte le verrou du joueur", () => {
  it("un achat en cours : une seconde action de la ville est ignorée, sans appel", async () => {
    const store = storeWith({ version: 1, city: emptyCity() });
    const views = createViewStore({ players: store, clock: fakeClock() });
    const calls: string[] = [];
    const releases: Array<() => void> = [];
    const reject = () => Promise.reject(new Error("inutile"));
    const api = {
      getCompany: reject,
      collect: reject,
      buyLevels: reject,
      restoreStep: reject,
      resolveIncident: reject,
      prestige: reject,
      prestigePreview: reject,
      getCandidates: reject,
      hire: reject,
      assignStaff: reject,
      fireStaff: reject,
      invite: reject,
      setShares: reject,
      acceptInvite: reject,
      placeBuilding: reject,
      upgradeBuilding: reject,
      buyZone: (companyId: number, zoneIndex: number) =>
        new Promise<ApiResult<CompanyUpdated>>((resolve) => {
          calls.push(`buyZone ${companyId} ${zoneIndex}`);
          releases.push(() => resolve(ok(afterBuy())));
        }),
    };
    const actions = createActions({ api, views, newRequestId: sequentialIds(), log: recordingLogger() });

    const first = actions.buyZone(XUID, 1);
    assert.deepEqual(await actions.buyZone(XUID, 1), { kind: "busy" });
    assert.deepEqual(await actions.placeBuilding(XUID, "maison", 208, 192, 0), { kind: "busy" });
    assert.deepEqual(await actions.upgradeBuilding(XUID, 1), { kind: "busy" });
    assert.deepEqual(calls, ["buyZone 7 1"]);
    releases.forEach((release) => release());
    assert.equal((await first).kind, "zoneBought");
  });

  it("le nom du joueur n'est pas l'identifiant : seul l'XUID part vers le service", async () => {
    const { actions, api } = setup({ buyZone: [ok(afterBuy())] });
    await actions.buyZone(XUID, 1);
    assert.doesNotMatch(JSON.stringify(api.calls), /Kr/);
  });
});
