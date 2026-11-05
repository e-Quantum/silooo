import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createCoreApi } from "../src/api";
import { createCoreClient } from "../src/http";
import { balances, companyState, factory, json, scriptedTransport } from "./helpers";

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
