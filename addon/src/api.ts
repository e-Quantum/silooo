// Une fonction typée par opération de l'OpenAPI utilisée en phase 2. Chaque fonction fabrique le corps
// (protocol.ts), appelle le client (http.ts) et lit la réponse. Ce fichier ne décide de rien.

import type { ApiResult, CoreClient } from "./http";
import {
  eventsUrl,
  heartbeatBody,
  joinBody,
  leaveBody,
  parseEventsPage,
  parseJoinResponse,
  PATHS,
  type EventsPage,
  type JoinResponse,
  type Parsed,
  type Xuid,
} from "./protocol";

export interface CoreApi {
  /** POST /v1/server/heartbeat (heartbeat), 204. */
  heartbeat(serverId: string, onlineXuids: readonly Xuid[]): Promise<ApiResult<null>>;
  /** POST /v1/session/join (join), 200 JoinResponse ou 409 Refused. */
  join(xuid: Xuid, gamertag: string): Promise<ApiResult<JoinResponse>>;
  /** POST /v1/session/leave (leave), 204. */
  leave(xuid: Xuid): Promise<ApiResult<null>>;
  /** GET /v1/events (pollEvents), 200. */
  pollEvents(after: number, limit: number): Promise<ApiResult<EventsPage>>;
}

/** Remplace le corps d'un succès par sa lecture ; une forme hors contrat devient une erreur. */
function readBody<T>(result: ApiResult<unknown>, read: (body: unknown) => Parsed<T>): ApiResult<T> {
  if (result.kind !== "ok") return result;
  const parsed = read(result.body);
  if (parsed.ok) return { kind: "ok", status: result.status, body: parsed.value };
  return { kind: "error", status: result.status, reason: "REPONSE_INATTENDUE", detail: parsed.problem };
}

/** Un 204 n'a pas de corps à lire. */
function noBody(result: ApiResult<unknown>): ApiResult<null> {
  return result.kind === "ok" ? { kind: "ok", status: result.status, body: null } : result;
}

export function createCoreApi(client: CoreClient): CoreApi {
  return {
    async heartbeat(serverId, onlineXuids) {
      return noBody(await client.post(PATHS.heartbeat, heartbeatBody(serverId, onlineXuids)));
    },
    async join(xuid, gamertag) {
      return readBody(await client.post(PATHS.join, joinBody(xuid, gamertag)), parseJoinResponse);
    },
    async leave(xuid) {
      return noBody(await client.post(PATHS.leave, leaveBody(xuid)));
    },
    async pollEvents(after, limit) {
      return readBody(await client.get(eventsUrl(after, limit)), parseEventsPage);
    },
  };
}
