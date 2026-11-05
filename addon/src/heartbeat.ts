// Le signal de vie : toutes les N secondes, l'add-on dit à silooo-core que le serveur tourne et
// qui est en ligne (POST /v1/server/heartbeat). Le service ne peut pas appeler l'add-on, donc ce
// signal est son seul moyen de savoir que le serveur est là.

import type { CoreApi } from "./api";
import { describeFailure } from "./http";
import type { Xuid } from "./protocol";
import { describeError, type Scheduler, type StatusLog } from "./runtime";

export interface HeartbeatDeps {
  readonly api: Pick<CoreApi, "heartbeat">;
  readonly serverId: string;
  /** Les XUID des joueurs en ligne, lus à chaque envoi. */
  readonly onlineXuids: () => readonly Xuid[];
  readonly status: StatusLog;
}

export interface Heartbeat {
  /** Un envoi. Rend sans rien faire si le précédent n'est pas fini. */
  beat(): Promise<void>;
}

export function createHeartbeat(deps: HeartbeatDeps): Heartbeat {
  let inFlight = false;
  return {
    async beat() {
      if (inFlight) return;
      inFlight = true;
      try {
        const result = await deps.api.heartbeat(deps.serverId, deps.onlineXuids());
        if (result.kind === "ok") deps.status.ok();
        else deps.status.failed(describeFailure(result));
      } catch (error) {
        deps.status.failed(describeError(error));
      } finally {
        inFlight = false;
      }
    },
  };
}

/** Un premier envoi après le délai de démarrage, puis un toutes les `intervalSeconds`. */
export function startHeartbeat(
  scheduler: Scheduler,
  run: () => void,
  intervalSeconds: number,
  startupDelaySeconds: number,
): void {
  scheduler.after(startupDelaySeconds, run);
  scheduler.every(intervalSeconds, run);
}
