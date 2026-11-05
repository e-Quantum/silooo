import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, it } from "node:test";
import {
  actionBody,
  BUY_AMOUNTS,
  buyLevelsBody,
  collectPath,
  COMPANY_PATHS,
  companyPath,
  ERROR_REASONS,
  EVENTS_LIMIT_MAX,
  EVENTS_LIMIT_MIN,
  eventsUrl,
  GAME_EVENT_KINDS,
  heartbeatBody,
  isRestoreStep,
  joinBody,
  leaveBody,
  levelsPath,
  parseCollectResponse,
  parseCompanyState,
  parseCompanyUpdated,
  parseEventsPage,
  parseJoinResponse,
  PATHS,
  REFUSAL_REASONS,
  RESTORE_STEP_MAX,
  RESTORE_STEP_MIN,
  restorePath,
  restoreStepBody,
} from "../src/protocol";
import { balances, companyState, factory, joinResponse } from "./helpers";

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

// ---------------------------------------------------------------- phase 3

describe("corps et chemins des quatre routes de l'entreprise", () => {
  const action = { requestId: "0197f2a0-0000-7000-8000-000000000001", actor: "42" };

  it("les chemins remplacent companyId et factoryId", () => {
    assert.equal(companyPath(7), "/v1/companies/7");
    assert.equal(collectPath(7), "/v1/companies/7/collect");
    assert.equal(levelsPath(7, "mine-profonde"), "/v1/companies/7/factories/mine-profonde/levels");
    assert.equal(restorePath(7, "scierie"), "/v1/companies/7/factories/scierie/restore");
  });

  it("l'identifiant d'usine est encodé, et un identifiant illisible lève une erreur", () => {
    assert.equal(levelsPath(7, "a/b c"), "/v1/companies/7/factories/a%2Fb%20c/levels");
    assert.throws(() => companyPath(1.5), RangeError);
    assert.throws(() => companyPath(-1), RangeError);
    assert.throws(() => collectPath(Number.NaN), RangeError);
    assert.throws(() => levelsPath(7, ""), RangeError);
  });

  it("une action porte requestId et actor, et rien d'autre", () => {
    assert.deepEqual(actionBody({ ...action, extra: 1 } as typeof action), action);
    assert.deepEqual(buyLevelsBody(action, "palier"), { ...action, amount: "palier" });
    assert.deepEqual(restoreStepBody(action, 2), { ...action, step: 2 });
  });

  it("l'acteur reste une chaîne, même au-delà de 2^53", () => {
    const big = { requestId: action.requestId, actor: "9007199254740993123" };
    assert.match(JSON.stringify(actionBody(big)), /"actor":"9007199254740993123"/);
  });

  it("isRestoreStep accepte 1 à 4 et rien d'autre", () => {
    for (const step of [1, 2, 3, 4]) assert.equal(isRestoreStep(step), true);
    for (const step of [0, 5, -1, 1.5, Number.NaN]) assert.equal(isRestoreStep(step), false);
  });
});

describe("lecture des réponses des quatre routes", () => {
  it("parseCompanyState lit un état complet", () => {
    const parsed = parseCompanyState(companyState({ donar: 12.5, factories: [factory({ restoreStep: 2, incidentId: 9 })] }));
    assert.ok(parsed.ok);
    assert.equal(parsed.value.donar, 12.5);
    assert.equal(parsed.value.factories[0]?.incidentId, 9);
  });

  it("parseCompanyState nomme la clé fautive", () => {
    assert.deepEqual(parseCompanyState({ ...companyState(), version: "1" }), {
      ok: false,
      problem: "company.version : nombre attendu",
    });
    assert.deepEqual(parseCompanyState(undefined), { ok: false, problem: "company : objet attendu" });
  });

  it("parseCollectResponse lit collected, offlineHours et state", () => {
    const parsed = parseCollectResponse({ collected: 240000, offlineHours: 8, state: companyState() });
    assert.ok(parsed.ok);
    assert.equal(parsed.value.collected, 240000);
    assert.equal(parsed.value.offlineHours, 8);
    assert.equal(parseCollectResponse({ collected: 1, state: companyState() }).ok, false);
  });

  it("parseCompanyUpdated lit state et balances", () => {
    const parsed = parseCompanyUpdated({ state: companyState(), balances: balances({ lingots: 4 }) });
    assert.ok(parsed.ok);
    assert.equal(parsed.value.balances.lingots, 4);
    assert.deepEqual(parseCompanyUpdated({ state: companyState() }), { ok: false, problem: "update.balances : objet attendu" });
  });
});

/** Le contrat est dans l'OpenAPI : si une clé obligatoire ou un chemin dérive, ces tests échouent. */
describe("accord avec api/silooo-core.openapi.yaml, routes de l'entreprise", () => {
  const yaml = readFileSync(resolve("..", "api", "silooo-core.openapi.yaml"), "utf8");

  function after(marqueur: string, depuis = 0): number {
    const debut = yaml.indexOf(marqueur, depuis);
    assert.notEqual(debut, -1, `« ${marqueur} » introuvable dans l'OpenAPI`);
    return debut;
  }

  function listAfter(marqueur: string, motCle: string): string[] {
    const debut = after(marqueur);
    const ouvre = yaml.indexOf("[", after(motCle, debut));
    const ferme = yaml.indexOf("]", ouvre);
    return yaml
      .slice(ouvre + 1, ferme)
      .split(",")
      .map((mot) => mot.trim().replace(/^['"]|['"]$/g, ""))
      .filter((mot) => mot !== "");
  }

  it("les quatre chemins existent", () => {
    for (const chemin of Object.values(COMPANY_PATHS)) {
      assert.ok(yaml.includes(`\n  ${chemin}:`), `${chemin} absent de l'OpenAPI`);
    }
  });

  it("les quantités de buyLevels sont celles de l'OpenAPI", () => {
    assert.deepEqual([...BUY_AMOUNTS], listAfter("operationId: buyLevels", "enum:"));
  });

  it("les étapes de restoreStep vont de 1 à 4", () => {
    const debut = after("operationId: restoreStep");
    assert.match(yaml.slice(debut, debut + 900), /step: \{ type: integer, minimum: 1, maximum: 4 \}/);
    assert.equal(RESTORE_STEP_MIN, 1);
    assert.equal(RESTORE_STEP_MAX, 4);
  });

  it("une action exige requestId et actor", () => {
    assert.deepEqual(listAfter("    ActionBase:", "required:"), ["requestId", "actor"]);
  });

  /** Retire chaque clé obligatoire du modèle, l'une après l'autre : la lecture doit refuser. */
  function exigeToutes(marqueur: string, modele: Record<string, unknown>, lire: (corps: unknown) => { ok: boolean }): void {
    const requises = listAfter(marqueur, "required:");
    assert.ok(requises.length > 0);
    assert.equal(lire(modele).ok, true, `le modèle de test de ${marqueur} doit être valide`);
    for (const cle of requises) {
      assert.ok(cle in modele, `le modèle de test n'a pas la clé obligatoire « ${cle} » de ${marqueur}`);
      const sans = { ...modele };
      delete sans[cle];
      assert.equal(lire(sans).ok, false, `${marqueur} : la lecture accepte une réponse sans « ${cle} »`);
    }
  }

  it("CompanyState : toutes les clés obligatoires sont lues et exigées", () => {
    exigeToutes("    CompanyState:", { ...companyState() }, parseCompanyState);
  });

  it("Factory : toutes les clés obligatoires sont lues et exigées", () => {
    exigeToutes("    Factory:", { ...factory() }, (corps) => parseCompanyState({ ...companyState(), factories: [corps] }));
  });

  it("Balances : toutes les clés obligatoires sont lues et exigées", () => {
    exigeToutes("    Balances:", { ...balances() }, (corps) => parseCompanyUpdated({ state: companyState(), balances: corps }));
  });

  it("collect : collected, offlineHours et state sont exigés", () => {
    const modele = { collected: 1, offlineHours: 2, state: companyState() };
    const debut = after("operationId: collect");
    assert.match(yaml.slice(debut, debut + 1200), /required: \[collected, offlineHours, state\]/);
    for (const cle of Object.keys(modele)) {
      const sans: Record<string, unknown> = { ...modele };
      delete sans[cle];
      assert.equal(parseCollectResponse(sans).ok, false, cle);
    }
  });

  it("CompanyUpdated exige state et balances", () => {
    assert.deepEqual(listAfter("    CompanyUpdated:", "required:"), ["state", "balances"]);
  });
});
