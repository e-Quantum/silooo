// Une fonction typée par opération de l'OpenAPI utilisée par l'add-on. Chaque fonction fabrique le corps
// (protocol.ts), appelle le client (http.ts) et lit la réponse. Ce fichier ne décide de rien.

import { describeError } from "./runtime";
import type { ApiResult, CoreClient } from "./http";
import {
  acceptInvitePath,
  actionBody,
  agencyPath,
  assignStaffBody,
  buyLevelsBody,
  collectPath,
  companyPath,
  eventsUrl,
  fireStaffPath,
  heartbeatBody,
  hireBody,
  hirePath,
  inviteBody,
  isRestoreStep,
  isSharePercent,
  isXuid,
  joinBody,
  leaveBody,
  levelsPath,
  membersPath,
  missionsPath,
  parseCandidates,
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
  resolveIncidentPath,
  restorePath,
  restoreStepBody,
  setSharesBody,
  staffPath,
  type ActionBase,
  type BuyAmount,
  type Candidate,
  type CollectResponse,
  type CompanyState,
  type CompanyUpdated,
  type EventsPage,
  type JoinResponse,
  type MissionState,
  type Parsed,
  type PrestigePreview,
  type ShareInput,
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
  /**
   * POST /v1/companies/{companyId}/incidents/{incidentId}/resolve (resolveIncident), 200 CompanyUpdated ou 409 Refused.
   * Le service ne juge pas le mini-jeu : l'add-on l'a joué, le service clôt l'incident. Un incident déjà clos rend le même 200.
   */
  resolveIncident(companyId: number, incidentId: number, action: ActionBase): Promise<ApiResult<CompanyUpdated>>;
  /** GET /v1/players/{xuid}/missions (getMissions), 200 MissionState. */
  getMissions(xuid: Xuid): Promise<ApiResult<MissionState>>;
  /** GET /v1/companies/{companyId}/prestige/preview (prestigePreview), 200 PrestigePreview. */
  prestigePreview(companyId: number): Promise<ApiResult<PrestigePreview>>;
  /** POST /v1/companies/{companyId}/prestige (prestige), 200 CompanyUpdated, 403 PAS_LE_PDG ou 409 SEUIL_NON_ATTEINT. */
  prestige(companyId: number, action: ActionBase): Promise<ApiResult<CompanyUpdated>>;
  /** GET /v1/companies/{companyId}/agency (getCandidates), 200 : les candidats du jour. */
  getCandidates(companyId: number): Promise<ApiResult<Candidate[]>>;
  /** POST /v1/companies/{companyId}/agency/{slot}/hire (hire), 200 CompanyUpdated ou 409 Refused. Sans usine, le candidat reste sans poste. */
  hire(companyId: number, slot: number, action: ActionBase, factoryId?: string): Promise<ApiResult<CompanyUpdated>>;
  /** PATCH /v1/companies/{companyId}/staff/{staffId} (assignStaff), 200 CompanyUpdated ou 409 Refused. */
  assignStaff(companyId: number, staffId: number, action: ActionBase, factoryId: string): Promise<ApiResult<CompanyUpdated>>;
  /** DELETE /v1/companies/{companyId}/staff/{staffId} (fireStaff), 200 CompanyUpdated, 403 PAS_LE_PDG ou 409 Refused. */
  fireStaff(companyId: number, staffId: number, action: ActionBase): Promise<ApiResult<CompanyUpdated>>;
  /** POST /v1/companies/{companyId}/members (invite), 200 CompanyUpdated (inchangé), 403 PAS_LE_PDG ou 409 Refused. */
  invite(companyId: number, action: ActionBase, targetXuid: Xuid): Promise<ApiResult<CompanyUpdated>>;
  /** PUT /v1/companies/{companyId}/members (setShares), 200 CompanyUpdated, 400 si les parts ne font pas 100, 403 PAS_LE_PDG ou 409 Refused. */
  setShares(companyId: number, action: ActionBase, shares: readonly ShareInput[]): Promise<ApiResult<CompanyUpdated>>;
  /** POST /v1/companies/{companyId}/members/accept (acceptInvite), 200 CompanyUpdated de l'entreprise rejointe, ou 409 Refused. `companyId` est celle qui a invité. */
  acceptInvite(companyId: number, action: ActionBase): Promise<ApiResult<CompanyUpdated>>;
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
    async resolveIncident(companyId, incidentId, action) {
      const target = pathOrError(() => resolveIncidentPath(companyId, incidentId));
      if ("failure" in target) return target.failure;
      return readBody(await client.post(target.path, actionBody(action)), parseCompanyUpdated);
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
    async getCandidates(companyId) {
      const target = pathOrError(() => agencyPath(companyId));
      if ("failure" in target) return target.failure;
      return readBody(await client.get(target.path), parseCandidates);
    },
    async hire(companyId, slot, action, factoryId) {
      const target = pathOrError(() => hirePath(companyId, slot));
      if ("failure" in target) return target.failure;
      if (factoryId === "") return invalidArgument("factoryId vide");
      return readBody(await client.post(target.path, hireBody(action, factoryId)), parseCompanyUpdated);
    },
    async assignStaff(companyId, staffId, action, factoryId) {
      const target = pathOrError(() => staffPath(companyId, staffId));
      if ("failure" in target) return target.failure;
      if (factoryId === "") return invalidArgument("factoryId vide");
      return readBody(await client.patch(target.path, assignStaffBody(action, factoryId)), parseCompanyUpdated);
    },
    async fireStaff(companyId, staffId, action) {
      const target = pathOrError(() => fireStaffPath(companyId, staffId, action));
      if ("failure" in target) return target.failure;
      return readBody(await client.delete(target.path), parseCompanyUpdated);
    },
    async invite(companyId, action, targetXuid) {
      const target = pathOrError(() => membersPath(companyId));
      if ("failure" in target) return target.failure;
      if (!isXuid(targetXuid)) return invalidArgument(`targetXuid invalide : ${targetXuid}`);
      return readBody(await client.post(target.path, inviteBody(action, targetXuid)), parseCompanyUpdated);
    },
    async setShares(companyId, action, shares) {
      const target = pathOrError(() => membersPath(companyId));
      if ("failure" in target) return target.failure;
      // Le format d'une part (un XUID, un entier de 0 à 100) est vérifié ici ; la somme, jamais : c'est le service qui la juge.
      for (const share of shares) {
        if (!isXuid(share.xuid)) return invalidArgument(`xuid invalide : ${share.xuid}`);
        if (!isSharePercent(share.percent)) return invalidArgument(`part invalide : ${share.percent}`);
      }
      return readBody(await client.put(target.path, setSharesBody(action, shares)), parseCompanyUpdated);
    },
    async acceptInvite(companyId, action) {
      const target = pathOrError(() => acceptInvitePath(companyId));
      if ("failure" in target) return target.failure;
      return readBody(await client.post(target.path, actionBody(action)), parseCompanyUpdated);
    },
  };
}
