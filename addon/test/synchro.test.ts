// Le relevé périodique des îles : la vue des joueurs mise en face du constructeur, sans rien calculer.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { config } from "../src/config";
import { createIslandBuilder } from "../src/monde/constructeur";
import { ILES } from "../src/monde/definitions";
import type { Xz } from "../src/monde/geometrie";
import { createWorldSync } from "../src/monde/synchro";
import type { Factory, Xuid } from "../src/protocol";
import type { CompanyView } from "../src/ui/etat";
import { balances, companyState, factory, recordingLogger } from "./helpers";
import { fakeMarks, fakeWorld, virtualTime } from "./monde-helpers";

interface Joueur {
  readonly xuid: Xuid;
}

const ORIGINE: Xz = { x: 1200, z: 600 };

function vue(companyId: number, factories: Factory[], island?: Xz): CompanyView {
  return {
    state: companyState({ companyId, factories, ...(island === undefined ? {} : { island }) }),
    balances: balances(),
    anchor: { donar: 0, ratePerHour: 0, atMs: 0 },
  };
}

function banc(vues: Record<Xuid, CompanyView>, joueurs: Joueur[]) {
  const log = recordingLogger();
  const syncs: Array<{ companyId: number; origin: Xz; ids: string[] }> = [];
  const reaps: Array<ReadonlySet<string>> = [];
  const sync = createWorldSync<Joueur>({
    defs: ILES,
    players: () => joueurs,
    xuidOf: (joueur) => joueur.xuid,
    views: { get: (xuid) => vues[xuid] },
    builder: { sync: (companyId, origin, factories) => (syncs.push({ companyId, origin, ids: factories.map((u) => u.factoryId) }), factories.length) },
    oooscar: { reap: (online) => (reaps.push(online), 0) },
    log,
  });
  return { sync, log, syncs, reaps };
}

describe("le relevé des îles", () => {
  it("montre au constructeur les usines de chaque joueur présent, la Carrière d'abord", () => {
    const b = banc(
      {
        a: vue(1, [factory({ factoryId: "scierie" }), factory({ factoryId: "carriere" })], ORIGINE),
        b: vue(2, [factory({ factoryId: "carriere" })], { x: 1800, z: 600 }),
      },
      [{ xuid: "a" }, { xuid: "b" }],
    );
    b.sync.tick();
    assert.deepEqual(b.syncs, [
      { companyId: 1, origin: ORIGINE, ids: ["carriere", "scierie"] },
      { companyId: 2, origin: { x: 1800, z: 600 }, ids: ["carriere"] },
    ]);
  });

  it("passe les joueurs dont la vue n'est pas encore là", () => {
    const b = banc({ a: vue(1, [factory()], ORIGINE) }, [{ xuid: "inconnu" }, { xuid: "a" }]);
    b.sync.tick();
    assert.equal(b.syncs.length, 1);
    assert.equal(b.log.all().length, 0);
  });

  it("ne dit qu'une fois qu'une entreprise n'a pas d'origine, et ne pose rien pour elle", () => {
    const b = banc({ a: vue(1, [factory()]) }, [{ xuid: "a" }]);
    b.sync.tick();
    b.sync.tick();
    b.sync.tick();
    assert.deepEqual(b.syncs, []);
    assert.equal(b.log.infos.length, 1);
    assert.match(b.log.infos[0] ?? "", /entreprise 1/);
  });

  it("retient la dernière origine connue quand une réponse ne la redonne pas", () => {
    const vues: Record<Xuid, CompanyView> = { a: vue(1, [factory()]) };
    const b = banc(vues, [{ xuid: "a" }]);
    b.sync.remember(1, ORIGINE);
    b.sync.tick();
    assert.deepEqual(b.syncs, [{ companyId: 1, origin: ORIGINE, ids: ["carriere"] }]);
    assert.deepEqual(b.log.all(), []);
  });

  it("une origine donnée par la vue l'emporte sur celle qu'on avait retenue", () => {
    const b = banc({ a: vue(1, [factory()], { x: 3000, z: 3000 }) }, [{ xuid: "a" }]);
    b.sync.remember(1, ORIGINE);
    b.sync.tick();
    assert.deepEqual(b.syncs[0]?.origin, { x: 3000, z: 3000 });
  });

  it("une origine retenue ensuite rend la parole : le message n'est plus attendu, la pose reprend", () => {
    const b = banc({ a: vue(1, [factory()]) }, [{ xuid: "a" }]);
    b.sync.tick();
    assert.equal(b.syncs.length, 0);
    b.sync.remember(1, ORIGINE);
    b.sync.tick();
    assert.equal(b.syncs.length, 1);
  });

  it("fait le ménage des Oooscar avec les joueurs présents, même quand personne n'a de vue", () => {
    const b = banc({}, [{ xuid: "a" }, { xuid: "b" }]);
    b.sync.tick();
    assert.equal(b.reaps.length, 1);
    assert.deepEqual([...(b.reaps[0] ?? [])].sort(), ["a", "b"]);
  });

  it("fonctionne sans gestionnaire d'Oooscar", () => {
    const log = recordingLogger();
    const sync = createWorldSync<Joueur>({
      defs: ILES,
      players: () => [{ xuid: "a" }],
      xuidOf: (joueur) => joueur.xuid,
      views: { get: () => undefined },
      builder: { sync: () => 0 },
      log,
    });
    assert.doesNotThrow(() => sync.tick());
  });
});

describe("l'origine retenue pour le voyage", () => {
  it("dit la dernière origine connue d'une entreprise, celle de la vue ou celle qu'on avait retenue", () => {
    const b = banc({ a: vue(1, [factory()], { x: 3000, z: 3000 }), b: vue(2, [factory()]) }, [{ xuid: "a" }, { xuid: "b" }]);
    assert.equal(b.sync.originOf(1), undefined);
    b.sync.remember(2, ORIGINE);
    b.sync.tick();
    assert.deepEqual(b.sync.originOf(1), { x: 3000, z: 3000 });
    assert.deepEqual(b.sync.originOf(2), ORIGINE);
    assert.equal(b.sync.originOf(3), undefined);
  });
});

describe("après un prestige", () => {
  /** Le relevé branché sur le vrai constructeur et un faux monde : on voit les îles changer de visage. */
  function banc2(vues: Record<Xuid, CompanyView>) {
    const temps = virtualTime();
    const monde = fakeWorld(temps.clock);
    const marques = fakeMarks();
    const log = recordingLogger();
    const constructeur = createIslandBuilder({
      defs: ILES,
      writer: monde,
      marks: marques,
      scheduler: temps.scheduler,
      clock: temps.clock,
      log,
      settings: config.world,
    });
    const sync = createWorldSync<Joueur>({
      defs: ILES,
      players: () => [{ xuid: "a" }],
      xuidOf: (joueur) => joueur.xuid,
      views: { get: (xuid) => vues[xuid] },
      builder: constructeur,
      log,
    });
    return { temps, marques, sync, log };
  }

  it("les usines passées en ruine par le service sont redessinées au relevé suivant, rien n'est refait avant", async () => {
    const vues: Record<Xuid, CompanyView> = {
      a: vue(1, [factory({ factoryId: "carriere" }), factory({ factoryId: "scierie" })], ORIGINE),
    };
    const b = banc2(vues);
    b.sync.tick();
    await b.temps.advance(10);
    assert.deepEqual(b.marques.stored.get(1), { carriere: "ACTIVE@1200,600", scierie: "ACTIVE@1200,600" });

    // Le prestige : le service renvoie les usines en ruine ; la vue est remplacée, le monde n'a pas encore bougé.
    vues["a"] = vue(
      1,
      [factory({ factoryId: "carriere", state: "EN_RUINE", level: 0 }), factory({ factoryId: "scierie", state: "EN_RUINE", level: 0 })],
      ORIGINE,
    );
    await b.temps.advance(10);
    assert.deepEqual(b.marques.stored.get(1), { carriere: "ACTIVE@1200,600", scierie: "ACTIVE@1200,600" });

    b.sync.tick();
    await b.temps.advance(10);
    assert.deepEqual(b.marques.stored.get(1), { carriere: "EN_RUINE@1200,600", scierie: "EN_RUINE@1200,600" });
  });
});
