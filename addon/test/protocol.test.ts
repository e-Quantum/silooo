import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, it } from "node:test";
import {
  ERROR_REASONS,
  EVENTS_LIMIT_MAX,
  EVENTS_LIMIT_MIN,
  eventsUrl,
  GAME_EVENT_KINDS,
  heartbeatBody,
  joinBody,
  leaveBody,
  parseEventsPage,
  parseJoinResponse,
  PATHS,
  REFUSAL_REASONS,
} from "../src/protocol";
import { joinResponse } from "./helpers";

describe("corps de requête", () => {
  it("join : le XUID reste une chaîne, même au-delà de 2^53", () => {
    const xuid = "9007199254740993123";
    assert.equal(JSON.stringify(joinBody(xuid, "Alex")), `{"xuid":"${xuid}","gamertag":"Alex"}`);
  });

  it("leave ne porte que le XUID", () => {
    assert.deepEqual(leaveBody("42"), { xuid: "42" });
  });

  it("heartbeat : identifiant du serveur et copie de la liste", () => {
    const online = ["1", "2"];
    const body = heartbeatBody("bds-1", online);
    online.push("3");
    assert.deepEqual(body, { serverId: "bds-1", onlineXuids: ["1", "2"] });
  });

  it("heartbeat : un serveur vide envoie une liste vide", () => {
    assert.deepEqual(heartbeatBody("bds-1", []), { serverId: "bds-1", onlineXuids: [] });
  });
});

describe("eventsUrl", () => {
  it("compose le chemin, le curseur et la taille de page", () => {
    assert.equal(eventsUrl(17, 100), "/v1/events?after=17&limit=100");
  });

  it("ramène limit dans les bornes du contrat", () => {
    assert.equal(eventsUrl(0, 0), `/v1/events?after=0&limit=${EVENTS_LIMIT_MIN}`);
    assert.equal(eventsUrl(0, 9999), `/v1/events?after=0&limit=${EVENTS_LIMIT_MAX}`);
    assert.equal(eventsUrl(0, Number.NaN), `/v1/events?after=0&limit=${EVENTS_LIMIT_MAX}`);
  });

  it("ramène after à un entier positif", () => {
    assert.equal(eventsUrl(-5, 10), "/v1/events?after=0&limit=10");
    assert.equal(eventsUrl(Number.NaN, 10), "/v1/events?after=0&limit=10");
    assert.equal(eventsUrl(12.9, 10), "/v1/events?after=12&limit=10");
  });
});

describe("parseJoinResponse", () => {
  it("lit une réponse complète", () => {
    const body = {
      ...joinResponse({ role: "PDG", rank: null, pilierUntil: "2026-12-01T00:00:00Z", tutorialStep: 2, offlineCollected: 12.5 }),
      state: {
        ...joinResponse().state,
        zonesOwned: [1, 2],
        factories: [
          {
            factoryId: "carriere",
            state: "ACTIVE",
            level: 3,
            ratePerHour: 7200,
            nextCost: 150,
            nextMilestone: 25,
            incidentId: null,
            stoppedNoPower: false,
            restoreStep: 2,
          },
        ],
      },
    };
    const parsed = parseJoinResponse(body);
    assert.ok(parsed.ok);
    assert.equal(parsed.value.state.factories[0]?.factoryId, "carriere");
    assert.equal(parsed.value.rank, null);
    assert.equal(parsed.value.role, "PDG");
    assert.deepEqual(parsed.value.state.zonesOwned, [1, 2]);
    assert.equal(parsed.value.offlineCollected, 12.5);
  });

  it("lit une réponse minimale, sans clé facultative", () => {
    const parsed = parseJoinResponse(joinResponse());
    assert.ok(parsed.ok);
    assert.equal("role" in parsed.value, false);
    assert.equal("tutorialStep" in parsed.value, false);
  });

  it("nomme la clé fautive", () => {
    const sansSoldes: Record<string, unknown> = { ...joinResponse() };
    delete sansSoldes["balances"];
    assert.deepEqual(parseJoinResponse(sansSoldes), { ok: false, problem: "join.balances : objet attendu" });
    assert.deepEqual(parseJoinResponse(null), { ok: false, problem: "join : objet attendu" });
    assert.deepEqual(parseJoinResponse({ ...joinResponse(), firstJoin: "oui" }), {
      ok: false,
      problem: "join.firstJoin : booléen attendu",
    });
  });

  it("refuse un état d'usine inconnu", () => {
    const body = { ...joinResponse(), state: { ...joinResponse().state, factories: [{ factoryId: "x", state: "CASSEE", level: 1, ratePerHour: 1, nextCost: 1, nextMilestone: 1 }] } };
    const parsed = parseJoinResponse(body);
    assert.equal(parsed.ok, false);
    if (!parsed.ok) assert.match(parsed.problem, /join\.state\.factories\[0\]\.state/);
  });
});

describe("parseEventsPage", () => {
  it("lit les événements du plus ancien au plus récent", () => {
    const parsed = parseEventsPage({
      cursor: 12,
      events: [
        { id: 11, xuid: "42", kind: "GEMMES_RECUES", payload: { gemmes: 50 } },
        { id: 12, kind: "ANNONCE", payload: {} },
      ],
    });
    assert.ok(parsed.ok);
    assert.equal(parsed.value.cursor, 12);
    assert.deepEqual(
      parsed.value.events.map((e) => [e.id, e.xuid, e.kind]),
      [
        [11, "42", "GEMMES_RECUES"],
        [12, null, "ANNONCE"],
      ],
    );
    assert.equal(parsed.value.skipped, 0);
  });

  it("garde un type d'événement que le contrat de cet add-on ne connaît pas encore", () => {
    const parsed = parseEventsPage({ cursor: 1, events: [{ id: 1, kind: "NOUVEAU_TYPE", payload: {} }] });
    assert.ok(parsed.ok);
    assert.equal(parsed.value.events[0]?.kind, "NOUVEAU_TYPE");
  });

  it("un payload absent devient un objet vide", () => {
    const parsed = parseEventsPage({ cursor: 1, events: [{ id: 1, kind: "ANNONCE" }] });
    assert.ok(parsed.ok);
    assert.deepEqual(parsed.value.events[0]?.payload, {});
  });

  it("ignore un événement illisible et le compte", () => {
    const parsed = parseEventsPage({
      cursor: 3,
      events: [{ id: 1, kind: "ANNONCE", payload: {} }, { id: "deux" }, null, { id: 3, kind: "ANNONCE", payload: {} }],
    });
    assert.ok(parsed.ok);
    assert.deepEqual(parsed.value.events.map((e) => e.id), [1, 3]);
    assert.equal(parsed.value.skipped, 2);
  });

  it("refuse une page sans curseur ou sans tableau", () => {
    assert.equal(parseEventsPage({ events: [] }).ok, false);
    assert.equal(parseEventsPage({ cursor: 0 }).ok, false);
    assert.equal(parseEventsPage("texte").ok, false);
  });
});

/** Le contrat est dans l'OpenAPI : ces tests échouent si une liste de codes ou un chemin dérive. */
describe("accord avec api/silooo-core.openapi.yaml", () => {
  const yaml = readFileSync(resolve("..", "api", "silooo-core.openapi.yaml"), "utf8");

  function enumAfter(marqueur: string): string[] {
    const debut = yaml.indexOf(marqueur);
    assert.notEqual(debut, -1, `« ${marqueur} » introuvable dans l'OpenAPI`);
    const ouvre = yaml.indexOf("[", yaml.indexOf("enum:", debut));
    const ferme = yaml.indexOf("]", ouvre);
    return yaml
      .slice(ouvre + 1, ferme)
      .split(",")
      .map((mot) => mot.trim())
      .filter((mot) => mot !== "");
  }

  it("les chemins des trois écritures et de la lecture existent", () => {
    for (const chemin of [PATHS.heartbeat, PATHS.join, PATHS.leave, "/v1/events"]) {
      assert.ok(yaml.includes(`\n  ${chemin}:`), `${chemin} absent de l'OpenAPI`);
    }
  });

  it("les types d'événements sont ceux de GameEvent", () => {
    assert.deepEqual([...GAME_EVENT_KINDS], enumAfter("    GameEvent:"));
  });

  it("les codes de refus sont ceux de Refused", () => {
    assert.deepEqual([...REFUSAL_REASONS], enumAfter("    Refused:"));
  });

  it("les codes d'erreur sont ceux de Erreur", () => {
    assert.deepEqual([...ERROR_REASONS], enumAfter("    Erreur:"));
  });

  it("limit va de 1 à 500 comme dans pollEvents", () => {
    assert.match(yaml, /name: limit, in: query, schema: \{ type: integer, default: \d+, maximum: 500 \}/);
    assert.equal(EVENTS_LIMIT_MAX, 500);
    assert.equal(EVENTS_LIMIT_MIN, 1);
  });
});
