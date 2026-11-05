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
  const { log } = deps;

  world.afterEvents.playerSpawn.subscribe(
    guard(log, "menuItem.spawn", (event) => {
      if (!event.initialSpawn) return;
      const { player } = event;
      deps.scheduler.after(
        delaySeconds,
        guard(log, "menuItem.give", () => {
          try {
            const result = giveMenuItem(player, deps);
            if (result === "noInventory") log.warn(`boussole de ${player.name} : inventaire illisible`);
          } catch (error) {
            // Le joueur est parti entre-temps : son objet n'existe plus.
            log.info(`boussole de ${player.name} : ${describeError(error)}`);
          }
        }),
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
