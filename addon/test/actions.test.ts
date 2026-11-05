import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { ApiResult } from "../src/http";
import type { CollectResponse, CompanyUpdated } from "../src/protocol";
import { createActions } from "../src/ui/actions";
import { createViewStore } from "../src/ui/etat";
import { balances, companyState, factory, joinResponse, ok, recordingLogger } from "./helpers";
import {
  GAMERTAG,
  XUID,
  failure,
  fakeClock,
  fakeCompany,
  refused,
  sequentialIds,
  storeWith,
  unreachable,
  type Script,
} from "./ui-helpers";

const BASE = { version: 1, donar: 100, ratePerHour: 3_600, factories: [factory({ level: 3 })] } as const;

function setup(script: Script, state = BASE) {
  const clock = fakeClock(0);
  const store = storeWith({ ...state, factories: [...state.factories] });
  const views = createViewStore({ players: store, clock });
  const api = fakeCompany(script);
  const log = recordingLogger();
  const actions = createActions({ api, views, newRequestId: sequentialIds(), log });
  return { actions, api, views, store, clock, log };
}

const updated = (version: number, level: number, lingots = 0): CompanyUpdated => ({
  state: companyState({ version, donar: 40, ratePerHour: 3_600, factories: [factory({ level })] }),
  balances: balances({ lingots }),
});

describe("encaisser", () => {
  it("envoie un requestId neuf et l'identifiant du joueur, range l'état rendu et rapporte le gain", async () => {
    const { actions, api, views, clock } = setup({
      collect: [ok({ collected: 250, offlineHours: 0, state: companyState({ version: 2, donar: 350 }) })],
    });
    clock.advance(9_000);
    assert.deepEqual(await actions.collect(XUID), { kind: "collected", collected: 250, offlineHours: 0 });
    assert.deepEqual(api.calls, [{ op: "collect", companyId: 7, action: { requestId: "id-1", actor: XUID } }]);
    assert.equal(views.get(XUID)?.state.donar, 350);
    assert.deepEqual(views.get(XUID)?.anchor, { donar: 350, ratePerHour: 0, atMs: 9_000 });
  });

  it("rapporte les heures hors ligne", async () => {
    const { actions } = setup({ collect: [ok({ collected: 1_000, offlineHours: 8, state: companyState({ version: 2 }) })] });
    assert.deepEqual(await actions.collect(XUID), { kind: "collected", collected: 1_000, offlineHours: 8 });
  });

  it("un requestId différent pour chaque action", async () => {
    const { actions, api } = setup({
      collect: [
        ok({ collected: 1, offlineHours: 0, state: companyState({ version: 2 }) }),
        ok({ collected: 1, offlineHours: 0, state: companyState({ version: 3 }) }),
      ],
    });
    await actions.collect(XUID);
    await actions.collect(XUID);
    const ids = api.calls.map((call) => ("action" in call ? call.action.requestId : ""));
    assert.deepEqual(ids, ["id-1", "id-2"]);
  });
});

describe("monter une usine", () => {
  it("envoie l'usine et la quantité, range l'état et les soldes rendus", async () => {
    const { actions, api, views } = setup({ buyLevels: [ok(updated(2, 4, 5))] });
    assert.deepEqual(await actions.buyLevels(XUID, "carriere", "10"), { kind: "levelsBought" });
    assert.deepEqual(api.calls, [
      { op: "buyLevels", companyId: 7, factoryId: "carriere", action: { requestId: "id-1", actor: XUID }, amount: "10" },
    ]);
    assert.equal(views.get(XUID)?.state.factories[0]?.level, 4);
    assert.equal(views.get(XUID)?.balances.lingots, 5);
  });

  it("un refus du jeu est rendu avec ce qui manque, l'état ne bouge pas, rien n'est rejoué", async () => {
    const { actions, api, views } = setup({ buyLevels: [refused("PAS_ASSEZ_DE_DONAR", 120)] });
    assert.deepEqual(await actions.buyLevels(XUID, "carriere", "1"), {
      kind: "refused",
      reason: "PAS_ASSEZ_DE_DONAR",
      missing: 120,
    });
    assert.equal(api.calls.length, 1);
    assert.equal(views.get(XUID)?.state.version, 1);
  });

  it("un refus sans somme n'invente pas de champ missing", async () => {
    const { actions } = setup({ buyLevels: [refused("USINE_VERROUILLEE")] });
    assert.deepEqual(await actions.buyLevels(XUID, "carriere", "max"), { kind: "refused", reason: "USINE_VERROUILLEE" });
  });
});

describe("remettre une usine en état", () => {
  it("envoie l'étape demandée", async () => {
    const { actions, api } = setup({ restoreStep: [ok(updated(2, 0))] });
    assert.deepEqual(await actions.restoreStep(XUID, "scierie", 2), { kind: "stepDone" });
    assert.deepEqual(api.calls, [
      { op: "restoreStep", companyId: 7, factoryId: "scierie", action: { requestId: "id-1", actor: XUID }, step: 2 },
    ]);
  });
});

describe("conflit de version", () => {
  it("CONFLIT_REESSAYER : recharge l'état, rejoue une fois avec le même requestId, rend le résultat du rejeu", async () => {
    const { actions, api, views } = setup({
      buyLevels: [refused("CONFLIT_REESSAYER"), ok(updated(6, 5))],
      getCompany: [ok(companyState({ version: 5, donar: 900, factories: [factory({ level: 4 })] }))],
    });
    assert.deepEqual(await actions.buyLevels(XUID, "carriere", "1"), { kind: "levelsBought" });
    assert.deepEqual(
      api.calls.map((call) => call.op),
      ["buyLevels", "getCompany", "buyLevels"],
    );
    const sends = api.calls.flatMap((call) => (call.op === "buyLevels" ? [call.action] : []));
    assert.equal(sends.length, 2);
    assert.deepEqual(sends[0], sends[1]);
    assert.equal(views.get(XUID)?.state.version, 6);
  });

  it("recharge avant de rejouer : l'état rechargé est déjà rangé quand le rejeu part", async () => {
    const { actions, api, views } = setup({
      collect: [
        refused("CONFLIT_REESSAYER"),
        ok({ collected: 5, offlineHours: 0, state: companyState({ version: 9 }) }),
      ],
      getCompany: [ok(companyState({ version: 8, donar: 800 }))],
    });
    const before = api.collect.bind(api);
    let seenAtReplay: number | undefined;
    let sends = 0;
    api.collect = (companyId, action) => {
      sends += 1;
      if (sends === 2) seenAtReplay = views.get(XUID)?.state.version;
      return before(companyId, action);
    };
    await actions.collect(XUID);
    assert.equal(seenAtReplay, 8);
  });

  it("un second conflit est rendu tel quel : un seul rejeu, jamais de boucle", async () => {
    const { actions, api } = setup({
      restoreStep: [refused("CONFLIT_REESSAYER"), refused("CONFLIT_REESSAYER")],
      getCompany: [ok(companyState({ version: 2 }))],
    });
    assert.deepEqual(await actions.restoreStep(XUID, "carriere", 1), { kind: "refused", reason: "CONFLIT_REESSAYER" });
    assert.deepEqual(
      api.calls.map((call) => call.op),
      ["restoreStep", "getCompany", "restoreStep"],
    );
  });

  it("si le rechargement échoue, rien n'est rejoué et l'échec est rendu", async () => {
    const { actions, api, log } = setup({
      buyLevels: [refused("CONFLIT_REESSAYER")],
      getCompany: [unreachable()],
    });
    assert.deepEqual(await actions.buyLevels(XUID, "carriere", "1"), { kind: "failed", failure: "network", reason: "RESEAU" });
    assert.equal(api.calls.filter((call) => call.op === "buyLevels").length, 1);
    assert.match(log.warns.join("\n"), /réseau : délai dépassé/);
  });

  it("un autre refus n'est jamais rejoué", async () => {
    const { actions, api } = setup({ buyLevels: [refused("SEUIL_NON_ATTEINT")] });
    await actions.buyLevels(XUID, "carriere", "1");
    assert.equal(api.calls.length, 1);
  });
});

describe("échecs techniques", () => {
  it("réseau coupé : échec « network », journalisé", async () => {
    const { actions, log } = setup({ collect: [unreachable()] });
    assert.deepEqual(await actions.collect(XUID), { kind: "failed", failure: "network", reason: "RESEAU" });
    assert.match(log.warns.join("\n"), /collect : réseau/);
  });

  it("erreur du service : échec « error » avec le code du service", async () => {
    const { actions } = setup({ collect: [failure(500, "ERREUR_INTERNE")] });
    assert.deepEqual(await actions.collect(XUID), { kind: "failed", failure: "error", reason: "ERREUR_INTERNE" });
  });

  it("route pas encore écrite : échec « error » PAS_FAIT", async () => {
    const { actions } = setup({ buyLevels: [failure(501, "PAS_FAIT")] });
    assert.deepEqual(await actions.buyLevels(XUID, "carriere", "1"), { kind: "failed", failure: "error", reason: "PAS_FAIT" });
  });

  it("un échec ne touche pas à l'état", async () => {
    const { actions, views } = setup({ collect: [unreachable()] });
    await actions.collect(XUID);
    assert.equal(views.get(XUID)?.state.version, 1);
  });
});

describe("garde-fous", () => {
  it("un joueur sans réponse de join : indisponible, aucun appel", async () => {
    const { actions, api } = setup({});
    assert.deepEqual(await actions.collect("999"), { kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" });
    assert.deepEqual(api.calls, []);
  });

  it("une action déjà en cours pour le joueur : la seconde est ignorée, sans appel", async () => {
    const store = storeWith({ version: 1 });
    store.set({ xuid: "2", gamertag: "Bea", join: joinResponse({ state: companyState({ version: 1 }) }) });
    const views = createViewStore({ players: store, clock: fakeClock() });
    const calls: string[] = [];
    const releases: Array<() => void> = [];
    const slow = {
      getCompany: () => Promise.reject(new Error("inutile")),
      buyLevels: () => Promise.reject(new Error("inutile")),
      restoreStep: () => Promise.reject(new Error("inutile")),
      collect: (companyId: number) =>
        new Promise<ApiResult<CollectResponse>>((resolve) => {
          calls.push(`collect ${companyId}`);
          releases.push(() => resolve(ok({ collected: 1, offlineHours: 0, state: companyState({ version: 2 }) })));
        }),
    };
    const actions = createActions({ api: slow, views, newRequestId: sequentialIds(), log: recordingLogger() });

    const first = actions.collect(XUID);
    assert.deepEqual(await actions.collect(XUID), { kind: "busy" });
    assert.equal(calls.length, 1);
    // Un autre joueur n'est pas bloqué par le premier.
    const other = actions.collect("2");
    assert.equal(calls.length, 2);
    releases.forEach((release) => release());
    assert.equal((await first).kind, "collected");
    assert.equal((await other).kind, "collected");
    // Le premier a fini : le joueur peut agir de nouveau.
    const again = actions.collect(XUID);
    assert.equal(calls.length, 3);
    releases[2]?.();
    assert.equal((await again).kind, "collected");
  });

  it("le verrou du joueur tombe aussi quand l'appel lève", async () => {
    const store = storeWith();
    const views = createViewStore({ players: store, clock: fakeClock() });
    let appels = 0;
    const api = {
      getCompany: () => Promise.reject(new Error("inutile")),
      buyLevels: () => Promise.reject(new Error("inutile")),
      restoreStep: () => Promise.reject(new Error("inutile")),
      collect: () => {
        appels += 1;
        return appels === 1 ? Promise.reject(new Error("coupure")) : Promise.resolve(unreachable<never>());
      },
    };
    const actions = createActions({ api, views, newRequestId: sequentialIds(), log: recordingLogger() });
    await assert.rejects(actions.collect(XUID), /coupure/);
    assert.equal((await actions.collect(XUID)).kind, "failed");
  });

  it("le nom du joueur n'est pas l'identifiant : seul l'XUID part vers le service", async () => {
    const { actions, api } = setup({ collect: [ok({ collected: 0, offlineHours: 0, state: companyState({ version: 2 }) })] });
    await actions.collect(XUID);
    assert.doesNotMatch(JSON.stringify(api.calls), new RegExp(GAMERTAG));
  });
});
