// L'arrivée d'un joueur sur son île, de la réponse de join jusqu'à la main rendue :
//
//   1. demander au constructeur de poser les mini-îles de ses usines, celle de départ (la Carrière) d'abord ;
//   2. attendre que l'île de départ soit posée (au plus config.world.arrivalWaitSeconds) ;
//   3. le téléporter sur le point d'apparition, face au bâtiment, une seule fois par connexion ;
//   4. faire apparaître Oooscar près de lui ;
//   5. si c'est son tout premier passage (`firstJoin`), jouer la cinématique d'arrivée.
//
// Tant que tout cela dure, le joueur est « occupé » (`busy`) : le HUD se tait, la boussole attend, le menu ne s'ouvre pas.
// Sans origine d'île dans la réponse du service, rien n'est posé ni déplacé : une ligne de journal, et le joueur reste où
// Bedrock l'a mis. Rien ici n'importe @minecraft/* : le monde est un `ArrivalWorld`.

import { config } from "../config";
import type { IlesDefinitions } from "../generated/definitions";
import type { Factory, JoinResponse } from "../protocol";
import { describeError, pause, type Logger, type Scheduler } from "../runtime";
import type { IslandBuilder } from "./constructeur";
import { buildTimeline, type CinematicRunner, type CinematicTexts } from "./cinematique";
import { arrivalOf, type Pos, type Xz } from "./geometrie";
import type { OooscarManager } from "./oooscar";

export interface ArrivalWorld<P> {
  /** Faux quand le joueur est parti. */
  isOnline(player: P): boolean;
  /** Pose le joueur en `at`, tourné vers `facing`. */
  teleport(player: P, at: Pos, facing: Pos): void;
  /** Où se tient le joueur dans le monde des joueurs, ou undefined quand on ne peut pas le dire (parti, autre dimension). */
  locate(player: P): Pos | undefined;
}

export interface ArrivalSettings {
  readonly arrivalWaitSeconds: number;
  readonly oooscarSpawnRetries: number;
  readonly oooscarSpawnRetrySeconds: number;
}

export interface ArrivalDeps<P extends { readonly name: string }> {
  readonly defs: IlesDefinitions;
  readonly builder: Pick<IslandBuilder, "sync" | "whenReady">;
  readonly oooscar: Pick<OooscarManager, "spawnFor" | "removeFor">;
  readonly runner: Pick<CinematicRunner<P>, "start" | "cancel">;
  readonly world: ArrivalWorld<P>;
  readonly keyOf: (player: P) => string;
  readonly texts: CinematicTexts;
  readonly scheduler: Pick<Scheduler, "after">;
  readonly log: Logger;
  /** Retient l'origine d'archipel d'une entreprise (le relevé périodique s'en sert quand une réponse ne la redonne pas). */
  readonly remember?: (companyId: number, origin: Xz) => void;
  /** La cinématique est acquittée après sa lecture, jamais au simple join du service. */
  readonly introduction?: { seen(player: P): boolean; complete(player: P): void };
  readonly withCinematicArea?: <T>(timeline: ReturnType<typeof buildTimeline>, run: () => Promise<T>) => Promise<T>;
  readonly settings?: ArrivalSettings;
}

export interface Arrivals<P> {
  /** Le joueur vient d'apparaître dans le monde, avant la réponse de join. */
  arriving(player: P): void;
  /** La réponse de join est arrivée : île, téléportation, Oooscar, cinématique. Se résout quand tout est fini. */
  joined(player: P, join: JoinResponse, replay?: boolean): Promise<void>;
  /** join a échoué : le joueur n'est plus occupé. */
  failed(player: P): void;
  /** Le joueur est parti : tout ce qui le concernait s'arrête. */
  left(key: string): void;
  /** Vrai de l'apparition du joueur jusqu'à la fin de son arrivée. */
  busy(player: P): boolean;
}

/** Les usines dans l'ordre où l'on pose leurs îles : celle de départ d'abord, puis l'ordre du service. */
export function inBuildOrder(factories: readonly Factory[], firstId: string): Factory[] {
  return [...factories].sort((a, b) => Number(b.factoryId === firstId) - Number(a.factoryId === firstId));
}

export function createArrivals<P extends { readonly name: string }>(deps: ArrivalDeps<P>): Arrivals<P> {
  const { defs, builder, oooscar, runner, world, keyOf, texts, scheduler, log } = deps;
  const settings = deps.settings ?? config.world;
  /** Une marque par connexion : une arrivée qui traîne ne doit pas libérer la suivante du même joueur. Sert à `busy`. */
  const connections = new Map<string, number>();
  /** La connexion en cours de chaque joueur présent : reste jusqu'à son départ, même une fois l'arrivée finie. */
  const present = new Map<string, number>();
  let counter = 0;

  function begin(key: string): number {
    counter += 1;
    connections.set(key, counter);
    present.set(key, counter);
    return counter;
  }

  async function placeOooscar(key: string, at: Pos, facing: Pos, connection: number): Promise<void> {
    for (let attempt = 0; attempt <= settings.oooscarSpawnRetries; attempt += 1) {
      // Le chunk d'arrivée n'est pas toujours chargé la seconde où le joueur y arrive : on réessaie un peu plus tard.
      await pause(scheduler, settings.oooscarSpawnRetrySeconds);
      if (present.get(key) !== connection) return;
      if (oooscar.spawnFor(key, at, facing)) return;
    }
    log.warn("Oooscar : jamais apparue près du joueur");
  }

  async function arrive(player: P, join: JoinResponse, key: string, connection: number, replay = false): Promise<void> {
    const island = join.state.island;
    if (island === undefined) {
      log.info(`arrivée de ${player.name} : pas d'origine d'île dans la réponse du service, rien posé ni déplacé`);
      return;
    }
    deps.remember?.(join.companyId, island);
    const arrival = arrivalOf(island, defs);
    if (arrival === undefined) {
      log.warn(`arrivée de ${player.name} : l'usine de départ ${defs.apparition.usine} n'a pas d'île dans iles.json`);
      return;
    }
    builder.sync(join.companyId, island, inBuildOrder(join.state.factories, arrival.factoryId));
    if (!join.state.factories.some((factory) => factory.factoryId === arrival.factoryId)) {
      log.info(`arrivée de ${player.name} : pas d'usine ${arrival.factoryId} dans son entreprise, pas de point d'apparition`);
      return;
    }
    const ready = await builder.whenReady(join.companyId, arrival.factoryId, settings.arrivalWaitSeconds);
    if (connections.get(key) !== connection || !world.isOnline(player)) return;
    if (!ready) {
      log.warn(`arrivée de ${player.name} : l'île de départ n'est pas prête après ${settings.arrivalWaitSeconds} s, joueur laissé où il est`);
      return;
    }
    world.teleport(player, arrival.position, arrival.facing);
    log.info(`arrivée de ${player.name} : sur l'île de ${arrival.factoryId}`);
    void placeOooscar(key, arrival.oooscar, arrival.position, connection).catch((error: unknown) => {
      log.warn(`Oooscar : ${describeError(error)}`);
    });
    if (!replay && !join.firstJoin && (deps.introduction === undefined || deps.introduction.seen(player))) return;
    const timeline = buildTimeline(defs, arrival, texts);
    const play = () => runner.start(key, player, timeline);
    const outcome = await (deps.withCinematicArea === undefined ? play() : deps.withCinematicArea(timeline, play));
    if (outcome !== "aborted") deps.introduction?.complete(player);
    log.info(`cinématique de ${player.name} : ${outcome}`);
  }

  return {
    arriving(player) {
      begin(keyOf(player));
    },

    async joined(player, join, replay = false) {
      const key = keyOf(player);
      const connection = connections.get(key) ?? begin(key);
      try {
        await arrive(player, join, key, connection, replay);
      } catch (error) {
        log.error(`arrivée de ${player.name} : ${describeError(error)}`);
      } finally {
        if (connections.get(key) === connection) connections.delete(key);
      }
    },

    failed(player) {
      const key = keyOf(player);
      connections.delete(key);
      present.delete(key);
    },

    left(key) {
      connections.delete(key);
      present.delete(key);
      runner.cancel(key);
      oooscar.removeFor(key);
    },

    busy: (player) => connections.has(keyOf(player)),
  };
}
