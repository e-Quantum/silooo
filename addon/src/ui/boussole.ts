// La boussole : l'objet qui ouvre le menu. Le joueur la reçoit à son arrivée s'il n'en a pas, et l'utiliser (clic
// droit) ouvre le menu principal. Rien ici n'importe @minecraft/* : main.ts fournit l'inventaire du joueur et
// fabrique l'objet (nom, verrou d'inventaire, conservation à la mort), les tests y mettent des faux.

import { config } from "../config";
import { describeError, guard, type Logger, type Scheduler } from "../runtime";

/** La partie d'un conteneur d'objets (inventaire du joueur) dont ce fichier a besoin. */
export interface InventoryLike<Item extends { readonly typeId: string }> {
  readonly size: number;
  readonly emptySlotsCount: number;
  getItem(slot: number): Item | undefined;
  addItem(item: Item): unknown;
}

interface Subscribable<Event> {
  subscribe(callback: (event: Event) => void): unknown;
}

/** Ce que le code lit d'un ItemUseAfterEvent. */
export interface ItemUseEventLike<P, Item extends { readonly typeId: string }> {
  readonly itemStack: Item;
  readonly source: P;
}

/** Ce que le code lit d'un PlayerSpawnAfterEvent. */
export interface SpawnEventOf<P> {
  readonly initialSpawn: boolean;
  readonly player: P;
}

/** La partie de `world` (@minecraft/server) dont ce fichier a besoin. */
export interface MenuItemWorldLike<P, Item extends { readonly typeId: string }> {
  readonly afterEvents: {
    readonly itemUse: Subscribable<ItemUseEventLike<P, Item>>;
    readonly playerSpawn: Subscribable<SpawnEventOf<P>>;
  };
}

export interface MenuItemDeps<P extends { readonly name: string }, Item extends { readonly typeId: string }> {
  readonly inventoryOf: (player: P) => InventoryLike<Item> | undefined;
  /** Fabrique un exemplaire de l'objet du menu, déjà nommé et verrouillé. */
  readonly makeItem: () => Item;
  readonly open: (player: P) => void | Promise<void>;
  readonly scheduler: Pick<Scheduler, "after">;
  readonly log: Logger;
  /** L'identifiant de l'objet qui ouvre le menu. */
  readonly itemId?: string;
  readonly delaySeconds?: number;
  /**
   * Vrai tant que le joueur ne doit pas encore recevoir la boussole (son arrivée sur son île, la cinématique) :
   * la remise attend, par pas de `waitStepSeconds`, au plus `waitMaxSeconds`, puis a lieu quand même.
   */
  readonly waitWhile?: (player: P) => boolean;
  readonly waitStepSeconds?: number;
  readonly waitMaxSeconds?: number;
}

export type GiveResult = "given" | "already" | "full" | "noInventory";

/** Donne la boussole au joueur s'il n'en a pas. Ne remplace rien : un inventaire plein reste plein. */
export function giveMenuItem<P extends { readonly name: string }, Item extends { readonly typeId: string }>(
  player: P,
  deps: Pick<MenuItemDeps<P, Item>, "inventoryOf" | "makeItem" | "log" | "itemId">,
): GiveResult {
  const itemId = deps.itemId ?? config.ui.menuItem;
  const inventory = deps.inventoryOf(player);
  if (inventory === undefined) return "noInventory";
  for (let slot = 0; slot < inventory.size; slot += 1) {
    if (inventory.getItem(slot)?.typeId === itemId) return "already";
  }
  if (inventory.emptySlotsCount <= 0) {
    deps.log.warn(`boussole de ${player.name} : inventaire plein, rien donné`);
    return "full";
  }
  inventory.addItem(deps.makeItem());
  return "given";
}

/**
 * Branche la boussole : donnée à l'arrivée (un peu après, l'inventaire n'est pas toujours prêt au tout premier
 * tick), menu ouvert quand le joueur l'utilise. Aucune exception ne sort des rappels.
 */
export function registerMenuItem<P extends { readonly name: string }, Item extends { readonly typeId: string }>(
  world: MenuItemWorldLike<P, Item>,
  deps: MenuItemDeps<P, Item>,
): void {
  const itemId = deps.itemId ?? config.ui.menuItem;
  const delaySeconds = deps.delaySeconds ?? config.ui.menuItemDelaySeconds;
  const waitStepSeconds = deps.waitStepSeconds ?? config.ui.menuItemWaitStepSeconds;
  const waitMaxSeconds = deps.waitMaxSeconds ?? config.ui.menuItemWaitMaxSeconds;
  const { log } = deps;

  function give(player: P): void {
    try {
      const result = giveMenuItem(player, deps);
      if (result === "noInventory") log.warn(`boussole de ${player.name} : inventaire illisible`);
    } catch (error) {
      // Le joueur est parti entre-temps : son objet n'existe plus.
      log.info(`boussole de ${player.name} : ${describeError(error)}`);
    }
  }

  /** Donne la boussole quand le joueur n'est plus occupé, ou quand l'attente est épuisée. */
  function giveWhenFree(player: P, waitedSeconds: number): void {
    if (deps.waitWhile?.(player) === true) {
      if (waitedSeconds < waitMaxSeconds) {
        deps.scheduler.after(
          waitStepSeconds,
          guard(log, "menuItem.wait", () => giveWhenFree(player, waitedSeconds + waitStepSeconds)),
        );
        return;
      }
      log.warn(`boussole de ${player.name} : toujours occupé après ${waitMaxSeconds} s, boussole donnée quand même`);
    }
    give(player);
  }

  world.afterEvents.playerSpawn.subscribe(
    guard(log, "menuItem.spawn", (event) => {
      if (!event.initialSpawn) return;
      const { player } = event;
      deps.scheduler.after(
        delaySeconds,
        guard(log, "menuItem.give", () => giveWhenFree(player, 0)),
      );
    }),
  );

  world.afterEvents.itemUse.subscribe(
    guard(log, "menuItem.use", (event) => {
      if (event.itemStack.typeId !== itemId) return;
      return deps.open(event.source);
    }),
  );
}
