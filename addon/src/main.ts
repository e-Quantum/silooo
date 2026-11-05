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
import { playerXuid } from "./identity";
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
import { createSessionHandlers, registerSessionEvents } from "./session";
import { createPlayerStore, type PlayerStore } from "./state";
import { createActions } from "./ui/actions";
import { registerMenuItem } from "./ui/boussole";
import { createLinkState, createViewStore, observeApi, type LinkState } from "./ui/etat";
import { createFormPresenter } from "./ui/formulaires";
import { createHud, startHud } from "./ui/hud";
import { createMenu } from "./ui/menu";
import { BOUSSOLE_TEXTES } from "./ui/textes";

/** L'objet du menu, tel que le joueur le reçoit : nommé, gardé à la mort, impossible à jeter ou à ranger ailleurs. */
function newMenuItem(): ItemStack {
  const item = new ItemStack(config.ui.menuItem, 1);
  item.nameTag = BOUSSOLE_TEXTES.nom;
  item.keepOnDeath = true;
  item.lockMode = ItemLockMode.inventory;
  return item;
}

/**
 * Les écrans : HUD, menu, boussole. Isolés du reste : si leur mise en place échoue, le heartbeat et la lecture
 * des événements tournent quand même, et le journal dit pourquoi les écrans manquent.
 */
function startScreens(deps: {
  readonly api: CoreApi;
  readonly store: PlayerStore;
  readonly link: LinkState;
  readonly scheduler: Scheduler;
  readonly log: Logger;
}): void {
  const { api, store, link, scheduler, log } = deps;
  try {
    const views = createViewStore({ players: store, clock: systemClock });
    const actions = createActions({ api, views, newRequestId: createRequestIdGenerator(), log });
    const menu = createMenu<Player>({
      views,
      actions,
      presenter: createFormPresenter(),
      clock: systemClock,
      wait: (seconds) => pause(scheduler, seconds),
      log,
    });
    registerMenuItem(world, {
      inventoryOf: (player) => player.getComponent("minecraft:inventory")?.container,
      makeItem: newMenuItem,
      open: menu.open,
      scheduler,
      log,
    });
    const hud = createHud({
      players: () => world.getAllPlayers(),
      views,
      link,
      clock: systemClock,
      status: createStatusLog(log, "HUD"),
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
    const store = createPlayerStore();

    registerSessionEvents(world, createSessionHandlers({ api, store, log }), log);

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

    // Aucun écouteur d'événements pour l'instant : l'affichage des événements du service arrive plus tard (listeners.on).
    const listeners = createEventListeners(log);
    startEventPolling(
      scheduler,
      createEventPoller({ api, listeners, status: createStatusLog(log, "événements"), log }),
      log,
      config.startupDelaySeconds,
    );

    startScreens({ api, store, link, scheduler, log });

    log.info(`add-on démarré (serveur ${serverId})`);
  } catch (error) {
    log.error(`démarrage impossible : ${describeError(error)}`);
  }
}

main();
