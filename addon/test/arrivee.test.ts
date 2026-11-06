// L'arrivée d'un joueur : île de départ posée, téléportation une fois, Oooscar, cinématique au premier passage,
// et le moment où le joueur est « occupé » (HUD silencieux, boussole en attente, menu fermé).

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { config } from "../src/config";
import { createArrivals, inBuildOrder, type ArrivalDeps, type ArrivalWorld, type Arrivals } from "../src/monde/arrivee";
import type { CinematicOutcome, CinematicRunner, Timeline } from "../src/monde/cinematique";
import type { IslandBuilder } from "../src/monde/constructeur";
import { ILES } from "../src/monde/definitions";
import { arrivalOf, type Pos, type Xz } from "../src/monde/geometrie";
import type { OooscarManager } from "../src/monde/oooscar";
import type { Factory, JoinResponse } from "../src/protocol";
import { CINEMATIQUE_TEXTES } from "../src/ui/textes";
import { companyState, factory, joinResponse, recordingLogger } from "./helpers";
import { virtualTime } from "./monde-helpers";

const ORIGINE: Xz = { x: 1200, z: 600 };
const TEXTES = { speaker: CINEMATIQUE_TEXTES.qui, plans: CINEMATIQUE_TEXTES.plans };
const ARRIVEE = arrivalOf(ORIGINE, ILES);
assert.ok(ARRIVEE !== undefined);

interface Joueur {
  readonly name: string;
  online: boolean;
}

const alex = (): Joueur => ({ name: "Alex", online: true });
const cle = (joueur: Joueur): string => joueur.name.toLowerCase();

function reponse(options: { firstJoin?: boolean; island?: Xz | undefined; factories?: Factory[] } = {}): JoinResponse {
  const { firstJoin = true, factories = [factory({ factoryId: "scierie" }), factory({ factoryId: "carriere" })] } = options;
  const island = "island" in options ? options.island : ORIGINE;
  return joinResponse({
    firstJoin,
    state: companyState({ factories, ...(island === undefined ? {} : { island }) }),
  });
}

function banc(introduction?: { seen(player: Joueur): boolean; complete(player: Joueur): void }, withCinematicArea?: ArrivalDeps<Joueur>["withCinematicArea"]) {
  const temps = virtualTime();
  const log = recordingLogger();
  const evenements: string[] = [];

  // Le constructeur : `whenReady` rend des promesses que le test résout à la main.
  const attentes: Array<{ companyId: number; factoryId: string; timeout: number; resoudre: (prete: boolean) => void }> = [];
  const syncs: Array<{ companyId: number; origin: Xz; factories: readonly Factory[] }> = [];
  const constructeur = {
    syncLeve: false as boolean,
    sync(companyId: number, origin: Xz, factories: readonly Factory[]): number {
      if (constructeur.syncLeve) throw new Error("constructeur en panne");
      syncs.push({ companyId, origin, factories });
      evenements.push("sync");
      return factories.length;
    },
    whenReady(companyId: number, factoryId: string, timeout: number): Promise<boolean> {
      return new Promise<boolean>((resolve) => attentes.push({ companyId, factoryId, timeout, resoudre: resolve }));
    },
  } satisfies Pick<IslandBuilder, "sync" | "whenReady"> & { syncLeve: boolean };

  const oooscar = {
    refus: 0,
    essais: [] as Array<{ key: string; at: Pos; facing: Pos; seconde: number }>,
    retires: [] as string[],
    spawnFor(key: string, at: Pos, facing: Pos): boolean {
      oooscar.essais.push({ key, at, facing, seconde: temps.elapsed() });
      if (oooscar.refus > 0) {
        oooscar.refus -= 1;
        return false;
      }
      evenements.push("oooscar");
      return true;
    },
    removeFor(key: string): boolean {
      oooscar.retires.push(key);
      return true;
    },
  } satisfies Pick<OooscarManager, "spawnFor" | "removeFor"> & Record<string, unknown>;

  const lecteur = {
    departs: [] as Array<{ key: string; player: Joueur; timeline: Timeline; finir: (issue: CinematicOutcome) => void }>,
    annules: [] as string[],
    start(key: string, player: Joueur, timeline: Timeline): Promise<CinematicOutcome> {
      evenements.push("cinématique");
      return new Promise<CinematicOutcome>((resolve) => lecteur.departs.push({ key, player, timeline, finir: resolve }));
    },
    cancel(key: string): boolean {
      lecteur.annules.push(key);
      return true;
    },
  } satisfies Pick<CinematicRunner<Joueur>, "start" | "cancel"> & Record<string, unknown>;

  const monde = {
    teleportLeve: false as boolean,
    teleports: [] as Array<{ player: Joueur; at: Pos; facing: Pos }>,
    isOnline: (joueur: Joueur): boolean => joueur.online,
    locate: (): Pos | undefined => undefined,
    teleport(player: Joueur, at: Pos, facing: Pos): void {
      if (monde.teleportLeve) throw new Error("téléportation refusée");
      monde.teleports.push({ player, at, facing });
      evenements.push("téléportation");
    },
  } satisfies ArrivalWorld<Joueur> & Record<string, unknown>;

  const retenues: Array<{ companyId: number; origin: Xz }> = [];
  const arrivees: Arrivals<Joueur> = createArrivals<Joueur>({
    defs: ILES,
    builder: constructeur,
    oooscar,
    runner: lecteur,
    world: monde,
    keyOf: cle,
    texts: TEXTES,
    scheduler: temps.scheduler,
    log,
    remember: (companyId, origin) => void retenues.push({ companyId, origin }),
    ...(introduction === undefined ? {} : { introduction }),
    ...(withCinematicArea === undefined ? {} : { withCinematicArea }),
  });
  return { temps, log, evenements, attentes, syncs, constructeur, oooscar, lecteur, monde, retenues, arrivees };
}

describe("inBuildOrder", () => {
  it("met l'usine de départ d'abord et garde l'ordre des autres", () => {
    const usines = ["scierie", "ferme", "carriere", "fonderie"].map((factoryId) => factory({ factoryId }));
    assert.deepEqual(inBuildOrder(usines, "carriere").map((u) => u.factoryId), ["carriere", "scierie", "ferme", "fonderie"]);
    assert.deepEqual(usines.map((u) => u.factoryId), ["scierie", "ferme", "carriere", "fonderie"], "ne modifie pas la liste d'origine");
  });

  it("ne change rien quand l'usine de départ est absente", () => {
    const usines = ["scierie", "ferme"].map((factoryId) => factory({ factoryId }));
    assert.deepEqual(inBuildOrder(usines, "carriere"), usines);
  });
});

describe("sans origine d'île dans la réponse du service", () => {
  it("ne pose rien, ne déplace personne, ne joue rien, et le dit en une ligne", async () => {
    const b = banc();
    const joueur = alex();
    b.arrivees.arriving(joueur);
    await b.arrivees.joined(joueur, reponse({ island: undefined }));
    assert.equal(b.syncs.length, 0);
    assert.equal(b.monde.teleports.length, 0);
    assert.equal(b.lecteur.departs.length, 0);
    assert.equal(b.oooscar.essais.length, 0);
    assert.equal(b.log.infos.length, 1);
    assert.match(b.log.infos[0] ?? "", /pas d'origine d'île/);
    assert.equal(b.arrivees.busy(joueur), false);
  });
});

describe("un joueur qui revient", () => {
  it("fait poser ses îles, la Carrière d'abord, attend la sienne, puis le téléporte face au bâtiment", async () => {
    const b = banc();
    const joueur = alex();
    b.arrivees.arriving(joueur);
    const fin = b.arrivees.joined(joueur, reponse({ firstJoin: false }));
    await b.temps.flush();
    assert.equal(b.syncs.length, 1);
    assert.deepEqual(b.syncs[0]?.origin, ORIGINE);
    assert.deepEqual(b.syncs[0]?.factories.map((u) => u.factoryId), ["carriere", "scierie"]);
    assert.equal(b.attentes[0]?.factoryId, "carriere");
    assert.equal(b.attentes[0]?.timeout, config.world.arrivalWaitSeconds);
    assert.equal(b.monde.teleports.length, 0, "pas avant que l'île soit prête");
    b.attentes[0]?.resoudre(true);
    await fin;
    assert.equal(b.monde.teleports.length, 1);
    assert.deepEqual(b.monde.teleports[0]?.at, ARRIVEE.position);
    assert.deepEqual(b.monde.teleports[0]?.facing, ARRIVEE.facing);
    assert.equal(b.lecteur.departs.length, 0, "pas de cinématique pour un retour");
    assert.equal(b.arrivees.busy(joueur), false);
  });

  it("fait apparaître Oooscar près du point d'arrivée, une seconde après la téléportation", async () => {
    const b = banc();
    const joueur = alex();
    b.arrivees.arriving(joueur);
    const fin = b.arrivees.joined(joueur, reponse({ firstJoin: false }));
    await b.temps.flush();
    b.attentes[0]?.resoudre(true);
    await fin;
    assert.equal(b.oooscar.essais.length, 0);
    await b.temps.advance(config.world.oooscarSpawnRetrySeconds);
    assert.equal(b.oooscar.essais.length, 1);
    assert.deepEqual(b.oooscar.essais[0], { key: "alex", at: ARRIVEE.oooscar, facing: ARRIVEE.position, seconde: config.world.oooscarSpawnRetrySeconds });
    await b.temps.advance(30);
    assert.equal(b.oooscar.essais.length, 1, "une fois posée, plus d'essai");
  });

  it("note l'origine de l'archipel pour le relevé périodique", async () => {
    const b = banc();
    const joueur = alex();
    b.arrivees.arriving(joueur);
    const fin = b.arrivees.joined(joueur, reponse({ firstJoin: false }));
    await b.temps.flush();
    b.attentes[0]?.resoudre(true);
    await fin;
    assert.deepEqual(b.retenues, [{ companyId: 7, origin: ORIGINE }]);
  });
});

describe("le premier passage", () => {
  it("reprend une introduction jamais vue après un join antérieur, et ne l'acquitte qu'une fois lue", async () => {
    let seen = false;
    const b = banc({ seen: () => seen, complete: () => { seen = true; } });
    const joueur = alex();
    b.arrivees.arriving(joueur);
    const fin = b.arrivees.joined(joueur, reponse({ firstJoin: false }));
    await b.temps.flush();
    b.attentes[0]?.resoudre(true);
    await b.temps.flush();
    assert.equal(b.lecteur.departs.length, 1);
    assert.equal(seen, false);
    b.lecteur.departs[0]?.finir("aborted");
    await fin;
    assert.equal(seen, false, "une déconnexion ne valide pas l'introduction");
    b.arrivees.left(cle(joueur));
    b.arrivees.arriving(joueur);
    const reprise = b.arrivees.joined(joueur, reponse({ firstJoin: false }));
    await b.temps.flush();
    b.attentes[1]?.resoudre(true);
    await b.temps.flush();
    b.lecteur.departs[1]?.finir("finished");
    await reprise;
    assert.equal(seen, true);
  });
  it("joue la cinématique de vingt secondes après la téléportation, et le joueur est occupé jusqu'à sa fin", async () => {
    const b = banc();
    const joueur = alex();
    b.arrivees.arriving(joueur);
    assert.equal(b.arrivees.busy(joueur), true, "occupé dès l'apparition, avant la réponse de join");
    const fin = b.arrivees.joined(joueur, reponse({ firstJoin: true }));
    await b.temps.flush();
    assert.equal(b.arrivees.busy(joueur), true, "occupé pendant l'attente de l'île");
    b.attentes[0]?.resoudre(true);
    await b.temps.flush();
    assert.equal(b.lecteur.departs.length, 1);
    assert.equal(b.lecteur.departs[0]?.key, "alex");
    assert.equal(b.lecteur.departs[0]?.timeline.totalSeconds, 20);
    assert.equal(b.arrivees.busy(joueur), true, "occupé pendant la cinématique");
    assert.deepEqual(b.evenements.filter((e) => e !== "oooscar"), ["sync", "téléportation", "cinématique"]);
    b.lecteur.departs[0]?.finir("finished");
    await fin;
    assert.equal(b.arrivees.busy(joueur), false);
    assert.ok(b.log.infos.some((ligne) => ligne.includes("cinématique de Alex : finished")));
  });

  it("libère le joueur aussi quand la cinématique est passée", async () => {
    const b = banc();
    const joueur = alex();
    b.arrivees.arriving(joueur);
    const fin = b.arrivees.joined(joueur, reponse());
    await b.temps.flush();
    b.attentes[0]?.resoudre(true);
    await b.temps.flush();
    b.lecteur.departs[0]?.finir("skipped");
    await fin;
    assert.equal(b.arrivees.busy(joueur), false);
  });
});

describe("quand l'arrivée ne peut pas se faire", () => {
  it("île de départ pas prête au bout du délai : pas de téléportation, pas de cinématique, joueur libéré", async () => {
    const b = banc();
    const joueur = alex();
    b.arrivees.arriving(joueur);
    const fin = b.arrivees.joined(joueur, reponse());
    await b.temps.flush();
    b.attentes[0]?.resoudre(false);
    await fin;
    assert.equal(b.monde.teleports.length, 0);
    assert.equal(b.lecteur.departs.length, 0);
    assert.equal(b.arrivees.busy(joueur), false);
    assert.equal(b.log.warns.length, 1);
    assert.match(b.log.warns[0] ?? "", /pas prête/);
  });

  it("le joueur est parti pendant l'attente : personne à téléporter", async () => {
    const b = banc();
    const joueur = alex();
    b.arrivees.arriving(joueur);
    const fin = b.arrivees.joined(joueur, reponse());
    await b.temps.flush();
    joueur.online = false;
    b.attentes[0]?.resoudre(true);
    await fin;
    assert.equal(b.monde.teleports.length, 0);
    assert.equal(b.lecteur.departs.length, 0);
  });

  it("le départ annonce par `left` pendant l'attente de l'île empêche la téléportation", async () => {
    const b = banc();
    const joueur = alex();
    b.arrivees.arriving(joueur);
    const fin = b.arrivees.joined(joueur, reponse());
    await b.temps.flush();
    b.arrivees.left("alex");
    assert.equal(b.arrivees.busy(joueur), false);
    b.attentes[0]?.resoudre(true);
    await fin;
    assert.equal(b.monde.teleports.length, 0);
    assert.equal(b.lecteur.departs.length, 0);
  });

  it("le départ pendant la cinématique l'arrête et retire Oooscar", async () => {
    const b = banc();
    const joueur = alex();
    b.arrivees.arriving(joueur);
    const fin = b.arrivees.joined(joueur, reponse());
    await b.temps.flush();
    b.attentes[0]?.resoudre(true);
    await b.temps.flush();
    b.arrivees.left("alex");
    assert.deepEqual(b.lecteur.annules, ["alex"]);
    assert.deepEqual(b.oooscar.retires, ["alex"]);
    assert.equal(b.arrivees.busy(joueur), false);
    b.lecteur.departs[0]?.finir("aborted");
    await fin;
  });

  it("join en échec : le joueur n'est plus occupé", () => {
    const b = banc();
    const joueur = alex();
    b.arrivees.arriving(joueur);
    assert.equal(b.arrivees.busy(joueur), true);
    b.arrivees.failed(joueur);
    assert.equal(b.arrivees.busy(joueur), false);
  });

  it("pas d'usine de départ dans l'entreprise : les îles sont posées, personne n'est déplacé", async () => {
    const b = banc();
    const joueur = alex();
    b.arrivees.arriving(joueur);
    await b.arrivees.joined(joueur, reponse({ factories: [factory({ factoryId: "scierie" })] }));
    assert.equal(b.syncs.length, 1);
    assert.equal(b.monde.teleports.length, 0);
    assert.equal(b.arrivees.busy(joueur), false);
    assert.ok(b.log.infos.some((ligne) => ligne.includes("pas d'usine carriere")));
  });

  it("le constructeur ou la téléportation qui échouent sont journalisés, et le joueur est libéré", async () => {
    const b = banc();
    const joueur = alex();
    b.constructeur.syncLeve = true;
    b.arrivees.arriving(joueur);
    await b.arrivees.joined(joueur, reponse());
    assert.equal(b.log.errors.length, 1);
    assert.equal(b.arrivees.busy(joueur), false);

    const c = banc();
    c.monde.teleportLeve = true;
    c.arrivees.arriving(joueur);
    const fin = c.arrivees.joined(joueur, reponse());
    await c.temps.flush();
    c.attentes[0]?.resoudre(true);
    await fin;
    assert.equal(c.log.errors.length, 1);
    assert.equal(c.lecteur.departs.length, 0);
    assert.equal(c.arrivees.busy(joueur), false);
  });
});

describe("deux connexions du même joueur de suite", () => {
  it("la fin de la première arrivée ne libère pas la seconde et ne téléporte personne", async () => {
    const b = banc();
    const premier = alex();
    b.arrivees.arriving(premier);
    const finPremiere = b.arrivees.joined(premier, reponse({ firstJoin: false }));
    await b.temps.flush();
    b.arrivees.left("alex");

    const second = alex();
    b.arrivees.arriving(second);
    const finSeconde = b.arrivees.joined(second, reponse({ firstJoin: false }));
    await b.temps.flush();
    assert.equal(b.attentes.length, 2);

    b.attentes[0]?.resoudre(true);
    await finPremiere;
    assert.equal(b.monde.teleports.length, 0);
    assert.equal(b.arrivees.busy(second), true, "la seconde arrivée est toujours en cours");

    b.attentes[1]?.resoudre(true);
    await finSeconde;
    assert.equal(b.monde.teleports.length, 1);
    assert.equal(b.monde.teleports[0]?.player, second);
    assert.equal(b.arrivees.busy(second), false);
  });
});

describe("Oooscar à l'arrivée", () => {
  it("réessaie chaque seconde tant que la zone n'est pas chargée, puis s'arrête dès qu'elle apparaît", async () => {
    const b = banc();
    b.oooscar.refus = 2;
    const joueur = alex();
    b.arrivees.arriving(joueur);
    const fin = b.arrivees.joined(joueur, reponse({ firstJoin: false }));
    await b.temps.flush();
    b.attentes[0]?.resoudre(true);
    await fin;
    await b.temps.advance(20);
    assert.deepEqual(b.oooscar.essais.map((e) => e.seconde), [1, 2, 3]);
  });

  it("abandonne après le nombre d'essais prévu, avec une seule ligne de journal", async () => {
    const b = banc();
    b.oooscar.refus = 100;
    const joueur = alex();
    b.arrivees.arriving(joueur);
    const fin = b.arrivees.joined(joueur, reponse({ firstJoin: false }));
    await b.temps.flush();
    b.attentes[0]?.resoudre(true);
    await fin;
    await b.temps.advance(60);
    assert.equal(b.oooscar.essais.length, config.world.oooscarSpawnRetries + 1);
    assert.equal(b.log.warns.filter((ligne) => ligne.includes("jamais apparue")).length, 1);
  });

  it("n'apparaît pas pour un joueur parti entre-temps", async () => {
    const b = banc();
    const joueur = alex();
    b.arrivees.arriving(joueur);
    const fin = b.arrivees.joined(joueur, reponse({ firstJoin: false }));
    await b.temps.flush();
    b.attentes[0]?.resoudre(true);
    await fin;
    b.arrivees.left("alex");
    await b.temps.advance(30);
    assert.equal(b.oooscar.essais.length, 0);
  });
});

describe("une usine de départ sans île dans le fichier", () => {
  it("le dit et ne déplace personne", async () => {
    const temps = virtualTime();
    const log = recordingLogger();
    const monde: ArrivalWorld<Joueur> = { isOnline: () => true, locate: () => undefined, teleport: () => assert.fail("pas de téléportation") };
    const arrivees = createArrivals<Joueur>({
      defs: { ...ILES, apparition: { ...ILES.apparition, usine: "ailleurs" } } as unknown as typeof ILES,
      builder: { sync: () => assert.fail("rien à poser"), whenReady: () => Promise.resolve(true) },
      oooscar: { spawnFor: () => true, removeFor: () => true },
      runner: { start: () => Promise.resolve("finished"), cancel: () => false },
      world: monde,
      keyOf: cle,
      texts: TEXTES,
      scheduler: temps.scheduler,
      log,
    });
    const joueur = alex();
    arrivees.arriving(joueur);
    await arrivees.joined(joueur, reponse());
    assert.equal(log.warns.length, 1);
    assert.equal(arrivees.busy(joueur), false);
  });
});


describe("revoir une arrivée et charger ses caméras", () => {
  for (const outcome of ["finished", "skipped", "aborted"] as const) {
    it(`garde la zone chargée pendant la relecture, puis la libère (${outcome})`, async () => {
      let loaded = false;
      let completed = 0;
      const b = banc({ seen: () => true, complete: () => { completed += 1; } }, async (timeline, run) => {
        assert.equal(timeline.totalSeconds, 20);
        loaded = true;
        try { return await run(); } finally { loaded = false; }
      });
      const joueur = alex();
      b.arrivees.arriving(joueur);
      const fin = b.arrivees.joined(joueur, reponse({ firstJoin: false }), true);
      b.attentes[0]?.resoudre(true);
      await b.temps.flush();
      assert.equal(loaded, true);
      assert.equal(b.arrivees.busy(joueur), true);
      assert.equal(b.lecteur.departs.length, 1, "une introduction déjà vue peut être rejouée");
      b.lecteur.departs[0]?.finir(outcome);
      await fin;
      assert.equal(loaded, false);
      assert.equal(b.arrivees.busy(joueur), false);
      assert.equal(completed, outcome === "aborted" ? 0 : 1);
    });
  }
  it("ne bloque pas le joueur si les chunks des caméras refusent de charger", async () => {
    const b = banc({ seen: () => false, complete: () => assert.fail("pas encore lue") }, async () => { throw Error("zone indisponible"); });
    const joueur = alex();
    b.arrivees.arriving(joueur);
    const fin = b.arrivees.joined(joueur, reponse());
    b.attentes[0]?.resoudre(true);
    await fin;
    assert.equal(b.arrivees.busy(joueur), false);
    assert.equal(b.lecteur.departs.length, 0);
    assert.match(b.log.errors[0] ?? "", /zone indisponible/);
  });
});
