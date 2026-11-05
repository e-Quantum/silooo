// Une fonction typée par opération de l'OpenAPI utilisée par l'add-on. Chaque fonction fabrique le corps
// (protocol.ts), appelle le client (http.ts) et lit la réponse. Ce fichier ne décide de rien.

import { describeError } from "./runtime";
import type { ApiResult, CoreClient } from "./http";
import {
  actionBody,
  buyLevelsBody,
  collectPath,
  companyPath,
  eventsUrl,
  heartbeatBody,
  isRestoreStep,
  joinBody,
  leaveBody,
  levelsPath,
  missionsPath,
  parseCollectResponse,
  parseCompanyState,
  parseCompanyUpdated,
  parseEventsPage,
  parseJoinResponse,
  parseMissionState,
  parsePrestigePreview,
  PATHS,
  prestigePath,
  prestigePreviewPath,
  restorePath,
  restoreStepBody,
  type ActionBase,
  type BuyAmount,
  type CollectResponse,
  type CompanyState,
  type CompanyUpdated,
  type EventsPage,
  type JoinResponse,
  type MissionState,
  type Parsed,
  type PrestigePreview,
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
  /** GET /v1/companies/{companyId} (getCompany), 200 CompanyState. */
  getCompany(companyId: number): Promise<ApiResult<CompanyState>>;
  /** POST /v1/companies/{companyId}/collect (collect), 200 ou 409 Refused. */
  collect(companyId: number, action: ActionBase): Promise<ApiResult<CollectResponse>>;
  /** POST /v1/companies/{companyId}/factories/{factoryId}/levels (buyLevels), 200 CompanyUpdated ou 409 Refused. */
  buyLevels(companyId: number, factoryId: string, action: ActionBase, amount: BuyAmount): Promise<ApiResult<CompanyUpdated>>;
  /** POST /v1/companies/{companyId}/factories/{factoryId}/restore (restoreStep), 200 CompanyUpdated ou 409 Refused. */
  restoreStep(companyId: number, factoryId: string, action: ActionBase, step: number): Promise<ApiResult<CompanyUpdated>>;
  /** GET /v1/players/{xuid}/missions (getMissions), 200 MissionState. */
  getMissions(xuid: Xuid): Promise<ApiResult<MissionState>>;
  /** GET /v1/companies/{companyId}/prestige/preview (prestigePreview), 200 PrestigePreview. */
  prestigePreview(companyId: number): Promise<ApiResult<PrestigePreview>>;
  /** POST /v1/companies/{companyId}/prestige (prestige), 200 CompanyUpdated, 403 PAS_LE_PDG ou 409 SEUIL_NON_ATTEINT. */
  prestige(companyId: number, action: ActionBase): Promise<ApiResult<CompanyUpdated>>;
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

/**
 * Un argument que le contrat refuserait (identifiant illisible, étape hors 1 à 4) n'envoie aucune requête :
 * le résultat est une erreur locale, de statut 0, au code REQUETE_INVALIDE.
 */
function invalidArgument(problem: string): ApiResult<never> {
  return { kind: "error", status: 0, reason: "REQUETE_INVALIDE", detail: problem };
}

function pathOrError(build: () => string): { readonly path: string } | { readonly failure: ApiResult<never> } {
  try {
    return { path: build() };
  } catch (error) {
    return { failure: invalidArgument(describeError(error)) };
  }
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
    async getCompany(companyId) {
      const target = pathOrError(() => companyPath(companyId));
      if ("failure" in target) return target.failure;
      return readBody(await client.get(target.path), parseCompanyState);
    },
    async collect(companyId, action) {
      const target = pathOrError(() => collectPath(companyId));
      if ("failure" in target) return target.failure;
      return readBody(await client.post(target.path, actionBody(action)), parseCollectResponse);
    },
    async buyLevels(companyId, factoryId, action, amount) {
      const target = pathOrError(() => levelsPath(companyId, factoryId));
      if ("failure" in target) return target.failure;
      return readBody(await client.post(target.path, buyLevelsBody(action, amount)), parseCompanyUpdated);
    },
    async restoreStep(companyId, factoryId, action, step) {
      if (!isRestoreStep(step)) return invalidArgument(`step invalide : ${step}`);
      const target = pathOrError(() => restorePath(companyId, factoryId));
      if ("failure" in target) return target.failure;
      return readBody(await client.post(target.path, restoreStepBody(action, step)), parseCompanyUpdated);
    },
    async getMissions(xuid) {
      const target = pathOrError(() => missionsPath(xuid));
      if ("failure" in target) return target.failure;
      return readBody(await client.get(target.path), parseMissionState);
    },
    async prestigePreview(companyId) {
      const target = pathOrError(() => prestigePreviewPath(companyId));
      if ("failure" in target) return target.failure;
      return readBody(await client.get(target.path), parsePrestigePreview);
    },
    async prestige(companyId, action) {
      const target = pathOrError(() => prestigePath(companyId));
      if ("failure" in target) return target.failure;
      return readBody(await client.post(target.path, actionBody(action)), parseCompanyUpdated);
    },
  };
}
