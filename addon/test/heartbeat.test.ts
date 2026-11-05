import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createCoreApi } from "../src/api";
import { createHeartbeat, startHeartbeat } from "../src/heartbeat";
import { createCoreClient } from "../src/http";
import { createStatusLog } from "../src/runtime";
import { fakeScheduler, json, recordingLogger, scriptedTransport } from "./helpers";

function setup(script: Parameters<typeof scriptedTransport>[0], online: () => string[] = () => ["1", "2"]) {
  const transport = scriptedTransport(script);
  const api = createCoreApi(
    createCoreClient({ transport, coreUrl: "http://core", apiKey: "k", timeoutSeconds: 5, readRetries: 1 }),
  );
  const log = recordingLogger();
  const heartbeat = createHeartbeat({ api, serverId: "bds-1", onlineXuids: online, status: createStatusLog(log, "heartbeat") });
  return { heartbeat, transport, log };
}

describe("heartbeat", () => {
  it("envoie l'identifiant du serveur et les XUID en ligne", async () => {
    const { heartbeat, transport } = setup([{ status: 204, body: "" }]);
    await heartbeat.beat();
    const [request] = transport.requests;
    assert.equal(request?.url, "http://core/v1/server/heartbeat");
    assert.equal(request?.method, "POST");
    assert.deepEqual(JSON.parse(request?.body ?? "null"), { serverId: "bds-1", onlineXuids: ["1", "2"] });
  });

  it("n'est jamais rejoué tout seul : un POST, un envoi", async () => {
    const { heartbeat, transport } = setup([new Error("coupé")]);
    await heartbeat.beat();
    assert.equal(transport.requests.length, 1);
  });

  it("un serveur arrêté ne remplit pas le journal : un échec, puis le retour", async () => {
    const { heartbeat, log } = setup([new Error("coupé"), json(500, { reason: "ERREUR_INTERNE" }), { status: 204, body: "" }]);
    await heartbeat.beat();
    await heartbeat.beat();
    await heartbeat.beat();
    assert.equal(log.warns.length, 2);
    assert.match(log.warns[0] ?? "", /heartbeat : échec \(réseau : Error: coupé\)/);
    assert.match(log.warns[1] ?? "", /heartbeat : échec \(erreur 500 ERREUR_INTERNE\)/);
    assert.match(log.infos.join("\n"), /heartbeat : rétabli après 2 échec/);
  });

  it("deux échecs identiques de suite ne donnent qu'une ligne", async () => {
    const { heartbeat, log } = setup([json(401, { reason: "CLE_INVALIDE" })]);
    await heartbeat.beat();
    await heartbeat.beat();
    assert.equal(log.warns.length, 1);
  });

  it("un envoi encore en vol empêche le suivant", async () => {
    let libere: () => void = () => undefined;
    const attente = new Promise<void>((resolve) => {
      libere = resolve;
    });
    const log = recordingLogger();
    let envois = 0;
    const heartbeat = createHeartbeat({
      api: {
        heartbeat: async () => {
          envois += 1;
          await attente;
          return { kind: "ok", status: 204, body: null };
        },
      },
      serverId: "bds-1",
      onlineXuids: () => [],
      status: createStatusLog(log, "heartbeat"),
    });
    const premier = heartbeat.beat();
    await heartbeat.beat();
    assert.equal(envois, 1);
    libere();
    await premier;
    await heartbeat.beat();
    assert.equal(envois, 2);
  });

  it("une lecture des joueurs qui lève est un échec journalisé, pas une exception", async () => {
    const { heartbeat, log, transport } = setup([{ status: 204, body: "" }], () => {
      throw new Error("monde pas prêt");
    });
    await heartbeat.beat();
    assert.equal(transport.requests.length, 0);
    assert.match(log.warns.join("\n"), /monde pas prêt/);
  });
});

describe("startHeartbeat", () => {
  it("un premier envoi après le délai de démarrage, puis un par intervalle", () => {
    const scheduler = fakeScheduler();
    const run = (): void => undefined;
    startHeartbeat(scheduler, run, 10, 1);
    assert.deepEqual(scheduler.timeouts.map((t) => t.seconds), [1]);
    assert.deepEqual(scheduler.intervals.map((t) => t.seconds), [10]);
  });
});
