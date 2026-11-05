// Arrivée et départ des joueurs : playerSpawn (premier passage seulement) appelle join, playerLeave
// appelle leave. La réponse de join est journalisée et gardée en mémoire par joueur (state.ts) pour
// la phase 3. Un refus ou une erreur est journalisé une fois, sans nouvel essai.

import type { CoreApi } from "./api";
import { describeFailure } from "./http";
import { leavingXuid, playerXuid } from "./identity";
import type { Xuid } from "./protocol";
import { guard, type Logger } from "./runtime";
import type { PlayerStore } from "./state";

/** Ce que le code lit d'un PlayerSpawnAfterEvent. */
export interface SpawnEventLike {
  readonly initialSpawn: boolean;
  readonly player: { readonly id: string; readonly name: string };
}

/** Ce que le code lit d'un PlayerLeaveAfterEvent. */
export interface LeaveEventLike {
  readonly playerId: string;
  readonly playerName: string;
}

interface Subscribable<Event> {
  subscribe(callback: (event: Event) => void): unknown;
}

/** La partie de `world` (@minecraft/server) dont ce fichier a besoin. */
export interface SessionWorldLike {
  readonly afterEvents: {
    readonly playerSpawn: Subscribable<SpawnEventLike>;
    readonly playerLeave: Subscribable<LeaveEventLike>;
  };
}

export interface SessionDeps {
  readonly api: Pick<CoreApi, "join" | "leave">;
  readonly store: PlayerStore;
  readonly log: Logger;
}

export interface SessionHandlers {
  onSpawn(event: SpawnEventLike): Promise<void>;
  onLeave(event: LeaveEventLike): Promise<void>;
}

export function createSessionHandlers(deps: SessionDeps): SessionHandlers {
  const { api, store, log } = deps;
  /** Les joueurs présents d'après les événements : un join qui rentre après un leave ne garde rien. */
  const present = new Set<Xuid>();
  /** Un seul appel à la fois par joueur : un leave attend le join encore en vol, le service les voit dans l'ordre. */
  const chains = new Map<Xuid, Promise<void>>();

  function enqueue(xuid: Xuid, task: () => Promise<void>): Promise<void> {
    const previous = chains.get(xuid) ?? Promise.resolve();
    const next = previous.catch(() => undefined).then(task);
    chains.set(xuid, next);
    return next.finally(() => {
      if (chains.get(xuid) === next) chains.delete(xuid);
    });
  }

  return {
    async onSpawn(event) {
      if (!event.initialSpawn) return;
      const xuid = playerXuid(event.player);
      const gamertag = event.player.name;
      present.add(xuid);
      await enqueue(xuid, async () => {
        const result = await api.join(xuid, gamertag);
        if (result.kind !== "ok") {
          log.warn(`join de ${gamertag} : ${describeFailure(result)}`);
          return;
        }
        if (!present.has(xuid)) return;
        store.set({ xuid, gamertag, join: result.body });
        const visit = result.body.firstJoin ? "premier passage" : "retour";
        log.info(
          `join de ${gamertag} : ${visit}, entreprise ${result.body.companyId}, ${result.body.state.factories.length} usine(s)`,
        );
      });
    },

    async onLeave(event) {
      const xuid = leavingXuid(event);
      present.delete(xuid);
      store.remove(xuid);
      await enqueue(xuid, async () => {
        const result = await api.leave(xuid);
        if (result.kind === "ok") log.info(`départ de ${event.playerName}`);
        else log.warn(`leave de ${event.playerName} : ${describeFailure(result)}`);
      });
    },
  };
}

export function registerSessionEvents(world: SessionWorldLike, handlers: SessionHandlers, log: Logger): void {
  world.afterEvents.playerSpawn.subscribe(guard(log, "playerSpawn", handlers.onSpawn));
  world.afterEvents.playerLeave.subscribe(guard(log, "playerLeave", handlers.onLeave));
}
