// Le constructeur d'îles sur un faux monde et un temps virtuel : poser une fois, ne refaire que ce qui change,
// ne jamais figer le serveur, réessayer sans s'acharner.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { config } from "../src/config";
import { createIslandBuilder, markOf, type IslandBuilder } from "../src/monde/constructeur";
import { ILES } from "../src/monde/definitions";
import { islandCenter, usineIle, type Xz } from "../src/monde/geometrie";
import { planIsland } from "../src/monde/plan";
import type { Factory, FactoryState } from "../src/protocol";
import { factory } from "./helpers";
import { recordingLogger, type RecordingLogger } from "./helpers";
import { fakeMarks, fakeWorld, virtualTime, type FakeMarks, type FakeWorld, type VirtualTime } from "./monde-helpers";

const ORIGINE: Xz = { x: 0, z: 0 };
const AUTRE_ORIGINE: Xz = { x: 600, z: 0 };

interface Banc {
  readonly temps: VirtualTime;
  readonly monde: FakeWorld;
  readonly marques: FakeMarks;
  readonly log: RecordingLogger;
  readonly constructeur: IslandBuilder;
}

function banc(options: { marques?: FakeMarks; reglages?: Partial<typeof config.world> } = {}): Banc {
  const temps = virtualTime();
  const monde = fakeWorld(temps.clock);
  const marques = options.marques ?? fakeMarks();
  const log = recordingLogger();
  const constructeur = createIslandBuilder({
    defs: ILES,
    writer: monde,
    marks: marques,
    scheduler: temps.scheduler,
    clock: temps.clock,
    log,
    settings: { ...config.world, ...options.reglages },
  });
  return { temps, monde, marques, log, constructeur };
}

const usine = (id: string, state: FactoryState = "ACTIVE"): Factory => factory({ factoryId: id, state });

describe("poser une île", () => {
  it("pose la mini-île de l'usine, note la marque, et dit combien d'îles restent à poser", async () => {
    const b = banc();
    assert.equal(b.constructeur.sync(7, ORIGINE, [usine("carriere")]), 1);
    await b.temps.advance(5);
    assert.equal(b.constructeur.pending(), 0);
    assert.equal(b.marques.stored.get(7)?.["carriere"], "ACTIVE@0,0");
    const centre = islandCenter(ORIGINE, usineIle(ILES, "carriere") ?? { angle: 0 }, ILES);
    assert.equal(b.monde.blockAt(centre.x, ILES.hauteur, centre.z), "minecraft:gravel");
    assert.equal(b.monde.count(ILES.materiaux.enseigne), 1);
    assert.ok(b.log.infos.some((ligne) => ligne.includes("île 7/carriere posée")));
  });

  it("charge la zone avant de poser, la relâche après, et ne pose rien hors de la zone chargée", async () => {
    const b = banc();
    b.constructeur.sync(7, ORIGINE, [usine("carriere")]);
    await b.temps.advance(5);
    assert.equal(b.monde.areas.length, 1);
    assert.deepEqual(b.monde.areas[0], planIsland(ILES, usineIle(ILES, "carriere") ?? ILES.usines[0]!, ORIGINE, "ACTIVE").box);
    assert.ok(b.monde.ops > 0);
  });

  it("pose les îles une à une, dans l'ordre demandé, jamais deux zones ouvertes à la fois", async () => {
    const b = banc();
    b.constructeur.sync(7, ORIGINE, [usine("carriere"), usine("scierie", "EN_RUINE"), usine("ferme", "REMISE_EN_ETAT")]);
    await b.temps.advance(20);
    assert.equal(b.monde.areas.length, 3);
    assert.equal(b.monde.maxOpenAreas, 1);
    const ordre = b.log.infos.filter((ligne) => ligne.includes("posée")).map((ligne) => ligne.split(" ")[1]);
    assert.deepEqual(ordre, ["7/carriere", "7/scierie", "7/ferme"]);
    assert.deepEqual(b.marques.stored.get(7), { carriere: "ACTIVE@0,0", scierie: "EN_RUINE@0,0", ferme: "REMISE_EN_ETAT@0,0" });
  });

  it("sépare les opérations en lots avec une pause entre deux lots : le serveur ne se fige pas", async () => {
    const b = banc({ reglages: { opsPerBatch: 10 } });
    b.constructeur.sync(7, ORIGINE, [usine("carriere")]);
    await b.temps.flush();
    assert.equal(b.monde.ops, 10, "un seul lot tant que le temps n'avance pas");
    await b.temps.advance(config.world.batchPauseSeconds);
    assert.equal(b.monde.ops, 20);
    await b.temps.advance(config.world.batchPauseSeconds);
    assert.equal(b.monde.ops, 30);
    await b.temps.advance(30);
    assert.equal(b.constructeur.pending(), 0);
    const plan = planIsland(ILES, usineIle(ILES, "carriere") ?? ILES.usines[0]!, ORIGINE, "ACTIVE");
    assert.equal(b.monde.ops, plan.base.length + plan.state.length);
  });
});

describe("ne rien refaire", () => {
  it("ne repose pas une île déjà posée dans cet état", async () => {
    const b = banc();
    b.constructeur.sync(7, ORIGINE, [usine("carriere")]);
    await b.temps.advance(5);
    const ops = b.monde.ops;
    assert.equal(b.constructeur.sync(7, ORIGINE, [usine("carriere")]), 0);
    await b.temps.advance(5);
    assert.equal(b.monde.ops, ops);
    assert.equal(b.monde.areas.length, 1);
  });

  it("retrouve ses marques après un redémarrage du serveur : rien n'est reposé", async () => {
    const marques = fakeMarks({ 7: { carriere: markOf("ACTIVE", ORIGINE) } });
    const b = banc({ marques });
    assert.equal(b.constructeur.sync(7, ORIGINE, [usine("carriere")]), 0);
    await b.temps.advance(5);
    assert.equal(b.monde.ops, 0);
    assert.equal(b.monde.areas.length, 0);
  });

  it("lit les marques d'une entreprise une seule fois", async () => {
    const b = banc();
    b.constructeur.sync(7, ORIGINE, [usine("carriere")]);
    await b.temps.advance(5);
    b.constructeur.sync(7, ORIGINE, [usine("carriere"), usine("scierie")]);
    await b.temps.advance(5);
    assert.equal(b.marques.reads, 1);
  });

  it("garde les marques de chaque entreprise à part", async () => {
    const b = banc();
    b.constructeur.sync(7, ORIGINE, [usine("carriere")]);
    b.constructeur.sync(8, AUTRE_ORIGINE, [usine("carriere", "EN_RUINE")]);
    await b.temps.advance(10);
    assert.deepEqual(b.marques.stored.get(7), { carriere: "ACTIVE@0,0" });
    assert.deepEqual(b.marques.stored.get(8), { carriere: "EN_RUINE@600,0" });
  });

  it("une marque illisible (propriété vide) fait reposer l'île, sans exception", async () => {
    const marques = fakeMarks({ 7: {} });
    const b = banc({ marques });
    b.constructeur.sync(7, ORIGINE, [usine("carriere")]);
    await b.temps.advance(5);
    assert.equal(b.marques.stored.get(7)?.["carriere"], "ACTIVE@0,0");
  });
});

describe("changer d'état", () => {
  it("ne refait que le bâtiment : pas de plateforme, une nouvelle marque", async () => {
    const b = banc();
    b.constructeur.sync(7, ORIGINE, [usine("carriere", "EN_RUINE")]);
    await b.temps.advance(5);
    const avant = b.monde.ops;
    b.constructeur.sync(7, ORIGINE, [usine("carriere", "ACTIVE")]);
    await b.temps.advance(5);
    const plan = planIsland(ILES, usineIle(ILES, "carriere") ?? ILES.usines[0]!, ORIGINE, "ACTIVE");
    assert.equal(b.monde.ops - avant, plan.state.length);
    assert.equal(b.marques.stored.get(7)?.["carriere"], "ACTIVE@0,0");
    assert.equal(b.monde.count("silooo:broyeur"), 1, "la chaîne de production est posée");
    assert.equal(b.monde.count("silooo:convoyeur"), ILES.atelier.machines.filter((machine) => machine.role === "convoyeur").length);
    assert.ok(b.log.infos.filter((ligne) => ligne.includes("plateforme comprise")).length === 1, "la plateforme n'a été posée qu'une fois");
  });

  it("la dernière demande l'emporte quand l'état change pendant une pose", async () => {
    const b = banc({ reglages: { opsPerBatch: 10 } });
    b.constructeur.sync(7, ORIGINE, [usine("carriere", "EN_RUINE")]);
    await b.temps.flush();
    b.constructeur.sync(7, ORIGINE, [usine("carriere", "ACTIVE")]);
    await b.temps.advance(60);
    assert.equal(b.marques.stored.get(7)?.["carriere"], "ACTIVE@0,0");
    assert.equal(b.monde.count("silooo:broyeur"), 1);
    assert.equal(b.monde.count("silooo:bureau_administration"), 1);
    assert.equal(b.constructeur.pending(), 0);
  });

  it("une demande qui revient à l'état déjà posé annule la pose en attente", async () => {
    const b = banc();
    b.constructeur.sync(7, ORIGINE, [usine("carriere", "ACTIVE")]);
    await b.temps.advance(5);
    b.monde.failLoads = 1;
    b.constructeur.sync(7, ORIGINE, [usine("carriere", "EN_RUINE")]);
    await b.temps.flush();
    b.constructeur.sync(7, ORIGINE, [usine("carriere", "ACTIVE")]);
    assert.equal(b.constructeur.pending(), 0);
    await b.temps.advance(60);
    assert.equal(b.monde.areas.length, 1, "la zone n'a plus été rechargée");
  });

  it("une autre origine d'archipel refait aussi la plateforme", async () => {
    const b = banc();
    b.constructeur.sync(7, ORIGINE, [usine("carriere")]);
    await b.temps.advance(5);
    b.constructeur.sync(7, AUTRE_ORIGINE, [usine("carriere")]);
    await b.temps.advance(5);
    assert.equal(b.marques.stored.get(7)?.["carriere"], "ACTIVE@600,0");
    assert.equal(b.log.infos.filter((ligne) => ligne.includes("plateforme comprise")).length, 2);
    const centre = islandCenter(AUTRE_ORIGINE, usineIle(ILES, "carriere") ?? ILES.usines[0]!, ILES);
    assert.equal(b.monde.blockAt(centre.x, ILES.hauteur, centre.z), "minecraft:gravel");
  });
});

describe("quand le monde refuse", () => {
  it("réessaie après 2 s, puis 4 s, 8 s, 16 s, et jamais plus de 30 s d'attente", async () => {
    const b = banc();
    b.monde.failLoads = 6;
    b.constructeur.sync(7, ORIGINE, [usine("carriere")]);
    await b.temps.advance(300);
    const ecarts = b.monde.attemptTimes.slice(1, 7).map((t, i) => (t - (b.monde.attemptTimes[i] ?? 0)) / 1000);
    assert.deepEqual(ecarts, [2, 4, 8, 16, 30, 30]);
    assert.equal(b.monde.attemptTimes.length, 7, "le septième essai réussit");
    assert.equal(b.marques.stored.get(7)?.["carriere"], "ACTIVE@0,0");
  });

  it("dit l'échec une fois, puis le rétablissement, sans remplir le journal", async () => {
    const b = banc();
    b.monde.failLoads = 4;
    b.constructeur.sync(7, ORIGINE, [usine("carriere")]);
    await b.temps.advance(120);
    assert.equal(b.log.warns.length, 1);
    assert.ok(b.log.infos.some((ligne) => ligne.includes("rétabli après 4 échec(s)")));
    assert.deepEqual(b.log.errors, []);
  });

  it("ne bloque pas les autres îles : l'île en échec passe après, au prochain essai", async () => {
    const b = banc();
    b.monde.failLoads = 1;
    b.constructeur.sync(7, ORIGINE, [usine("carriere"), usine("scierie")]);
    await b.temps.advance(1);
    assert.equal(b.marques.stored.get(7)?.["scierie"], "ACTIVE@0,0");
    assert.equal(b.marques.stored.get(7)?.["carriere"], undefined);
    await b.temps.advance(5);
    assert.equal(b.marques.stored.get(7)?.["carriere"], "ACTIVE@0,0");
  });

  it("n'écrit pas de marque pour une île à moitié posée", async () => {
    const b = banc({ reglages: { opsPerBatch: 10 } });
    const writeAvant = b.marques.writes;
    b.constructeur.sync(7, ORIGINE, [usine("carriere")]);
    await b.temps.flush();
    assert.equal(b.marques.writes, writeAvant);
    await b.temps.advance(30);
    assert.equal(b.marques.writes, writeAvant + 1);
  });
});

describe("whenReady", () => {
  it("répond tout de suite pour une île déjà posée", async () => {
    const b = banc({ marques: fakeMarks({ 7: { carriere: markOf("ACTIVE", ORIGINE) } }) });
    assert.equal(await b.constructeur.whenReady(7, "carriere", 30), true);
  });

  it("attend la fin de la pose, puis répond vrai", async () => {
    const b = banc();
    b.constructeur.sync(7, ORIGINE, [usine("carriere")]);
    const prete = b.constructeur.whenReady(7, "carriere", 30);
    let resolue = false;
    void prete.then(() => void (resolue = true));
    await b.temps.flush();
    assert.equal(resolue, false, "la pose dure plusieurs lots");
    await b.temps.advance(10);
    assert.equal(await prete, true);
  });

  it("répond faux au bout du délai quand le monde ne charge jamais la zone", async () => {
    const b = banc();
    b.monde.failLoads = 1000;
    b.constructeur.sync(7, ORIGINE, [usine("carriere")]);
    const prete = b.constructeur.whenReady(7, "carriere", 30);
    await b.temps.advance(29);
    let resolue = false;
    void prete.then(() => void (resolue = true));
    await b.temps.flush();
    assert.equal(resolue, false);
    await b.temps.advance(2);
    assert.equal(await prete, false);
  });

  it("répond faux pour une île qu'on n'a jamais demandée", async () => {
    const b = banc();
    const prete = b.constructeur.whenReady(7, "carriere", 5);
    await b.temps.advance(6);
    assert.equal(await prete, false);
  });
});

describe("usines inconnues", () => {
  it("une usine absente de iles.json n'est pas posée, la remarque n'est faite qu'une fois, les autres sont posées", async () => {
    const b = banc();
    b.constructeur.sync(7, ORIGINE, [usine("usine-du-futur"), usine("carriere")]);
    b.constructeur.sync(7, ORIGINE, [usine("usine-du-futur"), usine("carriere")]);
    await b.temps.advance(5);
    assert.equal(b.log.warns.filter((ligne) => ligne.includes("usine-du-futur")).length, 1);
    assert.deepEqual(b.marques.stored.get(7), { carriere: "ACTIVE@0,0" });
  });
});
