import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { ApiResult } from "../src/http";
import type { GameEvent, MissionState, Xuid } from "../src/protocol";
import { COLOR } from "../src/ui/apparence";
import { MISSION_ACTION, announcementText, createMissions } from "../src/ui/missions";
import { BULLES, OBJECTIFS_TEXTES, renderBubble } from "../src/ui/textes";
import { joinResponse, mission, missionAnnouncement, missionState, ok, recordingLogger } from "./helpers";
import { GAMERTAG, XUID, bare, fakeMissionsApi, storeWith, unreachable } from "./ui-helpers";

/** Laisse passer les promesses en attente. */
const settle = (): Promise<void> => new Promise((resolve) => setImmediate(resolve));

const ANNONCE_BIENVENUE = "Oooscar [joie] Premier encaissement, bien joué !";

function setup(script?: Parameters<typeof fakeMissionsApi>[0]) {
  const store = storeWith();
  const api = fakeMissionsApi(script);
  const said: Array<{ xuid: Xuid; text: string }> = [];
  const log = recordingLogger();
  const missions = createMissions({ api, players: store, say: (xuid, text) => void said.push({ xuid, text }), log });
  return { missions, api, store, said, log };
}

/** Une API de missions dont chaque réponse attend que le test la donne. */
function manualApi() {
  const calls: Xuid[] = [];
  const pending: Array<(result: ApiResult<MissionState>) => void> = [];
  return {
    calls,
    pending,
    getMissions(xuid: Xuid): Promise<ApiResult<MissionState>> {
      calls.push(xuid);
      return new Promise((resolve) => pending.push(resolve));
    },
  };
}

describe("announcementText", () => {
  it("la bulle d'Oooscar, puis la récompense en Lingots que le service a donnée", () => {
    const text = announcementText("bienvenue-mairie", 10);
    assert.equal(bare(text), `${ANNONCE_BIENVENUE}\n${OBJECTIFS_TEXTES.recompense("10")}`);
    assert.ok(text.endsWith(`${COLOR.lingots}Récompense 10 Lingots${COLOR.reset}`), text);
  });

  it("met la somme en forme sans la changer", () => {
    assert.match(bare(announcementText("bienvenue-mairie", 12_400)), /\nRécompense 12.400 Lingots$/u);
    assert.match(bare(announcementText("bienvenue-mairie", 0)), /\nRécompense 0 Lingots$/);
  });

  it("sans somme, seule la bulle est dite : rien n'est inventé", () => {
    assert.equal(bare(announcementText("bienvenue-mairie", undefined)), ANNONCE_BIENVENUE);
  });

  it("une mission que l'add-on ne connaît pas a la bulle de repli", () => {
    assert.equal(announcementText("mission-du-futur", undefined), renderBubble(BULLES.missionInconnue));
  });

  it("la valeur de payload.action est celle du contrat", () => {
    assert.equal(MISSION_ACTION, "mission");
  });
});

describe("missions : lecture", () => {
  it("rien n'est connu avant la première lecture, ni pour un joueur absent", () => {
    const { missions } = setup();
    assert.equal(missions.get(XUID), undefined);
    assert.equal(missions.get("999"), undefined);
  });

  it("arrive lit les missions du joueur au service", async () => {
    const { missions, api } = setup([ok(missionState({ current: mission({ progress: 0 }) }))]);
    await missions.arrive(XUID);
    assert.deepEqual(api.calls, [XUID]);
    assert.deepEqual(missions.get(XUID), missionState());
  });

  it("refresh relit : la dernière réponse du service est celle qui reste", async () => {
    const { missions, api } = setup([
      ok(missionState({ current: mission({ progress: 0 }) })),
      ok({ current: null, completed: ["bienvenue-mairie"] }),
    ]);
    await missions.refresh(XUID);
    assert.equal(missions.get(XUID)?.current?.progress, 0);
    await missions.refresh(XUID);
    assert.deepEqual(missions.get(XUID), { current: null, completed: ["bienvenue-mairie"] });
    assert.equal(api.calls.length, 2);
  });

  it("un échec de lecture garde la lecture précédente et le dit dans le journal", async () => {
    const { missions, log } = setup([ok(missionState({ current: mission({ progress: 0 }) })), unreachable()]);
    await missions.refresh(XUID);
    await missions.refresh(XUID);
    assert.equal(missions.get(XUID)?.current?.progress, 0);
    assert.equal(log.warns.length, 1);
    assert.match(log.warns[0] ?? "", new RegExp(`^missions de ${XUID} : réseau : délai dépassé$`));
  });

  it("un refus ou une erreur du service ne déforment rien non plus", async () => {
    const { missions, log } = setup([
      ok(missionState()),
      { kind: "error", status: 501, reason: "PAS_FAIT" },
      { kind: "error", status: 400, reason: "JOUEUR_INCONNU", detail: "x" },
    ]);
    await missions.refresh(XUID);
    await missions.refresh(XUID);
    await missions.refresh(XUID);
    assert.deepEqual(missions.get(XUID), missionState());
    assert.deepEqual(log.warns, [
      `missions de ${XUID} : erreur 501 PAS_FAIT`,
      `missions de ${XUID} : erreur 400 JOUEUR_INCONNU (x)`,
    ]);
  });

  it("une API qui lève ne fait jamais lever refresh, arrive ni onAnnonce", async () => {
    const store = storeWith();
    const log = recordingLogger();
    const said: string[] = [];
    const missions = createMissions({
      api: { getMissions: () => Promise.reject(new Error("boum")) },
      players: store,
      say: (_xuid, text) => void said.push(text),
      log,
    });
    await missions.arrive(XUID);
    await missions.refresh(XUID);
    await missions.onAnnonce(missionAnnouncement(1, XUID));
    assert.equal(missions.get(XUID), undefined);
    assert.equal(log.warns.length, 3);
    assert.match(log.warns[0] ?? "", /boum/);
    assert.equal(said.length, 1);
  });

  it("un joueur absent n'est pas lu", async () => {
    const { missions, api } = setup();
    await missions.refresh("999");
    await missions.arrive("999");
    assert.deepEqual(api.calls, []);
  });

  it("un joueur qui part n'a plus de missions", async () => {
    const { missions, store } = setup();
    await missions.refresh(XUID);
    assert.notEqual(missions.get(XUID), undefined);
    store.remove(XUID);
    assert.equal(missions.get(XUID), undefined);
  });

  it("un joueur qui revient repart de zéro, sans la lecture de sa visite précédente", async () => {
    const { missions, store } = setup();
    await missions.refresh(XUID);
    assert.notEqual(missions.get(XUID), undefined);
    store.set({ xuid: XUID, gamertag: GAMERTAG, join: joinResponse() });
    assert.equal(missions.get(XUID), undefined);
  });
});

describe("missions : lectures qui se chevauchent", () => {
  it("les lectures d'un même joueur se suivent : une réponse lente ne recouvre jamais une plus récente", async () => {
    const store = storeWith();
    const api = manualApi();
    const missions = createMissions({ api, players: store, say: () => undefined, log: recordingLogger() });
    const first = missions.refresh(XUID);
    const second = missions.refresh(XUID);
    await settle();
    assert.equal(api.calls.length, 1, "la seconde lecture attend la première");
    api.pending[0]?.(ok(missionState({ current: mission({ progress: 0 }) })));
    await settle();
    assert.equal(api.calls.length, 2);
    api.pending[1]?.(ok(missionState({ current: mission({ progress: 1 }) })));
    await Promise.all([first, second]);
    assert.equal(missions.get(XUID)?.current?.progress, 1);
  });

  it("une lecture qui échoue ne bloque pas la suivante", async () => {
    const store = storeWith();
    const api = manualApi();
    const missions = createMissions({ api, players: store, say: () => undefined, log: recordingLogger() });
    const first = missions.refresh(XUID);
    const second = missions.refresh(XUID);
    await settle();
    api.pending[0]?.(unreachable());
    await settle();
    api.pending[1]?.(ok(missionState()));
    await Promise.all([first, second]);
    assert.deepEqual(missions.get(XUID), missionState());
  });

  it("la réponse arrive quand le joueur est parti : elle est jetée", async () => {
    const store = storeWith();
    const api = manualApi();
    const missions = createMissions({ api, players: store, say: () => undefined, log: recordingLogger() });
    const reading = missions.refresh(XUID);
    await settle();
    store.remove(XUID);
    api.pending[0]?.(ok(missionState()));
    await reading;
    assert.equal(missions.get(XUID), undefined);
  });

  it("la réponse arrive après un nouveau join : elle n'est pas celle de cette visite", async () => {
    const store = storeWith();
    const api = manualApi();
    const missions = createMissions({ api, players: store, say: () => undefined, log: recordingLogger() });
    const reading = missions.refresh(XUID);
    await settle();
    store.set({ xuid: XUID, gamertag: GAMERTAG, join: joinResponse() });
    api.pending[0]?.(ok(missionState()));
    await reading;
    assert.equal(missions.get(XUID), undefined);
  });

  it("deux joueurs ne s'attendent pas", async () => {
    const store = storeWith();
    store.set({ xuid: "2", gamertag: "Bob", join: joinResponse({ xuid: "2" }) });
    const api = manualApi();
    const missions = createMissions({ api, players: store, say: () => undefined, log: recordingLogger() });
    void missions.refresh(XUID);
    void missions.refresh("2");
    await settle();
    assert.deepEqual(api.calls, [XUID, "2"]);
  });
});

describe("missions : l'annonce d'une mission terminée", () => {
  it("Oooscar le dit au joueur, avec la récompense du service, puis les missions sont relues", async () => {
    const { missions, api, said } = setup([ok({ current: null, completed: ["bienvenue-mairie"] })]);
    await missions.onAnnonce(missionAnnouncement(5, XUID, "bienvenue-mairie", 10));
    assert.equal(said.length, 1);
    assert.equal(said[0]?.xuid, XUID);
    assert.equal(bare(said[0]?.text ?? ""), `${ANNONCE_BIENVENUE}\nRécompense 10 Lingots`);
    assert.deepEqual(api.calls, [XUID]);
    assert.deepEqual(missions.get(XUID), { current: null, completed: ["bienvenue-mairie"] });
  });

  it("la bulle est dite avant la relecture : le joueur n'attend pas le service", async () => {
    const store = storeWith();
    const api = manualApi();
    const said: string[] = [];
    const missions = createMissions({ api, players: store, say: (_xuid, text) => void said.push(text), log: recordingLogger() });
    const done = missions.onAnnonce(missionAnnouncement(5, XUID));
    await settle();
    assert.equal(said.length, 1);
    assert.equal(api.calls.length, 1);
    api.pending[0]?.(ok(missionState()));
    await done;
  });

  it("sans Lingots dans le payload, seule la bulle est dite", async () => {
    const { missions, said } = setup();
    await missions.onAnnonce(missionAnnouncement(5, XUID, "bienvenue-mairie", null));
    assert.equal(bare(said[0]?.text ?? ""), ANNONCE_BIENVENUE);
  });

  it("des Lingots illisibles dans le payload ne sont pas montrés", async () => {
    const { missions, said } = setup();
    for (const [i, reward] of [-5, Number.NaN, "10", null, Number.POSITIVE_INFINITY].entries()) {
      const event: GameEvent = { id: i + 1, xuid: XUID, kind: "ANNONCE", payload: { action: "mission", missionId: `m${i}`, rewardLingots: reward } };
      await missions.onAnnonce(event);
    }
    assert.equal(said.length, 5);
    for (const { text } of said) assert.equal(bare(text), bare(renderBubble(BULLES.missionInconnue)));
  });

  it("une mission que l'add-on ne connaît pas : la bulle de repli, la récompense du service", async () => {
    const { missions, said } = setup();
    await missions.onAnnonce(missionAnnouncement(5, XUID, "mission-du-futur", 25));
    assert.equal(bare(said[0]?.text ?? ""), "Oooscar [joie] Bien joué, patron !\nRécompense 25 Lingots");
  });

  it("n'écoute que les ANNONCE de mission : le reste ne dit rien et ne relit rien", async () => {
    const { missions, api, said } = setup();
    const events: GameEvent[] = [
      { id: 1, xuid: XUID, kind: "AUTRE", payload: { action: "mission", missionId: "bienvenue-mairie" } },
      { id: 2, xuid: XUID, kind: "ANNONCE", payload: { action: "prestige", missionId: "bienvenue-mairie" } },
      { id: 3, xuid: XUID, kind: "ANNONCE", payload: { missionId: "bienvenue-mairie" } },
      { id: 4, xuid: XUID, kind: "ANNONCE", payload: { action: "mission" } },
      { id: 5, xuid: XUID, kind: "ANNONCE", payload: { action: "mission", missionId: 42 } },
      { id: 6, xuid: XUID, kind: "ANNONCE", payload: { action: "mission", missionId: "" } },
      { id: 7, xuid: null, kind: "ANNONCE", payload: { action: "mission", missionId: "bienvenue-mairie" } },
    ];
    for (const event of events) await missions.onAnnonce(event);
    assert.deepEqual(said, []);
    assert.deepEqual(api.calls, []);
  });

  it("un joueur absent n'a personne à qui parler : rien n'est dit, rien n'est lu", async () => {
    const { missions, api, said } = setup();
    await missions.onAnnonce(missionAnnouncement(5, "999"));
    assert.deepEqual(said, []);
    assert.deepEqual(api.calls, []);
  });

  it("la même annonce, rejouée, n'est dite qu'une fois", async () => {
    const { missions, api, said } = setup();
    await missions.onAnnonce(missionAnnouncement(5, XUID));
    await missions.onAnnonce(missionAnnouncement(5, XUID));
    await missions.onAnnonce(missionAnnouncement(9, XUID));
    assert.equal(said.length, 1);
    assert.equal(api.calls.length, 3, "chaque annonce relit quand même : l'état du service fait foi");
  });

  it("une mission déjà terminée à l'arrivée du joueur ne se redit pas (un curseur revenu à 0, sans magasin)", async () => {
    const { missions, said } = setup([ok({ current: null, completed: ["bienvenue-mairie"] })]);
    await missions.arrive(XUID);
    await missions.onAnnonce(missionAnnouncement(1, XUID));
    assert.deepEqual(said, []);
  });

  it("l'arrivée retient les missions terminées, pas la mission en cours", async () => {
    const { missions, said } = setup([ok(missionState({ current: mission({ progress: 0 }), completed: [] }))]);
    await missions.arrive(XUID);
    await missions.onAnnonce(missionAnnouncement(1, XUID));
    assert.equal(said.length, 1);
  });

  it("la relecture après un encaissement ne fait pas taire la vraie annonce", async () => {
    const { missions, said } = setup([ok({ current: null, completed: ["bienvenue-mairie"] })]);
    await missions.refresh(XUID);
    await missions.onAnnonce(missionAnnouncement(1, XUID));
    assert.equal(said.length, 1);
  });

  it("un joueur qui revient peut revoir l'annonce : sa nouvelle visite repart de zéro", async () => {
    const { missions, store, said } = setup();
    await missions.onAnnonce(missionAnnouncement(1, XUID));
    store.set({ xuid: XUID, gamertag: GAMERTAG, join: joinResponse() });
    await missions.onAnnonce(missionAnnouncement(2, XUID));
    assert.equal(said.length, 2);
  });

  it("sans lecture à l'arrivée, l'annonce est dite : une bulle de trop vaut mieux qu'une mission passée sous silence", async () => {
    const { missions, said } = setup([unreachable(), ok({ current: null, completed: ["bienvenue-mairie"] })]);
    await missions.arrive(XUID);
    await missions.onAnnonce(missionAnnouncement(1, XUID));
    assert.equal(said.length, 1);
  });

  it("une annonce pour un autre joueur ne parle qu'à lui", async () => {
    const { missions, store, said } = setup();
    store.set({ xuid: "2", gamertag: "Bob", join: joinResponse({ xuid: "2" }) });
    await missions.onAnnonce(missionAnnouncement(1, "2"));
    assert.deepEqual(said.map((s) => s.xuid), ["2"]);
  });

  it("si l'envoi du texte lève, c'est journalisé et la relecture a lieu quand même", async () => {
    const store = storeWith();
    const api = fakeMissionsApi();
    const log = recordingLogger();
    const missions = createMissions({
      api,
      players: store,
      say: () => {
        throw new Error("joueur parti");
      },
      log,
    });
    await missions.onAnnonce(missionAnnouncement(1, XUID));
    assert.equal(api.calls.length, 1);
    assert.equal(log.warns.length, 1);
    assert.match(log.warns[0] ?? "", /annonce de mission à .* : .*joueur parti/);
  });
});
