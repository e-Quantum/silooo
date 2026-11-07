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

// ---------------------------------------------------------------- v2 : paliers, accueil, vitrine, rappels

import { actionChoisie, construireAccueil, montrerAccueil, type EtatAccueil } from "../src/attente/accueil";
import { ATTENTE, nouveauPalier, palierDe } from "../src/attente/regles";
import { ACCUEIL_TEXTES } from "../src/attente/textes";
import { creerRappels, lignesClassement, texteHologramme } from "../src/attente/vitrine";
import type { LobbyBoard } from "../src/protocol";

const TABLEAU: LobbyBoard = {
  allTime: [
    { rank: 1, gamertag: "Ana", score: 80 },
    { rank: 2, gamertag: "Bob", score: 40 },
    { rank: 2, gamertag: "Bob", score: 40 },
  ],
  week: [
    { rank: 1, gamertag: "Ana", score: 50 },
    { rank: 2, gamertag: "Cid", score: 20 },
    { rank: 3, gamertag: "Dan", score: 10 },
    { rank: 4, gamertag: "Moi", score: 5 },
  ],
  pioneers: 12,
  news: [{ title: "Les plans du jeu sont prêts", date: "2026-11-05" }],
};

it("les paliers : le dernier seuil atteint s'applique, et seul le passage d'un seuil au-delà de 0 compte", () => {
  assert.equal(palierDe(0).des, 0);
  assert.equal(palierDe(29).des, 0);
  assert.equal(palierDe(30).des, 30);
  assert.equal(palierDe(500).des, ATTENTE.parcours.paliers.at(-1)!.des);
  assert.equal(nouveauPalier(29, 30), true);
  assert.equal(nouveauPalier(30, 31), false);
  assert.equal(nouveauPalier(0, 1), false);
});

it("au palier le plus dur, chaque bloc tiré reste atteignable", () => {
  for (let s = 1; s <= 10; s += 1) {
    const hasard = graine(100 + s);
    let pas: Pas = depart(2);
    for (let i = 0; i < 2000; i += 1) {
      const suivant = prochainBloc(pas, hasard, 200);
      const dy = suivant.bloc.y - pas.bloc.y;
      const dist = Math.abs(suivant.bloc.x - pas.bloc.x) + Math.abs(suivant.bloc.z - pas.bloc.z);
      assert.ok(dy >= -1 && dy <= 1);
      assert.ok(dist - 1 <= trouMax(dy as -1 | 0 | 1) + 1, `écart ${dist} pour une marche ${dy}`);
      pas = suivant;
    }
  }
});

function etat(partiel: Partial<EtatAccueil>): EtatAccueil {
  return { pseudo: "Moi", nouveau: true, testeur: false, tableau: TABLEAU, ...partiel };
}

it("l'accueil d'un nouveau venu : ce qu'est Silooo d'abord, Pionnier puis Sauter, les infos sous les boutons", () => {
  const a = construireAccueil(etat({}));
  assert.deepEqual(a.actions, ["pionnier", "sauter"]);
  assert.ok(a.spec.body.includes(ACCUEIL_TEXTES.cestQuoi));
  const kinds = a.spec.elements!.map((e) => e.kind);
  assert.ok(kinds.indexOf("button") < kinds.indexOf("label"), "les sections viennent sous les boutons");
  assert.deepEqual(a.spec.buttons, [ACCUEIL_TEXTES.pionnier, ACCUEIL_TEXTES.sauter]);
});

it("l'accueil d'un joueur qui revient : la dernière info d'abord", () => {
  const a = construireAccueil(etat({ nouveau: false }));
  assert.ok(a.spec.body.includes("Les plans du jeu sont prêts"));
  assert.equal(a.spec.elements![0]!.kind, "label");
});

it("l'accueil d'un joueur relié ne le relance plus, et seul un testeur voit « Rejoindre Silooo »", () => {
  const relie = construireAccueil(etat({ joueur: { linked: true, pioneer: true, best: 3, weekBest: 3 } }));
  assert.equal(relie.spec.buttons[0], ACCUEIL_TEXTES.dejaPionnier);
  const testeur = construireAccueil(etat({ testeur: true }));
  assert.deepEqual(testeur.actions, ["pionnier", "sauter", "rejoindre"]);
  assert.equal(actionChoisie(testeur, { kind: "selected", index: 2 }), "rejoindre");
  assert.equal(actionChoisie(testeur, { kind: "closed" }), undefined);
});

it("le classement de l'accueil montre le top 3, le rang du joueur au-delà, et les pionniers", () => {
  const a = construireAccueil(etat({}));
  const texte = a.spec.elements!.filter((e) => e.kind === "label").map((e) => (e as { text: string }).text).join("\n");
  assert.ok(texte.includes(ACCUEIL_TEXTES.tonRang(4)));
  assert.ok(texte.includes(ACCUEIL_TEXTES.pionniers(12)));
});

it("sans service, l'accueil reste utilisable", () => {
  const a = construireAccueil({ pseudo: "Moi", nouveau: true, testeur: false });
  assert.deepEqual(a.actions, ["pionnier", "sauter"]);
});

it("l'accueil réessaie quand le jeu est occupé, au plus le nombre d'essais", async () => {
  const a = construireAccueil(etat({}));
  let appels = 0;
  const choix = await montrerAccueil(a, {
    show: async () => (++appels < 3 ? { kind: "busy" } : { kind: "selected", index: 0 }),
    attendre: async () => undefined,
    essais: 5,
  });
  assert.equal(choix, "pionnier");
  assert.equal(appels, 3);
  appels = 0;
  const rien = await montrerAccueil(a, { show: async () => (appels++, { kind: "busy" }), attendre: async () => undefined, essais: 2 });
  assert.equal(rien, undefined);
  assert.equal(appels, 2);
});

it("la barre latérale : une ligne par nom, jamais deux fois le même joueur", () => {
  assert.deepEqual(lignesClassement(TABLEAU), [
    { nom: "Ana", score: 80 },
    { nom: "Bob", score: 40 },
  ]);
});

it("les hologrammes : liens fixes, actus et semaine tirées du tableau", () => {
  assert.ok(texteHologramme("liens", undefined).includes("silooo.com"));
  assert.ok(texteHologramme("liens", undefined).includes("discord.gg/ySnDWk4Dus"));
  assert.ok(texteHologramme("infos", TABLEAU).includes("Les plans du jeu sont prêts"));
  const semaine = texteHologramme("semaine", TABLEAU);
  assert.ok(semaine.includes("Ana") && semaine.includes("Moi"));
  assert.ok(semaine.includes(ACCUEIL_TEXTES.pionniers(12)));
});

it("les rappels : un par intervalle au plus, aucun pour un joueur relié", () => {
  const r = creerRappels();
  const pas = ATTENTE.rappels.tousLesPoints;
  const intervalle = ATTENTE.rappels.intervalleSecondes * 1000;
  assert.equal(r.auScore(pas, 0, true), false, "relié : jamais");
  assert.equal(r.auScore(pas - 1, 0, false), false, "pas un multiple");
  assert.equal(r.auScore(pas, 0, false), true);
  assert.equal(r.auScore(2 * pas, 1000, false), false, "trop tôt");
  assert.equal(r.auRecord(intervalle, false), true);
  assert.equal(r.auRecord(3 * intervalle, false), false, "le record n'est rappelé qu'une fois");
  assert.equal(r.auScore(3 * pas, 3 * intervalle, false), true);
});
