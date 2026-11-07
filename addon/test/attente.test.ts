import assert from "node:assert/strict";
import { it } from "node:test";
import { createCoreApi } from "../src/api";
import type { CoreClient } from "../src/http";
import { couloirLibre, depart, estTombe, prochainBloc, REGLES, surLeBloc, trouMax, type Pas } from "../src/attente/parcours";
import { ecranPionnier } from "../src/attente/pionnier";
import type { ActionBase } from "../src/protocol";
import type { ActionFormSpec, FormAnswer } from "../src/ui/formulaires";

/** Un hasard reproductible (mulberry32) : les mêmes tirages à chaque essai. */
function graine(n: number): () => number {
  let a = n >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

it("chaque bloc tiré est atteignable : trou de 1 à 4 selon la marche, pas de côté seulement sur les petits trous", () => {
  for (let s = 1; s <= 20; s += 1) {
    const hasard = graine(s);
    let pas: Pas = depart(1);
    for (let i = 0; i < 2000; i += 1) {
      const suivant = prochainBloc(pas, hasard);
      const dy = suivant.bloc.y - pas.bloc.y;
      assert.ok(dy >= -1 && dy <= 1, `marche ${dy}`);
      assert.ok(suivant.bloc.y >= REGLES.yMin && suivant.bloc.y <= REGLES.yMax, `hauteur ${suivant.bloc.y}`);
      const dx = Math.abs(suivant.bloc.x - pas.bloc.x);
      const dz = Math.abs(suivant.bloc.z - pas.bloc.z);
      const avant = Math.max(dx, dz);
      const cote = Math.min(dx, dz);
      const trou = avant - 1;
      assert.ok(trou >= 1 && trou <= trouMax(dy as -1 | 0 | 1), `trou ${trou} pour une marche ${dy}`);
      assert.ok(cote <= 1, `pas de côté ${cote}`);
      if (cote === 1) assert.ok(trou <= (dy === 1 ? 1 : 2), `pas de côté sur un trou de ${trou}`);
      pas = suivant;
    }
  }
});

it("les quatre écarts de 1 à 4 blocs sortent, et le parcours tourne parfois", () => {
  const hasard = graine(7);
  const trous = new Set<number>();
  const caps = new Set<string>();
  let pas: Pas = depart(1);
  for (let i = 0; i < 3000; i += 1) {
    const suivant = prochainBloc(pas, hasard);
    trous.add(Math.max(Math.abs(suivant.bloc.x - pas.bloc.x), Math.abs(suivant.bloc.z - pas.bloc.z)) - 1);
    caps.add(suivant.cap);
    pas = suivant;
  }
  assert.deepEqual([...trous].sort(), [1, 2, 3, 4]);
  assert.equal(caps.size, 4);
});

it("un saut compte quand les pieds reposent sur la face du bloc, pas en l'air ni à côté", () => {
  const bloc = { x: 10, y: 100, z: -3 };
  assert.equal(surLeBloc({ x: 10.4, y: 101, z: -2.6 }, bloc, true), true);
  assert.equal(surLeBloc({ x: 10.4, y: 101, z: -2.6 }, bloc, false), false);
  assert.equal(surLeBloc({ x: 11.1, y: 101, z: -2.6 }, bloc, true), false);
  assert.equal(surLeBloc({ x: 10.4, y: 102, z: -2.6 }, bloc, true), false);
});

it("la chute se constate quelques blocs sous le dernier bloc atteint", () => {
  const bloc = { x: 0, y: 110, z: 0 };
  assert.equal(estTombe({ x: 0, y: 107, z: 0 }, bloc), false);
  assert.equal(estTombe({ x: 0, y: 105.9, z: 0 }, bloc), true);
});

it("chaque joueur a son couloir, le premier libre", () => {
  assert.equal(couloirLibre([]), 1);
  assert.equal(couloirLibre([1, 2, 4]), 3);
  assert.notDeepEqual(depart(1).bloc, depart(2).bloc);
});

it("l'écran Pionnier montre le Discord, puis le code du service et la commande /lier", async () => {
  const appels: Array<{ action: ActionBase; gamertag: string }> = [];
  const ecrans: ActionFormSpec[] = [];
  const choix: FormAnswer[] = [{ kind: "selected", index: 0 }, { kind: "selected", index: 0 }, { kind: "closed" }];
  let n = 0;
  await ecranPionnier({
    actor: "7",
    gamertag: "Ana Bedrock",
    newRequestId: () => `id-${++n}`,
    api: {
      issueLobbyLinkCode: async (action, gamertag) => {
        appels.push({ action, gamertag });
        return appels.length === 1 ? { kind: "network", message: "coupure" } : { kind: "ok", status: 200, body: { code: "ABC234", expiresAt: "2026-11-07T12:10:00Z" } };
      },
    },
    show: async (spec) => { ecrans.push(spec); return choix.shift()!; },
  });
  assert.match(ecrans[0]!.body, /discord\.gg\/ySnDWk4Dus/);
  // La coupure rejoue la même intention.
  assert.deepEqual(appels.map((a) => a.action.requestId), ["id-1", "id-1"]);
  assert.equal(appels[0]!.gamertag, "Ana Bedrock");
  assert.match(ecrans[2]!.body, /ABC234/);
  assert.match(ecrans[2]!.body, /\/lier ABC234/);
});

it("sans réglages pour joindre le service, l'écran ne montre que l'invitation", async () => {
  const ecrans: ActionFormSpec[] = [];
  await ecranPionnier({ actor: "7", gamertag: "Ana", api: undefined, newRequestId: () => "x", show: async (spec) => { ecrans.push(spec); return { kind: "selected", index: 0 }; } });
  assert.equal(ecrans.length, 1);
  assert.equal(ecrans[0]!.buttons.length, 1);
});

it("issueLobbyLinkCode envoie le gamertag au contrat et refuse un gamertag vide sans appel", async () => {
  const envois: Array<{ path: string; body: unknown }> = [];
  const client: CoreClient = {
    get: async () => { throw new Error("pas de lecture"); },
    post: async (path, body) => { envois.push({ path, body }); return { kind: "ok", status: 200, body: { code: "ABC234", expiresAt: "2026-11-07T12:10:00Z" } }; },
    patch: async () => { throw new Error("non"); },
    put: async () => { throw new Error("non"); },
    delete: async () => { throw new Error("non"); },
  };
  const api = createCoreApi(client);
  const ok = await api.issueLobbyLinkCode({ requestId: "id-1", actor: "7" }, "  Ana Bedrock ");
  assert.equal(ok.kind, "ok");
  assert.deepEqual(envois, [{ path: "/v1/lobby/link-codes", body: { requestId: "id-1", actor: "7", gamertag: "Ana Bedrock" } }]);
  const vide = await api.issueLobbyLinkCode({ requestId: "id-2", actor: "7" }, "  ");
  assert.notEqual(vide.kind, "ok");
  assert.equal(envois.length, 1);
});
