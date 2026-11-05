// Arrivée et départ des joueurs : playerSpawn (premier passage seulement) appelle join, playerLeave
// appelle leave. La réponse de join est journalisée et gardée en mémoire par joueur (state.ts) pour
// la phase 3. Un refus ou une erreur est journalisé une fois, sans nouvel essai.
//
// Des crochets facultatifs (`hooks`) laissent la partie « monde » (île du joueur, cinématique, Oooscar) suivre ces
// événements sans que ce fichier la connaisse : voir monde/arrivee.ts.

import type { CoreApi } from "./api";
import { describeFailure } from "./http";
import { leavingXuid, playerXuid } from "./identity";
import type { JoinResponse, Xuid } from "./protocol";
import { guard, type Logger } from "./runtime";
import type { PlayerStore } from "./state";

/** Ce que le code sait d'un joueur : de quoi calculer son identifiant et le nommer dans le journal. */
export interface PlayerLike {
  readonly id: string;
  readonly name: string;
}

/** Ce que le code lit d'un PlayerSpawnAfterEvent. Le joueur garde son type (Player de Bedrock) pour les crochets. */
export interface SpawnEventLike<P extends PlayerLike = PlayerLike> {
  readonly initialSpawn: boolean;
  readonly player: P;
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
export interface SessionWorldLike<P extends PlayerLike = PlayerLike> {
  readonly afterEvents: {
    readonly playerSpawn: Subscribable<SpawnEventLike<P>>;
    readonly playerLeave: Subscribable<LeaveEventLike>;
  };
}

/**
 * Ce qui dépend de l'arrivée du joueur sans que ce fichier le sache. Aucune exception d'un crochet ne sort, et aucun
 * ne retient la file des appels au service : `joined` peut durer vingt secondes sans retarder le `leave` du joueur.
 */
export interface SessionHooks<P> {
  /** Le joueur vient d'apparaître, avant la réponse de join. */
  arriving?(player: P): void;
  /** join a abouti et l'état est rangé. Lancé sans être attendu. */
  joined?(player: P, join: JoinResponse): void | Promise<void>;
  /** join a échoué, ou le joueur est parti avant la réponse. */
  joinFailed?(player: P): void;
  /** Le joueur est parti. */
  left?(event: LeaveEventLike): void;
}

export interface SessionDeps<P extends PlayerLike = PlayerLike> {
  readonly api: Pick<CoreApi, "join" | "leave">;
  readonly store: PlayerStore;
  readonly log: Logger;
  readonly hooks?: SessionHooks<P>;
}

export interface SessionHandlers<P extends PlayerLike = PlayerLike> {
  onSpawn(event: SpawnEventLike<P>): Promise<void>;
  onLeave(event: LeaveEventLike): Promise<void>;
}

export function createSessionHandlers<P extends PlayerLike = PlayerLike>(deps: SessionDeps<P>): SessionHandlers<P> {
  const { api, store, log } = deps;
  const hooks: SessionHooks<P> = deps.hooks ?? {};
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

  /** Appelle un crochet sans jamais laisser sortir une exception, qu'elle soit synchrone ou celle d'une promesse. */
  function call(label: string, run: () => void | Promise<void>): void {
    guard(log, `crochet ${label}`, run)();
  }

  return {
    async onSpawn(event) {
      if (!event.initialSpawn) return;
      const player = event.player;
      const xuid = playerXuid(player);
      const gamertag = player.name;
      present.add(xuid);
      call("arriving", () => hooks.arriving?.(player));
      await enqueue(xuid, async () => {
        const result = await api.join(xuid, gamertag);
        if (result.kind !== "ok") {
          log.warn(`join de ${gamertag} : ${describeFailure(result)}`);
          call("joinFailed", () => hooks.joinFailed?.(player));
          return;
        }
        if (!present.has(xuid)) {
          call("joinFailed", () => hooks.joinFailed?.(player));
          return;
        }
        store.set({ xuid, gamertag, join: result.body });
        const visit = result.body.firstJoin ? "premier passage" : "retour";
        log.info(
          `join de ${gamertag} : ${visit}, entreprise ${result.body.companyId}, ${result.body.state.factories.length} usine(s)`,
        );
        call("joined", () => hooks.joined?.(player, result.body));
      });
    },

    async onLeave(event) {
      const xuid = leavingXuid(event);
      present.delete(xuid);
      store.remove(xuid);
      call("left", () => hooks.left?.(event));
      await enqueue(xuid, async () => {
        const result = await api.leave(xuid);
        if (result.kind === "ok") log.info(`départ de ${event.playerName}`);
        else log.warn(`leave de ${event.playerName} : ${describeFailure(result)}`);
      });
    },
  };
}

export function registerSessionEvents<P extends PlayerLike>(
  world: SessionWorldLike<P>,
  handlers: SessionHandlers<P>,
  log: Logger,
): void {
  world.afterEvents.playerSpawn.subscribe(guard(log, "playerSpawn", handlers.onSpawn));
  world.afterEvents.playerLeave.subscribe(guard(log, "playerLeave", handlers.onLeave));
}
