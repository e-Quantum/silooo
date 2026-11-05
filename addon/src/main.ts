// Le point d'entrée du pack de comportement (scripts/main.js après esbuild). Il lit les réglages,
// branche les pièces et ne laisse jamais une exception tuer le script : sans réglages, l'add-on reste
// inerte et le dit dans le journal du serveur.

import { ItemLockMode, ItemStack, system, world, type Player } from "@minecraft/server";
import { createCoreApi, type CoreApi } from "./api";
import { createBetaTransport, readSettings } from "./beta";
import { config } from "./config";
import { createEventListeners, createEventPoller, startEventPolling } from "./events";
import { createHeartbeat, startHeartbeat } from "./heartbeat";
import { createCoreClient } from "./http";
import { leavingXuid, playerXuid } from "./identity";
import { createArrivals } from "./monde/arrivee";
import {
  createArrivalWorld,
  createBlockWriter,
  createCinematicStage,
  createEventsCursorStore,
  createMarkStore,
  createOooscarWorld,
  releaseStaleAreas,
} from "./monde/bedrock";
import { createCinematicRunner } from "./monde/cinematique";
import { createIslandBuilder } from "./monde/constructeur";
import { ILES, VILLE } from "./monde/definitions";
import type { Pos, Xz } from "./monde/geometrie";
import { createOooscarManager } from "./monde/oooscar";
import { createWorldSync } from "./monde/synchro";
import { createCityTravel, createTravel, type CityTravel, type Travel } from "./monde/voyage";
import {
  consoleLogger,
  createRequestIdGenerator,
  createStatusLog,
  createSystemScheduler,
  describeError,
  guard,
  pause,
  systemClock,
  type Logger,
  type Scheduler,
} from "./runtime";
import type { Xuid } from "./protocol";
import { combineHooks, createSessionHandlers, registerSessionEvents, type SessionHooks } from "./session";
import { createPlayerStore, type PlayerStore } from "./state";
import { createActions } from "./ui/actions";
import { createAnnouncements, type Announcements } from "./ui/annonces";
import { createCityEvents } from "./ui/annonces-ville";
import { registerMenuItem } from "./ui/boussole";
import { createLinkState, createViewStore, observeApi, type LinkState, type ViewStore } from "./ui/etat";
import { createFormPresenter } from "./ui/formulaires";
import { createHud, startHud } from "./ui/hud";
import { createInvitations, type Invitations } from "./ui/invitations";
import { createIncidents } from "./ui/incidents";
import { createMenu } from "./ui/menu";
import { createMissions, type Missions } from "./ui/missions";
import { BOUSSOLE_TEXTES, CINEMATIQUE_TEXTES } from "./ui/textes";
import { createVotes } from "./ui/votes";

/** L'objet du menu, tel que le joueur le reçoit : nommé, gardé à la mort, impossible à jeter ou à ranger ailleurs. */
function newMenuItem(): ItemStack {
  const item = new ItemStack(config.ui.menuItem, 1);
  item.nameTag = BOUSSOLE_TEXTES.nom;
  item.keepOnDeath = true;
  item.lockMode = ItemLockMode.inventory;
  return item;
}

/** Écrit un texte dans le tchat du joueur. Un joueur parti entre-temps n'a personne à qui parler : rien ne se passe. */
function tellPlayer(xuid: Xuid, text: string): void {
  for (const player of world.getAllPlayers()) {
    if (playerXuid(player) === xuid) {
      player.sendMessage(text);
      return;
    }
  }
}

/** Ce que la partie « monde » donne au reste : les crochets de la session, et « ce joueur arrive, ne lui montre rien ». */
interface WorldPart {
  readonly hooks: SessionHooks<Player>;
  readonly busy: (player: Player) => boolean;
  /** Téléporte un joueur sur l'île d'une de ses usines (la carte du menu). Absent quand le monde n'a pas démarré. */
  readonly travel?: Travel<Player>;
  /** Téléporte un joueur à la mairie de l'île principale (la carte du menu). */
  readonly travelCity?: CityTravel<Player>;
  /** Où se tient le joueur (la pose d'un bâtiment lit la parcelle). */
  readonly where?: (player: Player) => Pos | undefined;
  /** La dernière origine d'archipel connue d'une entreprise. */
  readonly originOf?: (companyId: number) => Xz | undefined;
}

/** Sans partie « monde » : rien n'est occupé, aucun crochet, aucun voyage. */
const NO_WORLD: WorldPart = { hooks: {}, busy: () => false };

/**
 * Le monde : îles des usines, arrivée du joueur, cinématique, Oooscar. Isolé du reste : si sa mise en place échoue,
 * le heartbeat, les événements et les écrans tournent quand même, et le journal dit pourquoi le monde manque.
 */
function startWorld(deps: { readonly views: ViewStore; readonly scheduler: Scheduler; readonly log: Logger }): WorldPart {
  const { views, scheduler, log } = deps;
  try {
    const oooscar = createOooscarManager({
      world: createOooscarWorld(),
      log,
      sweepRadius: config.world.oooscarSweepRadius,
    });
    // Le monde n'est pas toujours prêt à la seconde où le script se charge : le ménage de départ attend un peu.
    scheduler.after(
      config.startupDelaySeconds,
      guard(log, "ménage de départ", () => {
        const areas = releaseStaleAreas();
        const strays = oooscar.sweep();
        if (areas > 0 || strays > 0) log.info(`ménage de départ : ${areas} zone(s) chargée(s) et ${strays} Oooscar retirée(s)`);
      }),
    );

    const builder = createIslandBuilder({
      defs: ILES,
      writer: createBlockWriter(),
      marks: createMarkStore(),
      scheduler,
      clock: systemClock,
      log,
    });
    const runner = createCinematicRunner({
      stage: createCinematicStage(),
      clock: systemClock,
      log,
      hint: CINEMATIQUE_TEXTES.passer,
    });
    scheduler.every(config.cinematic.tickSeconds, guard(log, "cinématique", runner.tick));

    const sync = createWorldSync({
      defs: ILES,
      players: () => world.getAllPlayers(),
      xuidOf: playerXuid,
      views,
      builder,
      oooscar,
      log,
    });
    scheduler.every(config.world.syncSeconds, guard(log, "îles", sync.tick));

    const arrivalWorld = createArrivalWorld();
    const arrivals = createArrivals<Player>({
      defs: ILES,
      builder,
      oooscar,
      runner,
      world: arrivalWorld,
      keyOf: playerXuid,
      texts: { speaker: CINEMATIQUE_TEXTES.qui, plans: CINEMATIQUE_TEXTES.plans },
      scheduler,
      log,
      remember: sync.remember,
    });
    return {
      hooks: {
        arriving: arrivals.arriving,
        joined: arrivals.joined,
        joinFailed: arrivals.failed,
        left: (event) => arrivals.left(leavingXuid(event)),
      },
      busy: arrivals.busy,
      travel: createTravel<Player>({ defs: ILES, builder, world: arrivalWorld, originOf: sync.originOf, log }),
      travelCity: createCityTravel<Player>({ defs: ILES, ville: VILLE, builder, world: arrivalWorld, originOf: sync.originOf, log }),
      where: arrivalWorld.locate,
      originOf: sync.originOf,
    };
  } catch (error) {
    log.error(`monde indisponible : ${describeError(error)}`);
    return NO_WORLD;
  }
}

/**
 * Les écrans : HUD, menu, boussole. Isolés du reste : si leur mise en place échoue, le heartbeat et la lecture
 * des événements tournent quand même, et le journal dit pourquoi les écrans manquent.
 */
function startScreens(deps: {
  readonly api: CoreApi;
  readonly views: ViewStore;
  readonly missions: Missions;
  readonly invitations: Invitations;
  readonly announcements: Announcements;
  readonly store: PlayerStore;
  readonly link: LinkState;
  readonly busy: (player: Player) => boolean;
  readonly travel: Travel<Player> | undefined;
  readonly travelCity: CityTravel<Player> | undefined;
  readonly where: ((player: Player) => Pos | undefined) | undefined;
  readonly originOf: ((companyId: number) => Xz | undefined) | undefined;
  readonly scheduler: Scheduler;
  readonly log: Logger;
}): void {
  const { api, views, missions, invitations, announcements, store, link, busy, travel, travelCity, where, originOf, scheduler, log } = deps;
  try {
    const hud = createHud<Player>({
      players: () => world.getAllPlayers(),
      views,
      missions,
      link,
      clock: systemClock,
      status: createStatusLog(log, "HUD"),
      silenced: busy,
    });
    // Après chaque encaissement, le service a pu faire avancer la mission : on la relit.
    // Après être entré dans une entreprise (acceptInvite), le HUD se redessine tout de suite.
    const actions = createActions({
      api,
      views,
      newRequestId: createRequestIdGenerator(),
      log,
      afterCollect: missions.refresh,
      afterJoin: () => hud.refresh(),
    });
    const menu = createMenu<Player>({
      views,
      missions,
      actions,
      invitations,
      // Les joueurs connectés que le service connaît : ceux dont le `join` a répondu.
      online: () =>
        store.xuids().flatMap((xuid) => {
          const joined = store.get(xuid);
          return joined === undefined ? [] : [{ xuid, name: joined.gamertag }];
        }),
      // Les cosmétiques de `join` (le titre Pionnier sous le nom de l'entreprise), plus celui qu'une annonce a appris depuis.
      cosmetics: (xuid) => announcements.cosmeticsOf(xuid, store.get(xuid)?.join.cosmetics),
      ...(travel === undefined ? {} : { travel }),
      ...(travelCity === undefined ? {} : { travelCity }),
      ...(where === undefined ? {} : { where }),
      ...(originOf === undefined ? {} : { originOf }),
      presenter: createFormPresenter(),
      clock: systemClock,
      wait: (seconds) => pause(scheduler, seconds),
      log,
    });
    registerMenuItem(world, {
      inventoryOf: (player) => player.getComponent("minecraft:inventory")?.container,
      makeItem: newMenuItem,
      // Pendant l'arrivée et la cinématique, la boussole attend et le menu ne s'ouvre pas.
      open: (player) => (busy(player) ? undefined : menu.open(player)),
      waitWhile: busy,
      scheduler,
      log,
    });
    startHud(scheduler, guard(log, "HUD", hud.refresh));
  } catch (error) {
    log.error(`écrans indisponibles : ${describeError(error)}`);
  }
}

function main(): void {
  const log = consoleLogger();
  try {
    const result = readSettings();
    if (!result.ok) {
      log.warn(`add-on inactif : ${result.problems.join(" ; ")}`);
      return;
    }
    const { coreUrl, serverId, apiKey } = result.settings;

    const link = createLinkState();
    const api = observeApi(
      createCoreApi(
        createCoreClient({
          transport: createBetaTransport(),
          coreUrl,
          apiKey,
          timeoutSeconds: config.requestTimeoutSeconds,
          readRetries: config.readRetries,
        }),
      ),
      link,
    );
    const scheduler = createSystemScheduler(system);
    const store: PlayerStore = createPlayerStore();
    const views = createViewStore({ players: store, clock: systemClock });

    const missions = createMissions({ api, players: store, say: tellPlayer, log });
    const invitations = createInvitations({ clock: systemClock, players: store, say: tellPlayer, log });
    // Les joueurs présents que le service connaît : ceux dont le `join` a répondu.
    const online = (): readonly Xuid[] => store.xuids();
    const votes = createVotes({ api, online, say: tellPlayer, clock: systemClock, log });
    const announcements = createAnnouncements({ views, online, say: tellPlayer, clock: systemClock, log });

    const worldPart = startWorld({ views, scheduler, log });
    // À l'arrivée, après le monde : les missions du joueur sont lues pour le HUD et le menu, ses invitations en attente et le vote
    // du jour s'il ne l'a pas fait lui sont dits.
    const hooks = combineHooks<Player>(log, worldPart.hooks, {
      joined: async (player) => {
        const xuid = playerXuid(player);
        await missions.arrive(xuid);
        invitations.arrive(xuid);
        await votes.arrive(xuid);
      },
    });
    registerSessionEvents(world, createSessionHandlers<Player>({ api, store, log, hooks }), log);

    const heartbeat = createHeartbeat({
      api,
      serverId,
      onlineXuids: () => world.getAllPlayers().map(playerXuid),
      status: createStatusLog(log, "heartbeat"),
    });
    startHeartbeat(
      scheduler,
      guard(log, "heartbeat", heartbeat.beat),
      config.heartbeatIntervalSeconds,
      config.startupDelaySeconds,
    );

    // Les écouteurs : l'annonce d'une mission terminée, les incidents d'usine (ouverts, réparés), l'invitation à une entreprise,
    // l'annonce d'un changement de parts, celles de la ville, les Gemmes reçues, le boost collectif, les votes, le titre de pionnier
    // et l'alerte « sans courant ». Les autres événements du service arrivent plus tard.
    const listeners = createEventListeners(log);
    listeners.on("ANNONCE", missions.onAnnonce);
    listeners.on("ANNONCE", invitations.onAnnonce);
    listeners.on("INVITATION", invitations.onInvitation);
    const incidents = createIncidents({ api, views, say: tellPlayer, log });
    listeners.on("INCIDENT", incidents.onIncident);
    // La ville : zone achetée, bâtiment posé ou amélioré. Les autres ANNONCE sont lues par missions et invitations, qui ignorent celles-ci.
    const cityEvents = createCityEvents({ api, views, say: tellPlayer, log });
    listeners.on("ANNONCE", cityEvents.onAnnonce);
    // La boutique : les Gemmes livrées, le boost collectif, puis les ANNONCE des votes, du titre de pionnier et de l'alerte sans courant.
    listeners.on("GEMMES_RECUES", announcements.onGemmes);
    listeners.on("BOOST_COLLECTIF", announcements.onBoost);
    listeners.on("ANNONCE", votes.onAnnonce);
    listeners.on("ANNONCE", announcements.onAnnonce);
    // Le curseur est gardé dans une propriété dynamique du monde : un redémarrage ne rejoue pas les annonces déjà dites.
    const poller = createEventPoller({
      api,
      listeners,
      status: createStatusLog(log, "événements"),
      log,
      cursorStore: createEventsCursorStore(),
    });
    startEventPolling(scheduler, poller, log, config.startupDelaySeconds);

    startScreens({
      api,
      views,
      missions,
      invitations,
      announcements,
      store,
      link,
      busy: worldPart.busy,
      travel: worldPart.travel,
      travelCity: worldPart.travelCity,
      where: worldPart.where,
      originOf: worldPart.originOf,
      scheduler,
      log,
    });

    log.info(`add-on démarré (serveur ${serverId})`);
  } catch (error) {
    log.error(`démarrage impossible : ${describeError(error)}`);
  }
}

main();
