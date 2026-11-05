// Oooscar dans le monde : une entité (silooo:oooscar, définie dans pack/entities/oooscar.json) qui se tient près du
// joueur quand il arrive sur son île. Elle ne bouge pas, ne blesse pas, ne laisse rien et n'est jamais persistante.
//
// Ce fichier tient les comptes pour qu'elle ne s'accumule jamais : une seule par joueur, retirée à son départ, et un
// balayage au démarrage du serveur et avant chaque apparition retire celles qu'un redémarrage ou un plantage aurait
// laissées. L'entité elle-même (apparition, retrait, recherche) est un `OooscarWorld` ; l'implémentation réelle est dans
// monde/bedrock.ts.

import { describeError, type Logger } from "../runtime";
import type { Pos } from "./geometrie";

/** Une entité d'Oooscar vue de l'extérieur : de quoi la retrouver et la retirer. */
export interface OooscarHandle {
  readonly id: string;
}

export interface OooscarWorld {
  /** Fait apparaître une Oooscar en `at`, tournée vers `facing`. Rend undefined si le monde refuse (zone non chargée). */
  spawn(at: Pos, facing: Pos): OooscarHandle | undefined;
  /** Retire une Oooscar. Sans effet si elle n'existe plus. */
  remove(handle: OooscarHandle): void;
  /** Vrai tant que l'entité existe encore. */
  exists(handle: OooscarHandle): boolean;
  /** Toutes les Oooscar des zones chargées. */
  all(): readonly OooscarHandle[];
  /** Celles qui sont à moins de `radius` blocs de `center`. */
  near(center: Pos, radius: number): readonly OooscarHandle[];
}

export interface OooscarManager {
  /** Une Oooscar pour ce joueur, près de lui : retire l'ancienne et celles qui traînent autour, puis en fait apparaître une. */
  spawnFor(key: string, at: Pos, facing: Pos): boolean;
  /** Retire l'Oooscar de ce joueur. Rend vrai s'il en avait une. */
  removeFor(key: string): boolean;
  /** Retire toutes les Oooscar des zones chargées : au démarrage du serveur. Rend combien. */
  sweep(): number;
  /** Retire les Oooscar dont le joueur n'est plus là, et celles qui n'existent plus. Rend combien ont été retirées. */
  reap(onlineKeys: ReadonlySet<string>): number;
  /** Combien d'Oooscar sont suivies. */
  readonly count: number;
}

export function createOooscarManager(deps: { readonly world: OooscarWorld; readonly log: Logger; readonly sweepRadius: number }): OooscarManager {
  const { world, log, sweepRadius } = deps;
  const owned = new Map<string, OooscarHandle>();

  function safeRemove(handle: OooscarHandle): boolean {
    try {
      world.remove(handle);
      return true;
    } catch (error) {
      log.warn(`Oooscar ${handle.id} : retrait impossible (${describeError(error)})`);
      return false;
    }
  }

  return {
    spawnFor(key, at, facing) {
      const previous = owned.get(key);
      if (previous !== undefined) {
        owned.delete(key);
        safeRemove(previous);
      }
      try {
        for (const stray of world.near(at, sweepRadius)) safeRemove(stray);
        const handle = world.spawn(at, facing);
        if (handle === undefined) {
          log.warn("Oooscar : le monde n'a pas pu la faire apparaître");
          return false;
        }
        owned.set(key, handle);
        return true;
      } catch (error) {
        log.warn(`Oooscar : apparition impossible (${describeError(error)})`);
        return false;
      }
    },

    removeFor(key) {
      const handle = owned.get(key);
      if (handle === undefined) return false;
      owned.delete(key);
      safeRemove(handle);
      return true;
    },

    sweep() {
      let removed = 0;
      owned.clear();
      try {
        for (const handle of world.all()) if (safeRemove(handle)) removed += 1;
      } catch (error) {
        log.warn(`Oooscar : balayage impossible (${describeError(error)})`);
      }
      return removed;
    },

    reap(onlineKeys) {
      let removed = 0;
      for (const [key, handle] of [...owned]) {
        const gone = !onlineKeys.has(key);
        let vanished = false;
        try {
          vanished = !world.exists(handle);
        } catch {
          vanished = true;
        }
        if (!gone && !vanished) continue;
        owned.delete(key);
        if (gone && !vanished && safeRemove(handle)) removed += 1;
      }
      return removed;
    },

    get count() {
      return owned.size;
    },
  };
}
