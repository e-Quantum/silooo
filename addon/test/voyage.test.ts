// Le voyage depuis la carte : retrouver l'origine de l'archipel et le point d'arrivée de l'île choisie, faire poser
// l'île si besoin, l'attendre, puis téléporter le joueur. Des faux pour le constructeur et le monde : aucun @minecraft/*.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { config } from "../src/config";
import { inBuildOrder } from "../src/monde/arrivee";
import type { IslandBuilder } from "../src/monde/constructeur";
import { ILES } from "../src/monde/definitions";
import { arrivalAt, type Pos, type Xz } from "../src/monde/geometrie";
import { createTravel, type TravelSettings } from "../src/monde/voyage";
import type { CompanyState, Factory } from "../src/protocol";
import { companyState, factory, recordingLogger } from "./helpers";

const ORIGINE: Xz = { x: 1200, z: 600 };

interface Joueur {
  readonly name: string;
  online: boolean;
}

const alex = (): Joueur => ({ name: "Alex", online: true });

const USINES = [
  factory({ factoryId: "scierie" }),
  factory({ factoryId: "carriere" }),
  factory({ factoryId: "ferme", state: "EN_RUINE", level: 0 }),
];

interface BancOptions {
  /** Réponse immédiate de whenReady ; absent : la promesse reste en attente jusqu'à `resoudre`. */
  readonly ready?: boolean;
  readonly retenue?: Xz;
  readonly settings?: TravelSettings;
}

function banc(options: BancOptions = {}) {
  const log = recordingLogger();
  const syncs: Array<{ companyId: number; origin: Xz; factories: readonly Factory[] }> = [];
  const attentes: Array<{ companyId: number; factoryId: string; timeout: number; resoudre: (prete: boolean) => void }> = [];
  const teleports: Array<{ player: Joueur; at: Pos; facing: Pos }> = [];
  const evenements: string[] = [];
  let refuse = false;

  const builder = {
    sync(companyId: number, origin: Xz, factories: readonly Factory[]): number {
      syncs.push({ companyId, origin, factories });
      evenements.push("sync");
      return factories.length;
    },
    whenReady(companyId: number, factoryId: string, timeout: number): Promise<boolean> {
      evenements.push("attente");
      return new Promise<boolean>((resolve) => {
        attentes.push({ companyId, factoryId, timeout, resoudre: resolve });
        if (options.ready !== undefined) resolve(options.ready);
      });
    },
  } satisfies Pick<IslandBuilder, "sync" | "whenReady">;

  const world = {
    isOnline: (joueur: Joueur): boolean => joueur.online,
    locate: (): Pos | undefined => undefined,
    teleport(player: Joueur, at: Pos, facing: Pos): void {
      if (refuse) throw new Error("téléportation refusée");
      teleports.push({ player, at, facing });
      evenements.push("téléportation");
    },
  };

  const travel = createTravel<Joueur>({
    defs: ILES,
    builder,
    world,
    originOf: () => options.retenue,
    log,
    ...(options.settings === undefined ? {} : { settings: options.settings }),
  });
  return { travel, log, syncs, attentes, teleports, evenements, refuserLaTeleportation: () => void (refuse = true) };
}

const etat = (overrides: Partial<CompanyState> = {}): CompanyState =>
  companyState({ companyId: 7, island: ORIGINE, factories: USINES, ...overrides });

/** Un état dont le service ne donne pas l'origine de l'archipel. */
function etatSansOrigine(): CompanyState {
  const { island: _island, ...sans } = etat();
  return sans;
}

describe("voyage vers une île de la carte", () => {
  it("l'île est prête : le joueur est téléporté à son point d'arrivée, face au bâtiment", async () => {
    const b = banc({ ready: true });
    const joueur = alex();
    assert.equal(await b.travel(joueur, etat(), "scierie"), "arrived");
    const arrivee = arrivalAt(ORIGINE, ILES, "scierie");
    assert.ok(arrivee !== undefined);
    assert.deepEqual(b.teleports, [{ player: joueur, at: arrivee.position, facing: arrivee.facing }]);
  });

  it("chaque île a son point d'arrivée", async () => {
    const b = banc({ ready: true });
    await b.travel(alex(), etat(), "scierie");
    await b.travel(alex(), etat(), "carriere");
    assert.equal(b.teleports.length, 2);
    assert.notDeepEqual(b.teleports[0]?.at, b.teleports[1]?.at);
  });

  it("avant de téléporter, fait poser les îles que dit le service, l'usine de départ d'abord", async () => {
    const b = banc({ ready: true });
    await b.travel(alex(), etat(), "ferme");
    assert.deepEqual(b.syncs, [{ companyId: 7, origin: ORIGINE, factories: inBuildOrder(USINES, ILES.apparition.usine) }]);
    assert.deepEqual(b.syncs[0]?.factories.map((u) => u.factoryId), ["carriere", "scierie", "ferme"]);
    assert.deepEqual(b.evenements, ["sync", "attente", "téléportation"]);
  });

  it("attend l'île voulue le temps réglé, et ne téléporte qu'une fois qu'elle est posée", async () => {
    const b = banc();
    const voyage = b.travel(alex(), etat(), "scierie");
    await Promise.resolve();
    assert.deepEqual(
      b.attentes.map((a) => [a.companyId, a.factoryId, a.timeout]),
      [[7, "scierie", config.world.travelWaitSeconds]],
    );
    assert.deepEqual(b.teleports, []);
    b.attentes[0]?.resoudre(true);
    assert.equal(await voyage, "arrived");
    assert.equal(b.teleports.length, 1);
  });

  it("le délai d'attente est celui des réglages passés", async () => {
    const b = banc({ ready: true, settings: { travelWaitSeconds: 3 } });
    await b.travel(alex(), etat(), "scierie");
    assert.equal(b.attentes[0]?.timeout, 3);
  });

  it("une usine en ruine a son île : le joueur y va", async () => {
    const b = banc({ ready: true });
    assert.equal(await b.travel(alex(), etat(), "ferme"), "arrived");
    assert.equal(b.teleports.length, 1);
  });

  it("l'île pas prête dans le délai : le joueur reste où il est, la raison est journalisée", async () => {
    const b = banc({ ready: false });
    assert.equal(await b.travel(alex(), etat(), "scierie"), "waiting");
    assert.deepEqual(b.teleports, []);
    assert.match(b.log.warns.join("\n"), /voyage de Alex : l'île scierie n'est pas prête après \d+ s/);
  });

  it("le joueur est parti pendant l'attente : personne à téléporter, même si l'île est prête", async () => {
    const b = banc();
    const joueur = alex();
    const voyage = b.travel(joueur, etat(), "scierie");
    await Promise.resolve();
    joueur.online = false;
    b.attentes[0]?.resoudre(true);
    assert.equal(await voyage, "gone");
    assert.deepEqual(b.teleports, []);
  });

  it("l'origine vient de l'état du service ; à défaut, de la dernière retenue par le relevé", async () => {
    const retenue: Xz = { x: 2400, z: 1200 };
    const b = banc({ ready: true, retenue });
    assert.equal(await b.travel(alex(), etatSansOrigine(), "scierie"), "arrived");
    assert.deepEqual(b.syncs[0]?.origin, retenue);
    assert.deepEqual(b.teleports[0]?.at, arrivalAt(retenue, ILES, "scierie")?.position);
    // L'origine du service l'emporte sur celle qu'on avait retenue.
    await b.travel(alex(), etat(), "scierie");
    assert.deepEqual(b.syncs[1]?.origin, ORIGINE);
    assert.deepEqual(b.teleports[1]?.at, arrivalAt(ORIGINE, ILES, "scierie")?.position);
  });

  it("aucune origine nulle part : on ne sait pas où aller, rien n'est posé ni déplacé", async () => {
    const b = banc({ ready: true });
    assert.equal(await b.travel(alex(), etatSansOrigine(), "scierie"), "unavailable");
    assert.deepEqual(b.syncs, []);
    assert.deepEqual(b.teleports, []);
    assert.match(b.log.infos.join("\n"), /voyage de Alex : le service ne donne pas d'origine d'île/);
  });

  it("une usine que l'entreprise n'a pas : introuvable, rien n'est posé ni déplacé", async () => {
    const b = banc({ ready: true });
    assert.equal(await b.travel(alex(), etat(), "cristallerie"), "unavailable");
    assert.deepEqual(b.syncs, []);
    assert.deepEqual(b.teleports, []);
    assert.match(b.log.infos.join("\n"), /l'entreprise n'a pas d'usine cristallerie/);
  });

  it("une usine du service sans île dans iles.json : introuvable, journalisée en avertissement", async () => {
    const b = banc({ ready: true });
    const futur = etat({ factories: [...USINES, factory({ factoryId: "usine-du-futur" })] });
    assert.equal(await b.travel(alex(), futur, "usine-du-futur"), "unavailable");
    assert.deepEqual(b.syncs, []);
    assert.deepEqual(b.teleports, []);
    assert.match(b.log.warns.join("\n"), /l'usine usine-du-futur n'a pas d'île dans iles\.json/);
  });

  it("une téléportation refusée par le monde remonte telle quelle : le menu la journalise", async () => {
    const b = banc({ ready: true });
    b.refuserLaTeleportation();
    await assert.rejects(b.travel(alex(), etat(), "scierie"), /téléportation refusée/);
  });

  it("le délai par défaut est config.world.travelWaitSeconds, un nombre de secondes positif", () => {
    assert.ok(Number.isFinite(config.world.travelWaitSeconds) && config.world.travelWaitSeconds > 0);
  });
});
