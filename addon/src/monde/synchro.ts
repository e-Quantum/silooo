// Le relevé périodique des îles : toutes les quelques secondes, pour chaque joueur présent, ce que le service dit de ses
// usines (la vue gardée par ui/etat.ts) est montré au constructeur, qui ne refait que ce qui a changé. Une usine remise
// en état par un achat change donc de visage au relevé suivant, sans rien de plus à brancher dans les écrans.
//
// Ce fichier n'écrit aucun bloc et ne calcule rien : il met en relation la vue des joueurs et le constructeur.
// Il retient la dernière origine d'archipel connue de chaque entreprise, pour le cas où une réponse du service
// (une action, par exemple) ne la redonnerait pas.

import { inBuildOrder } from "./arrivee";
import type { IlesDefinitions } from "../generated/definitions";
import type { Logger } from "../runtime";
import type { CompanyView } from "../ui/etat";
import type { Xuid } from "../protocol";
import type { IslandBuilder } from "./constructeur";
import type { Xz } from "./geometrie";
import type { OooscarManager } from "./oooscar";

export interface WorldSyncDeps<P> {
  readonly defs: IlesDefinitions;
  readonly players: () => readonly P[];
  readonly xuidOf: (player: P) => Xuid;
  readonly views: { get(xuid: Xuid): CompanyView | undefined };
  readonly builder: Pick<IslandBuilder, "sync" | "syncCity">;
  readonly oooscar?: Pick<OooscarManager, "reap">;
  readonly log: Logger;
}

export interface WorldSync {
  /** Un relevé : les îles de tous les joueurs présents, et le ménage des Oooscar dont le joueur est parti. */
  tick(): void;
  /** Retient l'origine d'archipel d'une entreprise. */
  remember(companyId: number, origin: Xz): void;
  /** La dernière origine d'archipel connue d'une entreprise, ou undefined si le service ne l'a jamais donnée. */
  originOf(companyId: number): Xz | undefined;
}

export function createWorldSync<P>(deps: WorldSyncDeps<P>): WorldSync {
  const { defs, players, xuidOf, views, builder, log } = deps;
  const origins = new Map<number, Xz>();
  const missing = new Set<number>();

  return {
    remember(companyId, origin) {
      origins.set(companyId, origin);
      missing.delete(companyId);
    },

    originOf: (companyId) => origins.get(companyId),

    tick() {
      const online = players();
      for (const player of online) {
        const state = views.get(xuidOf(player))?.state;
        if (state === undefined) continue;
        const origin = state.island ?? origins.get(state.companyId);
        if (origin === undefined) {
          if (!missing.has(state.companyId)) {
            missing.add(state.companyId);
            log.info(`entreprise ${state.companyId} : le service ne donne pas d'origine d'île, aucune île posée`);
          }
          continue;
        }
        origins.set(state.companyId, origin);
        missing.delete(state.companyId);
        builder.sync(state.companyId, origin, inBuildOrder(state.factories, defs.apparition.usine));
        // La ville de l'entreprise (île principale), quand le service en donne une : zone par zone, bâtiment par bâtiment.
        if (state.city !== undefined) builder.syncCity(state.companyId, origin, state.city);
      }
      deps.oooscar?.reap(new Set(online.map(xuidOf)));
    },
  };
}
