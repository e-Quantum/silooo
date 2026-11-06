import assert from "node:assert/strict";
import { it } from "node:test";
import { playDiscordLink } from "../src/ui/discord";
import { parseDiscordLinkCode, type ActionBase } from "../src/protocol";
import type { ActionFormSpec, FormAnswer } from "../src/ui/formulaires";

it("un code affiché vient du service, le retour n'émet rien", async () => {
  const screens: ActionFormSpec[] = [];
  await playDiscordLink({ actor: "7", newRequestId: () => { throw new Error("pas d'intention"); }, api: { issueDiscordLinkCode: async () => { throw new Error("pas d'appel"); } }, show: async spec => { screens.push(spec); return { kind: "selected", index: 1 }; } });
  assert.equal(screens.length, 1);
  assert.match(screens[0]!.body, /\/lier/);
});

it("une coupure reprend le même requestId puis affiche le code reçu sans journaliser", async () => {
  const calls: ActionBase[] = [];
  const screens: ActionFormSpec[] = [];
  const choices: FormAnswer[] = [{ kind: "selected", index: 0 }, { kind: "selected", index: 0 }, { kind: "closed" }];
  let generated = 0;
  await playDiscordLink({ actor: "7", newRequestId: () => `id-${++generated}`, api: { issueDiscordLinkCode: async action => {
    calls.push(action);
    return calls.length === 1 ? { kind: "network", message: "coupure" } : { kind: "ok", status: 200, body: { code: "ABC234", expiresAt: "2026-11-06T12:10:00Z" } };
  } }, show: async spec => { screens.push(spec); return choices.shift()!; } });
  assert.deepEqual(calls, [{ actor: "7", requestId: "id-1" }, { actor: "7", requestId: "id-1" }]);
  assert.match(screens[2]!.body, /Code : ABC234/);
  assert.match(screens[2]!.body, /2026-11-06T12:10:00Z/);
});

it("rafraîchir un succès émet une nouvelle intention", async () => {
  const ids: string[] = [];
  let generated = 0, selected = 0;
  await playDiscordLink({ actor: "7", newRequestId: () => `id-${++generated}`, api: { issueDiscordLinkCode: async action => {
    ids.push(action.requestId); return { kind: "ok", status: 200, body: { code: "ABC234", expiresAt: "2026-11-06T12:10:00Z" } };
  } }, show: async () => ({ kind: "selected", index: selected++ < 2 ? 0 : 1 }) });
  assert.deepEqual(ids, ["id-1", "id-2"]);
});

it("le protocole refuse un code ou une expiration hors contrat", () => {
  assert.equal(parseDiscordLinkCode({ code: "ABC234", expiresAt: "2026-11-06T12:10:00Z" }).ok, true);
  assert.equal(parseDiscordLinkCode({ code: "012345", expiresAt: "2026-11-06T12:10:00Z" }).ok, false);
  assert.equal(parseDiscordLinkCode({ code: "ABC234", expiresAt: "demain" }).ok, false);
});
