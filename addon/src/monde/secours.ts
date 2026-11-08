// Un joueur qui tombe de son île : il est reposé à l'arrivée de sa Carrière avant de toucher le vide.
import type { IlesDefinitions } from "../generated/definitions";
import type { ArrivalWorld } from "./arrivee";
import { arrivalOf, type Xz } from "./geometrie";

export interface RescueDeps<P> {
  readonly defs: IlesDefinitions;
  readonly players: () => readonly P[];
  readonly world: ArrivalWorld<P>;
  /** Vrai pendant une arrivée ou une cinématique : la caméra le tient, on n'y touche pas. */
  readonly busy: (player: P) => boolean;
  /** L'origine de l'archipel du joueur, quand on la connaît. */
  readonly originOf: (player: P) => Xz | undefined;
}

export function createFallRescue<P>(deps: RescueDeps<P>) {
  const { defs, world } = deps;
  const limit = defs.hauteur - defs.secours.sousLesIles;
  return {
    /** Rend le nombre de joueurs reposés. */
    tick(): number {
      let rescued = 0;
      for (const player of deps.players()) {
        if (deps.busy(player)) continue;
        const at = world.locate(player);
        if (at === undefined || at.y >= limit) continue;
        const origin = deps.originOf(player);
        const arrival = origin === undefined ? undefined : arrivalOf(origin, defs);
        if (arrival === undefined) continue;
        world.teleport(player, arrival.position, arrival.facing);
        rescued += 1;
      }
      return rescued;
    },
  };
}
