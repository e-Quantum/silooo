// Objets de navigation à place fixe. Les autres objets sont déplacés, jamais effacés.
import { NAVIGATION_TEXTES } from "./textes";
import type { Route } from "./ecran";
import type { CompanyState } from "../protocol";
import { ILES } from "../monde/definitions";
import { islandCenter, islandRadius, type Pos } from "../monde/geometrie";

export const NAVIGATION = [
  { id: "silooo:usine", slot: 0, name: NAVIGATION_TEXTES.usine, icon: "usine", route: { screen: "carte", page: 0 } },
  { id: "silooo:carte", slot: 1, name: NAVIGATION_TEXTES.carte, icon: "carte", route: { screen: "carte", page: 0 } },
  { id: "silooo:objectif", slot: 2, name: NAVIGATION_TEXTES.objectif, icon: "engrenage", route: { screen: "objectifs" } },
  { id: "silooo:ville", slot: 3, name: NAVIGATION_TEXTES.ville, icon: "ville", route: { screen: "ville" } },
] as const satisfies readonly { id: string; slot: number; name: string; icon: string; route: Route }[];

export interface NavigationItem { readonly typeId: string; readonly nameTag?: string }
export interface NavigationInventory<Item extends NavigationItem> {
  readonly size: number;
  getItem(slot: number): Item | undefined;
  setItem(slot: number, item?: Item): void;
}

export function navigationRoute(typeId: string): Route | undefined {
  return NAVIGATION.find((entry) => entry.id === typeId)?.route;
}

/** La fiche dépend de l'île où le joueur se tient, pas de la première usine de la liste. */
export function currentFactoryRoute(state: CompanyState, position: Pos | undefined): Route {
  if (state.island !== undefined && position !== undefined && Math.abs(position.y - ILES.hauteur) < 30) {
    for (const factory of state.factories) {
      const definition = ILES.usines.find((entry) => entry.id === factory.factoryId);
      if (definition === undefined) continue;
      const center = islandCenter(state.island, definition, ILES);
      if (Math.hypot(position.x - center.x, position.z - center.z) <= islandRadius(ILES, definition) + 2) {
        return { screen: "fiche", factoryId: factory.factoryId, page: 0 };
      }
    }
  }
  return { screen: "carte", page: 0 };
}

function isNavigation(item: NavigationItem): boolean {
  return navigationRoute(item.typeId) !== undefined || (item.typeId === "minecraft:compass" && item.nameTag === "Menu");
}

/** L'identifiant d'un objet d'une case de NAVIGATION. */
export function isNavigationItem(typeId: string): boolean {
  return navigationRoute(typeId) !== undefined;
}

/**
 * Vrai quand chaque objet de navigation est dans sa case et qu'aucun autre ne traîne ailleurs. Les objets n'ont pas de
 * verrou (son texte couvrait le HUD) : le joueur peut en jeter ou en déplacer un, et ce test dit qu'il faut les remettre.
 */
export function navigationInPlace<Item extends NavigationItem>(inventory: NavigationInventory<Item>): boolean {
  for (let index = 0; index < inventory.size; index += 1) {
    const item = inventory.getItem(index);
    const entry = NAVIGATION.find((candidate) => candidate.slot === index);
    if (entry !== undefined) {
      if (item?.typeId !== entry.id) return false;
    } else if (item !== undefined && isNavigation(item)) {
      return false;
    }
  }
  return true;
}

/** Prépare le déplacement avant d'écrire : un inventaire plein ne perd aucun objet. */
export function installNavigation<Item extends NavigationItem>(inventory: NavigationInventory<Item>, make: (entry: typeof NAVIGATION[number]) => Item): boolean {
  const contents = Array.from({ length: inventory.size }, (_, index) => inventory.getItem(index));
  const targetSlots = new Set<number>(NAVIGATION.map((entry) => entry.slot));
  const displaced: Item[] = [];
  const next = contents.map((item, index) => {
    if (item === undefined || isNavigation(item)) return undefined;
    if (targetSlots.has(index)) { displaced.push(item); return undefined; }
    return item;
  });
  const free = next.flatMap((item, index) => item === undefined && !targetSlots.has(index) ? [index] : []);
  if (displaced.length > free.length || NAVIGATION.some((entry) => entry.slot >= inventory.size)) return false;
  displaced.forEach((item, index) => { next[free[index]!] = item; });
  for (const entry of NAVIGATION) next[entry.slot] = make(entry);
  next.forEach((item, index) => inventory.setItem(index, item));
  return true;
}
