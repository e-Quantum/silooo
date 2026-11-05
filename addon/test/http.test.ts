import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { classifyResponse, createCoreClient, describeFailure, type OpaqueSecret } from "../src/http";
import { json, scriptedTransport } from "./helpers";

function client(script: Parameters<typeof scriptedTransport>[0], overrides: { readRetries?: number; coreUrl?: string } = {}) {
  const transport = scriptedTransport(script);
  const core = createCoreClient({
    transport,
    coreUrl: overrides.coreUrl ?? "http://silooo-core:8080",
    apiKey: "cle-de-test",
    timeoutSeconds: 5,
    readRetries: overrides.readRetries ?? 1,
  });
  return { core, transport };
}

describe("createCoreClient : la requête", () => {
  it("envoie du JSON avec Content-Type et X-Silooo-Key, vers coreUrl + chemin, avec le délai", async () => {
    const { core, transport } = client([{ status: 204, body: "" }]);
    await core.post("/v1/session/leave", { xuid: "42" });
    const [request] = transport.requests;
    assert.ok(request);
    assert.equal(request.method, "POST");
    assert.equal(request.url, "http://silooo-core:8080/v1/session/leave");
    assert.equal(request.body, '{"xuid":"42"}');
    assert.equal(request.timeoutSeconds, 5);
    assert.deepEqual(
      request.headers.map((h) => h.name),
      ["Content-Type", "X-Silooo-Key"],
    );
    assert.equal(request.headers[0]?.value, "application/json");
    assert.equal(request.headers[1]?.value, "cle-de-test");
  });

  it("GET : pas de corps, même en-têtes", async () => {
    const { core, transport } = client([json(200, {})]);
    await core.get("/v1/events?after=0&limit=100");
    const [request] = transport.requests;
    assert.equal(request?.method, "GET");
    assert.equal(request?.body, undefined);
    assert.equal(request?.url, "http://silooo-core:8080/v1/events?after=0&limit=100");
    assert.equal(request?.headers.length, 2);
  });

  it("retire les « / » de fin de l'adresse", async () => {
    const { core, transport } = client([json(200, {})], { coreUrl: "http://silooo-core:8080//" });
    await core.get("/v1/events?after=0&limit=1");
    assert.equal(transport.requests[0]?.url, "http://silooo-core:8080/v1/events?after=0&limit=1");
  });

  it("passe un secret opaque au transport tel quel, sans le lire", async () => {
    const transport = scriptedTransport([json(200, {})]);
    const secret = Object.freeze({}) as unknown as OpaqueSecret;
    const core = createCoreClient({ transport, coreUrl: "http://x", apiKey: secret, timeoutSeconds: 5, readRetries: 0 });
    await core.get("/v1/events?after=0&limit=1");
    assert.equal(transport.requests[0]?.headers[1]?.value, secret);
  });
});

describe("classifyResponse : les quatre cas", () => {
  it("2xx avec un corps JSON : ok", () => {
    assert.deepEqual(classifyResponse(200, '{"a":1}'), { kind: "ok", status: 200, body: { a: 1 } });
  });

  it("204 sans corps : ok, corps absent", () => {
    assert.deepEqual(classifyResponse(204, ""), { kind: "ok", status: 204, body: undefined });
  });

  it("2xx dont le corps n'est pas du JSON : erreur REPONSE_ILLISIBLE", () => {
    assert.deepEqual(classifyResponse(200, "<html>"), { kind: "error", status: 200, reason: "REPONSE_ILLISIBLE" });
  });

  it("409 Refused : refused avec reason et missing", () => {
    assert.deepEqual(classifyResponse(409, '{"reason":"PAS_ASSEZ_DE_DONAR","missing":12.5}'), {
      kind: "refused",
      status: 409,
      reason: "PAS_ASSEZ_DE_DONAR",
      missing: 12.5,
    });
  });

  it("409 Refused sans missing : la clé est absente", () => {
    const result = classifyResponse(409, '{"reason":"CONFLIT_REESSAYER"}');
    assert.deepEqual(result, { kind: "refused", status: 409, reason: "CONFLIT_REESSAYER" });
    assert.equal("missing" in result, false);
  });

  it("403 Refused : refused aussi", () => {
    assert.deepEqual(classifyResponse(403, '{"reason":"PAS_LE_PDG"}'), {
      kind: "refused",
      status: 403,
      reason: "PAS_LE_PDG",
    });
  });

  for (const [status, reason] of [
    [400, "REQUETE_INVALIDE"],
    [401, "CLE_INVALIDE"],
    [500, "ERREUR_INTERNE"],
    [501, "PAS_FAIT"],
  ] as const) {
    it(`${status} Erreur : error ${reason}`, () => {
      assert.deepEqual(classifyResponse(status, JSON.stringify({ reason })), { kind: "error", status, reason });
    });
  }

  it("un code d'erreur sans corps lisible : error HTTP_<code>", () => {
    assert.deepEqual(classifyResponse(502, "Bad Gateway"), { kind: "error", status: 502, reason: "HTTP_502" });
    assert.deepEqual(classifyResponse(404, ""), { kind: "error", status: 404, reason: "HTTP_404" });
  });

  it("409 sans reason lisible : error, pas refused", () => {
    assert.deepEqual(classifyResponse(409, "{}"), { kind: "error", status: 409, reason: "HTTP_409" });
  });
});

describe("createCoreClient : réseau et nouveaux essais", () => {
  it("une exception du transport donne network avec son message", async () => {
    const { core } = client([new Error("délai dépassé")], { readRetries: 0 });
    assert.deepEqual(await core.get("/v1/events?after=0&limit=1"), { kind: "network", message: "Error: délai dépassé" });
  });

  it("un GET en panne réseau est rejoué une fois", async () => {
    const { core, transport } = client([new Error("coupé"), json(200, { cursor: 0, events: [] })]);
    const result = await core.get("/v1/events?after=0&limit=1");
    assert.equal(result.kind, "ok");
    assert.equal(transport.requests.length, 2);
  });

  it("un GET en 500 est rejoué une fois, pas plus", async () => {
    const { core, transport } = client([json(500, { reason: "ERREUR_INTERNE" })]);
    const result = await core.get("/v1/events?after=0&limit=1");
    assert.deepEqual(result, { kind: "error", status: 500, reason: "ERREUR_INTERNE" });
    assert.equal(transport.requests.length, 2);
  });

  it("501 PAS_FAIT, 400 et 401 ne sont pas rejoués", async () => {
    for (const status of [501, 400, 401]) {
      const { core, transport } = client([json(status, { reason: "X" })]);
      await core.get("/v1/events?after=0&limit=1");
      assert.equal(transport.requests.length, 1, `code ${status}`);
    }
  });

  it("un refus 409 n'est pas rejoué", async () => {
    const { core, transport } = client([json(409, { reason: "CONFLIT_REESSAYER" })]);
    await core.get("/v1/events?after=0&limit=1");
    assert.equal(transport.requests.length, 1);
  });

  it("un POST n'est jamais rejoué tout seul", async () => {
    const { core, transport } = client([new Error("coupé"), json(204, "")]);
    const result = await core.post("/v1/session/join", { xuid: "1", gamertag: "a" });
    assert.equal(result.kind, "network");
    assert.equal(transport.requests.length, 1);
  });

  it("readRetries à 0 : un seul essai", async () => {
    const { core, transport } = client([new Error("coupé")], { readRetries: 0 });
    await core.get("/v1/events?after=0&limit=1");
    assert.equal(transport.requests.length, 1);
  });
});

describe("describeFailure", () => {
  it("décrit chaque cas sans en-tête ni corps", () => {
    assert.equal(describeFailure({ kind: "refused", status: 409, reason: "PAS_LE_PDG" }), "refusé PAS_LE_PDG");
    assert.equal(
      describeFailure({ kind: "refused", status: 409, reason: "PAS_ASSEZ_DE_DONAR", missing: 3 }),
      "refusé PAS_ASSEZ_DE_DONAR, il manque 3",
    );
    assert.equal(describeFailure({ kind: "error", status: 401, reason: "CLE_INVALIDE" }), "erreur 401 CLE_INVALIDE");
    assert.equal(
      describeFailure({ kind: "error", status: 200, reason: "REPONSE_INATTENDUE", detail: "join.balances : objet attendu" }),
      "erreur 200 REPONSE_INATTENDUE (join.balances : objet attendu)",
    );
    assert.equal(describeFailure({ kind: "network", message: "coupé" }), "réseau : coupé");
  });
});
