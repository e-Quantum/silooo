import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { CoreApi } from "../src/api";
import type { ApiResult } from "../src/http";
import { createLinkState, createViewStore, observeApi } from "../src/ui/etat";
import { balances, companyState, joinResponse, ok } from "./helpers";
import { XUID, GAMERTAG, fakeClock, joinOf, storeWith } from "./ui-helpers";

describe("ViewStore", () => {
  it("part de la réponse de join, avec le compteur ancré à l'instant de l'arrivée", () => {
    const clock = fakeClock(5_000);
    const store = storeWith({ donar: 1_000, ratePerHour: 3_600 }, { lingots: 4, gemmes: 2 });
    const views = createViewStore({ players: store, clock });
    const view = views.get(XUID);
    assert.equal(view?.state.donar, 1_000);
    assert.deepEqual(view?.balances, balances({ lingots: 4, gemmes: 2 }));
    assert.deepEqual(view?.anchor, { donar: 1_000, ratePerHour: 3_600, atMs: 5_000 });
  });

  it("ne connaît pas un joueur qui n'a pas eu de réponse de join", () => {
    const views = createViewStore({ players: storeWith(), clock: fakeClock() });
    assert.equal(views.get("999"), undefined);
    assert.equal(views.apply("999", { state: companyState({ version: 9 }) }), false);
  });

  it("range une réponse plus récente, ancre le compteur à son arrivée et garde les soldes quand elle n'en a pas", () => {
    const clock = fakeClock(0);
    const views = createViewStore({ players: storeWith({ version: 1 }, { lingots: 4 }), clock });
    clock.advance(10_000);
    assert.equal(views.apply(XUID, { state: companyState({ version: 2, donar: 50, ratePerHour: 7_200 }) }), true);
    const view = views.get(XUID);
    assert.equal(view?.state.version, 2);
    assert.equal(view?.balances.lingots, 4);
    assert.deepEqual(view?.anchor, { donar: 50, ratePerHour: 7_200, atMs: 10_000 });

    assert.equal(views.apply(XUID, { state: companyState({ version: 3 }), balances: balances({ lingots: 9 }) }), true);
    assert.equal(views.get(XUID)?.balances.lingots, 9);
  });

  it("ignore une réponse plus ancienne que celle qu'il a, accepte une réponse de même version", () => {
    const views = createViewStore({ players: storeWith({ version: 5, donar: 10 }), clock: fakeClock() });
    assert.equal(views.apply(XUID, { state: companyState({ version: 4, donar: 999 }) }), false);
    assert.equal(views.get(XUID)?.state.donar, 10);
    assert.equal(views.apply(XUID, { state: companyState({ version: 5, donar: 11 }) }), true);
    assert.equal(views.get(XUID)?.state.donar, 11);
  });

  it("repart de zéro quand le magasin reçoit une nouvelle réponse de join", () => {
    const clock = fakeClock();
    const store = storeWith({ version: 1, donar: 1 });
    const views = createViewStore({ players: store, clock });
    views.apply(XUID, { state: companyState({ version: 8, donar: 800 }) });
    store.set({ xuid: XUID, gamertag: GAMERTAG, join: joinResponse({ state: companyState({ version: 2, donar: 2 }) }) });
    assert.equal(views.get(XUID)?.state.version, 2);
    assert.equal(views.get(XUID)?.state.donar, 2);
  });

  it("oublie un joueur parti", () => {
    const store = storeWith();
    const views = createViewStore({ players: store, clock: fakeClock() });
    assert.ok(views.get(XUID));
    store.remove(XUID);
    assert.equal(views.get(XUID), undefined);
    assert.equal(views.apply(XUID, { state: companyState({ version: 50 }) }), false);
    // Il revient : la vue repart de sa nouvelle réponse de join, pas de l'ancienne.
    store.set({ xuid: XUID, gamertag: GAMERTAG, join: joinResponse({ state: companyState({ version: 1, donar: 77 }) }) });
    assert.equal(views.get(XUID)?.state.donar, 77);
    assert.equal(joinOf(store).state.donar, 77);
  });
});

describe("LinkState", () => {
  const down: ApiResult<unknown> = { kind: "network", message: "délai dépassé" };

  it("répond tant qu'on n'a pas de preuve du contraire", () => {
    assert.equal(createLinkState().status(), "ok");
  });

  it("tombe sur une erreur réseau et sur une erreur 5xx, se rétablit sur une réponse du service", () => {
    const link = createLinkState();
    link.note(down);
    assert.equal(link.status(), "down");
    link.note(ok({}));
    assert.equal(link.status(), "ok");
    link.note({ kind: "error", status: 503, reason: "ERREUR_INTERNE" });
    assert.equal(link.status(), "down");
    link.note({ kind: "refused", status: 409, reason: "PAS_ASSEZ_DE_DONAR" });
    assert.equal(link.status(), "ok");
  });

  it("un 501 (route pas encore écrite) et une erreur 4xx prouvent que le service répond", () => {
    const link = createLinkState();
    link.note(down);
    link.note({ kind: "error", status: 501, reason: "PAS_FAIT" });
    assert.equal(link.status(), "ok");
    link.note(down);
    link.note({ kind: "error", status: 400, reason: "REQUETE_INVALIDE" });
    assert.equal(link.status(), "ok");
  });

  it("une erreur locale (statut 0, aucune requête partie) ne change rien", () => {
    const link = createLinkState();
    link.note({ kind: "error", status: 0, reason: "REQUETE_INVALIDE" });
    assert.equal(link.status(), "ok");
    link.note(down);
    link.note({ kind: "error", status: 0, reason: "REQUETE_INVALIDE" });
    assert.equal(link.status(), "down");
  });
});

describe("observeApi", () => {
  it("rend les résultats tels quels et nourrit LinkState à chaque appel, sur les dix-neuf routes", async () => {
    const answers: ApiResult<unknown>[] = [];
    const result = (): Promise<ApiResult<never>> => {
      const next = answers.shift();
      if (next === undefined) throw new Error("plus de réponse");
      return Promise.resolve(next as ApiResult<never>);
    };
    const api: CoreApi = {
      heartbeat: result,
      join: result,
      leave: result,
      pollEvents: result,
      getCompany: result,
      collect: result,
      buyLevels: result,
      restoreStep: result,
      resolveIncident: result,
      getMissions: result,
      prestigePreview: result,
      prestige: result,
      getCandidates: result,
      hire: result,
      assignStaff: result,
      fireStaff: result,
      invite: result,
      setShares: result,
      acceptInvite: result,
    };
    const link = createLinkState();
    const seen = observeApi(api, link);
    const action = { requestId: "r", actor: XUID };
    const calls: Array<() => Promise<ApiResult<unknown>>> = [
      () => seen.heartbeat("bds-1", []),
      () => seen.join(XUID, GAMERTAG),
      () => seen.leave(XUID),
      () => seen.pollEvents(0, 10),
      () => seen.getCompany(7),
      () => seen.collect(7, action),
      () => seen.buyLevels(7, "carriere", action, "1"),
      () => seen.restoreStep(7, "carriere", action, 1),
      () => seen.resolveIncident(7, 41, action),
      () => seen.getMissions(XUID),
      () => seen.prestigePreview(7),
      () => seen.prestige(7, action),
      () => seen.getCandidates(7),
      () => seen.hire(7, 1, action, "carriere"),
      () => seen.assignStaff(7, 11, action, "carriere"),
      () => seen.fireStaff(7, 11, action),
      () => seen.invite(7, action, "2535465000000001"),
      () => seen.setShares(7, action, [{ xuid: XUID, percent: 100 }]),
      () => seen.acceptInvite(8, action),
    ];
    for (const call of calls) {
      const failed: ApiResult<unknown> = { kind: "network", message: "coupé" };
      answers.push(failed);
      assert.equal(await call(), failed);
      assert.equal(link.status(), "down");
      answers.push(ok({}));
      assert.equal((await call()).kind, "ok");
      assert.equal(link.status(), "ok");
    }
  });
});
