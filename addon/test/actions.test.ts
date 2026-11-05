import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { ApiResult } from "../src/http";
import type { CollectResponse, CompanyUpdated } from "../src/protocol";
import { createActions } from "../src/ui/actions";
import { createViewStore } from "../src/ui/etat";
import { balances, candidate, companyState, factory, joinResponse, ok, prestigePreview, recordingLogger } from "./helpers";
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

/** Les routes du personnel, de l'entreprise partagée et de la ville, que les faux de ces tests n'attendent pas. */
const INUTILES = {
  getCandidates: () => Promise.reject(new Error("inutile")),
  hire: () => Promise.reject(new Error("inutile")),
  assignStaff: () => Promise.reject(new Error("inutile")),
  fireStaff: () => Promise.reject(new Error("inutile")),
  resolveIncident: () => Promise.reject(new Error("inutile")),
  invite: () => Promise.reject(new Error("inutile")),
  setShares: () => Promise.reject(new Error("inutile")),
  acceptInvite: () => Promise.reject(new Error("inutile")),
  buyZone: () => Promise.reject(new Error("inutile")),
  placeBuilding: () => Promise.reject(new Error("inutile")),
  upgradeBuilding: () => Promise.reject(new Error("inutile")),
};

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

describe("réparer un incident", () => {
  it("envoie l'incident et l'action, range l'état rendu et rapporte la réparation", async () => {
    const { actions, api, views } = setup({ resolveIncident: [ok(updated(2, 3))] });
    assert.deepEqual(await actions.resolveIncident(XUID, 31), { kind: "incidentResolved" });
    assert.deepEqual(api.calls, [
      { op: "resolveIncident", companyId: 7, incidentId: 31, action: { requestId: "id-1", actor: XUID } },
    ]);
    assert.equal(views.get(XUID)?.state.version, 2);
    assert.equal(views.get(XUID)?.state.factories[0]?.level, 3);
  });

  it("un refus du service est rendu avec son code, l'état ne bouge pas", async () => {
    const { actions, views } = setup({ resolveIncident: [refused("PAS_DE_RAISON")] });
    const avant = views.get(XUID)?.state.version;
    assert.deepEqual(await actions.resolveIncident(XUID, 31), { kind: "refused", reason: "PAS_DE_RAISON" });
    assert.equal(views.get(XUID)?.state.version, avant);
  });

  it("CONFLIT_REESSAYER : recharge l'état et rejoue une fois avec le même requestId", async () => {
    const { actions, api } = setup({
      resolveIncident: [refused("CONFLIT_REESSAYER"), ok(updated(6, 3))],
      getCompany: [ok(companyState({ version: 5 }))],
    });
    assert.deepEqual(await actions.resolveIncident(XUID, 31), { kind: "incidentResolved" });
    assert.deepEqual(
      api.calls.map((call) => call.op),
      ["resolveIncident", "getCompany", "resolveIncident"],
    );
    const [first, replay] = api.calls.filter((call) => call.op === "resolveIncident");
    assert.deepEqual(first, replay);
  });

  it("un échec du service ou du réseau est rendu tel quel", async () => {
    const { actions } = setup({ resolveIncident: [unreachable()] });
    assert.deepEqual(await actions.resolveIncident(XUID, 31), { kind: "failed", failure: "network", reason: "RESEAU" });
  });

  it("un joueur sans état n'envoie rien", async () => {
    const { actions, api } = setup({});
    assert.deepEqual(await actions.resolveIncident("999", 31), {
      kind: "failed",
      failure: "unavailable",
      reason: "PAS_DE_REPONSE_JOIN",
    });
    assert.equal(api.calls.length, 0);
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
      prestige: () => Promise.reject(new Error("inutile")),
      prestigePreview: () => Promise.reject(new Error("inutile")),
      ...INUTILES,
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
      prestige: () => Promise.reject(new Error("inutile")),
      prestigePreview: () => Promise.reject(new Error("inutile")),
      ...INUTILES,
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

describe("après un encaissement (les missions sont relues)", () => {
  function withAfter(script: Script, afterCollect: (xuid: string) => Promise<void>) {
    const clock = fakeClock(0);
    const store = storeWith({ ...BASE, factories: [...BASE.factories] });
    const views = createViewStore({ players: store, clock });
    const api = fakeCompany(script);
    const log = recordingLogger();
    const actions = createActions({ api, views, newRequestId: sequentialIds(), log, afterCollect });
    return { actions, api, views, log };
  }
  const collected = (version = 2) => ok({ collected: 250, offlineHours: 0, state: companyState({ version, donar: 350 }) });

  it("est appelé avec le joueur, une fois l'état du service rangé", async () => {
    const seen: Array<{ xuid: string; donar: number | undefined }> = [];
    const { actions, views } = withAfter({ collect: [collected()] }, async (xuid) => {
      seen.push({ xuid, donar: views.get(xuid)?.state.donar });
    });
    assert.deepEqual(await actions.collect(XUID), { kind: "collected", collected: 250, offlineHours: 0 });
    assert.deepEqual(seen, [{ xuid: XUID, donar: 350 }]);
  });

  it("est attendu : l'encaissement ne rend la main qu'une fois la relecture finie", async () => {
    let released = false;
    let release: () => void = () => undefined;
    const { actions } = withAfter({ collect: [collected()] }, () => new Promise<void>((resolve) => {
      release = () => {
        released = true;
        resolve();
      };
    }));
    let done = false;
    const pending = actions.collect(XUID).then((outcome) => {
      done = true;
      return outcome;
    });
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(done, false);
    release();
    assert.equal((await pending).kind, "collected");
    assert.equal(released, true);
  });

  it("n'est appelé ni pour un refus, ni pour une panne, ni pour un joueur inconnu", async () => {
    const calls: string[] = [];
    const afterCollect = async (xuid: string) => void calls.push(xuid);
    const refus = withAfter({ collect: [refused("PAS_FAIT")] }, afterCollect);
    assert.equal((await refus.actions.collect(XUID)).kind, "refused");
    const panne = withAfter({ collect: [unreachable()] }, afterCollect);
    assert.equal((await panne.actions.collect(XUID)).kind, "failed");
    const erreur = withAfter({ collect: [failure(501, "PAS_FAIT")] }, afterCollect);
    assert.equal((await erreur.actions.collect(XUID)).kind, "failed");
    const inconnu = withAfter({}, afterCollect);
    assert.equal((await inconnu.actions.collect("999")).kind, "failed");
    assert.deepEqual(calls, []);
  });

  it("n'est pas appelé pour une montée de niveau ni une remise en état", async () => {
    const calls: string[] = [];
    const { actions } = withAfter({ buyLevels: [ok(updated(2, 4))], restoreStep: [ok(updated(3, 4))] }, async (xuid) => void calls.push(xuid));
    await actions.buyLevels(XUID, "carriere", "1");
    await actions.restoreStep(XUID, "carriere", 1);
    assert.deepEqual(calls, []);
  });

  it("après un conflit rejoué avec succès, il est appelé une seule fois", async () => {
    const calls: string[] = [];
    const { actions, api } = withAfter(
      {
        collect: [refused("CONFLIT_REESSAYER"), collected(6)],
        getCompany: [ok(companyState({ version: 5, factories: [factory({ level: 3 })] }))],
      },
      async (xuid) => void calls.push(xuid),
    );
    assert.equal((await actions.collect(XUID)).kind, "collected");
    assert.deepEqual(calls, [XUID]);
    assert.deepEqual(api.calls.map((call) => call.op), ["collect", "getCompany", "collect"]);
  });

  it("une exception de la relecture est journalisée et ne change rien au résultat de l'encaissement", async () => {
    const { actions, views, log } = withAfter({ collect: [collected()] }, () => Promise.reject(new Error("coupure des missions")));
    assert.deepEqual(await actions.collect(XUID), { kind: "collected", collected: 250, offlineHours: 0 });
    assert.equal(views.get(XUID)?.state.donar, 350);
    assert.equal(log.warns.length, 1);
    assert.match(log.warns[0] ?? "", /^après collect : .*coupure des missions/);
  });

  it("le verrou du joueur est déjà tombé quand la relecture commence", async () => {
    let second: string | undefined;
    const holder: { actions?: ReturnType<typeof createActions> } = {};
    const clock = fakeClock(0);
    const store = storeWith({ ...BASE, factories: [...BASE.factories] });
    const views = createViewStore({ players: store, clock });
    const api = fakeCompany({ collect: [collected(2), collected(3)] });
    holder.actions = createActions({
      api,
      views,
      newRequestId: sequentialIds(),
      log: recordingLogger(),
      afterCollect: async (xuid) => {
        if (second === undefined) second = (await holder.actions?.collect(xuid))?.kind;
      },
    });
    assert.equal((await holder.actions.collect(XUID)).kind, "collected");
    assert.equal(second, "collected");
  });

  it("sans afterCollect, rien ne change", async () => {
    const { actions } = setup({ collect: [collected()] });
    assert.deepEqual(await actions.collect(XUID), { kind: "collected", collected: 250, offlineHours: 0 });
  });
});

// ---------------------------------------------------------------- phase 4 : le prestige

/** Ce que le service rend après un prestige : la course suivante, le Donar à zéro, les Lingots versés. */
const prestiged = (version: number, lingots = 1_200): CompanyUpdated => ({
  state: companyState({ version, era: 2, courseNumber: 3, donar: 0, donarEarned: 0, factories: [factory({ level: 1 })] }),
  balances: balances({ lingots }),
});

describe("le prestige", () => {
  it("envoie un requestId neuf et l'identifiant du joueur, range l'état et les soldes rendus", async () => {
    const { actions, api, views } = setup({ prestige: [ok(prestiged(2))] });
    assert.deepEqual(await actions.prestige(XUID), { kind: "prestiged" });
    assert.deepEqual(api.calls, [{ op: "prestige", companyId: 7, action: { requestId: "id-1", actor: XUID } }]);
    assert.equal(views.get(XUID)?.state.version, 2);
    assert.equal(views.get(XUID)?.state.courseNumber, 3);
    assert.equal(views.get(XUID)?.state.donar, 0);
    assert.equal(views.get(XUID)?.balances.lingots, 1_200);
  });

  it("refus PAS_LE_PDG : rendu tel quel, l'état ne bouge pas, rien n'est rejoué", async () => {
    const { actions, api, views } = setup({ prestige: [refused("PAS_LE_PDG")] });
    assert.deepEqual(await actions.prestige(XUID), { kind: "refused", reason: "PAS_LE_PDG" });
    assert.equal(api.calls.length, 1);
    assert.equal(views.get(XUID)?.state.version, 1);
  });

  it("refus SEUIL_NON_ATTEINT : les Donar qui manquent sont rendus, l'état ne bouge pas", async () => {
    const { actions, views } = setup({ prestige: [refused("SEUIL_NON_ATTEINT", 4_000)] });
    assert.deepEqual(await actions.prestige(XUID), { kind: "refused", reason: "SEUIL_NON_ATTEINT", missing: 4_000 });
    assert.equal(views.get(XUID)?.state.version, 1);
    assert.equal(views.get(XUID)?.state.donar, 100);
  });

  it("CONFLIT_REESSAYER : recharge l'état, rejoue une fois avec le même requestId, rend le résultat du rejeu", async () => {
    const { actions, api, views } = setup({
      prestige: [refused("CONFLIT_REESSAYER"), ok(prestiged(6))],
      getCompany: [ok(companyState({ version: 5, donar: 900, factories: [factory({ level: 4 })] }))],
    });
    assert.deepEqual(await actions.prestige(XUID), { kind: "prestiged" });
    assert.deepEqual(api.calls.map((call) => call.op), ["prestige", "getCompany", "prestige"]);
    const sends = api.calls.flatMap((call) => (call.op === "prestige" ? [call.action] : []));
    assert.equal(sends.length, 2);
    assert.deepEqual(sends[0], sends[1]);
    assert.equal(views.get(XUID)?.state.version, 6);
  });

  it("un second conflit est rendu tel quel : un seul rejeu, jamais de boucle", async () => {
    const { actions, api } = setup({
      prestige: [refused("CONFLIT_REESSAYER"), refused("CONFLIT_REESSAYER")],
      getCompany: [ok(companyState({ version: 2 }))],
    });
    assert.deepEqual(await actions.prestige(XUID), { kind: "refused", reason: "CONFLIT_REESSAYER" });
    assert.deepEqual(api.calls.map((call) => call.op), ["prestige", "getCompany", "prestige"]);
  });

  it("réseau coupé : échec « network », journalisé, l'état ne bouge pas", async () => {
    const { actions, log, views } = setup({ prestige: [unreachable()] });
    assert.deepEqual(await actions.prestige(XUID), { kind: "failed", failure: "network", reason: "RESEAU" });
    assert.match(log.warns.join("\n"), /prestige : réseau/);
    assert.equal(views.get(XUID)?.state.version, 1);
  });

  it("route pas encore écrite : échec « error » PAS_FAIT", async () => {
    const { actions } = setup({ prestige: [failure(501, "PAS_FAIT")] });
    assert.deepEqual(await actions.prestige(XUID), { kind: "failed", failure: "error", reason: "PAS_FAIT" });
  });

  it("un joueur sans réponse de join : indisponible, aucun appel", async () => {
    const { actions, api } = setup({});
    assert.deepEqual(await actions.prestige("999"), { kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" });
    assert.deepEqual(api.calls, []);
  });

  it("un seul appel à la fois : un prestige déjà en cours rend « occupé » à la seconde demande, sans appel", async () => {
    const store = storeWith({ version: 1 });
    const views = createViewStore({ players: store, clock: fakeClock() });
    let envois = 0;
    let finir: (() => void) | undefined;
    const api = {
      getCompany: () => Promise.reject(new Error("inutile")),
      collect: () => Promise.reject(new Error("inutile")),
      buyLevels: () => Promise.reject(new Error("inutile")),
      restoreStep: () => Promise.reject(new Error("inutile")),
      prestigePreview: () => Promise.resolve(ok(prestigePreview())),
      ...INUTILES,
      prestige: () =>
        new Promise<ApiResult<CompanyUpdated>>((resolve) => {
          envois += 1;
          finir = () => resolve(ok(prestiged(2)));
        }),
    };
    const actions = createActions({ api, views, newRequestId: sequentialIds(), log: recordingLogger() });
    const premier = actions.prestige(XUID);
    assert.deepEqual(await actions.prestige(XUID), { kind: "busy" });
    assert.deepEqual(await actions.collect(XUID), { kind: "busy" });
    assert.equal(envois, 1);
    // L'aperçu est une lecture : il n'attend pas le prestige et ne prend pas le verrou.
    assert.equal((await actions.preview(XUID)).kind, "preview");
    finir?.();
    assert.deepEqual(await premier, { kind: "prestiged" });
    assert.equal(views.get(XUID)?.state.version, 2);
  });
});

describe("l'aperçu du prestige", () => {
  it("lit l'aperçu du service pour l'entreprise du joueur, sans requestId ni acteur", async () => {
    const apercu = prestigePreview({ lingotsTotal: 1_900, next: "ETOILE" });
    const { actions, api } = setup({ prestigePreview: [ok(apercu)] });
    assert.deepEqual(await actions.preview(XUID), { kind: "preview", preview: apercu });
    assert.deepEqual(api.calls, [{ op: "prestigePreview", companyId: 7 }]);
  });

  it("ne change rien à la vue du joueur", async () => {
    const { actions, views } = setup({ prestigePreview: [ok(prestigePreview())] });
    const avant = views.get(XUID);
    await actions.preview(XUID);
    assert.equal(views.get(XUID), avant);
    assert.equal(views.get(XUID)?.state.version, 1);
  });

  it("réseau coupé : échec « network », journalisé", async () => {
    const { actions, log } = setup({ prestigePreview: [unreachable()] });
    assert.deepEqual(await actions.preview(XUID), { kind: "failed", failure: "network", reason: "RESEAU" });
    assert.match(log.warns.join("\n"), /prestigePreview : réseau/);
  });

  it("erreur du service et route pas encore écrite : échec « error » avec le code du service", async () => {
    const { actions } = setup({ prestigePreview: [failure(500, "ERREUR_INTERNE"), failure(501, "PAS_FAIT")] });
    assert.deepEqual(await actions.preview(XUID), { kind: "failed", failure: "error", reason: "ERREUR_INTERNE" });
    assert.deepEqual(await actions.preview(XUID), { kind: "failed", failure: "error", reason: "PAS_FAIT" });
  });

  it("un refus que le contrat ne prévoit pas est une erreur du service, pas un refus du jeu", async () => {
    const { actions } = setup({ prestigePreview: [refused("CODE_DU_FUTUR")] });
    assert.deepEqual(await actions.preview(XUID), { kind: "failed", failure: "error", reason: "CODE_DU_FUTUR" });
  });

  it("un joueur sans réponse de join : indisponible, aucun appel", async () => {
    const { actions, api } = setup({});
    assert.deepEqual(await actions.preview("999"), { kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" });
    assert.deepEqual(api.calls, []);
  });
});

describe("embaucher", () => {
  it("envoie la place et l'usine avec un requestId neuf, range l'état et les soldes rendus", async () => {
    const { actions, api, views } = setup({ hire: [ok(updated(2, 3, 7))] });
    assert.deepEqual(await actions.hire(XUID, 3, "carriere"), { kind: "hired" });
    assert.deepEqual(api.calls, [
      { op: "hire", companyId: 7, slot: 3, action: { requestId: "id-1", actor: XUID }, factoryId: "carriere" },
    ]);
    assert.equal(views.get(XUID)?.state.version, 2);
    assert.equal(views.get(XUID)?.state.donar, 40);
    assert.equal(views.get(XUID)?.balances.lingots, 7);
  });

  it("sans usine, l'usine est absente de l'appel", async () => {
    const { actions, api } = setup({ hire: [ok(updated(2, 3))] });
    await actions.hire(XUID, 1);
    assert.deepEqual(api.calls, [
      { op: "hire", companyId: 7, slot: 1, action: { requestId: "id-1", actor: XUID }, factoryId: undefined },
    ]);
  });

  it("PAS_ASSEZ_DE_DONAR : les Donar qui manquent sont rendus, l'état ne bouge pas, rien n'est rejoué", async () => {
    const { actions, api, views } = setup({ hire: [refused("PAS_ASSEZ_DE_DONAR", 1_200)] });
    assert.deepEqual(await actions.hire(XUID, 1), { kind: "refused", reason: "PAS_ASSEZ_DE_DONAR", missing: 1_200 });
    assert.equal(api.calls.length, 1);
    assert.equal(views.get(XUID)?.state.version, 1);
    assert.equal(views.get(XUID)?.state.donar, 100);
  });

  it("DEJA_POSSEDE et EMPLACEMENT_OCCUPE sont rendus tels quels", async () => {
    const { actions } = setup({ hire: [refused("DEJA_POSSEDE"), refused("EMPLACEMENT_OCCUPE")] });
    assert.deepEqual(await actions.hire(XUID, 1), { kind: "refused", reason: "DEJA_POSSEDE" });
    assert.deepEqual(await actions.hire(XUID, 1, "carriere"), { kind: "refused", reason: "EMPLACEMENT_OCCUPE" });
  });

  it("CONFLIT_REESSAYER : recharge l'état, rejoue une fois avec le même requestId", async () => {
    const { actions, api, views } = setup({
      hire: [refused("CONFLIT_REESSAYER"), ok(updated(6, 3))],
      getCompany: [ok(companyState({ version: 5, donar: 900, factories: [factory({ level: 4 })] }))],
    });
    assert.deepEqual(await actions.hire(XUID, 2, "carriere"), { kind: "hired" });
    assert.deepEqual(api.calls.map((call) => call.op), ["hire", "getCompany", "hire"]);
    const envois = api.calls.flatMap((call) => (call.op === "hire" ? [call] : []));
    assert.deepEqual(envois[0]?.action, envois[1]?.action);
    assert.equal(envois[1]?.slot, 2);
    assert.equal(envois[1]?.factoryId, "carriere");
    assert.equal(views.get(XUID)?.state.version, 6);
  });

  it("un second conflit est rendu tel quel : un seul rejeu", async () => {
    const { actions, api } = setup({
      hire: [refused("CONFLIT_REESSAYER"), refused("CONFLIT_REESSAYER")],
      getCompany: [ok(companyState({ version: 2 }))],
    });
    assert.deepEqual(await actions.hire(XUID, 1), { kind: "refused", reason: "CONFLIT_REESSAYER" });
    assert.deepEqual(api.calls.map((call) => call.op), ["hire", "getCompany", "hire"]);
  });

  it("réseau coupé : échec « network », journalisé, l'état ne bouge pas", async () => {
    const { actions, log, views } = setup({ hire: [unreachable()] });
    assert.deepEqual(await actions.hire(XUID, 1), { kind: "failed", failure: "network", reason: "RESEAU" });
    assert.match(log.warns.join("\n"), /hire : réseau/);
    assert.equal(views.get(XUID)?.state.version, 1);
  });

  it("un joueur sans réponse de join : indisponible, aucun appel", async () => {
    const { actions, api } = setup({});
    assert.deepEqual(await actions.hire("999", 1), { kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" });
    assert.deepEqual(api.calls, []);
  });
});

describe("affecter un membre", () => {
  it("envoie le membre et l'usine, range l'état rendu", async () => {
    const { actions, api, views } = setup({ assignStaff: [ok(updated(2, 3))] });
    assert.deepEqual(await actions.assignStaff(XUID, 11, "scierie"), { kind: "assigned" });
    assert.deepEqual(api.calls, [
      { op: "assignStaff", companyId: 7, staffId: 11, action: { requestId: "id-1", actor: XUID }, factoryId: "scierie" },
    ]);
    assert.equal(views.get(XUID)?.state.version, 2);
  });

  it("EMPLACEMENT_OCCUPE : rendu tel quel, l'état ne bouge pas", async () => {
    const { actions, views } = setup({ assignStaff: [refused("EMPLACEMENT_OCCUPE")] });
    assert.deepEqual(await actions.assignStaff(XUID, 11, "scierie"), { kind: "refused", reason: "EMPLACEMENT_OCCUPE" });
    assert.equal(views.get(XUID)?.state.version, 1);
  });

  it("CONFLIT_REESSAYER : un seul rejeu, avec le même requestId", async () => {
    const { actions, api } = setup({
      assignStaff: [refused("CONFLIT_REESSAYER"), ok(updated(6, 3))],
      getCompany: [ok(companyState({ version: 5 }))],
    });
    assert.deepEqual(await actions.assignStaff(XUID, 11, "scierie"), { kind: "assigned" });
    const envois = api.calls.flatMap((call) => (call.op === "assignStaff" ? [call.action] : []));
    assert.equal(envois.length, 2);
    assert.deepEqual(envois[0], envois[1]);
  });

  it("erreur du service : échec « error » avec le code du service", async () => {
    const { actions } = setup({ assignStaff: [failure(400, "STAFF_INCONNU")] });
    assert.deepEqual(await actions.assignStaff(XUID, 11, "scierie"), { kind: "failed", failure: "error", reason: "STAFF_INCONNU" });
  });
});

describe("renvoyer un membre", () => {
  it("envoie le membre avec un requestId neuf, range l'état rendu", async () => {
    const { actions, api, views } = setup({ fireStaff: [ok(updated(2, 3))] });
    assert.deepEqual(await actions.fireStaff(XUID, 11), { kind: "fired" });
    assert.deepEqual(api.calls, [{ op: "fireStaff", companyId: 7, staffId: 11, action: { requestId: "id-1", actor: XUID } }]);
    assert.equal(views.get(XUID)?.state.version, 2);
  });

  it("PAS_LE_PDG : rendu tel quel, l'état ne bouge pas, rien n'est rejoué", async () => {
    const { actions, api, views } = setup({
      fireStaff: [{ kind: "refused", status: 403, reason: "PAS_LE_PDG" }],
    });
    assert.deepEqual(await actions.fireStaff(XUID, 11), { kind: "refused", reason: "PAS_LE_PDG" });
    assert.equal(api.calls.length, 1);
    assert.equal(views.get(XUID)?.state.version, 1);
  });

  it("CONFLIT_REESSAYER : un seul rejeu", async () => {
    const { actions, api } = setup({
      fireStaff: [refused("CONFLIT_REESSAYER"), ok(updated(6, 3))],
      getCompany: [ok(companyState({ version: 5 }))],
    });
    assert.deepEqual(await actions.fireStaff(XUID, 11), { kind: "fired" });
    assert.deepEqual(api.calls.map((call) => call.op), ["fireStaff", "getCompany", "fireStaff"]);
  });

  it("une seule action à la fois : un renvoi pendant une embauche rend « occupé » sans appel", async () => {
    const store = storeWith({ version: 1 });
    const views = createViewStore({ players: store, clock: fakeClock() });
    let envois = 0;
    let finir: (() => void) | undefined;
    const api = {
      getCompany: () => Promise.reject(new Error("inutile")),
      collect: () => Promise.reject(new Error("inutile")),
      buyLevels: () => Promise.reject(new Error("inutile")),
      restoreStep: () => Promise.reject(new Error("inutile")),
      prestige: () => Promise.reject(new Error("inutile")),
      prestigePreview: () => Promise.reject(new Error("inutile")),
      getCandidates: () => Promise.resolve(ok([candidate()])),
      assignStaff: () => Promise.reject(new Error("inutile")),
      resolveIncident: () => Promise.reject(new Error("inutile")),
      fireStaff: () => Promise.reject(new Error("inutile")),
      invite: () => Promise.reject(new Error("inutile")),
      setShares: () => Promise.reject(new Error("inutile")),
      acceptInvite: () => Promise.reject(new Error("inutile")),
      buyZone: () => Promise.reject(new Error("inutile")),
      placeBuilding: () => Promise.reject(new Error("inutile")),
      upgradeBuilding: () => Promise.reject(new Error("inutile")),
      hire: () =>
        new Promise<ApiResult<CompanyUpdated>>((resolve) => {
          envois += 1;
          finir = () => resolve(ok(updated(2, 3)));
        }),
    };
    const actions = createActions({ api, views, newRequestId: sequentialIds(), log: recordingLogger() });
    const premier = actions.hire(XUID, 1);
    assert.deepEqual(await actions.fireStaff(XUID, 11), { kind: "busy" });
    assert.deepEqual(await actions.hire(XUID, 2), { kind: "busy" });
    assert.equal(envois, 1);
    // Les candidats sont une lecture : elle n'attend pas l'embauche et ne prend pas le verrou.
    assert.equal((await actions.candidates(XUID)).kind, "candidates");
    finir?.();
    assert.deepEqual(await premier, { kind: "hired" });
    assert.equal(views.get(XUID)?.state.version, 2);
  });
});

describe("les candidats du jour", () => {
  it("lit la liste du service pour l'entreprise du joueur, sans requestId ni acteur", async () => {
    const liste = [candidate(), candidate({ slot: 2, name: "Camille", rarity: "RARE", role: "CHEF" })];
    const { actions, api } = setup({ getCandidates: [ok(liste)] });
    assert.deepEqual(await actions.candidates(XUID), { kind: "candidates", candidates: liste });
    assert.deepEqual(api.calls, [{ op: "getCandidates", companyId: 7 }]);
  });

  it("ne change rien à la vue du joueur", async () => {
    const { actions, views } = setup({ getCandidates: [ok([candidate()])] });
    const avant = views.get(XUID);
    await actions.candidates(XUID);
    assert.equal(views.get(XUID), avant);
    assert.equal(views.get(XUID)?.state.version, 1);
  });

  it("chaque ouverture relit le service", async () => {
    const { actions, api } = setup({ getCandidates: [ok([candidate()]), ok([candidate({ name: "Denis" })])] });
    await actions.candidates(XUID);
    const second = await actions.candidates(XUID);
    assert.equal(api.calls.length, 2);
    assert.equal(second.kind === "candidates" ? second.candidates[0]?.name : "", "Denis");
  });

  it("réseau coupé : échec « network », journalisé", async () => {
    const { actions, log } = setup({ getCandidates: [unreachable()] });
    assert.deepEqual(await actions.candidates(XUID), { kind: "failed", failure: "network", reason: "RESEAU" });
    assert.match(log.warns.join("\n"), /getCandidates : réseau/);
  });

  it("erreur du service, route pas encore écrite et refus imprévu : échec « error »", async () => {
    const { actions } = setup({
      getCandidates: [failure(500, "ERREUR_INTERNE"), failure(501, "PAS_FAIT"), refused("CODE_DU_FUTUR")],
    });
    assert.deepEqual(await actions.candidates(XUID), { kind: "failed", failure: "error", reason: "ERREUR_INTERNE" });
    assert.deepEqual(await actions.candidates(XUID), { kind: "failed", failure: "error", reason: "PAS_FAIT" });
    assert.deepEqual(await actions.candidates(XUID), { kind: "failed", failure: "error", reason: "CODE_DU_FUTUR" });
  });

  it("un joueur sans réponse de join : indisponible, aucun appel", async () => {
    const { actions, api } = setup({});
    assert.deepEqual(await actions.candidates("999"), { kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" });
    assert.deepEqual(api.calls, []);
  });
});

const CIBLE = "2535428000000002";
const PARTS = [
  { xuid: XUID, percent: 70 },
  { xuid: CIBLE, percent: 30 },
];

describe("inviter un joueur", () => {
  it("envoie le joueur invité avec un requestId neuf, range l'état rendu", async () => {
    const { actions, api, views } = setup({ invite: [ok(updated(2, 3))] });
    assert.deepEqual(await actions.invite(XUID, CIBLE), { kind: "invited" });
    assert.deepEqual(api.calls, [{ op: "invite", companyId: 7, action: { requestId: "id-1", actor: XUID }, targetXuid: CIBLE }]);
    assert.equal(views.get(XUID)?.state.version, 2);
  });

  it("les refus du contrat sont rendus tels quels, l'état ne bouge pas, rien n'est rejoué", async () => {
    for (const reason of ["PAS_LE_PDG", "ENTREPRISE_PLEINE", "DEJA_POSSEDE", "ENTREPRISE_EN_COURS"]) {
      const { actions, api, views } = setup({ invite: [refused(reason)] });
      assert.deepEqual(await actions.invite(XUID, CIBLE), { kind: "refused", reason });
      assert.equal(views.get(XUID)?.state.version, 1);
      assert.equal(api.calls.length, 1);
    }
  });

  it("un 400 (joueur inconnu) est un échec « error » REQUETE_INVALIDE, journalisé", async () => {
    const { actions, log } = setup({ invite: [failure(400, "REQUETE_INVALIDE")] });
    assert.deepEqual(await actions.invite(XUID, CIBLE), { kind: "failed", failure: "error", reason: "REQUETE_INVALIDE" });
    assert.match(log.warns.join(" "), /invite/);
  });

  it("CONFLIT_REESSAYER : recharge l'état, rejoue une fois avec le même requestId", async () => {
    const { actions, api } = setup({
      invite: [refused("CONFLIT_REESSAYER"), ok(updated(6, 3))],
      getCompany: [ok(companyState({ version: 5 }))],
    });
    assert.deepEqual(await actions.invite(XUID, CIBLE), { kind: "invited" });
    const envois = api.calls.flatMap((call) => (call.op === "invite" ? [call.action] : []));
    assert.equal(envois.length, 2);
    assert.deepEqual(envois[0], envois[1]);
    assert.ok(api.calls.some((call) => call.op === "getCompany"));
  });

  it("réseau coupé : échec « network », l'état ne bouge pas", async () => {
    const { actions, views } = setup({ invite: [unreachable()] });
    assert.deepEqual(await actions.invite(XUID, CIBLE), { kind: "failed", failure: "network", reason: "RESEAU" });
    assert.equal(views.get(XUID)?.state.version, 1);
  });

  it("un joueur sans réponse de join : indisponible, aucun appel", async () => {
    const { actions, api } = setup({});
    assert.deepEqual(await actions.invite("999", CIBLE), { kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" });
    assert.deepEqual(api.calls, []);
  });
});

describe("fixer les parts", () => {
  it("envoie les parts telles quelles avec un requestId neuf, range l'état rendu", async () => {
    const { actions, api, views } = setup({ setShares: [ok(updated(2, 3))] });
    assert.deepEqual(await actions.setShares(XUID, PARTS), { kind: "sharesSet" });
    assert.deepEqual(api.calls, [{ op: "setShares", companyId: 7, action: { requestId: "id-1", actor: XUID }, shares: PARTS }]);
    assert.equal(views.get(XUID)?.state.version, 2);
  });

  it("n'additionne rien : des parts qui ne font pas 100 partent quand même, le 400 du service est rendu", async () => {
    const trop = [
      { xuid: XUID, percent: 90 },
      { xuid: CIBLE, percent: 90 },
    ];
    const { actions, api, views } = setup({ setShares: [failure(400, "REQUETE_INVALIDE")] });
    assert.deepEqual(await actions.setShares(XUID, trop), { kind: "failed", failure: "error", reason: "REQUETE_INVALIDE" });
    assert.deepEqual(api.calls.length, 1);
    assert.equal(views.get(XUID)?.state.version, 1);
  });

  it("PAS_LE_PDG : rendu tel quel, l'état ne bouge pas", async () => {
    const { actions, views } = setup({ setShares: [refused("PAS_LE_PDG")] });
    assert.deepEqual(await actions.setShares(XUID, PARTS), { kind: "refused", reason: "PAS_LE_PDG" });
    assert.equal(views.get(XUID)?.state.version, 1);
  });

  it("CONFLIT_REESSAYER : un seul rejeu, avec le même requestId", async () => {
    const { actions, api } = setup({
      setShares: [refused("CONFLIT_REESSAYER"), ok(updated(6, 3))],
      getCompany: [ok(companyState({ version: 5 }))],
    });
    assert.deepEqual(await actions.setShares(XUID, PARTS), { kind: "sharesSet" });
    const envois = api.calls.flatMap((call) => (call.op === "setShares" ? [call.action] : []));
    assert.equal(envois.length, 2);
    assert.deepEqual(envois[0], envois[1]);
  });
});

describe("accepter une invitation", () => {
  const autre = (version: number): CompanyUpdated => ({
    state: companyState({ companyId: 12, version, donar: 500, ratePerHour: 1_800, factories: [factory({ level: 1 })] }),
    balances: balances({ lingots: 3 }),
  });

  function joining(script: Script, afterJoin?: (xuid: string) => void | Promise<void>) {
    const clock = fakeClock(0);
    const store = storeWith({ ...BASE, factories: [...BASE.factories] });
    const views = createViewStore({ players: store, clock });
    const api = fakeCompany(script);
    const log = recordingLogger();
    const actions = createActions({
      api,
      views,
      newRequestId: sequentialIds(),
      log,
      ...(afterJoin === undefined ? {} : { afterJoin }),
    });
    return { actions, api, views, log };
  }

  it("vise l'entreprise de l'invitation, pas celle du joueur, et prend l'état de la réponse", async () => {
    const { actions, api, views } = joining({ acceptInvite: [ok(autre(2))] });
    assert.deepEqual(await actions.acceptInvite(XUID, 12), { kind: "joined" });
    assert.deepEqual(api.calls, [{ op: "acceptInvite", companyId: 12, action: { requestId: "id-1", actor: XUID } }]);
    const view = views.get(XUID);
    assert.equal(view?.state.companyId, 12);
    assert.equal(view?.state.version, 2);
    assert.equal(view?.balances.lingots, 3);
  });

  it("appelle afterJoin avec le joueur, une fois la vue changée, et l'attend", async () => {
    const vus: Array<{ xuid: string; companyId: number | undefined }> = [];
    let fini = false;
    const { actions, views } = joining({ acceptInvite: [ok(autre(2))] }, async (xuid) => {
      await Promise.resolve();
      vus.push({ xuid, companyId: views.get(xuid)?.state.companyId });
      fini = true;
    });
    await actions.acceptInvite(XUID, 12);
    assert.equal(fini, true);
    assert.deepEqual(vus, [{ xuid: XUID, companyId: 12 }]);
  });

  it("une exception d'afterJoin est journalisée et ne change pas le résultat", async () => {
    const { actions, log, views } = joining({ acceptInvite: [ok(autre(2))] }, () => {
      throw new Error("hud cassé");
    });
    assert.deepEqual(await actions.acceptInvite(XUID, 12), { kind: "joined" });
    assert.equal(views.get(XUID)?.state.companyId, 12);
    assert.match(log.warns.join(" "), /hud cassé/);
  });

  it("afterJoin n'est appelé ni pour un refus, ni pour un 400, ni pour une panne", async () => {
    for (const reponse of [refused("DEJA_POSSEDE"), refused("ENTREPRISE_PLEINE"), failure(400, "REQUETE_INVALIDE"), unreachable<CompanyUpdated>()]) {
      let appels = 0;
      const { actions, views } = joining({ acceptInvite: [reponse] }, () => {
        appels += 1;
      });
      const outcome = await actions.acceptInvite(XUID, 12);
      assert.notEqual(outcome.kind, "joined");
      assert.equal(appels, 0);
      assert.equal(views.get(XUID)?.state.companyId, 7, "le joueur reste dans son entreprise");
    }
  });

  it("les refus du contrat sont rendus tels quels", async () => {
    for (const reason of ["DEJA_POSSEDE", "ENTREPRISE_PLEINE", "ENTREPRISE_EN_COURS"]) {
      const { actions } = joining({ acceptInvite: [refused(reason)] });
      assert.deepEqual(await actions.acceptInvite(XUID, 12), { kind: "refused", reason });
    }
  });

  it("un 400 (invitation absente ou périmée) est un échec « error » REQUETE_INVALIDE", async () => {
    const { actions } = joining({ acceptInvite: [failure(400, "REQUETE_INVALIDE")] });
    assert.deepEqual(await actions.acceptInvite(XUID, 12), { kind: "failed", failure: "error", reason: "REQUETE_INVALIDE" });
  });

  it("CONFLIT_REESSAYER : rejoue une fois avec le même requestId, sans relire l'entreprise du joueur", async () => {
    const { actions, api, views } = joining({ acceptInvite: [refused("CONFLIT_REESSAYER"), ok(autre(2))] });
    assert.deepEqual(await actions.acceptInvite(XUID, 12), { kind: "joined" });
    const envois = api.calls.flatMap((call) => (call.op === "acceptInvite" ? [call] : []));
    assert.equal(envois.length, 2);
    assert.deepEqual(envois[0]?.action, envois[1]?.action);
    assert.equal(envois[1]?.companyId, 12);
    assert.equal(api.calls.some((call) => call.op === "getCompany"), false);
    assert.equal(views.get(XUID)?.state.companyId, 12);
  });

  it("un second conflit est rendu tel quel : un seul rejeu", async () => {
    const { actions, api } = joining({ acceptInvite: [refused("CONFLIT_REESSAYER"), refused("CONFLIT_REESSAYER")] });
    assert.deepEqual(await actions.acceptInvite(XUID, 12), { kind: "refused", reason: "CONFLIT_REESSAYER" });
    assert.equal(api.calls.length, 2);
  });

  it("un joueur sans réponse de join : indisponible, aucun appel", async () => {
    const { actions, api } = joining({});
    assert.deepEqual(await actions.acceptInvite("999", 12), { kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" });
    assert.deepEqual(api.calls, []);
  });
});

describe("relire l'entreprise (refresh)", () => {
  it("lit l'entreprise du joueur et range l'état rendu", async () => {
    const membres = [{ xuid: XUID, name: GAMERTAG, role: "PDG" as const, sharePercent: 100 }];
    const { actions, api, views } = setup({ getCompany: [ok(companyState({ version: 4, members: membres }))] });
    assert.deepEqual(await actions.refresh(XUID), { kind: "refreshed" });
    assert.deepEqual(api.calls, [{ op: "getCompany", companyId: 7 }]);
    assert.deepEqual(views.get(XUID)?.state.members, membres);
  });

  it("une relecture plus ancienne que la vue ne la remplace pas", async () => {
    const { actions, views } = setup({ getCompany: [ok(companyState({ version: 0, donar: 999 }))] });
    assert.deepEqual(await actions.refresh(XUID), { kind: "refreshed" });
    assert.equal(views.get(XUID)?.state.donar, 100);
  });

  it("un échec est rendu et journalisé, la vue ne bouge pas", async () => {
    const { actions, views, log } = setup({ getCompany: [unreachable()] });
    assert.deepEqual(await actions.refresh(XUID), { kind: "failed", failure: "network", reason: "RESEAU" });
    assert.equal(views.get(XUID)?.state.version, 1);
    assert.match(log.warns.join(" "), /getCompany/);
  });

  it("un joueur sans réponse de join : indisponible, aucun appel", async () => {
    const { actions, api } = setup({});
    assert.deepEqual(await actions.refresh("999"), { kind: "failed", failure: "unavailable", reason: "PAS_DE_REPONSE_JOIN" });
    assert.deepEqual(api.calls, []);
  });
});

describe("les actions de l'entreprise partagée respectent le verrou du joueur", () => {
  it("une invitation pendant un encaissement rend « occupé » sans appel", async () => {
    let finir: (value: ApiResult<CollectResponse>) => void = () => undefined;
    const attente = new Promise<ApiResult<CollectResponse>>((resolve) => {
      finir = resolve;
    });
    const store = storeWith({ ...BASE, factories: [...BASE.factories] });
    const views = createViewStore({ players: store, clock: fakeClock(0) });
    const appels: string[] = [];
    const actions = createActions({
      api: {
        ...INUTILES,
        getCompany: () => Promise.reject(new Error("inutile")),
        collect: () => attente,
        buyLevels: () => Promise.reject(new Error("inutile")),
        restoreStep: () => Promise.reject(new Error("inutile")),
        prestige: () => Promise.reject(new Error("inutile")),
        prestigePreview: () => Promise.reject(new Error("inutile")),
        invite: () => {
          appels.push("invite");
          return Promise.reject(new Error("ne doit pas partir"));
        },
        setShares: () => {
          appels.push("setShares");
          return Promise.reject(new Error("ne doit pas partir"));
        },
        acceptInvite: () => {
          appels.push("acceptInvite");
          return Promise.reject(new Error("ne doit pas partir"));
        },
      },
      views,
      newRequestId: sequentialIds(),
      log: recordingLogger(),
    });
    const encaissement = actions.collect(XUID);
    assert.deepEqual(await actions.invite(XUID, CIBLE), { kind: "busy" });
    assert.deepEqual(await actions.setShares(XUID, PARTS), { kind: "busy" });
    assert.deepEqual(await actions.acceptInvite(XUID, 12), { kind: "busy" });
    assert.deepEqual(appels, []);
    finir(ok({ collected: 1, offlineHours: 0, state: companyState({ version: 2 }) }));
    assert.equal((await encaissement).kind, "collected");
  });
});
