// La ville entre le service et le monde : le relevé périodique qui la montre au constructeur, et le voyage depuis la carte
// qui mène le joueur devant la porte de la mairie. Des faux pour le constructeur dans la plupart des cas, le vrai
// constructeur sur un faux monde pour les parcours de bout en bout. Aucun @minecraft/*.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { config } from "../src/config";
import { createIslandBuilder, type IslandBuilder } from "../src/monde/constructeur";
import { ILES, VILLE } from "../src/monde/definitions";
import type { Pos, Xz } from "../src/monde/geometrie";
import { createWorldSync } from "../src/monde/synchro";
import { createCityTravel } from "../src/monde/voyage";
import { buildingKey, doorArrival, zoneKey } from "../src/monde/ville";
import type { CityState, CompanyState, Factory, Xuid } from "../src/protocol";
import type { CompanyView } from "../src/ui/etat";
import { balances, companyState, factory, recordingLogger } from "./helpers";
import { fakeMarks, fakeWorld, virtualTime } from "./monde-helpers";
import { cityBuilding, cityState, emptyCity, house } from "./ville-helpers";

const ORIGINE: Xz = { x: 1200, z: 600 };

// ---------------------------------------------------------------- le relevé

interface Joueur {
  readonly xuid: Xuid;
}

function vue(companyId: number, city: CityState | undefined, island?: Xz): CompanyView {
  return {
    state: companyState({
      companyId,
      factories: [factory({ factoryId: "carriere" })],
      ...(island === undefined ? {} : { island }),
      ...(city === undefined ? {} : { city }),
    }),
    balances: balances(),
    anchor: { donar: 0, ratePerHour: 0, atMs: 0 },
  };
}

function releve(vues: Record<Xuid, CompanyView>, joueurs: Joueur[]) {
  const log = recordingLogger();
  const cities: Array<{ companyId: number; origin: Xz; city: CityState }> = [];
  const events: string[] = [];
  const sync = createWorldSync<Joueur>({
    defs: ILES,
    players: () => joueurs,
    xuidOf: (joueur) => joueur.xuid,
    views: { get: (xuid) => vues[xuid] },
    builder: {
      sync: (companyId) => (events.push(`sync ${companyId}`), 0),
      syncCity: (companyId, origin, city) => (cities.push({ companyId, origin, city }), events.push(`city ${companyId}`), 0),
    },
    log,
  });
  return { sync, log, cities, events };
}

describe("le relevé montre la ville au constructeur", () => {
  it("avec l'origine de l'archipel, après les mini-îles de la même entreprise", () => {
    const city = cityState();
    const b = releve({ a: vue(1, city, ORIGINE) }, [{ xuid: "a" }]);
    b.sync.tick();
    assert.deepEqual(b.cities, [{ companyId: 1, origin: ORIGINE, city }]);
    assert.deepEqual(b.events, ["sync 1", "city 1"]);
  });

  it("une entreprise sans ville dans son état n'a pas d'appel de ville", () => {
    const b = releve({ a: vue(1, undefined, ORIGINE) }, [{ xuid: "a" }]);
    b.sync.tick();
    assert.deepEqual(b.cities, []);
    assert.deepEqual(b.events, ["sync 1"]);
  });

  it("une ville avant la première zone est transmise telle quelle : le constructeur n'a rien à poser, c'est à lui d'en juger", () => {
    const city = emptyCity();
    const b = releve({ a: vue(1, city, ORIGINE) }, [{ xuid: "a" }]);
    b.sync.tick();
    assert.deepEqual(b.cities.map((call) => call.city), [city]);
  });

  it("chaque joueur présent a sa ville, avec sa propre origine", () => {
    const b = releve(
      { a: vue(1, cityState(), ORIGINE), b: vue(2, emptyCity(), { x: 1800, z: 600 }), c: vue(3, undefined, { x: 0, z: 0 }) },
      [{ xuid: "a" }, { xuid: "b" }, { xuid: "c" }, { xuid: "absent" }],
    );
    b.sync.tick();
    assert.deepEqual(
      b.cities.map((call) => [call.companyId, call.origin]),
      [
        [1, ORIGINE],
        [2, { x: 1800, z: 600 }],
      ],
    );
  });

  it("sans origine, ni mini-île ni ville : un seul message au journal", () => {
    const b = releve({ a: vue(1, cityState()) }, [{ xuid: "a" }]);
    b.sync.tick();
    b.sync.tick();
    assert.deepEqual(b.events, []);
    assert.equal(b.log.infos.length, 1);
  });

  it("l'origine retenue sert aussi à la ville quand une réponse ne la redonne pas", () => {
    const b = releve({ a: vue(1, cityState()) }, [{ xuid: "a" }]);
    b.sync.remember(1, ORIGINE);
    b.sync.tick();
    assert.deepEqual(b.cities.map((call) => call.origin), [ORIGINE]);
  });
});

describe("le relevé et le vrai constructeur", () => {
  function complet(vues: Record<Xuid, CompanyView>) {
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
    return { temps, monde, marques, sync, log };
  }

  it("la ville grandit au rythme du service : une zone et une mairie d'abord, un achat de zone et une maison ensuite", async () => {
    const vues: Record<Xuid, CompanyView> = { a: vue(1, cityState(), ORIGINE) };
    const b = complet(vues);
    b.sync.tick();
    await b.temps.advance(10);
    assert.deepEqual(Object.keys(b.marques.stored.get(1) ?? {}).sort(), ["carriere", "ville:b:1", "ville:zone:1"]);

    // Le joueur achète la zone 2 et pose une maison : le service rend la nouvelle ville, la vue est remplacée.
    vues["a"] = vue(1, cityState({ nextZone: { zoneIndex: 3, priceLingots: 9, mairieLevelRequired: 3, activeFactoriesRequired: 0 }, buildings: [cityBuilding(), house({ zoneIndex: 2, x: 256, z: 128 })] }), ORIGINE);
    await b.temps.advance(10);
    assert.deepEqual(Object.keys(b.marques.stored.get(1) ?? {}).sort(), ["carriere", "ville:b:1", "ville:zone:1"], "rien n'est refait avant le relevé");

    b.sync.tick();
    await b.temps.advance(10);
    assert.deepEqual(Object.keys(b.marques.stored.get(1) ?? {}).sort(), ["carriere", "ville:b:1", "ville:b:2", "ville:zone:1", "ville:zone:2"]);
    assert.equal(b.monde.count(ILES.materiaux.enseigne) >= 2, true);
  });

  it("un relevé de plus sur la même ville ne change rien au monde", async () => {
    const b = complet({ a: vue(1, cityState(), ORIGINE) });
    b.sync.tick();
    await b.temps.advance(10);
    const ops = b.monde.ops;
    b.sync.tick();
    b.sync.tick();
    await b.temps.advance(10);
    assert.equal(b.monde.ops, ops);
  });
});

// ---------------------------------------------------------------- le voyage

interface Voyageur {
  readonly name: string;
  online: boolean;
}

const alex = (): Voyageur => ({ name: "Alex", online: true });

interface BancVoyage {
  readonly ready?: boolean;
  readonly retenue?: Xz;
  readonly settings?: { readonly travelWaitSeconds: number };
}

function voyage(options: BancVoyage = {}) {
  const log = recordingLogger();
  const syncs: Array<{ companyId: number; origin: Xz; city: CityState }> = [];
  const attentes: Array<{ companyId: number; markKey: string; timeout: number; resoudre: (prete: boolean) => void }> = [];
  const teleports: Array<{ player: Voyageur; at: Pos; facing: Pos }> = [];
  const evenements: string[] = [];
  let refuse = false;

  const builder = {
    syncCity(companyId: number, origin: Xz, city: CityState): number {
      syncs.push({ companyId, origin, city });
      evenements.push("syncCity");
      return 0;
    },
    whenReady(companyId: number, markKey: string, timeout: number): Promise<boolean> {
      evenements.push("attente");
      return new Promise<boolean>((resolve) => {
        attentes.push({ companyId, markKey, timeout, resoudre: resolve });
        if (options.ready !== undefined) resolve(options.ready);
      });
    },
  } satisfies Pick<IslandBuilder, "syncCity" | "whenReady">;

  const world = {
    isOnline: (joueur: Voyageur): boolean => joueur.online,
    locate: (): Pos | undefined => undefined,
    teleport(player: Voyageur, at: Pos, facing: Pos): void {
      if (refuse) throw new Error("téléportation refusée");
      teleports.push({ player, at, facing });
      evenements.push("téléportation");
    },
  };

  const travel = createCityTravel<Voyageur>({
    defs: ILES,
    ville: VILLE,
    builder,
    world,
    originOf: () => options.retenue,
    log,
    ...(options.settings === undefined ? {} : { settings: options.settings }),
  });
  return { travel, log, syncs, attentes, teleports, evenements, refuserLaTeleportation: () => void (refuse = true) };
}

const etat = (overrides: Partial<CompanyState> = {}): CompanyState =>
  companyState({ companyId: 7, island: ORIGINE, city: cityState(), ...overrides });

function etatSansOrigine(): CompanyState {
  const { island: _island, ...sans } = etat();
  return sans;
}

function etatSansVille(): CompanyState {
  const { city: _city, ...sans } = etat();
  return sans;
}

describe("voyage vers la mairie", () => {
  it("la mairie est prête : le joueur est devant sa porte, le regard sur elle", async () => {
    const b = voyage({ ready: true });
    const joueur = alex();
    assert.equal(await b.travel(joueur, etat()), "arrived");
    const arrivee = doorArrival(ORIGINE, ILES, VILLE, cityBuilding());
    assert.deepEqual(b.teleports, [{ player: joueur, at: arrivee.position, facing: arrivee.facing }]);
    assert.match(b.log.infos.join("\n"), /voyage de Alex : à la mairie/);
  });

  it("la porte suit la rotation de la mairie", async () => {
    const sud = voyage({ ready: true });
    const ouest = voyage({ ready: true });
    await sud.travel(alex(), etat());
    await ouest.travel(alex(), etat({ city: cityState({ buildings: [cityBuilding({ rotation: 90 })] }) }));
    assert.notDeepEqual(sud.teleports[0]?.at, ouest.teleports[0]?.at);
    assert.deepEqual(ouest.teleports[0]?.at, doorArrival(ORIGINE, ILES, VILLE, cityBuilding({ rotation: 90 })).position);
  });

  it("fait poser la ville que dit le service avant d'attendre la mairie, puis téléporte", async () => {
    const b = voyage({ ready: true });
    const city = cityState({ buildings: [house(), cityBuilding({ buildingId: 4 })] });
    await b.travel(alex(), etat({ city }));
    assert.deepEqual(b.syncs, [{ companyId: 7, origin: ORIGINE, city }]);
    assert.deepEqual(b.evenements, ["syncCity", "attente", "téléportation"]);
    assert.deepEqual(
      b.attentes.map((a) => [a.companyId, a.markKey]),
      [[7, buildingKey(4)]],
    );
  });

  it("attend la mairie le temps réglé, et ne téléporte qu'une fois qu'elle est posée", async () => {
    const b = voyage();
    const trajet = b.travel(alex(), etat());
    await Promise.resolve();
    assert.deepEqual(
      b.attentes.map((a) => [a.companyId, a.markKey, a.timeout]),
      [[7, buildingKey(1), config.world.travelWaitSeconds]],
    );
    assert.deepEqual(b.teleports, []);
    b.attentes[0]?.resoudre(true);
    assert.equal(await trajet, "arrived");
    assert.equal(b.teleports.length, 1);
  });

  it("le délai d'attente est celui des réglages passés", async () => {
    const b = voyage({ ready: true, settings: { travelWaitSeconds: 3 } });
    await b.travel(alex(), etat());
    assert.equal(b.attentes[0]?.timeout, 3);
  });

  it("la mairie pas prête dans le délai : le joueur reste où il est, la raison est journalisée", async () => {
    const b = voyage({ ready: false });
    assert.equal(await b.travel(alex(), etat()), "waiting");
    assert.deepEqual(b.teleports, []);
    assert.match(b.log.warns.join("\n"), /voyage de Alex : la mairie n'est pas prête après \d+ s/);
  });

  it("le joueur est parti pendant l'attente : personne à téléporter", async () => {
    const b = voyage();
    const joueur = alex();
    const trajet = b.travel(joueur, etat());
    await Promise.resolve();
    joueur.online = false;
    b.attentes[0]?.resoudre(true);
    assert.equal(await trajet, "gone");
    assert.deepEqual(b.teleports, []);
  });

  it("sans ville dans l'état, ou sans mairie, il n'y a nulle part où aller et rien n'est posé", async () => {
    const sansVille = voyage({ ready: true });
    assert.equal(await sansVille.travel(alex(), etatSansVille()), "unavailable");
    const sansMairie = voyage({ ready: true });
    assert.equal(await sansMairie.travel(alex(), etat({ city: cityState({ buildings: [house()] }) })), "unavailable");
    const avantLaZone = voyage({ ready: true });
    assert.equal(await avantLaZone.travel(alex(), etat({ city: emptyCity() })), "unavailable");
    for (const b of [sansVille, sansMairie, avantLaZone]) {
      assert.deepEqual(b.syncs, []);
      assert.deepEqual(b.teleports, []);
      assert.match(b.log.infos.join("\n"), /l'entreprise n'a pas de mairie posée/);
    }
  });

  it("l'origine vient de l'état du service ; à défaut, de la dernière retenue ; sinon nulle part", async () => {
    const retenue: Xz = { x: 2400, z: 1200 };
    const b = voyage({ ready: true, retenue });
    assert.equal(await b.travel(alex(), etatSansOrigine()), "arrived");
    assert.deepEqual(b.syncs[0]?.origin, retenue);
    assert.deepEqual(b.teleports[0]?.at, doorArrival(retenue, ILES, VILLE, cityBuilding()).position);
    await b.travel(alex(), etat());
    assert.deepEqual(b.syncs[1]?.origin, ORIGINE);

    const rien = voyage({ ready: true });
    assert.equal(await rien.travel(alex(), etatSansOrigine()), "unavailable");
    assert.deepEqual(rien.syncs, []);
    assert.match(rien.log.infos.join("\n"), /le service ne donne pas d'origine d'île/);
  });

  it("une téléportation refusée par le monde remonte telle quelle : le menu la journalise", async () => {
    const b = voyage({ ready: true });
    b.refuserLaTeleportation();
    await assert.rejects(b.travel(alex(), etat()), /téléportation refusée/);
  });
});

describe("voyage vers la mairie avec le vrai constructeur", () => {
  it("la plateforme et la mairie se posent, puis le joueur arrive devant une porte ouverte", async () => {
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
    const teleports: Array<{ at: Pos; facing: Pos }> = [];
    const trajet = createCityTravel<Voyageur>({
      defs: ILES,
      ville: VILLE,
      builder: constructeur,
      world: {
        isOnline: () => true,
        locate: () => undefined,
        teleport: (_player, at, facing) => void teleports.push({ at, facing }),
      },
      originOf: () => undefined,
      log,
      settings: { travelWaitSeconds: 10 },
    });

    let issue: string | undefined;
    void trajet(alex(), etat()).then((outcome) => {
      issue = outcome;
    });
    await temps.advance(0.06);
    assert.equal(issue, undefined, "la plateforme n'est pas finie");
    assert.equal(teleports.length, 0);
    await temps.advance(5);
    assert.equal(issue, "arrived");
    assert.deepEqual(Object.keys(marques.stored.get(7) ?? {}).sort(), [buildingKey(1), zoneKey(1)]);

    const arrivee = teleports[0];
    assert.ok(arrivee !== undefined);
    const x = Math.floor(arrivee.at.x);
    const z = Math.floor(arrivee.at.z);
    assert.equal(monde.blockAt(x, 101, z), undefined, "pieds libres");
    assert.equal(monde.blockAt(x, 102, z), undefined, "tête libre");
    assert.equal(monde.blockAt(x, 100, z), ILES.materiaux.herbe, "sur la plateforme");
  });

  it("la mairie trop longue à poser : le joueur reste où il est", async () => {
    const temps = virtualTime();
    const monde = fakeWorld(temps.clock);
    monde.failLoads = 100;
    const log = recordingLogger();
    const constructeur = createIslandBuilder({
      defs: ILES,
      writer: monde,
      marks: fakeMarks(),
      scheduler: temps.scheduler,
      clock: temps.clock,
      log,
      settings: config.world,
    });
    const teleports: Pos[] = [];
    const trajet = createCityTravel<Voyageur>({
      defs: ILES,
      ville: VILLE,
      builder: constructeur,
      world: { isOnline: () => true, locate: () => undefined, teleport: (_player, at) => void teleports.push(at) },
      originOf: () => undefined,
      log,
      settings: { travelWaitSeconds: 4 },
    });
    let issue: string | undefined;
    void trajet(alex(), etat()).then((outcome) => {
      issue = outcome;
    });
    await temps.advance(5);
    assert.equal(issue, "waiting");
    assert.deepEqual(teleports, []);
  });
});

// Une usine de plus dans l'état ne change rien au voyage de la ville : il ne lit que la ville.
describe("le voyage de la ville ne dépend pas des usines", () => {
  it("les usines de l'état ne sont ni posées ni attendues", async () => {
    const b = voyage({ ready: true });
    const usines: Factory[] = [factory({ factoryId: "carriere" }), factory({ factoryId: "scierie" })];
    await b.travel(alex(), etat({ factories: usines }));
    assert.equal(b.syncs.length, 1);
    assert.equal(b.attentes.length, 1);
  });
});
