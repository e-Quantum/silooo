import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createCoreApi } from "../src/api";
import { config } from "../src/config";
import {
  advanceCursor,
  createEventListeners,
  createEventPoller,
  nextPollDelaySeconds,
  startEventPolling,
  summarizeKinds,
  type CursorStore,
} from "../src/events";
import { createCoreClient } from "../src/http";
import type { GameEvent } from "../src/protocol";
import { createStatusLog } from "../src/runtime";
import { eventsPage, fakeScheduler, gameEvent, json, recordingLogger, scriptedTransport } from "./helpers";

const settings = { pollIntervalSeconds: 3, drainDelaySeconds: 0.25, pageSize: 100, maxBackoffSeconds: 60 };

describe("advanceCursor", () => {
  it("prend le curseur de la page", () => {
    assert.equal(advanceCursor(0, eventsPage([gameEvent(1), gameEvent(2)])), 2);
  });

  it("garde le curseur quand la page est vide (le service rend `after`)", () => {
    assert.equal(advanceCursor(40, eventsPage([], 40)), 40);
  });

  it("ne recule jamais", () => {
    assert.equal(advanceCursor(40, eventsPage([], 10)), 40);
  });

  it("dépasse un événement plus haut que le curseur annoncé", () => {
    assert.equal(advanceCursor(0, eventsPage([gameEvent(5)], 3)), 5);
  });
});

describe("nextPollDelaySeconds", () => {
  const base = { failures: 0, lastPageCount: 0, pageSize: 100 };

  it("page vide ou partielle : l'intervalle normal", () => {
    assert.equal(nextPollDelaySeconds(base, settings), 3);
    assert.equal(nextPollDelaySeconds({ ...base, lastPageCount: 99 }, settings), 3);
  });

  it("page pleine : relire presque tout de suite", () => {
    assert.equal(nextPollDelaySeconds({ ...base, lastPageCount: 100 }, settings), 0.25);
  });

  it("après des échecs : délai qui double, plafonné", () => {
    const delais = [1, 2, 3, 4, 5, 6].map((failures) => nextPollDelaySeconds({ ...base, failures }, settings));
    assert.deepEqual(delais, [6, 12, 24, 48, 60, 60]);
  });

  it("les réglages par défaut tiennent dans le contrat", () => {
    assert.ok(config.events.pageSize >= 1 && config.events.pageSize <= 500);
    assert.ok(config.events.drainDelaySeconds < config.events.pollIntervalSeconds);
    assert.ok(config.events.maxBackoffSeconds >= config.events.pollIntervalSeconds);
  });
});

describe("summarizeKinds", () => {
  it("compte par type dans l'ordre d'arrivée", () => {
    const events = [gameEvent(1, "ANNONCE"), gameEvent(2, "GEMMES_RECUES"), gameEvent(3, "ANNONCE")];
    assert.equal(summarizeKinds(events), "ANNONCE x2, GEMMES_RECUES x1");
  });
});

describe("createEventListeners", () => {
  it("répartit par type, dans l'ordre d'enregistrement", async () => {
    const log = recordingLogger();
    const listeners = createEventListeners(log);
    const seen: string[] = [];
    listeners.on("ANNONCE", (e) => void seen.push(`a1:${e.id}`));
    listeners.on("ANNONCE", async (e) => void seen.push(`a2:${e.id}`));
    listeners.on("INCIDENT", (e) => void seen.push(`i:${e.id}`));
    await listeners.dispatch(gameEvent(1, "ANNONCE"));
    await listeners.dispatch(gameEvent(2, "INCIDENT"));
    await listeners.dispatch(gameEvent(3, "VENTE_MARCHE"));
    assert.deepEqual(seen, ["a1:1", "a2:1", "i:2"]);
  });

  it("un écouteur qui lève est journalisé et n'empêche pas les suivants", async () => {
    const log = recordingLogger();
    const listeners = createEventListeners(log);
    const seen: number[] = [];
    listeners.on("ANNONCE", () => {
      throw new Error("cassé");
    });
    listeners.on("ANNONCE", (e) => void seen.push(e.id));
    await listeners.dispatch(gameEvent(7, "ANNONCE"));
    assert.deepEqual(seen, [7]);
    assert.equal(log.errors.length, 1);
    assert.match(log.errors[0] ?? "", /ANNONCE.*7.*cassé/);
  });
});

function pollerWith(script: Parameters<typeof scriptedTransport>[0], pageSize = 100, cursorStore?: CursorStore) {
  const transport = scriptedTransport(script);
  const api = createCoreApi(
    createCoreClient({ transport, coreUrl: "http://core", apiKey: "k", timeoutSeconds: 5, readRetries: 0 }),
  );
  const log = recordingLogger();
  const listeners = createEventListeners(log);
  const received: GameEvent[] = [];
  for (const kind of ["ANNONCE", "INCIDENT"] as const) listeners.on(kind, (e) => void received.push(e));
  const poller = createEventPoller({
    api,
    listeners,
    status: createStatusLog(log, "événements"),
    log,
    pageSize,
    ...(cursorStore === undefined ? {} : { cursorStore }),
  });
  return { poller, transport, log, received };
}

/** Un magasin de curseur en mémoire, qui compte ses lectures et garde chaque écriture. */
function memoryCursorStore(initial = 0) {
  const store = {
    value: initial,
    loads: 0,
    saves: [] as number[],
    load() {
      store.loads += 1;
      return store.value;
    },
    save(cursor: number) {
      store.saves.push(cursor);
      store.value = cursor;
    },
  };
  return store;
}

const page = (cursor: number, ...ids: number[]) =>
  json(200, { cursor, events: ids.map((id) => ({ id, kind: "ANNONCE", payload: {} })) });

describe("createEventPoller", () => {
  it("part du curseur 0, le fait avancer, et redemande à partir de là", async () => {
    const { poller, transport, received } = pollerWith([
      json(200, { cursor: 2, events: [{ id: 1, kind: "ANNONCE", payload: {} }, { id: 2, kind: "INCIDENT", payload: {} }] }),
      json(200, { cursor: 2, events: [] }),
    ]);
    assert.equal(poller.cursor, 0);
    assert.equal(await poller.pollOnce(), 3);
    assert.equal(poller.cursor, 2);
    assert.deepEqual(received.map((e) => e.id), [1, 2]);
    await poller.pollOnce();
    assert.deepEqual(
      transport.requests.map((r) => r.url),
      ["http://core/v1/events?after=0&limit=100", "http://core/v1/events?after=2&limit=100"],
    );
  });

  it("une page pleine appelle une relecture rapide", async () => {
    const { poller } = pollerWith(
      [json(200, { cursor: 2, events: [{ id: 1, kind: "ANNONCE", payload: {} }, { id: 2, kind: "ANNONCE", payload: {} }] })],
      2,
    );
    assert.equal(await poller.pollOnce(), config.events.drainDelaySeconds);
  });

  it("ne livre pas deux fois un événement déjà passé", async () => {
    const { poller, received } = pollerWith([
      json(200, { cursor: 2, events: [{ id: 1, kind: "ANNONCE", payload: {} }, { id: 2, kind: "ANNONCE", payload: {} }] }),
      json(200, { cursor: 3, events: [{ id: 2, kind: "ANNONCE", payload: {} }, { id: 3, kind: "ANNONCE", payload: {} }] }),
    ]);
    await poller.pollOnce();
    await poller.pollOnce();
    assert.deepEqual(received.map((e) => e.id), [1, 2, 3]);
    assert.equal(poller.cursor, 3);
  });

  it("un type sans écouteur avance le curseur sans rien livrer", async () => {
    const { poller, received, log } = pollerWith([
      json(200, { cursor: 4, events: [{ id: 4, kind: "VENTE_MARCHE", payload: {} }] }),
    ]);
    await poller.pollOnce();
    assert.equal(poller.cursor, 4);
    assert.equal(received.length, 0);
    assert.match(log.infos.join("\n"), /1 événement\(s\) : VENTE_MARCHE x1/);
  });

  it("un événement illisible est signalé, ignoré, et le curseur le dépasse", async () => {
    const { poller, received, log } = pollerWith([
      json(200, { cursor: 2, events: [{ id: 1, kind: "ANNONCE", payload: {} }, { id: "?" }] }),
    ]);
    await poller.pollOnce();
    assert.equal(poller.cursor, 2);
    assert.deepEqual(received.map((e) => e.id), [1]);
    assert.match(log.warns.join("\n"), /1 événement\(s\) illisible\(s\)/);
  });

  it("un échec garde le curseur, allonge le délai, et se journalise une fois", async () => {
    const { poller, log } = pollerWith([
      json(500, { reason: "ERREUR_INTERNE" }),
      json(500, { reason: "ERREUR_INTERNE" }),
      json(200, { cursor: 0, events: [] }),
    ]);
    assert.equal(await poller.pollOnce(), 6);
    assert.equal(await poller.pollOnce(), 12);
    assert.equal(poller.cursor, 0);
    assert.equal(log.warns.length, 1);
    assert.match(log.warns[0] ?? "", /événements : échec \(erreur 500 ERREUR_INTERNE\)/);
    assert.equal(await poller.pollOnce(), 3);
    assert.match(log.infos.join("\n"), /rétabli après 2 échec/);
  });

  it("une page ok mais hors contrat compte comme un échec", async () => {
    const { poller, log } = pollerWith([json(200, { events: [] })]);
    assert.equal(await poller.pollOnce(), 6);
    assert.match(log.warns.join("\n"), /REPONSE_INATTENDUE/);
  });
});

describe("startEventPolling", () => {
  it("lit après le délai de démarrage, puis replanifie chaque lecture", async () => {
    const scheduler = fakeScheduler();
    const log = recordingLogger();
    const delays = [3, 0.25, 6];
    let reads = 0;
    startEventPolling(scheduler, { pollOnce: async () => delays[reads++] ?? 3 }, log, 1);
    assert.deepEqual(scheduler.timeouts.map((t) => t.seconds), [1]);

    scheduler.runNextTimeout();
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(scheduler.timeouts.map((t) => t.seconds), [3]);

    scheduler.runNextTimeout();
    await new Promise((resolve) => setImmediate(resolve));
    scheduler.runNextTimeout();
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(scheduler.timeouts.map((t) => t.seconds), [6]);
    assert.equal(reads, 3);
  });

  it("une exception inattendue est journalisée et la boucle continue", async () => {
    const scheduler = fakeScheduler();
    const log = recordingLogger();
    startEventPolling(
      scheduler,
      {
        pollOnce: async () => {
          throw new Error("imprévu");
        },
      },
      log,
      1,
    );
    scheduler.runNextTimeout();
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(log.errors.length, 1);
    assert.deepEqual(scheduler.timeouts.map((t) => t.seconds), [config.events.maxBackoffSeconds]);
  });
});

describe("createEventPoller : le curseur gardé entre deux démarrages", () => {
  it("part du curseur que le magasin rend, et le demande au service", async () => {
    const store = memoryCursorStore(40);
    const { poller, transport } = pollerWith([json(200, { cursor: 40, events: [] })], 100, store);
    assert.equal(poller.cursor, 40);
    await poller.pollOnce();
    assert.deepEqual(transport.requests.map((r) => r.url), ["http://core/v1/events?after=40&limit=100"]);
    assert.equal(poller.cursor, 40);
  });

  it("ne lit le magasin qu'une fois, à la première lecture : le monde n'est pas lisible à la création", async () => {
    const store = memoryCursorStore(7);
    const { poller } = pollerWith([page(8, 8), page(8)], 100, store);
    assert.equal(store.loads, 0);
    await poller.pollOnce();
    await poller.pollOnce();
    assert.equal(poller.cursor, 8);
    assert.equal(store.loads, 1);
  });

  it("garde le curseur après chaque page qui l'avance, et seulement alors", async () => {
    const store = memoryCursorStore();
    const { poller } = pollerWith(
      [page(2, 1, 2), json(200, { cursor: 2, events: [] }), json(500, { reason: "ERREUR_INTERNE" }), page(5, 3), page(9, 7)],
      100,
      store,
    );
    for (let i = 0; i < 5; i += 1) await poller.pollOnce();
    // Page vide et échec : rien à garder. Le curseur gardé dépasse les identifiants reçus (la dernière page : cursor 9, id 7).
    assert.deepEqual(store.saves, [2, 5, 9]);
    assert.equal(store.value, poller.cursor);
  });

  it("sans magasin, rien ne change : le curseur part de 0 et reste en mémoire", async () => {
    const { poller, transport } = pollerWith([page(1, 1), page(1)]);
    await poller.pollOnce();
    await poller.pollOnce();
    assert.deepEqual(
      transport.requests.map((r) => r.url),
      ["http://core/v1/events?after=0&limit=100", "http://core/v1/events?after=1&limit=100"],
    );
  });

  it("un serveur qui redémarre reprend là où le précédent s'était arrêté, sans rien rejouer", async () => {
    const store = memoryCursorStore();
    const avant = pollerWith([page(3, 1, 2, 3)], 100, store);
    await avant.poller.pollOnce();
    assert.deepEqual(avant.received.map((e) => e.id), [1, 2, 3]);

    const apres = pollerWith([page(4, 4)], 100, store);
    assert.equal(apres.poller.cursor, 3);
    await apres.poller.pollOnce();
    assert.deepEqual(apres.transport.requests.map((r) => r.url), ["http://core/v1/events?after=3&limit=100"]);
    assert.deepEqual(apres.received.map((e) => e.id), [4]);
    assert.deepEqual(store.saves, [3, 4]);
  });

  it("le filtre des doublons tient avec un curseur chargé", async () => {
    const store = memoryCursorStore(5);
    const { poller, received } = pollerWith([page(6, 4, 5, 6), page(7, 6, 7)], 100, store);
    await poller.pollOnce();
    assert.deepEqual(received.map((e) => e.id), [6]);
    await poller.pollOnce();
    assert.deepEqual(received.map((e) => e.id), [6, 7]);
    assert.equal(poller.cursor, 7);
    assert.deepEqual(store.saves, [6, 7]);
  });

  it("le curseur ne recule jamais, même si le service rend un curseur plus bas : rien n'est gardé", async () => {
    const store = memoryCursorStore(10);
    const { poller, received } = pollerWith([json(200, { cursor: 3, events: [] })], 100, store);
    await poller.pollOnce();
    assert.equal(poller.cursor, 10);
    assert.equal(received.length, 0);
    assert.deepEqual(store.saves, []);
  });

  it("un magasin qui lève à la lecture donne 0, se journalise, et le poller tourne", async () => {
    const store: CursorStore = {
      load() {
        throw new Error("propriété illisible");
      },
      save() {},
    };
    const { poller, transport, log, received } = pollerWith([page(1, 1)], 100, store);
    assert.equal(poller.cursor, 0);
    await poller.pollOnce();
    assert.deepEqual(transport.requests.map((r) => r.url), ["http://core/v1/events?after=0&limit=100"]);
    assert.deepEqual(received.map((e) => e.id), [1]);
    assert.equal(log.warns.length, 1);
    assert.match(log.warns[0] ?? "", /propriété illisible/);
  });

  for (const [nom, valeur] of [
    ["NaN", Number.NaN],
    ["un négatif", -3],
    ["un décimal", 2.5],
    ["l'infini", Number.POSITIVE_INFINITY],
    ["un entier trop grand pour être exact", 2 ** 60],
    ["une chaîne", "7"],
    ["null", null],
    ["undefined", undefined],
    ["un objet", { cursor: 7 }],
  ] as const) {
    it(`un magasin qui rend ${nom} donne 0, sans planter`, async () => {
      const store: CursorStore = { load: () => valeur as unknown as number, save() {} };
      const { poller, transport } = pollerWith([json(200, { cursor: 0, events: [] })], 100, store);
      assert.equal(poller.cursor, 0);
      await poller.pollOnce();
      assert.deepEqual(transport.requests.map((r) => r.url), ["http://core/v1/events?after=0&limit=100"]);
    });
  }

  it("un magasin qui ne sait pas écrire est journalisé une fois ; le poller continue et le curseur avance", async () => {
    let broken = true;
    const saved: number[] = [];
    const store: CursorStore = {
      load: () => 0,
      save(cursor) {
        if (broken) throw new Error("disque plein");
        saved.push(cursor);
      },
    };
    const { poller, log, received } = pollerWith([page(1, 1), page(2, 2), page(3, 3), page(4, 4)], 100, store);
    await poller.pollOnce();
    await poller.pollOnce();
    await poller.pollOnce();
    assert.deepEqual(received.map((e) => e.id), [1, 2, 3]);
    assert.equal(poller.cursor, 3);
    assert.equal(log.warns.length, 1);
    assert.match(log.warns[0] ?? "", /disque plein/);
    assert.equal(log.errors.length, 0);

    broken = false;
    await poller.pollOnce();
    assert.deepEqual(saved, [4]);
    assert.equal(log.warns.length, 1);
  });
});
