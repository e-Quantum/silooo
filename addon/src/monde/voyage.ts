// Le voyage d'un joueur vers l'île d'une de ses usines, depuis la carte du menu :
//
//   1. retrouver l'origine de son archipel (celle que le service a donnée, sinon la dernière retenue par le relevé)
//      et le point d'arrivée de l'île choisie (geometrie.ts) ;
//   2. redemander au constructeur de poser les îles dans l'état que le service dit : il ne refait que ce qui a changé,
//      donc une île déjà posée est prête tout de suite, et une île qui se redessine (après un prestige) est attendue ;
//   3. attendre que l'île voulue soit posée, au plus config.world.travelWaitSeconds ;
//   4. téléporter le joueur à son point d'arrivée, face au bâtiment.
//
// Le joueur ne peut aller que sur l'île d'une usine que l'entreprise a : l'état du service fait foi. Oooscar ne le suit
// pas (il reste là où l'arrivée l'a posé). Rien ici n'importe @minecraft/* : le monde est un `ArrivalWorld`.

import { config } from "../config";
import type { IlesDefinitions } from "../generated/definitions";
import type { CompanyState } from "../protocol";
import type { Logger } from "../runtime";
import { inBuildOrder, type ArrivalWorld } from "./arrivee";
import type { IslandBuilder } from "./constructeur";
import { arrivalAt, type Xz } from "./geometrie";

/**
 * Ce qui s'est passé :
 *  - `arrived`    : le joueur a été téléporté ;
 *  - `unavailable`: on ne sait pas où aller (pas d'origine d'archipel, usine hors de l'entreprise ou absente de iles.json) ;
 *  - `waiting`    : l'île n'était pas posée à temps, le joueur reste où il est ;
 *  - `gone`       : le joueur est parti pendant l'attente.
 */
export type TravelOutcome = "arrived" | "unavailable" | "waiting" | "gone";

export interface TravelSettings {
  readonly travelWaitSeconds: number;
}

export interface TravelDeps<P extends { readonly name: string }> {
  readonly defs: IlesDefinitions;
  readonly builder: Pick<IslandBuilder, "sync" | "whenReady">;
  readonly world: ArrivalWorld<P>;
  /** La dernière origine d'archipel connue d'une entreprise (monde/synchro.ts), pour une réponse du service qui ne la redonne pas. */
  readonly originOf: (companyId: number) => Xz | undefined;
  readonly log: Logger;
  readonly settings?: TravelSettings;
}

export type Travel<P> = (player: P, state: CompanyState, factoryId: string) => Promise<TravelOutcome>;

export function createTravel<P extends { readonly name: string }>(deps: TravelDeps<P>): Travel<P> {
  const { defs, builder, world, originOf, log } = deps;
  const settings = deps.settings ?? config.world;

  return async (player, state, factoryId) => {
    const origin = state.island ?? originOf(state.companyId);
    if (origin === undefined) {
      log.info(`voyage de ${player.name} : le service ne donne pas d'origine d'île, nulle part où aller`);
      return "unavailable";
    }
    const arrival = arrivalAt(origin, defs, factoryId);
    if (arrival === undefined) {
      log.warn(`voyage de ${player.name} : l'usine ${factoryId} n'a pas d'île dans iles.json`);
      return "unavailable";
    }
    if (!state.factories.some((factory) => factory.factoryId === factoryId)) {
      log.info(`voyage de ${player.name} : l'entreprise n'a pas d'usine ${factoryId}`);
      return "unavailable";
    }
    builder.sync(state.companyId, origin, inBuildOrder(state.factories, defs.apparition.usine));
    const ready = await builder.whenReady(state.companyId, factoryId, settings.travelWaitSeconds);
    if (!world.isOnline(player)) return "gone";
    if (!ready) {
      log.warn(`voyage de ${player.name} : l'île ${factoryId} n'est pas prête après ${settings.travelWaitSeconds} s, joueur laissé où il est`);
      return "waiting";
    }
    world.teleport(player, arrival.position, arrival.facing);
    log.info(`voyage de ${player.name} : sur l'île de ${factoryId}`);
    return "arrived";
  };
}
