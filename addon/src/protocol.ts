// Le contrat avec silooo-core pour les quatre opérations de la phase 2 : heartbeat, join, leave et
// pollEvents. Types écrits d'après api/silooo-core.openapi.yaml, fonctions pures qui fabriquent les
// corps et lisent les réponses. Quand le contrat change, il change d'abord dans l'OpenAPI.

// ---------------------------------------------------------------- types du contrat

/** XUID en chaîne : un entier 64 bits dépasse la précision des nombres JavaScript. */
export type Xuid = string;

export interface Balances {
  lingots: number;
  gemmes: number;
  jetons: number;
  cles: number;
}

export type FactoryState = "EN_RUINE" | "REMISE_EN_ETAT" | "ACTIVE";

export interface Factory {
  factoryId: string;
  state: FactoryState;
  restoreStep?: number;
  level: number;
  ratePerHour: number;
  nextCost: number;
  nextMilestone: number;
  incidentId?: number | null;
  stoppedNoPower?: boolean;
}

export interface CompanyState {
  companyId: number;
  name: string;
  era: number;
  stars: number;
  courseNumber: number;
  donar: number;
  donarEarned: number;
  ratePerHour: number;
  multiplier: number;
  threshold: number;
  factories: Factory[];
  zonesOwned?: number[];
  version: number;
}

export interface JoinResponse {
  xuid: Xuid;
  firstJoin: boolean;
  tutorialStep?: number;
  companyId: number;
  role?: "PDG" | "ASSOCIE";
  rank?: string | null;
  pilierUntil?: string | null;
  balances: Balances;
  state: CompanyState;
  offlineCollected?: number;
}

export const GAME_EVENT_KINDS = [
  "GEMMES_RECUES",
  "COMMANDE_TERMINEE",
  "PRESTIGE_ENTREPRISE",
  "INVITATION",
  "VENTE_MARCHE",
  "INCIDENT",
  "BOOST_COLLECTIF",
  "ANNONCE",
] as const;

export type GameEventKind = (typeof GAME_EVENT_KINDS)[number];

export interface GameEvent {
  id: number;
  xuid: Xuid | null;
  /** Un type du contrat, ou un type plus récent que cet add-on : le registre l'ignore alors. */
  kind: string;
  payload: Record<string, unknown>;
}

export interface EventsPage {
  /** Identifiant du dernier événement rendu, ou `after` s'il n'y en a pas. */
  cursor: number;
  events: GameEvent[];
  /** Événements de la réponse dont la forme était illisible : ignorés, le curseur les dépasse. */
  skipped: number;
}

/** Codes de `Refused` (409). L'add-on choisit l'icône et la bulle d'Oooscar d'après eux. */
export const REFUSAL_REASONS = [
  "PAS_ASSEZ_DE_DONAR",
  "PAS_ASSEZ_DE_LINGOTS",
  "PAS_ASSEZ_DE_GEMMES",
  "PAS_LE_PDG",
  "SEUIL_NON_ATTEINT",
  "USINE_VERROUILLEE",
  "ZONE_VERROUILLEE",
  "EMPLACEMENT_OCCUPE",
  "ENTREPRISE_PLEINE",
  "DEJA_POSSEDE",
  "GRADE_PRECEDENT_REQUIS",
  "CONFLIT_REESSAYER",
] as const;

export type RefusalReason = (typeof REFUSAL_REASONS)[number];

/** Codes de `Erreur` (400, 401, 500, 501). */
export const ERROR_REASONS = ["REQUETE_INVALIDE", "CLE_INVALIDE", "ERREUR_INTERNE", "PAS_FAIT"] as const;

export type ErrorReason = (typeof ERROR_REASONS)[number];

// ---------------------------------------------------------------- chemins et corps de requête

export const PATHS = {
  heartbeat: "/v1/server/heartbeat",
  join: "/v1/session/join",
  leave: "/v1/session/leave",
} as const;

/** Bornes de `limit` dans l'OpenAPI (pollEvents) : de 1 à 500. */
export const EVENTS_LIMIT_MIN = 1;
export const EVENTS_LIMIT_MAX = 500;

export interface JoinBody {
  xuid: Xuid;
  gamertag: string;
}

export interface LeaveBody {
  xuid: Xuid;
}

export interface HeartbeatBody {
  serverId: string;
  onlineXuids: Xuid[];
}

export function joinBody(xuid: Xuid, gamertag: string): JoinBody {
  return { xuid, gamertag };
}

export function leaveBody(xuid: Xuid): LeaveBody {
  return { xuid };
}

export function heartbeatBody(serverId: string, onlineXuids: readonly Xuid[]): HeartbeatBody {
  return { serverId, onlineXuids: [...onlineXuids] };
}

/** Chemin et requête de GET /v1/events. `after` et `limit` sont ramenés dans les bornes du contrat. */
export function eventsUrl(after: number, limit: number): string {
  const safeAfter = Number.isFinite(after) ? Math.max(0, Math.trunc(after)) : 0;
  const safeLimit = Number.isFinite(limit)
    ? Math.min(EVENTS_LIMIT_MAX, Math.max(EVENTS_LIMIT_MIN, Math.trunc(limit)))
    : EVENTS_LIMIT_MAX;
  return `/v1/events?after=${safeAfter}&limit=${safeLimit}`;
}

// ---------------------------------------------------------------- lecture des réponses

export type Parsed<T> = { readonly ok: true; readonly value: T } | { readonly ok: false; readonly problem: string };

class ShapeError extends Error {}

type Rec = Record<string, unknown>;

function attempt<T>(read: () => T): Parsed<T> {
  try {
    return { ok: true, value: read() };
  } catch (error) {
    if (error instanceof ShapeError) return { ok: false, problem: error.message };
    throw error;
  }
}

function record(value: unknown, path: string): Rec {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ShapeError(`${path} : objet attendu`);
  }
  return value as Rec;
}

function list(value: unknown, path: string): unknown[] {
  if (!Array.isArray(value)) throw new ShapeError(`${path} : tableau attendu`);
  return value as unknown[];
}

function num(rec: Rec, key: string, path: string): number {
  const value = rec[key];
  if (typeof value !== "number" || !Number.isFinite(value)) throw new ShapeError(`${path}.${key} : nombre attendu`);
  return value;
}

function str(rec: Rec, key: string, path: string): string {
  const value = rec[key];
  if (typeof value !== "string") throw new ShapeError(`${path}.${key} : chaîne attendue`);
  return value;
}

function bool(rec: Rec, key: string, path: string): boolean {
  const value = rec[key];
  if (typeof value !== "boolean") throw new ShapeError(`${path}.${key} : booléen attendu`);
  return value;
}

function oneOf<T extends string>(rec: Rec, key: string, path: string, allowed: readonly T[]): T {
  const value = rec[key];
  const found = allowed.find((candidate) => candidate === value);
  if (found === undefined) throw new ShapeError(`${path}.${key} : une de ${allowed.join(", ")} attendue`);
  return found;
}

function readBalances(value: unknown, path: string): Balances {
  const rec = record(value, path);
  return {
    lingots: num(rec, "lingots", path),
    gemmes: num(rec, "gemmes", path),
    jetons: num(rec, "jetons", path),
    cles: num(rec, "cles", path),
  };
}

function readFactory(value: unknown, path: string): Factory {
  const rec = record(value, path);
  const factory: Factory = {
    factoryId: str(rec, "factoryId", path),
    state: oneOf(rec, "state", path, ["EN_RUINE", "REMISE_EN_ETAT", "ACTIVE"]),
    level: num(rec, "level", path),
    ratePerHour: num(rec, "ratePerHour", path),
    nextCost: num(rec, "nextCost", path),
    nextMilestone: num(rec, "nextMilestone", path),
  };
  if (rec["restoreStep"] !== undefined) factory.restoreStep = num(rec, "restoreStep", path);
  if (rec["incidentId"] !== undefined) factory.incidentId = rec["incidentId"] === null ? null : num(rec, "incidentId", path);
  if (rec["stoppedNoPower"] !== undefined) factory.stoppedNoPower = bool(rec, "stoppedNoPower", path);
  return factory;
}

function readCompanyState(value: unknown, path: string): CompanyState {
  const rec = record(value, path);
  const state: CompanyState = {
    companyId: num(rec, "companyId", path),
    name: str(rec, "name", path),
    era: num(rec, "era", path),
    stars: num(rec, "stars", path),
    courseNumber: num(rec, "courseNumber", path),
    donar: num(rec, "donar", path),
    donarEarned: num(rec, "donarEarned", path),
    ratePerHour: num(rec, "ratePerHour", path),
    multiplier: num(rec, "multiplier", path),
    threshold: num(rec, "threshold", path),
    factories: list(rec["factories"], `${path}.factories`).map((item, i) =>
      readFactory(item, `${path}.factories[${i}]`),
    ),
    version: num(rec, "version", path),
  };
  if (rec["zonesOwned"] !== undefined) {
    state.zonesOwned = list(rec["zonesOwned"], `${path}.zonesOwned`).map((zone, i) => {
      if (typeof zone !== "number") throw new ShapeError(`${path}.zonesOwned[${i}] : nombre attendu`);
      return zone;
    });
  }
  return state;
}

function readNullableString(rec: Rec, key: string, path: string): string | null | undefined {
  const value = rec[key];
  if (value === undefined) return undefined;
  if (value === null) return null;
  return str(rec, key, path);
}

/** Lit le corps 200 de join. Une clé du contrat manquante ou de mauvais type donne un problème lisible. */
export function parseJoinResponse(body: unknown): Parsed<JoinResponse> {
  return attempt(() => {
    const rec = record(body, "join");
    const join: JoinResponse = {
      xuid: str(rec, "xuid", "join"),
      firstJoin: bool(rec, "firstJoin", "join"),
      companyId: num(rec, "companyId", "join"),
      balances: readBalances(rec["balances"], "join.balances"),
      state: readCompanyState(rec["state"], "join.state"),
    };
    if (rec["tutorialStep"] !== undefined) join.tutorialStep = num(rec, "tutorialStep", "join");
    if (rec["role"] !== undefined) join.role = oneOf(rec, "role", "join", ["PDG", "ASSOCIE"]);
    const rank = readNullableString(rec, "rank", "join");
    if (rank !== undefined) join.rank = rank;
    const pilierUntil = readNullableString(rec, "pilierUntil", "join");
    if (pilierUntil !== undefined) join.pilierUntil = pilierUntil;
    if (rec["offlineCollected"] !== undefined) join.offlineCollected = num(rec, "offlineCollected", "join");
    return join;
  });
}

/**
 * Lit le corps 200 de pollEvents. Un événement illisible est compté dans `skipped` et ignoré : un seul
 * mauvais élément ne doit pas bloquer le flux, et le curseur de la réponse le dépasse.
 */
export function parseEventsPage(body: unknown): Parsed<EventsPage> {
  return attempt(() => {
    const rec = record(body, "events");
    const cursor = num(rec, "cursor", "events");
    const events: GameEvent[] = [];
    let skipped = 0;
    for (const item of list(rec["events"], "events.events")) {
      const event = attempt(() => readEvent(item));
      if (event.ok) events.push(event.value);
      else skipped += 1;
    }
    return { cursor, events, skipped };
  });
}

function readEvent(value: unknown): GameEvent {
  const rec = record(value, "event");
  const xuid = rec["xuid"];
  if (xuid !== undefined && xuid !== null && typeof xuid !== "string") throw new ShapeError("event.xuid : chaîne attendue");
  const payload = rec["payload"] === undefined ? {} : record(rec["payload"], "event.payload");
  return {
    id: num(rec, "id", "event"),
    xuid: xuid ?? null,
    kind: str(rec, "kind", "event"),
    payload,
  };
}
