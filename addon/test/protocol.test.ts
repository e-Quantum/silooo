import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, it } from "node:test";
import {
  acceptInvitePath,
  actionBody,
  agencyPath,
  assignStaffBody,
  BOOST_ITEM_ID,
  BUY_AMOUNTS,
  buyItemBody,
  buyLevelsBody,
  catalogPath,
  claimPassBody,
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
  inviteBody,
  ITEM_ID_MAX,
  isRestoreStep,
  isSharePercent,
  isXuid,
  joinBody,
  leaveBody,
  levelsPath,
  MEMBER_PATHS,
  MEMBER_ROLES,
  membersPath,
  MISSIONS_PATH,
  missionsPath,
  parseBalances,
  parseCandidates,
  parseCatalog,
  parseCollectResponse,
  parseCompanyState,
  parseCompanyUpdated,
  parseEventsPage,
  parseJoinResponse,
  parseMissionState,
  parsePassClaimed,
  parsePassState,
  parsePrestigePreview,
  parseVotesToday,
  PASS_PATHS,
  PASS_PREMIUM_ITEM_ID,
  PASS_TRACKS,
  passClaimKey,
  passPath,
  PATHS,
  PIONEER_COSMETIC_ID,
  PRESTIGE_NEXT,
  prestigePath,
  prestigePreviewPath,
  REFUSAL_REASONS,
  RESTORE_STEP_MAX,
  RESTORE_STEP_MIN,
  resolveIncidentPath,
  restorePath,
  restoreStepBody,
  setSharesBody,
  SHARE_MAX,
  SHARE_MIN,
  SLOT_MAX,
  SHOP_PATHS,
  SLOT_MIN,
  STAFF_PATHS,
  STAFF_RARITIES,
  STAFF_ROLES,
  staffPath,
  VOTES_PATH,
  votesTodayPath,
} from "../src/protocol";
import { balances, companyState, factory, joinResponse, passClaimed, passState, shopCatalog, votesToday } from "./helpers";

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
            nextPaybackHours: 1.5,
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
    assert.equal(parsed.value.state.factories[0]?.nextPaybackHours, 1.5);
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

describe("l'entreprise partagée : chemins, corps et lecture des membres", () => {
  const action = { requestId: "0197f2a0-0000-7000-8000-000000000001", actor: "9007199254740993123" };
  const membres = [
    { xuid: "2535428000000001", name: "Kr", role: "PDG", sharePercent: 60 },
    { xuid: "2535428000000002", name: "Ada", role: "ASSOCIE", sharePercent: 40 },
  ];

  it("les chemins : invite et setShares partagent /members, acceptInvite vise l'entreprise qui invite", () => {
    assert.equal(membersPath(7), "/v1/companies/7/members");
    assert.equal(acceptInvitePath(12), "/v1/companies/12/members/accept");
    assert.equal(MEMBER_PATHS.members, "/v1/companies/{companyId}/members");
    assert.equal(MEMBER_PATHS.accept, "/v1/companies/{companyId}/members/accept");
  });

  it("une entreprise illisible lève une erreur au lieu de fabriquer un chemin", () => {
    for (const id of [-1, 1.5, Number.NaN, Number.POSITIVE_INFINITY]) {
      assert.throws(() => membersPath(id), RangeError, `entreprise ${id}`);
      assert.throws(() => acceptInvitePath(id), RangeError, `entreprise ${id}`);
    }
  });

  it("inviteBody : l'action et le joueur invité, rien d'autre", () => {
    assert.deepEqual(inviteBody(action, "2535428000000002"), { ...action, targetXuid: "2535428000000002" });
  });

  it("setSharesBody : l'action et une part par membre, dans l'ordre donné, sans rien additionner", () => {
    const shares = [
      { xuid: "2535428000000001", percent: 70 },
      { xuid: "2535428000000002", percent: 20 },
    ];
    const body = setSharesBody(action, shares);
    assert.deepEqual(body, { ...action, shares });
    assert.notEqual(body.shares, shares, "le corps copie la liste");
    assert.deepEqual(Object.keys(body).sort(), ["actor", "requestId", "shares"]);
  });

  it("isSharePercent : un entier de 0 à 100, la somme n'est pas son affaire", () => {
    assert.equal(SHARE_MIN, 0);
    assert.equal(SHARE_MAX, 100);
    for (const bon of [0, 1, 50, 99, 100]) assert.equal(isSharePercent(bon), true, `${bon}`);
    for (const mauvais of [-1, 101, 12.5, Number.NaN, Number.POSITIVE_INFINITY]) assert.equal(isSharePercent(mauvais), false, `${mauvais}`);
  });

  it("isXuid : un entier décimal de 1 à 20 chiffres, écrit en chaîne", () => {
    assert.equal(isXuid("2535428000000002"), true);
    assert.equal(isXuid("42"), true);
    for (const mauvais of ["", "abc", "42&x=1", "-4", "4.2", "123456789012345678901"]) assert.equal(isXuid(mauvais), false, `« ${mauvais} »`);
  });

  it("parseCompanyState lit les membres tels que le service les donne, dans son ordre", () => {
    const parsed = parseCompanyState(companyState({ members: membres as never }));
    assert.equal(parsed.ok, true);
    if (!parsed.ok) return;
    assert.deepEqual(parsed.value.members, membres);
    assert.deepEqual(MEMBER_ROLES, ["PDG", "ASSOCIE"]);
  });

  it("members est facultatif : absent, la clé reste absente", () => {
    const parsed = parseCompanyState(companyState());
    assert.equal(parsed.ok, true);
    if (parsed.ok) assert.equal("members" in parsed.value, false);
  });

  it("un membre mal formé rend toute la réponse illisible, avec le chemin du champ", () => {
    const abimes: Array<[string, Record<string, unknown>]> = [
      ["role", { ...membres[0], role: "CHEF" }],
      ["xuid", { ...membres[0], xuid: "pas-un-xuid" }],
      ["sharePercent", { ...membres[0], sharePercent: 101 }],
      ["sharePercent", { ...membres[0], sharePercent: -1 }],
      ["sharePercent", { ...membres[0], sharePercent: 12.5 }],
      ["name", { ...membres[0], name: 7 }],
    ];
    for (const [champ, membre] of abimes) {
      const parsed = parseCompanyState({ ...companyState(), members: [membre] });
      assert.equal(parsed.ok, false, `membre abîmé : ${champ}`);
      if (!parsed.ok) assert.ok(parsed.problem.includes(`members[0].${champ}`), parsed.problem);
    }
    assert.equal(parseCompanyState({ ...companyState(), members: "Kr" }).ok, false);
  });

  it("parseCompanyUpdated lit aussi les membres de la réponse", () => {
    const parsed = parseCompanyUpdated({ state: companyState({ members: membres as never }), balances: balances() });
    assert.equal(parsed.ok, true);
    if (parsed.ok) assert.deepEqual(parsed.value.state.members, membres);
  });
});

/** Le contrat est dans l'OpenAPI : si un chemin, un verbe ou une énumération des membres dérive, ces tests échouent. */
describe("accord avec api/silooo-core.openapi.yaml, entreprise partagée", () => {
  const yaml = readFileSync(resolve("..", "api", "silooo-core.openapi.yaml"), "utf8");

  function blocOf(chemin: string): string {
    const debut = yaml.indexOf(`\n  ${chemin}:`);
    assert.notEqual(debut, -1, `${chemin} absent de l'OpenAPI`);
    const fin = yaml.indexOf("\n  /v1/", debut + 1);
    return yaml.slice(debut, fin === -1 ? undefined : fin);
  }

  it("les gabarits des chemins sont ceux du contrat", () => {
    assert.ok(yaml.includes(`\n  ${MEMBER_PATHS.members}:`));
    assert.ok(yaml.includes(`\n  ${MEMBER_PATHS.accept}:`));
  });

  it("invite est un POST, setShares un PUT, sur le même chemin ; acceptInvite un POST", () => {
    const membres = blocOf(MEMBER_PATHS.members);
    assert.match(membres, /\n {4}post:\n/);
    assert.match(membres, /operationId: invite\n/);
    assert.match(membres, /\n {4}put:\n/);
    assert.match(membres, /operationId: setShares\n/);
    const accepter = blocOf(MEMBER_PATHS.accept);
    assert.match(accepter, /\n {4}post:\n/);
    assert.match(accepter, /operationId: acceptInvite\n/);
  });

  it("invite exige targetXuid, setShares exige shares, avec un percent de 0 à 100", () => {
    const membres = blocOf(MEMBER_PATHS.members);
    assert.match(membres, /required: \[targetXuid\]/);
    assert.match(membres, /required: \[shares\]/);
    assert.match(membres, /required: \[xuid, percent\]/);
    assert.match(membres, new RegExp(`minimum: ${SHARE_MIN}, maximum: ${SHARE_MAX}`));
  });

  it("les rôles lus sont ceux du contrat, ni plus ni moins", () => {
    const debut = yaml.indexOf("    Member:\n");
    assert.notEqual(debut, -1);
    const ligne = yaml.slice(yaml.indexOf("role:", debut), yaml.indexOf("\n", yaml.indexOf("role:", debut)));
    const valeurs = ligne.slice(ligne.indexOf("enum: [") + "enum: [".length, ligne.indexOf("]")).split(",").map((mot) => mot.trim());
    assert.deepEqual([...MEMBER_ROLES], valeurs);
  });

  it("Member : les clés obligatoires sont lues et exigées", () => {
    const debut = yaml.indexOf("    Member:\n");
    const ouvre = yaml.indexOf("required: [", debut);
    const requises = yaml
      .slice(ouvre + "required: [".length, yaml.indexOf("]", ouvre))
      .split(",")
      .map((mot) => mot.trim());
    assert.deepEqual(requises, ["xuid", "name", "role", "sharePercent"]);
    const modele: Record<string, unknown> = { xuid: "2535428000000001", name: "Kr", role: "PDG", sharePercent: 100 };
    for (const cle of requises) {
      const sans = { ...modele };
      delete sans[cle];
      assert.equal(parseCompanyState({ ...companyState(), members: [sans] }).ok, false, `la lecture accepte un Member sans « ${cle} »`);
    }
  });

  it("le contrat n'a ni route pour quitter une entreprise ni route pour lister les invitations", () => {
    assert.doesNotMatch(yaml, /operationId: (leaveCompany|removeMember|kickMember|listInvitations|declineInvite)/);
  });
});

// ---------------------------------------------------------------- la boutique, le pass, les votes

describe("la boutique, le pass, les votes : chemins et corps", () => {
  const action = { requestId: "0197f2a0-0000-7000-8000-000000000001", actor: "42" };

  it("les chemins sont ceux du contrat, l'acteur ou le joueur dans la requête", () => {
    assert.equal(catalogPath("42"), "/v1/shop/catalog?actor=42");
    assert.equal(passPath("42"), "/v1/pass?actor=42");
    assert.equal(votesTodayPath("42"), "/v1/votes/today?xuid=42");
    assert.equal(votesTodayPath(), "/v1/votes/today");
    assert.equal(SHOP_PATHS.purchases, "/v1/shop/purchases");
    assert.equal(PASS_PATHS.claims, "/v1/pass/claims");
    assert.equal(VOTES_PATH, "/v1/votes/today");
  });

  it("un acteur qui n'est pas un XUID lève une erreur : rien ne part au service", () => {
    for (const mauvais of ["", "Alex", "4 2", "42?x=1", "1".repeat(21)]) {
      assert.throws(() => catalogPath(mauvais), RangeError);
      assert.throws(() => passPath(mauvais), RangeError);
      assert.throws(() => votesTodayPath(mauvais), RangeError);
    }
  });

  it("buyItemBody : sans cadeau la clé forXuid n'existe pas, avec un cadeau elle porte le destinataire", () => {
    assert.deepEqual(buyItemBody(action, "mvip"), { ...action, itemId: "mvip" });
    assert.equal("forXuid" in buyItemBody(action, "mvip"), false);
    assert.deepEqual(buyItemBody(action, "mvip", "2535428000000003"), { ...action, itemId: "mvip", forXuid: "2535428000000003" });
  });

  it("buyItemBody : un article vide ou de plus de 48 caractères, un destinataire illisible lèvent une erreur", () => {
    assert.equal(ITEM_ID_MAX, 48);
    assert.doesNotThrow(() => buyItemBody(action, "x".repeat(ITEM_ID_MAX)));
    assert.throws(() => buyItemBody(action, ""), RangeError);
    assert.throws(() => buyItemBody(action, "x".repeat(ITEM_ID_MAX + 1)), RangeError);
    assert.throws(() => buyItemBody(action, "mvip", "Bea"), RangeError);
    assert.throws(() => buyItemBody(action, "mvip", ""), RangeError);
  });

  it("claimPassBody : le palier est un entier de 1 ou plus, la piste est l'une des deux du contrat", () => {
    assert.deepEqual([...PASS_TRACKS], ["GRATUIT", "PREMIUM"]);
    assert.deepEqual(claimPassBody(action, 3, "PREMIUM"), { ...action, tier: 3, track: "PREMIUM" });
    for (const palier of [0, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY, 2 ** 60]) {
      assert.throws(() => claimPassBody(action, palier, "GRATUIT"), RangeError);
    }
    assert.throws(() => claimPassBody(action, 1, "OR" as unknown as "GRATUIT"), RangeError);
  });

  it("la clé d'un palier réclamé est « palier-PISTE », comme le service la rend", () => {
    assert.equal(passClaimKey(3, "GRATUIT"), "3-GRATUIT");
    assert.equal(passClaimKey(12, "PREMIUM"), "12-PREMIUM");
  });

  it("les identifiants que le contrat nomme sont exportés", () => {
    assert.equal(BOOST_ITEM_ID, "boost-collectif");
    assert.equal(PASS_PREMIUM_ITEM_ID, "pass-premium");
    assert.equal(PIONEER_COSMETIC_ID, "pionnier");
  });
});

describe("la boutique, le pass, les votes : lecture des réponses", () => {
  it("parseBalances lit les soldes de l'acheteur", () => {
    const lus = parseBalances(balances({ lingots: 5, gemmes: 90 }));
    assert.ok(lus.ok);
    assert.equal(lus.value.gemmes, 90);
    assert.equal(parseBalances({ lingots: 1 }).ok, false);
    assert.equal(parseBalances(null).ok, false);
  });

  it("parseCatalog lit les articles dans l'ordre du service", () => {
    const catalogue = shopCatalog({ mvip: { owned: true } });
    const lu = parseCatalog(catalogue);
    assert.ok(lu.ok);
    assert.deepEqual(lu.value, catalogue);
    assert.deepEqual(parseCatalog([]), { ok: true, value: [] });
  });

  it("parseCatalog refuse un article mal formé et nomme la clé", () => {
    const base = { itemId: "mvip", gemmes: 100, owned: false, available: true };
    for (const [cle, valeur, attendu] of [
      ["itemId", 7, /itemId/],
      ["itemId", "", /itemId/],
      ["gemmes", -1, /gemmes/],
      ["gemmes", 1.5, /gemmes/],
      ["owned", "oui", /owned/],
      ["available", undefined, /available/],
    ] as const) {
      const lu = parseCatalog([{ ...base, [cle]: valeur }]);
      assert.equal(lu.ok, false, `${cle}=${String(valeur)}`);
      if (!lu.ok) assert.match(lu.problem, attendu);
    }
    assert.equal(parseCatalog({ itemId: "mvip" }).ok, false);
    assert.equal(parseCatalog("catalogue").ok, false);
  });

  it("parsePassState lit la saison, les points et les paliers, les récompenses vides restent nulles", () => {
    const pass = passState({ premium: true });
    const lu = parsePassState(pass);
    assert.ok(lu.ok);
    assert.deepEqual(lu.value, pass);
    assert.equal(lu.value.tiers[1]?.premium, null);
    const sansRecompense = parsePassState({ ...pass, tiers: [{ tier: 1, points: 40 }] });
    assert.ok(sansRecompense.ok);
    assert.deepEqual(sansRecompense.value.tiers, [{ tier: 1, points: 40, free: null, premium: null }]);
  });

  it("parsePassState refuse un pass mal formé et nomme la clé", () => {
    const pass = passState();
    for (const [cle, valeur, attendu] of [
      ["seasonId", 3, /seasonId/],
      ["name", undefined, /name/],
      ["startsOn", null, /startsOn/],
      ["endsOn", 20270131, /endsOn/],
      ["active", "oui", /active/],
      ["points", -5, /points/],
      ["premium", 1, /premium/],
      ["claimed", "1-GRATUIT", /claimed/],
      ["claimed", [1], /claimed/],
      ["tiers", {}, /tiers/],
    ] as const) {
      const lu = parsePassState({ ...pass, [cle]: valeur });
      assert.equal(lu.ok, false, `${cle}=${JSON.stringify(valeur)}`);
      if (!lu.ok) assert.match(lu.problem, attendu);
    }
    const mauvaisPalier = parsePassState({ ...pass, tiers: [{ tier: 0, points: 40, free: null, premium: null }] });
    assert.equal(mauvaisPalier.ok, false);
    const mauvaiseRecompense = parsePassState({ ...pass, tiers: [{ tier: 1, points: 40, free: { lingots: 0 }, premium: null }] });
    assert.equal(mauvaiseRecompense.ok, false);
    const mauvaisCosmetique = parsePassState({ ...pass, tiers: [{ tier: 1, points: 40, free: null, premium: { cosmeticId: 4 } }] });
    assert.equal(mauvaisCosmetique.ok, false);
  });

  it("parsePassClaimed lit les Lingots de la piste gratuite et le cosmétique de la piste premium", () => {
    const gratuit = parsePassClaimed(passClaimed());
    assert.ok(gratuit.ok);
    assert.equal(gratuit.value.cosmeticId, null);
    assert.equal(gratuit.value.rewardLingots, 1);
    const premium = parsePassClaimed(passClaimed({ track: "PREMIUM", rewardLingots: 0, cosmeticId: "s1-lanterne" }));
    assert.ok(premium.ok);
    assert.equal(premium.value.cosmeticId, "s1-lanterne");
    // Un service qui oublie la clé la rend nulle : la lecture ne l'invente pas autrement.
    const { cosmeticId: _oublie, ...sans } = passClaimed();
    const lu = parsePassClaimed(sans);
    assert.ok(lu.ok);
    assert.equal(lu.value.cosmeticId, null);
  });

  it("parsePassClaimed refuse une piste inconnue, un cosmétique qui n'est pas du texte, des soldes absents", () => {
    assert.equal(parsePassClaimed({ ...passClaimed(), track: "OR" }).ok, false);
    assert.equal(parsePassClaimed({ ...passClaimed(), cosmeticId: 5 }).ok, false);
    assert.equal(parsePassClaimed({ ...passClaimed(), tier: 0 }).ok, false);
    const { balances: _perdus, ...sans } = passClaimed();
    const lu = parsePassClaimed(sans);
    assert.equal(lu.ok, false);
    if (!lu.ok) assert.match(lu.problem, /balances/);
  });

  it("parseVotesToday lit le jour, l'objectif, les chiffres de vote.json et la part du joueur", () => {
    const votes = votesToday({ mine: { votes: 1, streak: 3 } });
    const lu = parseVotesToday(votes);
    assert.ok(lu.ok);
    assert.deepEqual(lu.value, votes);
  });

  it("parseVotesToday refuse un corps mal formé et nomme la clé", () => {
    const votes = votesToday();
    for (const [cle, valeur, attendu] of [
      ["day", 5, /day/],
      ["votes", -1, /votes/],
      ["goal", 0, /goal/],
      ["partyReached", "non", /partyReached/],
      ["rewardLingots", 1.5, /rewardLingots/],
      ["streakGoalDays", 1, /streakGoalDays/],
      ["mine", undefined, /mine/],
    ] as const) {
      const lu = parseVotesToday({ ...votes, [cle]: valeur });
      assert.equal(lu.ok, false, `${cle}=${String(valeur)}`);
      if (!lu.ok) assert.match(lu.problem, attendu);
    }
    const sansSerie = parseVotesToday({ ...votes, mine: { votes: 0 } });
    assert.equal(sansSerie.ok, false);
    if (!sansSerie.ok) assert.match(sansSerie.problem, /streak/);
  });

  it("parseJoinResponse lit les cosmétiques du joueur, et rien quand le service n'en donne pas", () => {
    const avec = parseJoinResponse({ ...joinResponse(), cosmetics: ["pionnier", "s1-fanion"] });
    assert.ok(avec.ok);
    assert.deepEqual(avec.value.cosmetics, ["pionnier", "s1-fanion"]);
    const sans = parseJoinResponse(joinResponse());
    assert.ok(sans.ok);
    assert.equal("cosmetics" in sans.value, false);
    const mauvais = parseJoinResponse({ ...joinResponse(), cosmetics: ["pionnier", 7] });
    assert.equal(mauvais.ok, false);
    if (!mauvais.ok) assert.match(mauvais.problem, /cosmetics/);
  });
});

describe("accord avec api/silooo-core.openapi.yaml, boutique, pass et votes", () => {
  const yaml = readFileSync(resolve("..", "api", "silooo-core.openapi.yaml"), "utf8");

  function blocOf(chemin: string): string {
    const debut = yaml.indexOf(`\n  ${chemin}:`);
    assert.notEqual(debut, -1, `${chemin} absent de l'OpenAPI`);
    const fin = yaml.indexOf("\n  /v1/", debut + 1);
    return yaml.slice(debut, fin === -1 ? undefined : fin);
  }

  function schemaOf(nom: string): string {
    const debut = yaml.indexOf(`\n    ${nom}:\n`);
    assert.notEqual(debut, -1, `${nom} absent de l'OpenAPI`);
    const suite = yaml.slice(debut + 1).search(/\n {4}[A-Za-z]+:\n/);
    return yaml.slice(debut, suite === -1 ? undefined : debut + 1 + suite);
  }

  function requisesDe(bloc: string): string[] {
    const ouvre = bloc.indexOf("required: [");
    assert.notEqual(ouvre, -1);
    return bloc
      .slice(ouvre + "required: [".length, bloc.indexOf("]", ouvre))
      .split(",")
      .map((mot) => mot.trim());
  }

  it("les chemins et les méthodes sont ceux du contrat", () => {
    const catalogue = blocOf(SHOP_PATHS.catalog);
    assert.match(catalogue, /\n {4}get:\n/);
    assert.match(catalogue, /operationId: catalog\n/);
    const achats = blocOf(SHOP_PATHS.purchases);
    assert.match(achats, /\n {4}post:\n/);
    assert.match(achats, /operationId: buyItem\n/);
    const pass = blocOf(PASS_PATHS.state);
    assert.match(pass, /\n {4}get:\n/);
    assert.match(pass, /operationId: getPass\n/);
    const reclamations = blocOf(PASS_PATHS.claims);
    assert.match(reclamations, /\n {4}post:\n/);
    assert.match(reclamations, /operationId: claimPass\n/);
    const votes = blocOf(VOTES_PATH);
    assert.match(votes, /\n {4}get:\n/);
    assert.match(votes, /operationId: todayVotes\n/);
  });

  it("catalog et getPass demandent l'acteur, todayVotes un xuid facultatif", () => {
    assert.match(blocOf(SHOP_PATHS.catalog), /parameters: \[ \{ \$ref: '#\/components\/parameters\/ActorXuid' \} \]/);
    assert.match(blocOf(PASS_PATHS.state), /parameters: \[ \{ \$ref: '#\/components\/parameters\/ActorXuid' \} \]/);
    assert.match(blocOf(VOTES_PATH), /parameters: \[ \{ \$ref: '#\/components\/parameters\/PlayerXuidQuery' \} \]/);
    assert.match(yaml, /PlayerXuidQuery: \{ name: xuid, in: query, required: false/);
  });

  it("le catalogue rend itemId, gemmes, owned et available, tous obligatoires", () => {
    assert.deepEqual(requisesDe(blocOf(SHOP_PATHS.catalog)), ["itemId", "gemmes", "owned", "available"]);
    const modele: Record<string, unknown> = { itemId: "mvip", gemmes: 100, owned: false, available: true };
    for (const cle of Object.keys(modele)) {
      const sans = { ...modele };
      delete sans[cle];
      assert.equal(parseCatalog([sans]).ok, false, `la lecture accepte un article sans « ${cle} »`);
    }
  });

  it("buyItem exige itemId (1 à 48 caractères) et accepte forXuid ; les identifiants spéciaux sont ceux du contrat", () => {
    const achats = blocOf(SHOP_PATHS.purchases);
    assert.match(achats, /required: \[itemId\]/);
    assert.match(achats, new RegExp(`minLength: 1, maxLength: ${ITEM_ID_MAX}`));
    assert.match(achats, /forXuid: \{ \$ref: '#\/components\/schemas\/Xuid' \}/);
    assert.ok(yaml.includes(`\`${BOOST_ITEM_ID}\``), "boost-collectif n'est plus nommé par le contrat");
    assert.ok(yaml.includes(`\`${PASS_PREMIUM_ITEM_ID}\``), "pass-premium n'est plus nommé par le contrat");
    assert.ok(yaml.includes(`\`${PIONEER_COSMETIC_ID}\``), "pionnier n'est plus nommé par le contrat");
  });

  it("claimPass exige tier (1 ou plus) et track, avec les deux pistes de l'add-on", () => {
    const reclamations = blocOf(PASS_PATHS.claims);
    assert.match(reclamations, /required: \[tier, track\]/);
    assert.match(reclamations, /tier: \{ type: integer, minimum: 1 \}/);
    const piste = reclamations.match(/track: \{ type: string, enum: \[([^\]]*)\] \}/);
    assert.ok(piste, "enum de track absent");
    assert.deepEqual([...PASS_TRACKS], piste[1]?.split(",").map((mot) => mot.trim()));
  });

  it("PassState, PassTier et PassClaimed : les clés obligatoires sont lues et exigées", () => {
    const pass = passState();
    const requisesPass = requisesDe(schemaOf("PassState"));
    assert.deepEqual(requisesPass, ["seasonId", "name", "startsOn", "endsOn", "active", "points", "premium", "claimed", "tiers"]);
    for (const cle of requisesPass) {
      const sans: Record<string, unknown> = { ...pass };
      delete sans[cle];
      assert.equal(parsePassState(sans).ok, false, `la lecture accepte un PassState sans « ${cle} »`);
    }
    const requisesPalier = requisesDe(schemaOf("PassTier"));
    assert.deepEqual(requisesPalier, ["tier", "points", "free", "premium"]);
    for (const cle of ["tier", "points"]) {
      const palier: Record<string, unknown> = { ...pass.tiers[0] };
      delete palier[cle];
      assert.equal(parsePassState({ ...pass, tiers: [palier] }).ok, false, `la lecture accepte un PassTier sans « ${cle} »`);
    }
    const requisesReclame = requisesDe(schemaOf("PassClaimed"));
    assert.deepEqual(requisesReclame, ["seasonId", "tier", "track", "rewardLingots", "cosmeticId", "balances"]);
    for (const cle of ["seasonId", "tier", "track", "rewardLingots", "balances"]) {
      const sans: Record<string, unknown> = { ...passClaimed() };
      delete sans[cle];
      assert.equal(parsePassClaimed(sans).ok, false, `la lecture accepte un PassClaimed sans « ${cle} »`);
    }
  });

  it("VotesToday et PlayerVotes : les clés obligatoires sont lues et exigées", () => {
    const votes = votesToday();
    const requises = requisesDe(schemaOf("VotesToday"));
    assert.deepEqual(requises, [
      "day",
      "votes",
      "goal",
      "partyReached",
      "rewardLingots",
      "partyRewardLingots",
      "streakGoalDays",
      "streakBonusLingots",
      "mine",
    ]);
    for (const cle of requises) {
      const sans: Record<string, unknown> = { ...votes };
      delete sans[cle];
      assert.equal(parseVotesToday(sans).ok, false, `la lecture accepte un VotesToday sans « ${cle} »`);
    }
    assert.deepEqual(requisesDe(schemaOf("PlayerVotes")), ["votes", "streak"]);
  });

  it("les refus de la boutique et du pass sont dans le contrat comme dans la liste de l'add-on", () => {
    for (const code of [
      "DEJA_POSSEDE",
      "GRADE_PRECEDENT_REQUIS",
      "PAS_ASSEZ_DE_GEMMES",
      "SAISON_INACTIVE",
      "PALIER_NON_ATTEINT",
      "PREMIUM_REQUIS",
      "DEJA_RECLAME",
    ]) {
      assert.ok(yaml.includes(code), `${code} absent de l'OpenAPI`);
      assert.ok((REFUSAL_REASONS as readonly string[]).includes(code), `${code} absent de REFUSAL_REASONS`);
    }
  });

  it("le contrat décrit le payload de BOOST_COLLECTIF avec percent et minutes, sans xuid", () => {
    assert.match(yaml, /`BOOST_COLLECTIF` \(sans `xuid` ; `percent`, `minutes`,/);
    assert.ok((GAME_EVENT_KINDS as readonly string[]).includes("BOOST_COLLECTIF"));
    assert.ok((GAME_EVENT_KINDS as readonly string[]).includes("GEMMES_RECUES"));
  });
});
