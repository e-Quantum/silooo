// Le relevé périodique des îles : la vue des joueurs mise en face du constructeur, sans rien calculer.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ILES } from "../src/monde/definitions";
import type { Xz } from "../src/monde/geometrie";
import { createWorldSync } from "../src/monde/synchro";
import type { Factory, Xuid } from "../src/protocol";
import type { CompanyView } from "../src/ui/etat";
import { balances, companyState, factory, recordingLogger } from "./helpers";

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
