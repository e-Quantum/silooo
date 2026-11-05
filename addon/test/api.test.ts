import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createCoreApi } from "../src/api";
import { createCoreClient } from "../src/http";
import { balances, companyState, factory, json, passClaimed, passState, scriptedTransport, shopCatalog, votesToday } from "./helpers";

const action = { requestId: "0197f2a0-0000-7000-8000-000000000001", actor: "42" };

function setup(script: Parameters<typeof scriptedTransport>[0]) {
  const transport = scriptedTransport(script);
  const api = createCoreApi(
    createCoreClient({ transport, coreUrl: "http://core:8080/", apiKey: "cle-de-test", timeoutSeconds: 5, readRetries: 1 }),
  );
  return { transport, api };
}

function bodyOf(request: { body: string | undefined }): unknown {
  return JSON.parse(request.body ?? "null");
}

describe("getCompany", () => {
  it("lit l'état de l'entreprise par un GET", async () => {
    const { transport, api } = setup([json(200, companyState({ donar: 5, factories: [factory()] }))]);
    const result = await api.getCompany(7);
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") assert.equal(result.body.factories[0]?.factoryId, "carriere");
    assert.equal(transport.requests[0]?.method, "GET");
    assert.equal(transport.requests[0]?.url, "http://core:8080/v1/companies/7");
  });

  it("rejoue une fois une lecture qui tombe en panne", async () => {
    const { transport, api } = setup([json(500, { reason: "ERREUR_INTERNE" }), json(200, companyState())]);
    const result = await api.getCompany(7);
    assert.equal(result.kind, "ok");
    assert.equal(transport.requests.length, 2);
  });

  it("une réponse hors contrat devient une erreur lisible", async () => {
    const { api } = setup([json(200, { companyId: 7 })]);
    const result = await api.getCompany(7);
    assert.equal(result.kind, "error");
    if (result.kind === "error") {
      assert.equal(result.reason, "REPONSE_INATTENDUE");
      assert.match(result.detail ?? "", /company\.name/);
    }
  });

  it("un identifiant d'entreprise illisible n'envoie aucune requête", async () => {
    const { transport, api } = setup([json(200, companyState())]);
    const result = await api.getCompany(Number.NaN);
    assert.equal(result.kind, "error");
    if (result.kind === "error") {
      assert.equal(result.reason, "REQUETE_INVALIDE");
      assert.equal(result.status, 0);
    }
    assert.equal(transport.requests.length, 0);
  });
});

describe("collect", () => {
  it("poste l'action et lit ce qui a été encaissé", async () => {
    const { transport, api } = setup([json(200, { collected: 240000, offlineHours: 8, state: companyState() })]);
    const result = await api.collect(7, action);
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") {
      assert.equal(result.body.collected, 240000);
      assert.equal(result.body.offlineHours, 8);
    }
    const request = transport.requests[0];
    assert.equal(request?.method, "POST");
    assert.equal(request?.url, "http://core:8080/v1/companies/7/collect");
    assert.deepEqual(bodyOf(request ?? { body: undefined }), action);
  });

  it("un refus 409 garde son code", async () => {
    const { api } = setup([json(409, { reason: "CONFLIT_REESSAYER" })]);
    const result = await api.collect(7, action);
    assert.deepEqual(result, { kind: "refused", status: 409, reason: "CONFLIT_REESSAYER" });
  });

  it("une écriture n'est jamais rejouée toute seule", async () => {
    const { transport, api } = setup([json(500, { reason: "ERREUR_INTERNE" }), json(200, {})]);
    const result = await api.collect(7, action);
    assert.equal(result.kind, "error");
    assert.equal(transport.requests.length, 1);
  });
});

describe("buyLevels", () => {
  it("poste la quantité avec requestId et actor, à l'adresse de l'usine", async () => {
    const { transport, api } = setup([json(200, { state: companyState(), balances: balances({ lingots: 2 }) })]);
    const result = await api.buyLevels(7, "mine-profonde", action, "palier");
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") assert.equal(result.body.balances.lingots, 2);
    const request = transport.requests[0];
    assert.equal(request?.url, "http://core:8080/v1/companies/7/factories/mine-profonde/levels");
    assert.deepEqual(bodyOf(request ?? { body: undefined }), { ...action, amount: "palier" });
  });

  it("rend le refus et ce qui manque", async () => {
    const { api } = setup([json(409, { reason: "PAS_ASSEZ_DE_DONAR", missing: 240000 })]);
    const result = await api.buyLevels(7, "scierie", action, "1");
    assert.deepEqual(result, { kind: "refused", status: 409, reason: "PAS_ASSEZ_DE_DONAR", missing: 240000 });
  });

  it("un réseau coupé devient un résultat network", async () => {
    const { api } = setup([new Error("connexion refusée")]);
    const result = await api.buyLevels(7, "scierie", action, "max");
    assert.equal(result.kind, "network");
  });
});

describe("restoreStep", () => {
  it("poste l'étape à l'adresse de remise en état", async () => {
    const { transport, api } = setup([json(200, { state: companyState(), balances: balances() })]);
    const result = await api.restoreStep(7, "fonderie", action, 3);
    assert.equal(result.kind, "ok");
    const request = transport.requests[0];
    assert.equal(request?.url, "http://core:8080/v1/companies/7/factories/fonderie/restore");
    assert.deepEqual(bodyOf(request ?? { body: undefined }), { ...action, step: 3 });
  });

  it("une étape hors de 1 à 4 n'envoie aucune requête", async () => {
    const { transport, api } = setup([json(200, { state: companyState(), balances: balances() })]);
    for (const step of [0, 5, 2.5]) {
      const result = await api.restoreStep(7, "fonderie", action, step);
      assert.equal(result.kind, "error");
    }
    assert.equal(transport.requests.length, 0);
  });

  it("une route pas encore écrite (501) reste une erreur du service", async () => {
    const { api } = setup([json(501, { reason: "PAS_FAIT" })]);
    const result = await api.restoreStep(7, "fonderie", action, 1);
    assert.deepEqual(result, { kind: "error", status: 501, reason: "PAS_FAIT" });
  });
});

describe("resolveIncident", () => {
  it("poste l'action à l'adresse de l'incident, sans autre champ", async () => {
    const { transport, api } = setup([json(200, { state: companyState(), balances: balances() })]);
    const result = await api.resolveIncident(7, 31, action);
    assert.equal(result.kind, "ok");
    const request = transport.requests[0];
    assert.equal(request?.method, "POST");
    assert.equal(request?.url, "http://core:8080/v1/companies/7/incidents/31/resolve");
    assert.deepEqual(bodyOf(request ?? { body: undefined }), action);
  });

  it("rend l'état de l'entreprise tel que le service le dit, sans incident", async () => {
    const state = companyState({ version: 9, factories: [factory({ incidentId: null, incidentKind: null })] });
    const { api } = setup([json(200, { state, balances: balances() })]);
    const result = await api.resolveIncident(7, 31, action);
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") {
      assert.equal(result.body.state.version, 9);
      assert.equal(result.body.state.factories[0]?.incidentKind, null);
    }
  });

  it("un refus 409 garde son code", async () => {
    const { api } = setup([json(409, { reason: "CONFLIT_REESSAYER" })]);
    assert.deepEqual(await api.resolveIncident(7, 31, action), { kind: "refused", status: 409, reason: "CONFLIT_REESSAYER" });
  });

  it("un incident inconnu (400) est une erreur du service, jamais rejouée", async () => {
    const { transport, api } = setup([json(400, { reason: "INCIDENT_INTROUVABLE" }), json(200, { state: companyState(), balances: balances() })]);
    assert.deepEqual(await api.resolveIncident(7, 99, action), { kind: "error", status: 400, reason: "INCIDENT_INTROUVABLE" });
    assert.equal(transport.requests.length, 1);
  });

  it("un identifiant d'incident illisible n'envoie aucune requête", async () => {
    const { transport, api } = setup([json(200, { state: companyState(), balances: balances() })]);
    for (const incidentId of [-1, 1.5, Number.NaN, Number.POSITIVE_INFINITY, 2 ** 60]) {
      const result = await api.resolveIncident(7, incidentId, action);
      assert.equal(result.kind, "error");
    }
    assert.equal(transport.requests.length, 0);
  });

  it("un réseau coupé devient un résultat network, sans rejeu (c'est une écriture)", async () => {
    const { transport, api } = setup([new Error("connexion refusée")]);
    const result = await api.resolveIncident(7, 31, action);
    assert.equal(result.kind, "network");
    assert.equal(transport.requests.length, 1);
  });
});

describe("getMissions", () => {
  const missions = { current: { id: "bienvenue-mairie", progress: 0, target: 1, rewardLingots: 10 }, completed: [] };

  it("lit les missions du joueur par un GET sur son adresse", async () => {
    const { transport, api } = setup([json(200, missions)]);
    const result = await api.getMissions("42");
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") assert.deepEqual(result.body, missions);
    assert.equal(transport.requests[0]?.method, "GET");
    assert.equal(transport.requests[0]?.url, "http://core:8080/v1/players/42/missions");
    assert.equal(transport.requests[0]?.body, undefined);
  });

  it("toutes les missions terminées : current vaut null", async () => {
    const { api } = setup([json(200, { current: null, completed: ["bienvenue-mairie"] })]);
    const result = await api.getMissions("42");
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") assert.deepEqual(result.body, { current: null, completed: ["bienvenue-mairie"] });
  });

  it("rejoue une fois une lecture qui tombe en panne", async () => {
    const { transport, api } = setup([json(500, { reason: "ERREUR_INTERNE" }), json(200, missions)]);
    const result = await api.getMissions("42");
    assert.equal(result.kind, "ok");
    assert.equal(transport.requests.length, 2);
  });

  it("une réponse hors contrat devient une erreur lisible qui nomme la clé", async () => {
    const { api } = setup([json(200, { completed: [] })]);
    const result = await api.getMissions("42");
    assert.equal(result.kind, "error");
    if (result.kind === "error") {
      assert.equal(result.reason, "REPONSE_INATTENDUE");
      assert.match(result.detail ?? "", /missions\.current/);
    }
  });

  it("un joueur inconnu du service (400) est une erreur du service, pas une panne", async () => {
    const { api } = setup([json(400, { reason: "JOUEUR_INCONNU" })]);
    const result = await api.getMissions("42");
    assert.deepEqual(result, { kind: "error", status: 400, reason: "JOUEUR_INCONNU" });
  });

  it("une route pas encore écrite (501) reste une erreur du service", async () => {
    const { api } = setup([json(501, { reason: "PAS_FAIT" })]);
    const result = await api.getMissions("42");
    assert.deepEqual(result, { kind: "error", status: 501, reason: "PAS_FAIT" });
  });

  it("un XUID illisible n'envoie aucune requête", async () => {
    const { transport, api } = setup([json(200, missions)]);
    const result = await api.getMissions("pas-un-xuid");
    assert.equal(result.kind, "error");
    if (result.kind === "error") {
      assert.equal(result.reason, "REQUETE_INVALIDE");
      assert.equal(result.status, 0);
    }
    assert.equal(transport.requests.length, 0);
  });

  it("le service injoignable : kind network", async () => {
    const { api } = setup([new Error("connexion refusée")]);
    const result = await api.getMissions("42");
    assert.equal(result.kind, "network");
  });
});

describe("prestigePreview", () => {
  const apercu = { allowed: true, lingotsTotal: 1_200, lingotsIfDoubled: 1_700, shares: [{ xuid: "42", lingots: 1_200 }], next: "EPOQUE_2" };

  it("lit l'aperçu par un GET, sans corps", async () => {
    const { transport, api } = setup([json(200, apercu)]);
    const result = await api.prestigePreview(7);
    assert.deepEqual(result, { kind: "ok", status: 200, body: apercu });
    assert.equal(transport.requests[0]?.method, "GET");
    assert.equal(transport.requests[0]?.url, "http://core:8080/v1/companies/7/prestige/preview");
    assert.equal(transport.requests[0]?.body, undefined);
  });

  it("rejoue une fois une lecture qui tombe en panne", async () => {
    const { transport, api } = setup([json(500, { reason: "ERREUR_INTERNE" }), json(200, apercu)]);
    const result = await api.prestigePreview(7);
    assert.equal(result.kind, "ok");
    assert.equal(transport.requests.length, 2);
  });

  it("une réponse hors contrat devient une erreur lisible qui nomme la clé", async () => {
    const { api } = setup([json(200, { ...apercu, next: "EPOQUE_9" })]);
    const result = await api.prestigePreview(7);
    assert.equal(result.kind, "error");
    if (result.kind === "error") {
      assert.equal(result.reason, "REPONSE_INATTENDUE");
      assert.match(result.detail ?? "", /prestige\.next/);
    }
  });

  it("une route pas encore écrite (501) reste une erreur du service", async () => {
    const { api } = setup([json(501, { reason: "PAS_FAIT" })]);
    const result = await api.prestigePreview(7);
    assert.deepEqual(result, { kind: "error", status: 501, reason: "PAS_FAIT" });
  });

  it("un identifiant d'entreprise illisible n'envoie aucune requête", async () => {
    const { transport, api } = setup([json(200, apercu)]);
    const result = await api.prestigePreview(Number.NaN);
    assert.equal(result.kind, "error");
    if (result.kind === "error") assert.equal(result.reason, "REQUETE_INVALIDE");
    assert.equal(transport.requests.length, 0);
  });
});

describe("prestige", () => {
  it("poste l'action, sans montant, et lit l'état rendu avec les soldes", async () => {
    const { transport, api } = setup([json(200, { state: companyState({ courseNumber: 3 }), balances: balances({ lingots: 1_200 }) })]);
    const result = await api.prestige(7, action);
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") {
      assert.equal(result.body.state.courseNumber, 3);
      assert.equal(result.body.balances.lingots, 1_200);
    }
    const request = transport.requests[0];
    assert.equal(request?.method, "POST");
    assert.equal(request?.url, "http://core:8080/v1/companies/7/prestige");
    assert.deepEqual(bodyOf(request ?? { body: undefined }), action);
  });

  it("le refus 403 PAS_LE_PDG garde son code", async () => {
    const { api } = setup([json(403, { reason: "PAS_LE_PDG" })]);
    const result = await api.prestige(7, action);
    assert.deepEqual(result, { kind: "refused", status: 403, reason: "PAS_LE_PDG" });
  });

  it("le refus 409 SEUIL_NON_ATTEINT rend les Donar qui manquent", async () => {
    const { api } = setup([json(409, { reason: "SEUIL_NON_ATTEINT", missing: 4_000 })]);
    const result = await api.prestige(7, action);
    assert.deepEqual(result, { kind: "refused", status: 409, reason: "SEUIL_NON_ATTEINT", missing: 4_000 });
  });

  it("une écriture n'est jamais rejouée toute seule", async () => {
    const { transport, api } = setup([json(500, { reason: "ERREUR_INTERNE" }), json(200, {})]);
    const result = await api.prestige(7, action);
    assert.equal(result.kind, "error");
    assert.equal(transport.requests.length, 1);
  });

  it("un réseau coupé devient un résultat network", async () => {
    const { api } = setup([new Error("connexion refusée")]);
    const result = await api.prestige(7, action);
    assert.equal(result.kind, "network");
  });

  it("un identifiant d'entreprise illisible n'envoie aucune requête", async () => {
    const { transport, api } = setup([json(200, {})]);
    const result = await api.prestige(-3, action);
    assert.equal(result.kind, "error");
    assert.equal(transport.requests.length, 0);
  });
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

describe("getCandidates", () => {
  it("lit les candidats du jour par un GET, sans corps", async () => {
    const { transport, api } = setup([json(200, [libre, { ...libre, slot: 2, name: "Camille", rarity: "RARE", role: "CHEF" }])]);
    const result = await api.getCandidates(7);
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") {
      assert.deepEqual(
        result.body.map((candidat) => [candidat.slot, candidat.name, candidat.rarity]),
        [[1, "Basile", "COMMUN"], [2, "Camille", "RARE"]],
      );
    }
    const request = transport.requests[0];
    assert.equal(request?.method, "GET");
    assert.equal(request?.url, "http://core:8080/v1/companies/7/agency");
    assert.equal(request?.body, undefined);
  });

  it("rejoue une fois une lecture qui tombe en panne", async () => {
    const { transport, api } = setup([json(500, { reason: "ERREUR_INTERNE" }), json(200, [libre])]);
    const result = await api.getCandidates(7);
    assert.equal(result.kind, "ok");
    assert.equal(transport.requests.length, 2);
  });

  it("une liste hors contrat devient une erreur lisible", async () => {
    const { api } = setup([json(200, [{ ...libre, rarity: "MYTHIQUE" }])]);
    const result = await api.getCandidates(7);
    assert.equal(result.kind, "error");
    if (result.kind === "error") {
      assert.equal(result.reason, "REPONSE_INATTENDUE");
      assert.match(result.detail ?? "", /agence\[0\]\.rarity/);
    }
  });

  it("un objet à la place de la liste est une erreur, pas une liste vide", async () => {
    const { api } = setup([json(200, {})]);
    assert.equal((await api.getCandidates(7)).kind, "error");
  });

  it("une entreprise inconnue (400) garde son code", async () => {
    const { api } = setup([json(400, { reason: "ENTREPRISE_INCONNUE" })]);
    assert.deepEqual(await api.getCandidates(7), { kind: "error", status: 400, reason: "ENTREPRISE_INCONNUE" });
  });

  it("un identifiant d'entreprise illisible n'envoie aucune requête", async () => {
    const { transport, api } = setup([json(200, [])]);
    const result = await api.getCandidates(Number.NaN);
    assert.equal(result.kind, "error");
    if (result.kind === "error") assert.equal(result.reason, "REQUETE_INVALIDE");
    assert.equal(transport.requests.length, 0);
  });
});

describe("hire", () => {
  const rendu = { state: companyState({ version: 2, donar: 5 }), balances: balances({ lingots: 3 }) };

  it("poste l'action vers la place demandée, sans usine : la clé factoryId n'existe pas", async () => {
    const { transport, api } = setup([json(200, rendu)]);
    const result = await api.hire(7, 3, action);
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") assert.equal(result.body.balances.lingots, 3);
    const request = transport.requests[0];
    assert.equal(request?.method, "POST");
    assert.equal(request?.url, "http://core:8080/v1/companies/7/agency/3/hire");
    assert.deepEqual(bodyOf(request ?? { body: undefined }), action);
  });

  it("avec une usine, l'usine part dans le corps", async () => {
    const { transport, api } = setup([json(200, rendu)]);
    await api.hire(7, 1, action, "scierie");
    assert.deepEqual(bodyOf(transport.requests[0] ?? { body: undefined }), { ...action, factoryId: "scierie" });
  });

  it("le refus PAS_ASSEZ_DE_DONAR rend ce qui manque", async () => {
    const { api } = setup([json(409, { reason: "PAS_ASSEZ_DE_DONAR", missing: 1_200 })]);
    assert.deepEqual(await api.hire(7, 1, action), { kind: "refused", status: 409, reason: "PAS_ASSEZ_DE_DONAR", missing: 1_200 });
  });

  it("les refus DEJA_POSSEDE, EMPLACEMENT_OCCUPE et USINE_VERROUILLEE gardent leur code", async () => {
    for (const reason of ["DEJA_POSSEDE", "EMPLACEMENT_OCCUPE", "USINE_VERROUILLEE", "CONFLIT_REESSAYER"]) {
      const { api } = setup([json(409, { reason })]);
      assert.deepEqual(await api.hire(7, 1, action, "scierie"), { kind: "refused", status: 409, reason });
    }
  });

  it("une écriture n'est jamais rejouée toute seule", async () => {
    const { transport, api } = setup([json(500, { reason: "ERREUR_INTERNE" }), json(200, rendu)]);
    const result = await api.hire(7, 1, action);
    assert.equal(result.kind, "error");
    assert.equal(transport.requests.length, 1);
  });

  it("un réseau coupé devient un résultat network", async () => {
    const { api } = setup([new Error("connexion refusée")]);
    assert.equal((await api.hire(7, 1, action)).kind, "network");
  });

  it("une réponse hors contrat devient une erreur lisible", async () => {
    const { api } = setup([json(200, { state: {} })]);
    const result = await api.hire(7, 1, action);
    assert.equal(result.kind, "error");
    if (result.kind === "error") assert.equal(result.reason, "REPONSE_INATTENDUE");
  });

  it("une place hors de 1 à 5, une usine vide ou une entreprise illisible n'envoient aucune requête", async () => {
    const { transport, api } = setup([json(200, rendu)]);
    for (const resultat of [
      await api.hire(7, 0, action),
      await api.hire(7, 6, action),
      await api.hire(7, 1, action, ""),
      await api.hire(-3, 1, action),
    ]) {
      assert.equal(resultat.kind, "error");
      if (resultat.kind === "error") assert.equal(resultat.reason, "REQUETE_INVALIDE");
    }
    assert.equal(transport.requests.length, 0);
  });
});

describe("assignStaff", () => {
  const rendu = { state: companyState({ version: 2 }), balances: balances() };

  it("envoie un PATCH vers le membre, avec l'usine dans le corps", async () => {
    const { transport, api } = setup([json(200, rendu)]);
    const result = await api.assignStaff(7, 11, action, "scierie");
    assert.equal(result.kind, "ok");
    const request = transport.requests[0];
    assert.equal(request?.method, "PATCH");
    assert.equal(request?.url, "http://core:8080/v1/companies/7/staff/11");
    assert.deepEqual(bodyOf(request ?? { body: undefined }), { ...action, factoryId: "scierie" });
  });

  it("les refus EMPLACEMENT_OCCUPE et USINE_VERROUILLEE gardent leur code", async () => {
    for (const reason of ["EMPLACEMENT_OCCUPE", "USINE_VERROUILLEE"]) {
      const { api } = setup([json(409, { reason })]);
      assert.deepEqual(await api.assignStaff(7, 11, action, "scierie"), { kind: "refused", status: 409, reason });
    }
  });

  it("une écriture n'est jamais rejouée toute seule", async () => {
    const { transport, api } = setup([new Error("coupé"), json(200, rendu)]);
    assert.equal((await api.assignStaff(7, 11, action, "scierie")).kind, "network");
    assert.equal(transport.requests.length, 1);
  });

  it("un staffId illisible, une usine vide ou une entreprise illisible n'envoient aucune requête", async () => {
    const { transport, api } = setup([json(200, rendu)]);
    for (const resultat of [
      await api.assignStaff(7, -1, action, "scierie"),
      await api.assignStaff(7, 1.5, action, "scierie"),
      await api.assignStaff(7, 11, action, ""),
      await api.assignStaff(Number.NaN, 11, action, "scierie"),
    ]) {
      assert.equal(resultat.kind, "error");
      if (resultat.kind === "error") assert.equal(resultat.reason, "REQUETE_INVALIDE");
    }
    assert.equal(transport.requests.length, 0);
  });
});

describe("fireStaff", () => {
  const rendu = { state: companyState({ version: 2 }), balances: balances() };

  it("envoie un DELETE sans corps, requestId et acteur dans la requête", async () => {
    const { transport, api } = setup([json(200, rendu)]);
    const result = await api.fireStaff(7, 11, action);
    assert.equal(result.kind, "ok");
    const request = transport.requests[0];
    assert.equal(request?.method, "DELETE");
    assert.equal(request?.url, `http://core:8080/v1/companies/7/staff/11?requestId=${action.requestId}&actor=42`);
    assert.equal(request?.body, undefined);
  });

  it("le refus 403 PAS_LE_PDG garde son code", async () => {
    const { api } = setup([json(403, { reason: "PAS_LE_PDG" })]);
    assert.deepEqual(await api.fireStaff(7, 11, action), { kind: "refused", status: 403, reason: "PAS_LE_PDG" });
  });

  it("le refus 409 CONFLIT_REESSAYER garde son code", async () => {
    const { api } = setup([json(409, { reason: "CONFLIT_REESSAYER" })]);
    assert.deepEqual(await api.fireStaff(7, 11, action), { kind: "refused", status: 409, reason: "CONFLIT_REESSAYER" });
  });

  it("une écriture n'est jamais rejouée toute seule", async () => {
    const { transport, api } = setup([json(500, { reason: "ERREUR_INTERNE" }), json(200, rendu)]);
    assert.equal((await api.fireStaff(7, 11, action)).kind, "error");
    assert.equal(transport.requests.length, 1);
  });

  it("un acteur qui n'est pas un XUID, un staffId ou une entreprise illisibles n'envoient aucune requête", async () => {
    const { transport, api } = setup([json(200, rendu)]);
    for (const resultat of [
      await api.fireStaff(7, 11, { requestId: "r", actor: "42&x=1" }),
      await api.fireStaff(7, -4, action),
      await api.fireStaff(-3, 11, action),
    ]) {
      assert.equal(resultat.kind, "error");
      if (resultat.kind === "error") assert.equal(resultat.reason, "REQUETE_INVALIDE");
    }
    assert.equal(transport.requests.length, 0);
  });
});

describe("invite", () => {
  const rendu = { state: companyState({ version: 2 }), balances: balances() };
  const cible = "2535428000000002";

  it("envoie un POST vers /members avec l'action et le joueur invité", async () => {
    const { transport, api } = setup([json(200, rendu)]);
    const result = await api.invite(7, action, cible);
    assert.equal(result.kind, "ok");
    const request = transport.requests[0];
    assert.equal(request?.method, "POST");
    assert.equal(request?.url, "http://core:8080/v1/companies/7/members");
    assert.deepEqual(bodyOf(request ?? { body: undefined }), { ...action, targetXuid: cible });
  });

  it("les refus du contrat gardent leur code : PAS_LE_PDG en 403, les quatre autres en 409", async () => {
    assert.deepEqual(await setup([json(403, { reason: "PAS_LE_PDG" })]).api.invite(7, action, cible), {
      kind: "refused",
      status: 403,
      reason: "PAS_LE_PDG",
    });
    for (const reason of ["ENTREPRISE_PLEINE", "DEJA_POSSEDE", "ENTREPRISE_EN_COURS", "CONFLIT_REESSAYER"]) {
      assert.deepEqual(await setup([json(409, { reason })]).api.invite(7, action, cible), { kind: "refused", status: 409, reason });
    }
  });

  it("un 400 (joueur inconnu) reste une erreur du service, sans refus du jeu", async () => {
    const { api } = setup([json(400, { reason: "REQUETE_INVALIDE" })]);
    assert.deepEqual(await api.invite(7, action, cible), { kind: "error", status: 400, reason: "REQUETE_INVALIDE" });
  });

  it("une écriture n'est jamais rejouée toute seule", async () => {
    const { transport, api } = setup([json(500, { reason: "ERREUR_INTERNE" }), json(200, rendu)]);
    assert.equal((await api.invite(7, action, cible)).kind, "error");
    assert.equal(transport.requests.length, 1);
  });

  it("un réseau coupé devient un résultat network", async () => {
    const { api } = setup([new Error("coupé")]);
    assert.equal((await api.invite(7, action, cible)).kind, "network");
  });

  it("une réponse hors contrat devient une erreur lisible", async () => {
    const { api } = setup([json(200, { state: {} })]);
    const result = await api.invite(7, action, cible);
    assert.equal(result.kind, "error");
    if (result.kind === "error") assert.equal(result.reason, "REPONSE_INATTENDUE");
  });

  it("un joueur qui n'est pas un XUID, une entreprise ou un acteur illisibles n'envoient aucune requête", async () => {
    const { transport, api } = setup([json(200, rendu)]);
    for (const resultat of [
      await api.invite(7, action, ""),
      await api.invite(7, action, "Ada"),
      await api.invite(7, action, "42&x=1"),
      await api.invite(-3, action, cible),
      await api.invite(Number.NaN, action, cible),
    ]) {
      assert.equal(resultat.kind, "error");
      if (resultat.kind === "error") assert.equal(resultat.reason, "REQUETE_INVALIDE");
    }
    assert.equal(transport.requests.length, 0);
  });
});

describe("setShares", () => {
  const membres = [
    { xuid: "2535428000000001", name: "Kr", role: "PDG", sharePercent: 70 },
    { xuid: "2535428000000002", name: "Ada", role: "ASSOCIE", sharePercent: 30 },
  ];
  const rendu = { state: companyState({ version: 3, members: membres as never }), balances: balances() };
  const parts = [
    { xuid: "2535428000000001", percent: 70 },
    { xuid: "2535428000000002", percent: 30 },
  ];

  it("envoie un PUT vers /members avec l'action et une part par membre", async () => {
    const { transport, api } = setup([json(200, rendu)]);
    const result = await api.setShares(7, action, parts);
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") assert.deepEqual(result.body.state.members, membres);
    const request = transport.requests[0];
    assert.equal(request?.method, "PUT");
    assert.equal(request?.url, "http://core:8080/v1/companies/7/members");
    assert.deepEqual(bodyOf(request ?? { body: undefined }), { ...action, shares: parts });
  });

  it("la somme n'est pas vérifiée dans l'add-on : une liste qui ne fait pas 100 part, et le 400 du service revient tel quel", async () => {
    const { transport, api } = setup([json(400, { reason: "REQUETE_INVALIDE" })]);
    const trop = [
      { xuid: "2535428000000001", percent: 90 },
      { xuid: "2535428000000002", percent: 90 },
    ];
    assert.deepEqual(await api.setShares(7, action, trop), { kind: "error", status: 400, reason: "REQUETE_INVALIDE" });
    assert.equal(transport.requests.length, 1);
  });

  it("les refus PAS_LE_PDG (403) et CONFLIT_REESSAYER (409) gardent leur code", async () => {
    assert.deepEqual(await setup([json(403, { reason: "PAS_LE_PDG" })]).api.setShares(7, action, parts), {
      kind: "refused",
      status: 403,
      reason: "PAS_LE_PDG",
    });
    assert.deepEqual(await setup([json(409, { reason: "CONFLIT_REESSAYER" })]).api.setShares(7, action, parts), {
      kind: "refused",
      status: 409,
      reason: "CONFLIT_REESSAYER",
    });
  });

  it("une écriture n'est jamais rejouée toute seule", async () => {
    const { transport, api } = setup([new Error("coupé"), json(200, rendu)]);
    assert.equal((await api.setShares(7, action, parts)).kind, "network");
    assert.equal(transport.requests.length, 1);
  });

  it("un XUID, un pourcentage ou une entreprise illisibles n'envoient aucune requête", async () => {
    const { transport, api } = setup([json(200, rendu)]);
    for (const resultat of [
      await api.setShares(7, action, [{ xuid: "Kr", percent: 100 }]),
      await api.setShares(7, action, [{ xuid: "2535428000000001", percent: 101 }]),
      await api.setShares(7, action, [{ xuid: "2535428000000001", percent: -1 }]),
      await api.setShares(7, action, [{ xuid: "2535428000000001", percent: 12.5 }]),
      await api.setShares(-3, action, parts),
    ]) {
      assert.equal(resultat.kind, "error");
      if (resultat.kind === "error") assert.equal(resultat.reason, "REQUETE_INVALIDE");
    }
    assert.equal(transport.requests.length, 0);
  });
});

describe("acceptInvite", () => {
  const rendu = { state: companyState({ companyId: 12, version: 5 }), balances: balances() };

  it("envoie un POST vers /members/accept de l'entreprise qui invite, avec l'action seule", async () => {
    const { transport, api } = setup([json(200, rendu)]);
    const result = await api.acceptInvite(12, action);
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") assert.equal(result.body.state.companyId, 12);
    const request = transport.requests[0];
    assert.equal(request?.method, "POST");
    assert.equal(request?.url, "http://core:8080/v1/companies/12/members/accept");
    assert.deepEqual(bodyOf(request ?? { body: undefined }), action);
  });

  it("les refus du contrat gardent leur code", async () => {
    for (const reason of ["DEJA_POSSEDE", "ENTREPRISE_PLEINE", "ENTREPRISE_EN_COURS", "CONFLIT_REESSAYER"]) {
      assert.deepEqual(await setup([json(409, { reason })]).api.acceptInvite(12, action), { kind: "refused", status: 409, reason });
    }
  });

  it("un 400 (invitation absente ou périmée) reste une erreur du service", async () => {
    const { api } = setup([json(400, { reason: "REQUETE_INVALIDE" })]);
    assert.deepEqual(await api.acceptInvite(12, action), { kind: "error", status: 400, reason: "REQUETE_INVALIDE" });
  });

  it("une écriture n'est jamais rejouée toute seule", async () => {
    const { transport, api } = setup([json(500, { reason: "ERREUR_INTERNE" }), json(200, rendu)]);
    assert.equal((await api.acceptInvite(12, action)).kind, "error");
    assert.equal(transport.requests.length, 1);
  });

  it("une entreprise illisible n'envoie aucune requête", async () => {
    const { transport, api } = setup([json(200, rendu)]);
    for (const id of [-1, 1.5, Number.NaN]) {
      const resultat = await api.acceptInvite(id, action);
      assert.equal(resultat.kind, "error");
      if (resultat.kind === "error") assert.equal(resultat.reason, "REQUETE_INVALIDE");
    }
    assert.equal(transport.requests.length, 0);
  });
});

// ---------------------------------------------------------------- la boutique, le pass, les votes

describe("catalog", () => {
  const catalogue = shopCatalog();

  it("lit le catalogue d'un joueur par un GET, l'acteur dans la requête", async () => {
    const { transport, api } = setup([json(200, catalogue)]);
    const result = await api.catalog("42");
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") assert.deepEqual(result.body, catalogue);
    assert.equal(transport.requests[0]?.method, "GET");
    assert.equal(transport.requests[0]?.url, "http://core:8080/v1/shop/catalog?actor=42");
    assert.equal(transport.requests[0]?.body, undefined);
  });

  it("rejoue une fois une lecture qui tombe en panne", async () => {
    const { transport, api } = setup([json(500, { reason: "ERREUR_INTERNE" }), json(200, catalogue)]);
    assert.equal((await api.catalog("42")).kind, "ok");
    assert.equal(transport.requests.length, 2);
  });

  it("un article sans possession devient une erreur lisible qui nomme la clé", async () => {
    const { api } = setup([json(200, [{ itemId: "vip", gemmes: 500, available: true }])]);
    const result = await api.catalog("42");
    assert.equal(result.kind, "error");
    if (result.kind === "error") {
      assert.equal(result.reason, "REPONSE_INATTENDUE");
      assert.match(result.detail ?? "", /owned/);
    }
  });

  it("un joueur inconnu du service (400) est une erreur du service, pas une panne", async () => {
    const { api } = setup([json(400, { reason: "REQUETE_INVALIDE" })]);
    assert.deepEqual(await api.catalog("42"), { kind: "error", status: 400, reason: "REQUETE_INVALIDE" });
  });

  it("un acteur qui n'est pas un XUID n'envoie aucune requête", async () => {
    const { transport, api } = setup([json(200, catalogue)]);
    const result = await api.catalog("Alex");
    assert.equal(result.kind, "error");
    if (result.kind === "error") assert.equal(result.reason, "REQUETE_INVALIDE");
    assert.equal(transport.requests.length, 0);
  });
});

describe("buyItem", () => {
  const soldes = balances({ lingots: 4, gemmes: 90 });

  it("poste l'action et l'article ; sans cadeau la clé forXuid n'existe pas", async () => {
    const { transport, api } = setup([json(200, soldes)]);
    const result = await api.buyItem(action, "vip");
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") assert.deepEqual(result.body, soldes);
    const request = transport.requests[0];
    assert.equal(request?.method, "POST");
    assert.equal(request?.url, "http://core:8080/v1/shop/purchases");
    assert.deepEqual(bodyOf(request ?? { body: undefined }), { ...action, itemId: "vip" });
  });

  it("un cadeau envoie le destinataire dans le corps", async () => {
    const { transport, api } = setup([json(200, soldes)]);
    await api.buyItem(action, "chat-atelier", "2535428000000003");
    assert.deepEqual(bodyOf(transport.requests[0] ?? { body: undefined }), { ...action, itemId: "chat-atelier", forXuid: "2535428000000003" });
  });

  it("rejoué avec le même requestId, le corps est identique : le service rend la même réponse", async () => {
    const { transport, api } = setup([json(200, soldes)]);
    await api.buyItem(action, "vip");
    await api.buyItem(action, "vip");
    assert.equal(transport.requests.length, 2);
    assert.equal(transport.requests[0]?.body, transport.requests[1]?.body);
  });

  it("les refus du contrat gardent leur code ; PAS_ASSEZ_DE_GEMMES rend ce qui manque", async () => {
    const { api } = setup([json(409, { reason: "PAS_ASSEZ_DE_GEMMES", missing: 40 })]);
    assert.deepEqual(await api.buyItem(action, "vip"), { kind: "refused", status: 409, reason: "PAS_ASSEZ_DE_GEMMES", missing: 40 });
    for (const reason of ["DEJA_POSSEDE", "GRADE_PRECEDENT_REQUIS", "SAISON_INACTIVE", "CONFLIT_REESSAYER"]) {
      const { api: autre } = setup([json(409, { reason })]);
      assert.deepEqual(await autre.buyItem(action, "vip"), { kind: "refused", status: 409, reason });
    }
  });

  it("un article inconnu ou un destinataire inconnu (400) reste une erreur du service, sans refus du jeu", async () => {
    const { api } = setup([json(400, { reason: "REQUETE_INVALIDE" })]);
    assert.deepEqual(await api.buyItem(action, "inconnu", "2535428000000003"), { kind: "error", status: 400, reason: "REQUETE_INVALIDE" });
  });

  it("une écriture n'est jamais rejouée toute seule", async () => {
    const { transport, api } = setup([json(500, { reason: "ERREUR_INTERNE" }), json(200, soldes)]);
    assert.equal((await api.buyItem(action, "vip")).kind, "error");
    assert.equal(transport.requests.length, 1);
  });

  it("un réseau coupé devient un résultat network", async () => {
    const { api } = setup([new Error("connexion refusée")]);
    assert.equal((await api.buyItem(action, "vip")).kind, "network");
  });

  it("une réponse hors contrat devient une erreur lisible", async () => {
    const { api } = setup([json(200, { lingots: 1 })]);
    const result = await api.buyItem(action, "vip");
    assert.equal(result.kind, "error");
    if (result.kind === "error") assert.equal(result.reason, "REPONSE_INATTENDUE");
  });

  it("un article vide ou trop long, un destinataire qui n'est pas un XUID n'envoient aucune requête", async () => {
    const { transport, api } = setup([json(200, soldes)]);
    for (const resultat of [
      await api.buyItem(action, ""),
      await api.buyItem(action, "x".repeat(49)),
      await api.buyItem(action, "vip", "Bea"),
    ]) {
      assert.equal(resultat.kind, "error");
      if (resultat.kind === "error") assert.equal(resultat.reason, "REQUETE_INVALIDE");
    }
    assert.equal(transport.requests.length, 0);
  });
});

describe("getPass", () => {
  const pass = passState();

  it("lit le pass d'un joueur par un GET, l'acteur dans la requête", async () => {
    const { transport, api } = setup([json(200, pass)]);
    const result = await api.getPass("42");
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") assert.deepEqual(result.body, pass);
    assert.equal(transport.requests[0]?.method, "GET");
    assert.equal(transport.requests[0]?.url, "http://core:8080/v1/pass?actor=42");
  });

  it("une saison hors contrat devient une erreur lisible qui nomme la clé", async () => {
    const { seasonId: _perdu, ...sans } = pass;
    const { api } = setup([json(200, sans)]);
    const result = await api.getPass("42");
    assert.equal(result.kind, "error");
    if (result.kind === "error") assert.match(result.detail ?? "", /seasonId/);
  });

  it("un acteur qui n'est pas un XUID n'envoie aucune requête", async () => {
    const { transport, api } = setup([json(200, pass)]);
    assert.equal((await api.getPass("")).kind, "error");
    assert.equal(transport.requests.length, 0);
  });
});

describe("claimPass", () => {
  const reclame = passClaimed();

  it("poste l'action, le palier et la piste", async () => {
    const { transport, api } = setup([json(200, reclame)]);
    const result = await api.claimPass(action, 2, "GRATUIT");
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") assert.deepEqual(result.body, reclame);
    const request = transport.requests[0];
    assert.equal(request?.method, "POST");
    assert.equal(request?.url, "http://core:8080/v1/pass/claims");
    assert.deepEqual(bodyOf(request ?? { body: undefined }), { ...action, tier: 2, track: "GRATUIT" });
  });

  it("la piste premium rend un cosmétique et pas de Lingots", async () => {
    const premium = passClaimed({ tier: 3, track: "PREMIUM", rewardLingots: 0, cosmeticId: "s1-lanterne" });
    const { api } = setup([json(200, premium)]);
    const result = await api.claimPass(action, 3, "PREMIUM");
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") {
      assert.equal(result.body.cosmeticId, "s1-lanterne");
      assert.equal(result.body.rewardLingots, 0);
    }
  });

  it("les refus du contrat gardent leur code ; PALIER_NON_ATTEINT rend les points qui manquent", async () => {
    const { api } = setup([json(409, { reason: "PALIER_NON_ATTEINT", missing: 60 })]);
    assert.deepEqual(await api.claimPass(action, 3, "GRATUIT"), { kind: "refused", status: 409, reason: "PALIER_NON_ATTEINT", missing: 60 });
    for (const reason of ["PREMIUM_REQUIS", "DEJA_RECLAME", "CONFLIT_REESSAYER"]) {
      const { api: autre } = setup([json(409, { reason })]);
      assert.deepEqual(await autre.claimPass(action, 1, "PREMIUM"), { kind: "refused", status: 409, reason });
    }
  });

  it("une écriture n'est jamais rejouée toute seule, et le même requestId part tel quel au rejeu", async () => {
    const { transport, api } = setup([json(500, { reason: "ERREUR_INTERNE" }), json(200, reclame)]);
    assert.equal((await api.claimPass(action, 2, "GRATUIT")).kind, "error");
    assert.equal(transport.requests.length, 1);
    await api.claimPass(action, 2, "GRATUIT");
    assert.equal(transport.requests[0]?.body, transport.requests[1]?.body);
  });

  it("un palier ou une piste que le contrat refuserait n'envoient aucune requête", async () => {
    const { transport, api } = setup([json(200, reclame)]);
    for (const resultat of [
      await api.claimPass(action, 0, "GRATUIT"),
      await api.claimPass(action, 1.5, "GRATUIT"),
      await api.claimPass(action, 1, "OR" as unknown as "GRATUIT"),
    ]) {
      assert.equal(resultat.kind, "error");
      if (resultat.kind === "error") assert.equal(resultat.reason, "REQUETE_INVALIDE");
    }
    assert.equal(transport.requests.length, 0);
  });

  it("une réponse sans soldes devient une erreur lisible", async () => {
    const { balances: _perdus, ...sans } = reclame;
    const { api } = setup([json(200, sans)]);
    const result = await api.claimPass(action, 2, "GRATUIT");
    assert.equal(result.kind, "error");
    if (result.kind === "error") assert.equal(result.reason, "REPONSE_INATTENDUE");
  });
});

describe("todayVotes", () => {
  const votes = votesToday({ mine: { votes: 1, streak: 3 } });

  it("lit les votes du jour par un GET, le joueur dans la requête", async () => {
    const { transport, api } = setup([json(200, votes)]);
    const result = await api.todayVotes("42");
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") assert.deepEqual(result.body, votes);
    assert.equal(transport.requests[0]?.method, "GET");
    assert.equal(transport.requests[0]?.url, "http://core:8080/v1/votes/today?xuid=42");
  });

  it("sans joueur, la requête n'a pas de paramètre", async () => {
    const { transport, api } = setup([json(200, votesToday())]);
    await api.todayVotes();
    assert.equal(transport.requests[0]?.url, "http://core:8080/v1/votes/today");
  });

  it("un joueur qui n'a pas voté : zéro vote et zéro série", async () => {
    const { api } = setup([json(200, votesToday())]);
    const result = await api.todayVotes("42");
    assert.equal(result.kind, "ok");
    if (result.kind === "ok") assert.deepEqual(result.body.mine, { votes: 0, streak: 0 });
  });

  it("une réponse sans la part du joueur devient une erreur lisible", async () => {
    const { mine: _perdue, ...sans } = votes;
    const { api } = setup([json(200, sans)]);
    const result = await api.todayVotes("42");
    assert.equal(result.kind, "error");
    if (result.kind === "error") assert.match(result.detail ?? "", /mine/);
  });

  it("un XUID illisible n'envoie aucune requête", async () => {
    const { transport, api } = setup([json(200, votes)]);
    assert.equal((await api.todayVotes("Alex")).kind, "error");
    assert.equal(transport.requests.length, 0);
  });
});
