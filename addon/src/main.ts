// Le point d'entrée du pack de comportement (scripts/main.js après esbuild). Il lit les réglages,
// branche les pièces et ne laisse jamais une exception tuer le script : sans réglages, l'add-on reste
// inerte et le dit dans le journal du serveur.

import { system, world } from "@minecraft/server";
import { createCoreApi } from "./api";
import { createBetaTransport, readSettings } from "./beta";
import { config } from "./config";
import { createEventListeners, createEventPoller, startEventPolling } from "./events";
import { createHeartbeat, startHeartbeat } from "./heartbeat";
import { createCoreClient } from "./http";
import { playerXuid } from "./identity";
import { consoleLogger, createStatusLog, createSystemScheduler, describeError, guard } from "./runtime";
import { createSessionHandlers, registerSessionEvents } from "./session";
import { createPlayerStore } from "./state";

function main(): void {
  const log = consoleLogger();
  try {
    const result = readSettings();
    if (!result.ok) {
      log.warn(`add-on inactif : ${result.problems.join(" ; ")}`);
      return;
    }
    const { coreUrl, serverId, apiKey } = result.settings;

    const api = createCoreApi(
      createCoreClient({
        transport: createBetaTransport(),
        coreUrl,
        apiKey,
        timeoutSeconds: config.requestTimeoutSeconds,
        readRetries: config.readRetries,
      }),
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

    // Aucun écouteur en phase 2 : l'affichage des événements arrive en phase 3 (listeners.on).
    const listeners = createEventListeners(log);
    startEventPolling(
      scheduler,
      createEventPoller({ api, listeners, status: createStatusLog(log, "événements"), log }),
      log,
      config.startupDelaySeconds,
    );

    log.info(`add-on démarré (serveur ${serverId})`);
  } catch (error) {
    log.error(`démarrage impossible : ${describeError(error)}`);
  }
}

main();
