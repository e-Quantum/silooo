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

function pollerWith(script: Parameters<typeof scriptedTransport>[0], pageSize = 100) {
  const transport = scriptedTransport(script);
  const api = createCoreApi(
    createCoreClient({ transport, coreUrl: "http://core", apiKey: "k", timeoutSeconds: 5, readRetries: 0 }),
  );
  const log = recordingLogger();
  const listeners = createEventListeners(log);
  const received: GameEvent[] = [];
  for (const kind of ["ANNONCE", "INCIDENT"] as const) listeners.on(kind, (e) => void received.push(e));
  const poller = createEventPoller({ api, listeners, status: createStatusLog(log, "événements"), log, pageSize });
  return { poller, transport, log, received };
}

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
