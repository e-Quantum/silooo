// Les annonces de la ville : le service dit « zone achetée », « bâtiment posé », « bâtiment amélioré » par un événement
// ANNONCE adressé à celui qui agit. L'add-on relit l'état de l'entreprise et le dit d'une bulle courte.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { GameEvent } from "../src/protocol";
import { createEventListeners } from "../src/events";
import { CITY_ACTIONS, cityBubble, createCityEvents, readCityAnnouncement } from "../src/ui/annonces-ville";
import { createViewStore } from "../src/ui/etat";
import { renderBubble, VILLE_TEXTES } from "../src/ui/textes";
import { companyState, ok, recordingLogger } from "./helpers";
import { failure, fakeClock, fakeCompany, storeWith, unreachable, XUID, type Script } from "./ui-helpers";
import { cityBuilding, cityState, emptyCity } from "./ville-helpers";

const announce = (action: unknown, xuid: string | null = XUID, extra: Record<string, unknown> = {}): GameEvent => ({
  id: 1,
  xuid,
  kind: "ANNONCE",
  payload: { action, ...extra },
});

function setup(script: Script, state: Parameters<typeof storeWith>[0] = { version: 1, city: emptyCity() }) {
  const views = createViewStore({ players: storeWith(state), clock: fakeClock(0) });
  const api = fakeCompany(script);
  const log = recordingLogger();
  const said: Array<{ xuid: string; text: string }> = [];
  const events = createCityEvents({ api, views, say: (xuid, text) => void said.push({ xuid, text }), log });
  return { events, api, views, log, said };
}

describe("lire une annonce de la ville", () => {
  it("reconnaît les trois actions, et elles seules", () => {
    for (const action of CITY_ACTIONS) assert.equal(readCityAnnouncement(announce(action)), action);
    assert.deepEqual([...CITY_ACTIONS], ["buyZone", "placeBuilding", "upgradeBuilding"]);
  });

  it("ignore les autres annonces (missions, parts), une action absente ou d'un autre type, et les autres événements", () => {
    for (const action of ["completeMission", "setShares", "", "BUYZONE", undefined, null, 3, ["buyZone"], { action: "buyZone" }]) {
      assert.equal(readCityAnnouncement(announce(action)), undefined, String(action));
    }
    assert.equal(readCityAnnouncement({ id: 1, xuid: XUID, kind: "ANNONCE", payload: {} }), undefined);
    assert.equal(readCityAnnouncement({ id: 1, xuid: XUID, kind: "GEMMES_RECUES", payload: { action: "buyZone" } }), undefined);
    assert.equal(readCityAnnouncement({ id: 1, xuid: XUID, kind: "INCIDENT", payload: { action: "placeBuilding" } }), undefined);
  });

  it("une annonce plus riche que prévu reste lisible : les champs inconnus sont ignorés", () => {
    assert.equal(readCityAnnouncement(announce("buyZone", XUID, { zoneIndex: 2, futur: { a: 1 } })), "buyZone");
  });
});

describe("la bulle de chaque annonce", () => {
  it("chaque action a sa bulle, courte et distincte", () => {
    assert.equal(cityBubble("buyZone"), VILLE_TEXTES.bulles.zoneAchetee);
    assert.equal(cityBubble("placeBuilding"), VILLE_TEXTES.bulles.batimentPose);
    assert.equal(cityBubble("upgradeBuilding"), VILLE_TEXTES.bulles.batimentAmeliore);
    const texts = new Set(CITY_ACTIONS.map((action) => cityBubble(action).text));
    assert.equal(texts.size, 3);
    for (const action of CITY_ACTIONS) assert.ok(cityBubble(action).text.split(/\s+/).length <= 6, "six mots au plus");
  });
});

describe("recevoir une annonce de la ville", () => {
  const afterBuy = () => ok(companyState({ version: 2, city: cityState() }));

  it("relit l'état de l'entreprise, le range dans la vue, puis Oooscar le dit au joueur", async () => {
    const b = setup({ getCompany: [afterBuy()] });
    await b.events.onAnnonce(announce("buyZone"));
    assert.deepEqual(b.api.calls, [{ op: "getCompany", companyId: 7 }]);
    assert.equal(b.views.get(XUID)?.state.version, 2);
    assert.equal(b.views.get(XUID)?.state.city?.buildings[0]?.buildingType, "mairie");
    assert.deepEqual(b.said, [{ xuid: XUID, text: renderBubble(VILLE_TEXTES.bulles.zoneAchetee) }]);
    assert.deepEqual(b.log.all(), []);
  });

  it("chaque action dit sa propre bulle", async () => {
    for (const action of CITY_ACTIONS) {
      const b = setup({ getCompany: [afterBuy()] });
      await b.events.onAnnonce(announce(action));
      assert.deepEqual(b.said.map((line) => line.text), [renderBubble(cityBubble(action))]);
    }
  });

  it("l'ancien état n'écrase pas le nouveau : une relecture plus vieille que la vue est laissée de côté", async () => {
    const b = setup({ getCompany: [ok(companyState({ version: 1, city: emptyCity() }))] }, { version: 3, city: cityState({ mairieLevel: 2 }) });
    await b.events.onAnnonce(announce("upgradeBuilding"));
    assert.equal(b.views.get(XUID)?.state.version, 3);
    assert.equal(b.views.get(XUID)?.state.city?.mairieLevel, 2);
    assert.equal(b.said.length, 1);
  });

  it("une annonce sans joueur, ou pour un joueur absent, ne fait rien : à son retour, join rend l'état", async () => {
    const sansJoueur = setup({});
    await sansJoueur.events.onAnnonce(announce("buyZone", null));
    const absent = setup({});
    await absent.events.onAnnonce(announce("buyZone", "999"));
    for (const b of [sansJoueur, absent]) {
      assert.deepEqual(b.api.calls, []);
      assert.deepEqual(b.said, []);
    }
  });

  it("une annonce qui n'est pas de la ville ne fait rien", async () => {
    const b = setup({});
    await b.events.onAnnonce(announce("completeMission"));
    await b.events.onAnnonce({ id: 2, xuid: XUID, kind: "ANNONCE", payload: {} });
    assert.deepEqual(b.api.calls, []);
    assert.deepEqual(b.said, []);
  });

  it("le service ne répond pas : la vue reste, la bulle est dite quand même, un avertissement est journalisé", async () => {
    const b = setup({ getCompany: [unreachable()] });
    await b.events.onAnnonce(announce("placeBuilding"));
    assert.equal(b.views.get(XUID)?.state.version, 1);
    assert.equal(b.said.length, 1);
    assert.match(b.log.warns.join("\n"), /annonce de la ville, état de/);
  });

  it("une erreur du service est journalisée de même, sans lever", async () => {
    const b = setup({ getCompany: [failure(500, "ERREUR_INTERNE")] });
    await assert.doesNotReject(b.events.onAnnonce(announce("placeBuilding")));
    assert.equal(b.log.warns.length, 1);
    assert.equal(b.said.length, 1);
  });

  it("une relecture qui lève est journalisée, sans lever", async () => {
    const b = setup({});
    await assert.doesNotReject(b.events.onAnnonce(announce("buyZone")));
    assert.match(b.log.warns.join("\n"), /appel imprévu/);
    assert.equal(b.said.length, 1);
  });

  it("un tchat qui échoue est journalisé, sans lever", async () => {
    const views = createViewStore({ players: storeWith({ version: 1, city: emptyCity() }), clock: fakeClock(0) });
    const log = recordingLogger();
    const events = createCityEvents({
      api: fakeCompany({ getCompany: [afterBuy()] }),
      views,
      say: () => {
        throw new Error("joueur parti");
      },
      log,
    });
    await assert.doesNotReject(events.onAnnonce(announce("buyZone")));
    assert.match(log.warns.join("\n"), /annonce de la ville à .* : .*joueur parti/);
    assert.equal(views.get(XUID)?.state.version, 2, "l'état est rangé avant la bulle");
  });

  it("branché sur le registre d'événements : seules les annonces de la ville le déclenchent", async () => {
    const b = setup({ getCompany: [afterBuy()] });
    const listeners = createEventListeners(b.log);
    listeners.on("ANNONCE", b.events.onAnnonce);
    await listeners.dispatch(announce("completeMission"));
    assert.equal(b.said.length, 0);
    await listeners.dispatch(announce("buyZone"));
    assert.equal(b.said.length, 1);
    assert.equal(b.api.calls.length, 1);
  });

  it("la ville relue contient la mairie : l'écran Ville la trouve à la prochaine ouverture", async () => {
    const b = setup({ getCompany: [afterBuy()] });
    await b.events.onAnnonce(announce("buyZone"));
    assert.deepEqual(b.views.get(XUID)?.state.city?.buildings, [cityBuilding()]);
  });
});
