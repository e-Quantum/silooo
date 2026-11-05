import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { config } from "../src/config";
import {
  giveMenuItem,
  registerMenuItem,
  type InventoryLike,
  type ItemUseEventLike,
  type MenuItemWorldLike,
  type SpawnEventOf,
} from "../src/ui/boussole";
import { fakeScheduler, recordingLogger } from "./helpers";

interface Item {
  readonly typeId: string;
}
interface Joueur {
  readonly name: string;
}

/** Un inventaire de neuf cases ; `contenu` donne le type de chaque case (undefined = vide). */
function inventaire(contenu: ReadonlyArray<string | undefined>): InventoryLike<Item> & { readonly ajouts: Item[] } {
  const cases: Array<Item | undefined> = contenu.map((typeId) => (typeId === undefined ? undefined : { typeId }));
  const ajouts: Item[] = [];
  return {
    ajouts,
    get size() {
      return cases.length;
    },
    get emptySlotsCount() {
      return cases.filter((c) => c === undefined).length;
    },
    getItem: (slot) => cases[slot],
    addItem(item) {
      ajouts.push(item);
      const libre = cases.indexOf(undefined);
      if (libre >= 0) cases[libre] = item;
      return undefined;
    },
  };
}

const ALEX: Joueur = { name: "Alex" };
const makeItem = (): Item => ({ typeId: config.ui.menuItem });

describe("giveMenuItem", () => {
  it("donne la boussole quand l'inventaire n'en a pas", () => {
    const inv = inventaire(["minecraft:apple", undefined]);
    const log = recordingLogger();
    assert.equal(giveMenuItem(ALEX, { inventoryOf: () => inv, makeItem, log }), "given");
    assert.deepEqual(inv.ajouts, [{ typeId: "minecraft:compass" }]);
  });

  it("n'en donne pas une seconde, où qu'elle soit rangée", () => {
    for (const place of [0, 4, 8]) {
      const contenu: Array<string | undefined> = Array.from({ length: 9 }, () => undefined);
      contenu[place] = config.ui.menuItem;
      const inv = inventaire(contenu);
      assert.equal(giveMenuItem(ALEX, { inventoryOf: () => inv, makeItem, log: recordingLogger() }), "already");
      assert.deepEqual(inv.ajouts, []);
    }
  });

  it("inventaire plein : rien n'est donné ni remplacé, le journal le dit", () => {
    const inv = inventaire(Array.from({ length: 9 }, () => "minecraft:stone"));
    const log = recordingLogger();
    assert.equal(giveMenuItem(ALEX, { inventoryOf: () => inv, makeItem, log }), "full");
    assert.deepEqual(inv.ajouts, []);
    assert.match(log.warns.join("\n"), /boussole de Alex : inventaire plein/);
  });

  it("inventaire illisible : rien n'est donné", () => {
    assert.equal(giveMenuItem(ALEX, { inventoryOf: () => undefined, makeItem, log: recordingLogger() }), "noInventory");
  });

  it("un autre objet réglé dans config.ui.menuItem est reconnu", () => {
    const inv = inventaire(["minecraft:clock"]);
    assert.equal(
      giveMenuItem(ALEX, { inventoryOf: () => inv, makeItem, log: recordingLogger(), itemId: "minecraft:clock" }),
      "already",
    );
  });
});

describe("registerMenuItem", () => {
  function monde() {
    const spawns: Array<(event: SpawnEventOf<Joueur>) => void> = [];
    const usages: Array<(event: ItemUseEventLike<Joueur, Item>) => void> = [];
    const world: MenuItemWorldLike<Joueur, Item> = {
      afterEvents: {
        playerSpawn: { subscribe: (callback) => void spawns.push(callback) },
        itemUse: { subscribe: (callback) => void usages.push(callback) },
      },
    };
    return { world, spawns, usages };
  }

  function setup(inv: InventoryLike<Item> | undefined, open: (player: Joueur) => void | Promise<void> = () => undefined) {
    const { world, spawns, usages } = monde();
    const scheduler = fakeScheduler();
    const log = recordingLogger();
    let fabriques = 0;
    registerMenuItem(world, {
      inventoryOf: () => inv,
      makeItem: () => {
        fabriques += 1;
        return makeItem();
      },
      open,
      scheduler,
      log,
    });
    return { spawns, usages, scheduler, log, fabriques: () => fabriques };
  }

  it("s'abonne une fois à l'arrivée et une fois à l'usage d'un objet", () => {
    const { spawns, usages } = setup(inventaire([undefined]));
    assert.equal(spawns.length, 1);
    assert.equal(usages.length, 1);
  });

  it("à l'arrivée, attend le délai réglé puis donne la boussole", () => {
    const inv = inventaire([undefined]);
    const { spawns, scheduler } = setup(inv);
    spawns[0]?.({ initialSpawn: true, player: ALEX });
    assert.deepEqual(inv.ajouts, []);
    assert.equal(scheduler.timeouts[0]?.seconds, config.ui.menuItemDelaySeconds);
    scheduler.runNextTimeout();
    assert.deepEqual(inv.ajouts, [{ typeId: "minecraft:compass" }]);
  });

  it("une réapparition (mort, changement de dimension) ne redonne rien", () => {
    const inv = inventaire([undefined]);
    const { spawns, scheduler } = setup(inv);
    spawns[0]?.({ initialSpawn: false, player: ALEX });
    assert.equal(scheduler.timeouts.length, 0);
  });

  it("un joueur qui a déjà sa boussole n'en reçoit pas une autre", () => {
    const inv = inventaire([config.ui.menuItem, undefined]);
    const { spawns, scheduler, fabriques } = setup(inv);
    spawns[0]?.({ initialSpawn: true, player: ALEX });
    scheduler.runNextTimeout();
    assert.deepEqual(inv.ajouts, []);
    assert.equal(fabriques(), 0);
  });

  it("un joueur parti avant la fin du délai : l'erreur est journalisée, rien ne remonte", () => {
    const { world, spawns } = monde();
    const scheduler = fakeScheduler();
    const log = recordingLogger();
    registerMenuItem(world, {
      inventoryOf: () => {
        throw new Error("joueur invalide");
      },
      makeItem,
      open: () => undefined,
      scheduler,
      log,
    });
    spawns[0]?.({ initialSpawn: true, player: ALEX });
    assert.doesNotThrow(() => scheduler.runNextTimeout());
    assert.match(log.infos.join("\n"), /boussole de Alex : Error: joueur invalide/);
  });

  it("utiliser la boussole ouvre le menu du joueur qui l'utilise", () => {
    const ouverts: string[] = [];
    const { usages } = setup(inventaire([undefined]), (player) => void ouverts.push(player.name));
    usages[0]?.({ itemStack: { typeId: "minecraft:compass" }, source: ALEX });
    assert.deepEqual(ouverts, ["Alex"]);
  });

  it("utiliser un autre objet n'ouvre rien", () => {
    const ouverts: string[] = [];
    const { usages } = setup(inventaire([undefined]), (player) => void ouverts.push(player.name));
    usages[0]?.({ itemStack: { typeId: "minecraft:apple" }, source: ALEX });
    assert.deepEqual(ouverts, []);
  });

  it("une erreur du menu (synchrone ou d'une promesse) ne sort pas vers le moteur de scripts", async () => {
    const sync = setup(inventaire([undefined]), () => {
      throw new Error("coupure");
    });
    assert.doesNotThrow(() => sync.usages[0]?.({ itemStack: { typeId: "minecraft:compass" }, source: ALEX }));
    assert.match(sync.log.errors.join("\n"), /menuItem\.use : Error: coupure/);

    const asynchrone = setup(inventaire([undefined]), () => Promise.reject(new Error("promesse rompue")));
    asynchrone.usages[0]?.({ itemStack: { typeId: "minecraft:compass" }, source: ALEX });
    await new Promise((resolve) => setImmediate(resolve));
    assert.match(asynchrone.log.errors.join("\n"), /menuItem\.use : Error: promesse rompue/);
  });
});

describe("la boussole attend la fin de l'arrivée", () => {
  function avecAttente(options: { occupe: () => boolean; step?: number; max?: number; inv?: InventoryLike<Item> | undefined }) {
    const spawns: Array<(event: SpawnEventOf<Joueur>) => void> = [];
    const world: MenuItemWorldLike<Joueur, Item> = {
      afterEvents: {
        playerSpawn: { subscribe: (callback) => void spawns.push(callback) },
        itemUse: { subscribe: () => undefined },
      },
    };
    const inv = "inv" in options ? options.inv : inventaire([undefined]);
    const scheduler = fakeScheduler();
    const log = recordingLogger();
    registerMenuItem(world, {
      inventoryOf: () => inv,
      makeItem,
      open: () => undefined,
      scheduler,
      log,
      waitWhile: options.occupe,
      ...(options.step === undefined ? {} : { waitStepSeconds: options.step }),
      ...(options.max === undefined ? {} : { waitMaxSeconds: options.max }),
    });
    spawns[0]?.({ initialSpawn: true, player: ALEX });
    return { inv, scheduler, log };
  }

  it("ne donne rien tant que le joueur est occupé, puis la donne dès qu'il est libre", () => {
    let occupe = true;
    const { inv, scheduler } = avecAttente({ occupe: () => occupe });
    assert.equal(scheduler.runNextTimeout(), config.ui.menuItemDelaySeconds);
    assert.equal(inv?.size, 1);
    assert.equal((inv as ReturnType<typeof inventaire>).ajouts.length, 0);
    assert.equal(scheduler.timeouts[0]?.seconds, config.ui.menuItemWaitStepSeconds, "elle revient voir au pas réglé");
    scheduler.runNextTimeout();
    assert.equal((inv as ReturnType<typeof inventaire>).ajouts.length, 0);
    occupe = false;
    scheduler.runNextTimeout();
    assert.equal((inv as ReturnType<typeof inventaire>).ajouts.length, 1);
    assert.equal(scheduler.timeouts.length, 0, "plus rien en attente");
  });

  it("n'attend pas au-delà du maximum : la boussole est donnée quand même, et le journal le dit", () => {
    const { inv, scheduler, log } = avecAttente({ occupe: () => true, step: 1, max: 3 });
    scheduler.runNextTimeout();
    let tours = 0;
    while (scheduler.timeouts.length > 0 && tours < 20) {
      scheduler.runNextTimeout();
      tours += 1;
    }
    assert.equal(tours, 3);
    assert.equal((inv as ReturnType<typeof inventaire>).ajouts.length, 1);
    assert.equal(log.warns.length, 1);
    assert.match(log.warns[0] ?? "", /toujours occupé après 3 s/);
  });

  it("un joueur libre dès le départ la reçoit au délai habituel, sans attente", () => {
    const { inv, scheduler } = avecAttente({ occupe: () => false });
    scheduler.runNextTimeout();
    assert.equal((inv as ReturnType<typeof inventaire>).ajouts.length, 1);
    assert.equal(scheduler.timeouts.length, 0);
  });

  it("un joueur parti pendant l'attente : une ligne de journal, aucune exception, plus de nouvelle attente", () => {
    let present = true;
    const spawns: Array<(event: SpawnEventOf<Joueur>) => void> = [];
    const world: MenuItemWorldLike<Joueur, Item> = {
      afterEvents: {
        playerSpawn: { subscribe: (callback) => void spawns.push(callback) },
        itemUse: { subscribe: () => undefined },
      },
    };
    const scheduler = fakeScheduler();
    const log = recordingLogger();
    registerMenuItem(world, {
      inventoryOf: () => {
        if (!present) throw new Error("joueur invalide");
        return inventaire([undefined]);
      },
      makeItem,
      open: () => undefined,
      scheduler,
      log,
      waitWhile: () => present,
    });
    spawns[0]?.({ initialSpawn: true, player: ALEX });
    scheduler.runNextTimeout();
    present = false;
    assert.doesNotThrow(() => scheduler.runNextTimeout());
    assert.match(log.infos.join("\n"), /boussole de Alex : Error: joueur invalide/);
    assert.equal(scheduler.timeouts.length, 0);
  });

  it("une exception de waitWhile est journalisée sans sortir", () => {
    const { scheduler, log } = avecAttente({
      occupe: () => {
        throw new Error("attente cassée");
      },
    });
    assert.doesNotThrow(() => scheduler.runNextTimeout());
    assert.match(log.errors.join("\n"), /menuItem\.give : Error: attente cassée/);
  });
});
