import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, it } from "node:test";
import {
  actionBody,
  agencyPath,
  assignStaffBody,
  BUY_AMOUNTS,
  buyLevelsBody,
  collectPath,
  COMPANY_PATHS,
  companyPath,
  ERROR_REASONS,
  EVENTS_LIMIT_MAX,
  EVENTS_LIMIT_MIN,
  eventsUrl,
  fireStaffPath,
  GAME_EVENT_KINDS,
  heartbeatBody,
  hireBody,
  hirePath,
  isRestoreStep,
  joinBody,
  leaveBody,
  levelsPath,
  MISSIONS_PATH,
  missionsPath,
  parseCandidates,
  parseCollectResponse,
  parseCompanyState,
  parseCompanyUpdated,
  parseEventsPage,
  parseJoinResponse,
  parseMissionState,
  parsePrestigePreview,
  PATHS,
  PRESTIGE_NEXT,
  prestigePath,
  prestigePreviewPath,
  REFUSAL_REASONS,
  RESTORE_STEP_MAX,
  RESTORE_STEP_MIN,
  resolveIncidentPath,
  restorePath,
  restoreStepBody,
  SLOT_MAX,
  SLOT_MIN,
  STAFF_PATHS,
  STAFF_RARITIES,
  STAFF_ROLES,
  staffPath,
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

  it("le chemin de résolution d'un incident remplace companyId et incidentId", () => {
    assert.equal(resolveIncidentPath(7, 31), "/v1/companies/7/incidents/31/resolve");
    assert.equal(resolveIncidentPath(7, 0), "/v1/companies/7/incidents/0/resolve");
    assert.equal(COMPANY_PATHS.resolveIncident, "/v1/companies/{companyId}/incidents/{incidentId}/resolve");
  });

  it("un identifiant d'incident ou d'entreprise illisible lève une erreur : rien ne part au service", () => {
    for (const id of [-1, 1.5, Number.NaN, Number.POSITIVE_INFINITY, 2 ** 60]) {
      assert.throws(() => resolveIncidentPath(7, id), RangeError);
    }
    assert.throws(() => resolveIncidentPath(1.5, 31), RangeError);
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

  it("parseCompanyState lit la sorte de l'incident (incidentKind), nulle ou absente", () => {
    const avec = parseCompanyState(companyState({ factories: [factory({ incidentId: 31, incidentKind: "incendie" })] }));
    assert.ok(avec.ok);
    assert.equal(avec.value.factories[0]?.incidentKind, "incendie");
    const nulle = parseCompanyState(companyState({ factories: [factory({ incidentId: null, incidentKind: null })] }));
    assert.ok(nulle.ok);
    assert.equal(nulle.value.factories[0]?.incidentKind, null);
    // Un service plus ancien ne la donne pas : la clé reste absente, sans valeur inventée.
    const absente = parseCompanyState(companyState({ factories: [factory({ incidentId: 31 })] }));
    assert.ok(absente.ok);
    assert.equal("incidentKind" in (absente.value.factories[0] ?? {}), false);
  });

  it("parseCompanyState refuse une sorte d'incident qui n'est pas du texte et nomme la clé", () => {
    const parsed = parseCompanyState({
      ...companyState(),
      factories: [{ ...factory({ incidentId: 31 }), incidentKind: 7 }],
    });
    assert.equal(parsed.ok, false);
    if (!parsed.ok) assert.match(parsed.problem, /incidentKind/);
  });

  it("parseCompanyState nomme la clé fautive", () => {
    assert.deepEqual(parseCompanyState({ ...companyState(), version: "1" }), {
      ok: false,
      problem: "company.version : nombre attendu",
    });
    assert.deepEqual(parseCompanyState(undefined), { ok: false, problem: "company : objet attendu" });
  });

  it("parseCompanyState lit l'origine de l'île quand le service la donne, et rien quand il ne la donne pas", () => {
    const avec = parseCompanyState({ ...companyState(), island: { x: 1200, z: -600 } });
    assert.ok(avec.ok);
    assert.deepEqual(avec.value.island, { x: 1200, z: -600 });
    const sans = parseCompanyState(companyState());
    assert.ok(sans.ok);
    assert.equal("island" in sans.value, false);
    const dansJoin = parseJoinResponse({ ...joinResponse(), state: { ...companyState(), island: { x: 0, z: 0 } } });
    assert.ok(dansJoin.ok);
    assert.deepEqual(dansJoin.value.state.island, { x: 0, z: 0 });
  });

  it("parseCompanyState refuse une île illisible et nomme la clé fautive", () => {
    const lire = (island: unknown) => parseCompanyState({ ...companyState(), island });
    assert.deepEqual(lire({ x: 1 }), { ok: false, problem: "company.island.z : nombre attendu" });
    assert.deepEqual(lire({ x: "1", z: 2 }), { ok: false, problem: "company.island.x : nombre attendu" });
    assert.deepEqual(lire({ x: 1.5, z: 2 }), { ok: false, problem: "company.island.x : entier attendu" });
    assert.deepEqual(lire({ x: 1, z: 2.25 }), { ok: false, problem: "company.island.z : entier attendu" });
    assert.deepEqual(lire(null), { ok: false, problem: "company.island : objet attendu" });
    assert.deepEqual(lire([1, 2]), { ok: false, problem: "company.island : objet attendu" });
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

  it("les sept chemins existent", () => {
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

  it("l'île du contrat est un objet facultatif {x, z} en entiers", () => {
    const debut = after("    CompanyState:");
    const bloc = yaml.slice(debut, debut + 2600);
    assert.match(bloc, /island:\s*\n\s*type: object/);
    assert.match(bloc, /required: \[x, z\]/);
    assert.deepEqual(listAfter("    CompanyState:", "required:").includes("island"), false);
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

describe("les missions : chemin et lecture", () => {
  it("le chemin remplace le XUID, qui reste une chaîne", () => {
    assert.equal(missionsPath("42"), "/v1/players/42/missions");
    assert.equal(missionsPath("9007199254740993123"), "/v1/players/9007199254740993123/missions");
  });

  it("un XUID illisible lève une erreur : rien ne part au service", () => {
    for (const xuid of ["", "abc", "4 2", "42/missions", "-1", "1.5", "123456789012345678901"]) {
      assert.throws(() => missionsPath(xuid), RangeError, xuid);
    }
  });

  it("parseMissionState lit la mission en cours et les missions terminées", () => {
    const parsed = parseMissionState({
      current: { id: "bienvenue-mairie", progress: 0, target: 1, rewardLingots: 10 },
      completed: ["premiere", "seconde"],
    });
    assert.ok(parsed.ok);
    assert.deepEqual(parsed.value, {
      current: { id: "bienvenue-mairie", progress: 0, target: 1, rewardLingots: 10 },
      completed: ["premiere", "seconde"],
    });
  });

  it("parseMissionState lit `current: null` quand toutes les missions sont terminées", () => {
    const parsed = parseMissionState({ current: null, completed: ["bienvenue-mairie"] });
    assert.ok(parsed.ok);
    assert.deepEqual(parsed.value, { current: null, completed: ["bienvenue-mairie"] });
  });

  it("parseMissionState rend les chiffres du service tels quels, sans rien en déduire", () => {
    const parsed = parseMissionState({ current: { id: "x", progress: 7, target: 3, rewardLingots: 0 }, completed: [] });
    assert.ok(parsed.ok);
    assert.deepEqual(parsed.value.current, { id: "x", progress: 7, target: 3, rewardLingots: 0 });
  });

  it("parseMissionState ignore les clés en plus, que le contrat pourra ajouter", () => {
    const parsed = parseMissionState({
      current: { id: "x", progress: 1, target: 2, rewardLingots: 3, bonus: "oui" },
      completed: [],
      saison: 4,
    });
    assert.ok(parsed.ok);
    assert.deepEqual(parsed.value, { current: { id: "x", progress: 1, target: 2, rewardLingots: 3 }, completed: [] });
  });

  it("parseMissionState nomme la clé fautive", () => {
    const mission = { id: "x", progress: 1, target: 2, rewardLingots: 3 };
    const lire = (corps: unknown) => parseMissionState(corps);
    assert.deepEqual(lire(undefined), { ok: false, problem: "missions : objet attendu" });
    assert.deepEqual(lire([]), { ok: false, problem: "missions : objet attendu" });
    assert.deepEqual(lire({ completed: [] }), { ok: false, problem: "missions.current : mission ou null attendu" });
    assert.deepEqual(lire({ current: mission }), { ok: false, problem: "missions.completed : tableau attendu" });
    assert.deepEqual(lire({ current: "x", completed: [] }), { ok: false, problem: "missions.current : objet attendu" });
    assert.deepEqual(lire({ current: { ...mission, id: 3 }, completed: [] }), { ok: false, problem: "missions.current.id : chaîne attendue" });
    assert.deepEqual(lire({ current: { ...mission, id: "" }, completed: [] }), { ok: false, problem: "missions.current.id : identifiant vide" });
    assert.deepEqual(lire({ current: { ...mission, progress: "1" }, completed: [] }), {
      ok: false,
      problem: "missions.current.progress : nombre attendu",
    });
    assert.deepEqual(lire({ current: { ...mission, target: 1.5 }, completed: [] }), {
      ok: false,
      problem: "missions.current.target : entier attendu",
    });
    assert.deepEqual(lire({ current: { ...mission, rewardLingots: 0.25 }, completed: [] }), {
      ok: false,
      problem: "missions.current.rewardLingots : entier attendu",
    });
    assert.deepEqual(lire({ current: mission, completed: ["a", 4] }), { ok: false, problem: "missions.completed[1] : identifiant attendu" });
    assert.deepEqual(lire({ current: mission, completed: [""] }), { ok: false, problem: "missions.completed[0] : identifiant attendu" });
  });

  it("parseMissionState refuse les bornes du contrat : progress au moins 0, target au moins 1, récompense au moins 0", () => {
    const mission = { id: "x", progress: 1, target: 2, rewardLingots: 3 };
    for (const [cle, valeur] of [["progress", -1], ["target", 0], ["target", -2], ["rewardLingots", -5]] as const) {
      const parsed = parseMissionState({ current: { ...mission, [cle]: valeur }, completed: [] });
      assert.equal(parsed.ok, false, `${cle} = ${valeur}`);
    }
    assert.equal(parseMissionState({ current: { ...mission, progress: 0, rewardLingots: 0 }, completed: [] }).ok, true);
    assert.equal(parseMissionState({ current: { ...mission, target: 1 }, completed: [] }).ok, true);
  });

  it("parseMissionState refuse les nombres qui ne sont pas des nombres", () => {
    const mission = { id: "x", progress: 1, target: 2, rewardLingots: 3 };
    for (const valeur of [Number.NaN, Number.POSITIVE_INFINITY, null, undefined]) {
      assert.equal(parseMissionState({ current: { ...mission, progress: valeur }, completed: [] }).ok, false);
    }
  });
});

/** Le contrat est dans l'OpenAPI : si le chemin ou une clé obligatoire des missions dérive, ces tests échouent. */
describe("accord avec api/silooo-core.openapi.yaml, missions", () => {
  const yaml = readFileSync(resolve("..", "api", "silooo-core.openapi.yaml"), "utf8");

  function requiredOf(schema: string): string[] {
    const debut = yaml.indexOf(`    ${schema}:\n`);
    assert.notEqual(debut, -1, `schéma ${schema} absent de l'OpenAPI`);
    const ouvre = yaml.indexOf("required: [", debut);
    const ferme = yaml.indexOf("]", ouvre);
    return yaml
      .slice(ouvre + "required: [".length, ferme)
      .split(",")
      .map((mot) => mot.trim())
      .filter((mot) => mot !== "");
  }

  it("le chemin existe et c'est un GET nommé getMissions", () => {
    const debut = yaml.indexOf(`\n  ${MISSIONS_PATH}:`);
    assert.notEqual(debut, -1, `${MISSIONS_PATH} absent de l'OpenAPI`);
    assert.match(yaml.slice(debut, debut + 300), /\n {4}get:\n/);
    assert.ok(yaml.indexOf("operationId: getMissions", debut) > debut);
  });

  it("Mission : toutes les clés obligatoires sont lues et exigées", () => {
    const modele: Record<string, unknown> = { id: "bienvenue-mairie", progress: 0, target: 1, rewardLingots: 10 };
    const requises = requiredOf("Mission");
    assert.deepEqual([...requises].sort(), Object.keys(modele).sort());
    for (const cle of requises) {
      const sans = { ...modele };
      delete sans[cle];
      assert.equal(parseMissionState({ current: sans, completed: [] }).ok, false, `Mission : la lecture accepte une réponse sans « ${cle} »`);
    }
  });

  it("MissionState : current et completed sont exigés", () => {
    const requises = requiredOf("MissionState");
    assert.deepEqual(requises, ["current", "completed"]);
    assert.equal(parseMissionState({ completed: [] }).ok, false);
    assert.equal(parseMissionState({ current: null }).ok, false);
  });

  it("les annonces de mission portent payload.action = mission, comme le contrat le dit", () => {
    const debut = yaml.indexOf("operationId: getMissions");
    assert.notEqual(debut, -1);
    const texte = yaml.slice(Math.max(0, debut - 900), debut);
    assert.match(texte, /`payload\.action` vaut `mission`/);
    assert.match(texte, /`missionId`/);
    assert.match(texte, /`rewardLingots`/);
  });
});

// ---------------------------------------------------------------- phase 4 : le prestige

describe("le prestige : chemins et lecture", () => {
  const apercu = {
    allowed: true,
    lingotsTotal: 1_200,
    lingotsIfDoubled: 1_700,
    shares: [
      { xuid: "42", lingots: 700 },
      { xuid: "9007199254740993123", lingots: 500 },
    ],
    next: "EPOQUE_2",
  };

  it("les chemins remplacent companyId", () => {
    assert.equal(prestigePath(7), "/v1/companies/7/prestige");
    assert.equal(prestigePreviewPath(7), "/v1/companies/7/prestige/preview");
  });

  it("un identifiant d'entreprise illisible lève une erreur : rien ne part au service", () => {
    for (const id of [1.5, -1, Number.NaN]) {
      assert.throws(() => prestigePath(id), RangeError);
      assert.throws(() => prestigePreviewPath(id), RangeError);
    }
  });

  it("parsePrestigePreview lit l'aperçu tel que le service le donne, les XUID restent des chaînes", () => {
    const parsed = parsePrestigePreview(apercu);
    assert.ok(parsed.ok);
    assert.deepEqual(parsed.value, apercu);
  });

  it("lingotsIfDoubled est facultatif : absent, il reste absent", () => {
    const { lingotsIfDoubled: _omis, ...sans } = apercu;
    const parsed = parsePrestigePreview(sans);
    assert.ok(parsed.ok);
    assert.equal("lingotsIfDoubled" in parsed.value, false);
  });

  it("pas permis : les zéros du service sont rendus tels quels", () => {
    const parsed = parsePrestigePreview({ allowed: false, lingotsTotal: 0, lingotsIfDoubled: 0, shares: [], next: "ETOILE" });
    assert.ok(parsed.ok);
    assert.deepEqual(parsed.value, { allowed: false, lingotsTotal: 0, lingotsIfDoubled: 0, shares: [], next: "ETOILE" });
  });

  it("chaque valeur de « next » du contrat est lue", () => {
    for (const next of PRESTIGE_NEXT) {
      const parsed = parsePrestigePreview({ ...apercu, next });
      assert.ok(parsed.ok, next);
      assert.equal(parsed.value.next, next);
    }
  });

  it("ignore les clés en plus, que le contrat pourra ajouter", () => {
    const parsed = parsePrestigePreview({ ...apercu, saison: 4, shares: [{ xuid: "42", lingots: 3, nom: "Alex" }] });
    assert.ok(parsed.ok);
    assert.deepEqual(parsed.value.shares, [{ xuid: "42", lingots: 3 }]);
  });

  it("nomme la clé fautive", () => {
    const lire = (corps: unknown) => parsePrestigePreview(corps);
    assert.deepEqual(lire(undefined), { ok: false, problem: "prestige : objet attendu" });
    assert.deepEqual(lire([]), { ok: false, problem: "prestige : objet attendu" });
    assert.deepEqual(lire({ ...apercu, allowed: "oui" }), { ok: false, problem: "prestige.allowed : booléen attendu" });
    assert.deepEqual(lire({ ...apercu, lingotsTotal: "12" }), { ok: false, problem: "prestige.lingotsTotal : nombre attendu" });
    assert.deepEqual(lire({ ...apercu, lingotsTotal: 1.5 }), { ok: false, problem: "prestige.lingotsTotal : entier attendu" });
    assert.deepEqual(lire({ ...apercu, lingotsTotal: -1 }), { ok: false, problem: "prestige.lingotsTotal : 0 au moins attendu" });
    assert.deepEqual(lire({ ...apercu, lingotsIfDoubled: -5 }), { ok: false, problem: "prestige.lingotsIfDoubled : 0 au moins attendu" });
    assert.deepEqual(lire({ ...apercu, shares: undefined }), { ok: false, problem: "prestige.shares : tableau attendu" });
    assert.deepEqual(lire({ ...apercu, shares: ["x"] }), { ok: false, problem: "prestige.shares[0] : objet attendu" });
    assert.deepEqual(lire({ ...apercu, shares: [{ lingots: 1 }] }), { ok: false, problem: "prestige.shares[0].xuid : chaîne attendue" });
    assert.deepEqual(lire({ ...apercu, shares: [{ xuid: "abc", lingots: 1 }] }), { ok: false, problem: "prestige.shares[0].xuid : XUID attendu" });
    assert.deepEqual(lire({ ...apercu, shares: [{ xuid: "42" }] }), { ok: false, problem: "prestige.shares[0].lingots : nombre attendu" });
    assert.deepEqual(lire({ ...apercu, next: "EPOQUE_9" }), {
      ok: false,
      problem: "prestige.next : une de EPOQUE_2, EPOQUE_3, EPOQUE_4, ETOILE attendue",
    });
  });

  it("refuse les nombres qui ne sont pas des nombres", () => {
    for (const valeur of [Number.NaN, Number.POSITIVE_INFINITY, null, undefined]) {
      assert.equal(parsePrestigePreview({ ...apercu, lingotsTotal: valeur }).ok, false);
    }
  });
});

/** Le contrat est dans l'OpenAPI : si un chemin ou une clé obligatoire du prestige dérive, ces tests échouent. */
describe("accord avec api/silooo-core.openapi.yaml, prestige", () => {
  const yaml = readFileSync(resolve("..", "api", "silooo-core.openapi.yaml"), "utf8");

  function blocOf(chemin: string): string {
    const debut = yaml.indexOf(`\n  ${chemin}:`);
    assert.notEqual(debut, -1, `${chemin} absent de l'OpenAPI`);
    const fin = yaml.indexOf("\n  /v1/", debut + 1);
    return yaml.slice(debut, fin === -1 ? undefined : fin);
  }

  it("prestigePreview est un GET, prestige un POST qui peut refuser en 403 et en 409", () => {
    const apercu = blocOf(COMPANY_PATHS.prestigePreview);
    assert.match(apercu, /\n {4}get:\n/);
    assert.match(apercu, /operationId: prestigePreview/);
    const prestige = blocOf(COMPANY_PATHS.prestige);
    assert.match(prestige, /\n {4}post:\n/);
    assert.match(prestige, /operationId: prestige\n/);
    assert.match(prestige, /'403': \{ \$ref: '#\/components\/responses\/Refused' \}/);
    assert.match(prestige, /'409': \{ \$ref: '#\/components\/responses\/Refused' \}/);
    assert.match(prestige, /requestBodies\/Action/);
  });

  it("PrestigePreview : toutes les clés obligatoires sont lues et exigées", () => {
    const debut = yaml.indexOf("    PrestigePreview:\n");
    assert.notEqual(debut, -1);
    const ouvre = yaml.indexOf("required: [", debut);
    const ferme = yaml.indexOf("]", ouvre);
    const requises = yaml
      .slice(ouvre + "required: [".length, ferme)
      .split(",")
      .map((mot) => mot.trim());
    assert.deepEqual(requises, ["allowed", "lingotsTotal", "shares", "next"]);
    const modele: Record<string, unknown> = { allowed: true, lingotsTotal: 1, shares: [], next: "ETOILE" };
    for (const cle of requises) {
      const sans = { ...modele };
      delete sans[cle];
      assert.equal(parsePrestigePreview(sans).ok, false, `la lecture accepte un aperçu sans « ${cle} »`);
    }
  });

  it("« next » prend les valeurs du contrat, ni plus ni moins", () => {
    const debut = yaml.indexOf("    PrestigePreview:\n");
    const ligne = yaml.slice(yaml.indexOf("next:", debut), yaml.indexOf("\n", yaml.indexOf("next:", debut)));
    const valeurs = ligne.slice(ligne.indexOf("[") + 1, ligne.indexOf("]")).split(",").map((mot) => mot.trim());
    assert.deepEqual([...PRESTIGE_NEXT], valeurs);
  });
});

describe("le personnel : chemins, corps et lecture", () => {
  const action = { requestId: "0197f2a0-0000-7000-8000-000000000001", actor: "9007199254740993123" };

  it("les chemins remplacent companyId, slot et staffId", () => {
    assert.equal(agencyPath(7), "/v1/companies/7/agency");
    assert.equal(hirePath(7, 3), "/v1/companies/7/agency/3/hire");
    assert.equal(staffPath(7, 11), "/v1/companies/7/staff/11");
  });

  it("une place hors de 1 à 5 lève une erreur : rien ne part au service", () => {
    for (const slot of [0, 6, 1.5, Number.NaN, -1]) {
      assert.throws(() => hirePath(7, slot), RangeError, `place ${slot}`);
    }
    assert.equal(hirePath(7, SLOT_MIN), "/v1/companies/7/agency/1/hire");
    assert.equal(hirePath(7, SLOT_MAX), "/v1/companies/7/agency/5/hire");
  });

  it("un staffId ou un identifiant d'entreprise illisible lève une erreur", () => {
    for (const id of [-1, 1.5, Number.NaN, Number.POSITIVE_INFINITY]) {
      assert.throws(() => staffPath(7, id), RangeError);
      assert.throws(() => fireStaffPath(7, id, action), RangeError);
    }
    assert.throws(() => agencyPath(-1), RangeError);
    assert.throws(() => hirePath(Number.NaN, 1), RangeError);
    assert.throws(() => staffPath(1.5, 11), RangeError);
  });

  it("fireStaffPath porte requestId et actor dans la requête, sans corps", () => {
    assert.equal(
      fireStaffPath(7, 11, action),
      "/v1/companies/7/staff/11?requestId=0197f2a0-0000-7000-8000-000000000001&actor=9007199254740993123",
    );
  });

  it("fireStaffPath encode le requestId et refuse un acteur qui n'est pas un XUID", () => {
    assert.match(fireStaffPath(7, 11, { requestId: "a b&c", actor: "42" }), /requestId=a%20b%26c&actor=42$/);
    for (const actor of ["", "abc", "4 2", "42&x=1", "-5"]) {
      assert.throws(() => fireStaffPath(7, 11, { requestId: "r", actor }), RangeError, `acteur « ${actor} »`);
    }
  });

  it("hireBody : sans usine, la clé factoryId n'existe pas ; avec usine, elle part telle quelle", () => {
    const sans = hireBody(action);
    assert.deepEqual(sans, action);
    assert.equal("factoryId" in sans, false);
    assert.deepEqual(hireBody(action, "scierie"), { ...action, factoryId: "scierie" });
  });

  it("assignStaffBody porte toujours l'usine, et l'acteur reste une chaîne", () => {
    const body = assignStaffBody(action, "carriere");
    assert.deepEqual(body, { ...action, factoryId: "carriere" });
    assert.equal(JSON.stringify(body).includes('"actor":"9007199254740993123"'), true);
  });

  const libre = {
    slot: 1,
    name: "Basile",
    skinId: "ouvrier-casque",
    role: "SALARIE",
    rarity: "COMMUN",
    bonusPercent: 10,
    salaryPercent: 2.5,
    hireCost: 4_800,
  };
  const embauche = {
    slot: 2,
    staffId: 11,
    name: "Adèle",
    skinId: "ouvriere-gilet",
    role: "CHEF",
    rarity: "RARE",
    bonusPercent: 25,
    salaryPercent: 4,
    salaryPerHour: 360,
    factoryId: "carriere",
  };

  it("parseCandidates lit la liste telle que le service la donne, dans son ordre", () => {
    const parsed = parseCandidates([libre, embauche]);
    assert.ok(parsed.ok);
    assert.deepEqual(parsed.value, [libre, embauche]);
  });

  it("une liste vide est lue : l'add-on ne fixe pas le nombre de candidats", () => {
    const parsed = parseCandidates([]);
    assert.ok(parsed.ok);
    assert.deepEqual(parsed.value, []);
  });

  it("un candidat libre n'a ni staffId ni usine ; un facultatif absent reste absent", () => {
    const parsed = parseCandidates([{ slot: 3, name: "Zoé", skinId: "x", role: "DIRECTEUR", rarity: "EPIQUE", bonusPercent: 40 }]);
    assert.ok(parsed.ok);
    const [candidat] = parsed.value;
    for (const cle of ["staffId", "salaryPercent", "salaryPerHour", "hireCost", "factoryId"]) {
      assert.equal(cle in (candidat ?? {}), false, cle);
    }
  });

  it("factoryId nul est lu comme nul : le membre est sans usine", () => {
    const parsed = parseCandidates([{ ...embauche, factoryId: null }]);
    assert.ok(parsed.ok);
    assert.equal(parsed.value[0]?.factoryId, null);
  });

  it("chaque rôle et chaque rareté du contrat est lu", () => {
    for (const role of STAFF_ROLES) {
      for (const rarity of STAFF_RARITIES) {
        const parsed = parseCandidates([{ ...libre, role, rarity }]);
        assert.ok(parsed.ok, `${role} ${rarity}`);
      }
    }
  });

  it("refuse un rôle ou une rareté hors contrat", () => {
    assert.equal(parseCandidates([{ ...libre, role: "PATRON" }]).ok, false);
    assert.equal(parseCandidates([{ ...libre, rarity: "LEGENDAIRE" }]).ok, false);
    assert.equal(parseCandidates([{ ...libre, rarity: "commun" }]).ok, false);
  });

  it("refuse un candidat sans place, ou avec une place hors de 1 à 5", () => {
    const { slot: _omise, ...sansPlace } = libre;
    assert.equal(parseCandidates([sansPlace]).ok, false);
    assert.equal(parseCandidates([{ ...libre, slot: 0 }]).ok, false);
    assert.equal(parseCandidates([{ ...libre, slot: 6 }]).ok, false);
    assert.equal(parseCandidates([{ ...libre, slot: 1.5 }]).ok, false);
  });

  it("refuse une clé obligatoire absente ou mal typée", () => {
    for (const cle of ["name", "skinId", "role", "rarity", "bonusPercent"]) {
      const sans: Record<string, unknown> = { ...libre };
      delete sans[cle];
      assert.equal(parseCandidates([sans]).ok, false, `sans ${cle}`);
    }
    assert.equal(parseCandidates([{ ...libre, bonusPercent: "10" }]).ok, false);
    assert.equal(parseCandidates([{ ...libre, hireCost: Number.NaN }]).ok, false);
    assert.equal(parseCandidates([{ ...libre, salaryPerHour: "180" }]).ok, false);
    assert.equal(parseCandidates([{ ...libre, factoryId: 4 }]).ok, false);
  });

  it("refuse ce qui n'est pas une liste de candidats", () => {
    for (const corps of [null, {}, "x", 4, [null], [4], [[]]]) {
      assert.equal(parseCandidates(corps).ok, false);
    }
  });

  it("dit quel candidat est faux", () => {
    const parsed = parseCandidates([libre, { ...libre, slot: 2, rarity: "MYTHIQUE" }]);
    assert.equal(parsed.ok, false);
    if (!parsed.ok) assert.match(parsed.problem, /agence\[1\]\.rarity/);
  });

  const base = {
    companyId: 7,
    name: "Les Carrières",
    era: 1,
    stars: 0,
    courseNumber: 1,
    donar: 10,
    donarEarned: 10,
    ratePerHour: 3_600,
    multiplier: 1,
    threshold: 1_000,
    factories: [],
    version: 3,
  };

  it("CompanyState.staff est facultatif : absent, il reste absent", () => {
    const parsed = parseCompanyState(base);
    assert.ok(parsed.ok);
    assert.equal("staff" in parsed.value, false);
  });

  it("CompanyState.staff est lu membre par membre, avec son staffId", () => {
    const membre = { ...embauche };
    delete (membre as Record<string, unknown>)["slot"];
    const parsed = parseCompanyState({ ...base, staff: [membre] });
    assert.ok(parsed.ok);
    assert.deepEqual(parsed.value.staff, [membre]);
  });

  it("un membre du personnel sans staffId est refusé", () => {
    const { staffId: _omis, ...sansId } = embauche;
    const parsed = parseCompanyState({ ...base, staff: [sansId] });
    assert.equal(parsed.ok, false);
    if (!parsed.ok) assert.match(parsed.problem, /staff\[0\]\.staffId/);
  });

  it("un membre au rôle, à la rareté ou au staffId hors contrat est refusé", () => {
    assert.equal(parseCompanyState({ ...base, staff: [{ ...embauche, role: "PATRON" }] }).ok, false);
    assert.equal(parseCompanyState({ ...base, staff: [{ ...embauche, rarity: "ORDINAIRE" }] }).ok, false);
    assert.equal(parseCompanyState({ ...base, staff: [{ ...embauche, staffId: -1 }] }).ok, false);
    assert.equal(parseCompanyState({ ...base, staff: [{ ...embauche, staffId: "11" }] }).ok, false);
    assert.equal(parseCompanyState({ ...base, staff: {} }).ok, false);
  });

  it("l'état d'une entreprise sans personnel donne une liste vide, lue telle quelle", () => {
    const parsed = parseCompanyState({ ...base, staff: [] });
    assert.ok(parsed.ok);
    assert.deepEqual(parsed.value.staff, []);
  });
});

/** Le contrat est dans l'OpenAPI : si un chemin, un verbe ou une énumération du personnel dérive, ces tests échouent. */
describe("accord avec api/silooo-core.openapi.yaml, personnel", () => {
  const yaml = readFileSync(resolve("..", "api", "silooo-core.openapi.yaml"), "utf8");

  function blocOf(chemin: string): string {
    const debut = yaml.indexOf(`\n  ${chemin}:`);
    assert.notEqual(debut, -1, `${chemin} absent de l'OpenAPI`);
    const fin = yaml.indexOf("\n  /v1/", debut + 1);
    return yaml.slice(debut, fin === -1 ? undefined : fin);
  }

  function enumOf(champ: string): string[] {
    const debut = yaml.indexOf("    Staff:\n");
    assert.notEqual(debut, -1);
    const ligne = yaml.slice(yaml.indexOf(`${champ}:`, debut), yaml.indexOf("\n", yaml.indexOf(`${champ}:`, debut)));
    return ligne
      .slice(ligne.indexOf("enum: [") + "enum: [".length, ligne.indexOf("]"))
      .split(",")
      .map((mot) => mot.trim());
  }

  it("les gabarits des chemins sont ceux du contrat", () => {
    assert.ok(yaml.includes(`\n  ${STAFF_PATHS.agency}:`));
    assert.ok(yaml.includes(`\n  ${STAFF_PATHS.hire}:`));
    assert.ok(yaml.includes(`\n  ${STAFF_PATHS.staff}:`));
  });

  it("getCandidates est un GET, hire un POST, assignStaff un PATCH, fireStaff un DELETE", () => {
    const agence = blocOf(STAFF_PATHS.agency);
    assert.match(agence, /\n {4}get:\n/);
    assert.match(agence, /operationId: getCandidates/);
    const embauche = blocOf(STAFF_PATHS.hire);
    assert.match(embauche, /\n {4}post:\n/);
    assert.match(embauche, /operationId: hire\n/);
    assert.match(embauche, /'409': \{ \$ref: '#\/components\/responses\/Refused' \}/);
    const membre = blocOf(STAFF_PATHS.staff);
    assert.match(membre, /\n {4}patch:\n/);
    assert.match(membre, /operationId: assignStaff/);
    assert.match(membre, /\n {4}delete:\n/);
    assert.match(membre, /operationId: fireStaff/);
  });

  it("le renvoi (DELETE) refuse en 403 et en 409 et porte requestId et acteur dans la requête, pas dans un corps", () => {
    const membre = blocOf(STAFF_PATHS.staff);
    const suppression = membre.slice(membre.indexOf("\n    delete:\n"));
    assert.match(suppression, /'403': \{ \$ref: '#\/components\/responses\/Refused' \}/);
    assert.match(suppression, /'409': \{ \$ref: '#\/components\/responses\/Refused' \}/);
    assert.match(suppression, /parameters\/ActorXuid/);
    assert.match(suppression, /parameters\/RequestId/);
    assert.doesNotMatch(suppression, /requestBody/);
  });

  it("l'affectation exige factoryId ; l'embauche le laisse facultatif", () => {
    const membre = blocOf(STAFF_PATHS.staff);
    const affectation = membre.slice(membre.indexOf("\n    patch:\n"), membre.indexOf("\n    delete:\n"));
    assert.match(affectation, /required: \[factoryId\]/);
    const embauche = blocOf(STAFF_PATHS.hire);
    assert.doesNotMatch(embauche, /required: \[factoryId\]/);
    assert.match(embauche, /minimum: 1, maximum: 5/);
  });

  it("les rôles et les raretés lus sont ceux du contrat, ni plus ni moins", () => {
    assert.deepEqual([...STAFF_ROLES], enumOf("role"));
    assert.deepEqual([...STAFF_RARITIES], enumOf("rarity"));
  });

  it("Staff : les clés obligatoires sont lues et exigées", () => {
    const debut = yaml.indexOf("    Staff:\n");
    const ouvre = yaml.indexOf("required: [", debut);
    const requises = yaml
      .slice(ouvre + "required: [".length, yaml.indexOf("]", ouvre))
      .split(",")
      .map((mot) => mot.trim());
    assert.deepEqual(requises, ["name", "role", "rarity", "bonusPercent", "skinId"]);
    const modele: Record<string, unknown> = { slot: 1, name: "A", skinId: "s", role: "SALARIE", rarity: "COMMUN", bonusPercent: 1 };
    for (const cle of requises) {
      const sans = { ...modele };
      delete sans[cle];
      assert.equal(parseCandidates([sans]).ok, false, `la lecture accepte un Staff sans « ${cle} »`);
    }
  });

  it("le contrat ne promet ni nouvelle liste ni tirage payant", () => {
    const agence = blocOf(STAFF_PATHS.agency);
    assert.match(agence, /ni nouvelle liste contre du Donar/);
    assert.doesNotMatch(yaml, /operationId: (rerollCandidates|refreshAgency|newCandidates)/);
  });
});
