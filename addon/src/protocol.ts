// Le contrat avec silooo-core : heartbeat, join, leave et pollEvents (phase 2), puis getCompany, collect,
// buyLevels et restoreStep, puis getMissions (phase 3), puis le prestige et le personnel (phase 4). Types écrits
// d'après api/silooo-core.openapi.yaml, fonctions pures qui fabriquent les corps et lisent les réponses. Quand le
// contrat change, il change d'abord dans l'OpenAPI.

import type { PersonnelDefinitions } from "./generated/definitions";

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
  /** La sorte de l'incident ouvert (`definitions/incidents.json`, tableau `sortes`) ; nulle sans incident. Elle dit quel mini-jeu proposer. */
  incidentKind?: string | null;
  stoppedNoPower?: boolean;
}

export interface IslandOrigin {
  x: number;
  z: number;
}

/** Les rôles de `Staff.role`. Le contrat connaît DIRECTEUR, le service n'en rend pas encore : l'add-on le lit quand même. */
export const STAFF_ROLES = ["SALARIE", "CHEF", "DIRECTEUR"] as const;

export type StaffRole = (typeof STAFF_ROLES)[number];

/** Les raretés de `Staff.rarity`, celles de `definitions/personnel.json` (le type est tiré du fichier généré). */
export type StaffRarity = keyof PersonnelDefinitions["raretes"];

// Les codes sont des clés et non des chaînes : la charte des textes bannit un des mots (design/README.md), et
// tools/anti_ia.py lit toutes les chaînes de src/. TypeScript refuse une rareté oubliée ou en trop.
const RARITY_CODES: Readonly<Record<StaffRarity, true>> = { COMMUN: true, RARE: true, EPIQUE: true };

export const STAFF_RARITIES: readonly StaffRarity[] = Object.keys(RARITY_CODES) as StaffRarity[];

/**
 * Un candidat du jour ou un membre du personnel (`Staff` du contrat). `staffId` n'existe qu'une fois embauché, `slot`
 * que pour un candidat du jour. Tous les montants sont ceux du service, jamais recalculés ici.
 */
export interface Staff {
  staffId?: number;
  slot?: number;
  name: string;
  /** L'apparence, telle que le service la donne. */
  skinId: string;
  role: StaffRole;
  rarity: StaffRarity;
  /** Le gain de production de la rareté, en pourcent de la production de l'usine où le membre travaille. */
  bonusPercent: number;
  /** Le salaire, en pourcent du revenu de l'usine. */
  salaryPercent?: number;
  /** Ce que ce salaire coûte à l'entreprise en Donar par heure, maintenant : zéro tant que le membre n'a pas d'usine active. */
  salaryPerHour?: number;
  /** Le prix d'embauche d'un candidat libre, en Donar. */
  hireCost?: number;
  /** L'usine du membre ; nul (ou absent) tant qu'il n'en a pas. */
  factoryId?: string | null;
}

/** Un membre embauché de `CompanyState.staff` : il a toujours un `staffId`. */
export type StaffMember = Staff & { staffId: number };

/** Les rôles de `Member.role` : le PDG décide, l'associé entre par invitation. */
export const MEMBER_ROLES = ["PDG", "ASSOCIE"] as const;

export type MemberRole = (typeof MEMBER_ROLES)[number];

/**
 * Un membre de l'entreprise, PDG ou associé (`Member` du contrat). `sharePercent` est sa part du partage des Lingots au
 * prestige, en pourcent entier : le service les fait toujours sommer à 100, l'add-on les affiche sans les calculer.
 */
export interface Member {
  xuid: Xuid;
  /** Le gamertag du joueur. */
  name: string;
  role: MemberRole;
  sharePercent: number;
}

/** Un candidat de `getCandidates` : il a toujours une place, de 1 à 5. Embauché, il porte aussi `staffId`. */
export type Candidate = Staff & { slot: number };

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
  /** Origine de l'archipel de l'entreprise, en blocs. Le service la donne dès qu'il l'a attribuée ; l'add-on la lit, il ne la calcule pas. */
  island?: IslandOrigin;
  /** Le personnel embauché, affecté ou non. Le contrat ne le marque pas obligatoire : absent, les écrans le lisent comme vide. */
  staff?: StaffMember[];
  version: number;
  /** Les membres, le PDG d'abord puis les associés dans l'ordre d'arrivée. Facultatif au contrat : absent, les écrans le lisent comme vide. */
  members?: Member[];
  /** La ville de l'entreprise (mairie, prochaine zone, bâtiments, bonus). Absente tant que le service ne la rend pas : les écrans la lisent comme « pas de ville ». */
  city?: CityState;
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
  "ENTREPRISE_EN_COURS",
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
  if (rec["incidentKind"] !== undefined) factory.incidentKind = rec["incidentKind"] === null ? null : str(rec, "incidentKind", path);
  if (rec["stoppedNoPower"] !== undefined) factory.stoppedNoPower = bool(rec, "stoppedNoPower", path);
  return factory;
}

function readIslandOrigin(value: unknown, path: string): IslandOrigin {
  const rec = record(value, path);
  const x = num(rec, "x", path);
  const z = num(rec, "z", path);
  if (!Number.isInteger(x)) throw new ShapeError(`${path}.x : entier attendu`);
  if (!Number.isInteger(z)) throw new ShapeError(`${path}.z : entier attendu`);
  return { x, z };
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
  if (rec["island"] !== undefined) state.island = readIslandOrigin(rec["island"], `${path}.island`);
  if (rec["staff"] !== undefined) {
    state.staff = list(rec["staff"], `${path}.staff`).map((item, i) => readMember(item, `${path}.staff[${i}]`));
  }
  if (rec["members"] !== undefined) {
    state.members = list(rec["members"], `${path}.members`).map((item, i) => readCompanyMember(item, `${path}.members[${i}]`));
  }
  if (rec["city"] !== undefined && rec["city"] !== null) state.city = readCity(rec["city"], `${path}.city`);
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

// ---------------------------------------------------------------- phase 3 : l'entreprise et ses usines

/** Les quantités de `buyLevels` (`amount`) : palier = jusqu'au prochain ×2, max = tout ce qu'on peut payer. */
export const BUY_AMOUNTS = ["1", "10", "palier", "max"] as const;

export type BuyAmount = (typeof BUY_AMOUNTS)[number];

/** Bornes de `step` dans `restoreStep` : les quatre étapes de la remise en état. */
export const RESTORE_STEP_MIN = 1;
export const RESTORE_STEP_MAX = 4;

export function isRestoreStep(step: number): boolean {
  return Number.isInteger(step) && step >= RESTORE_STEP_MIN && step <= RESTORE_STEP_MAX;
}

/** `ActionBase` : ce que porte toute action d'écriture. */
export interface ActionBase {
  /** UUID choisi par l'add-on : le service rend le même résultat si la même action arrive deux fois. */
  requestId: string;
  /** Le joueur qui agit. */
  actor: Xuid;
}

export interface BuyLevelsBody extends ActionBase {
  amount: BuyAmount;
}

export interface RestoreStepBody extends ActionBase {
  step: number;
}

/** Réponse 200 de `buyLevels` et `restoreStep` (`CompanyUpdated`). */
export interface CompanyUpdated {
  state: CompanyState;
  balances: Balances;
}

/** Réponse 200 de `collect`. */
export interface CollectResponse {
  /** Donar encaissés. */
  collected: number;
  /** Heures hors ligne comptées, plafond appliqué. */
  offlineHours: number;
  state: CompanyState;
}

/** Les gabarits de chemin du contrat. `{companyId}`, `{factoryId}` et `{incidentId}` sont remplacés par `companyPath` et ses voisins. */
export const COMPANY_PATHS = {
  company: "/v1/companies/{companyId}",
  collect: "/v1/companies/{companyId}/collect",
  levels: "/v1/companies/{companyId}/factories/{factoryId}/levels",
  restore: "/v1/companies/{companyId}/factories/{factoryId}/restore",
  resolveIncident: "/v1/companies/{companyId}/incidents/{incidentId}/resolve",
  prestige: "/v1/companies/{companyId}/prestige",
  prestigePreview: "/v1/companies/{companyId}/prestige/preview",
} as const;

function companySegment(companyId: number): string {
  if (!Number.isSafeInteger(companyId) || companyId < 0) throw new RangeError(`companyId invalide : ${companyId}`);
  return String(companyId);
}

function factorySegment(factoryId: string): string {
  if (factoryId === "") throw new RangeError("factoryId vide");
  return encodeURIComponent(factoryId);
}

export function companyPath(companyId: number): string {
  return COMPANY_PATHS.company.replace("{companyId}", companySegment(companyId));
}

export function collectPath(companyId: number): string {
  return COMPANY_PATHS.collect.replace("{companyId}", companySegment(companyId));
}

export function levelsPath(companyId: number, factoryId: string): string {
  return COMPANY_PATHS.levels.replace("{companyId}", companySegment(companyId)).replace("{factoryId}", factorySegment(factoryId));
}

export function restorePath(companyId: number, factoryId: string): string {
  return COMPANY_PATHS.restore.replace("{companyId}", companySegment(companyId)).replace("{factoryId}", factorySegment(factoryId));
}

/** Le chemin de `resolveIncident`. `incidentId` est un entier du service (int64 dans le contrat) : un identifiant illisible lève une erreur. */
export function resolveIncidentPath(companyId: number, incidentId: number): string {
  if (!Number.isSafeInteger(incidentId) || incidentId < 0) throw new RangeError(`incidentId invalide : ${incidentId}`);
  return COMPANY_PATHS.resolveIncident.replace("{companyId}", companySegment(companyId)).replace("{incidentId}", String(incidentId));
}

export function prestigePath(companyId: number): string {
  return COMPANY_PATHS.prestige.replace("{companyId}", companySegment(companyId));
}

export function prestigePreviewPath(companyId: number): string {
  return COMPANY_PATHS.prestigePreview.replace("{companyId}", companySegment(companyId));
}

export function actionBody(action: ActionBase): ActionBase {
  return { requestId: action.requestId, actor: action.actor };
}

export function buyLevelsBody(action: ActionBase, amount: BuyAmount): BuyLevelsBody {
  return { requestId: action.requestId, actor: action.actor, amount };
}

export function restoreStepBody(action: ActionBase, step: number): RestoreStepBody {
  return { requestId: action.requestId, actor: action.actor, step };
}

/** Lit un `CompanyState` seul : le corps 200 de getCompany. */
export function parseCompanyState(body: unknown): Parsed<CompanyState> {
  return attempt(() => readCompanyState(body, "company"));
}

/** Lit le corps 200 de collect. */
export function parseCollectResponse(body: unknown): Parsed<CollectResponse> {
  return attempt(() => {
    const rec = record(body, "collect");
    return {
      collected: num(rec, "collected", "collect"),
      offlineHours: num(rec, "offlineHours", "collect"),
      state: readCompanyState(rec["state"], "collect.state"),
    };
  });
}

/** Lit le corps 200 de buyLevels et de restoreStep (`CompanyUpdated`). */
export function parseCompanyUpdated(body: unknown): Parsed<CompanyUpdated> {
  return attempt(() => {
    const rec = record(body, "update");
    return {
      state: readCompanyState(rec["state"], "update.state"),
      balances: readBalances(rec["balances"], "update.balances"),
    };
  });
}

// ---------------------------------------------------------------- phase 3 : les missions

/** Une mission en cours (`Mission` du contrat). Les textes sont ceux de l'add-on, retrouvés par `id`. */
export interface Mission {
  /** Identifiant du catalogue `definitions/missions.json`. */
  id: string;
  /** Actions faites, d'après le service. */
  progress: number;
  /** Actions à faire. */
  target: number;
  /** Lingots versés par le service quand la mission se termine. */
  rewardLingots: number;
}

/** Réponse 200 de `getMissions` (`MissionState`). */
export interface MissionState {
  /** La première mission pas terminée, ou null quand toutes le sont. */
  current: Mission | null;
  /** Les identifiants des missions terminées, dans l'ordre du catalogue. */
  completed: string[];
}

/** Gabarit du chemin de `getMissions`. `{xuid}` est remplacé par `missionsPath`. */
export const MISSIONS_PATH = "/v1/players/{xuid}/missions";

/** Le schéma `Xuid` du contrat : un entier décimal de 1 à 20 chiffres, écrit en chaîne. */
const XUID_PATTERN = /^[0-9]{1,20}$/;

export function missionsPath(xuid: Xuid): string {
  if (!XUID_PATTERN.test(xuid)) throw new RangeError(`xuid invalide : ${xuid}`);
  return MISSIONS_PATH.replace("{xuid}", xuid);
}

function whole(rec: Rec, key: string, path: string, least: number): number {
  const value = num(rec, key, path);
  if (!Number.isInteger(value)) throw new ShapeError(`${path}.${key} : entier attendu`);
  if (value < least) throw new ShapeError(`${path}.${key} : ${least} au moins attendu`);
  return value;
}

function readMission(value: unknown, path: string): Mission {
  const rec = record(value, path);
  const id = str(rec, "id", path);
  if (id === "") throw new ShapeError(`${path}.id : identifiant vide`);
  return {
    id,
    progress: whole(rec, "progress", path, 0),
    target: whole(rec, "target", path, 1),
    rewardLingots: whole(rec, "rewardLingots", path, 0),
  };
}

/** Lit le corps 200 de getMissions. `current` doit être là, mission ou null : son absence est une réponse hors contrat. */
export function parseMissionState(body: unknown): Parsed<MissionState> {
  return attempt(() => {
    const rec = record(body, "missions");
    const current = rec["current"];
    if (current === undefined) throw new ShapeError("missions.current : mission ou null attendu");
    return {
      current: current === null ? null : readMission(current, "missions.current"),
      completed: list(rec["completed"], "missions.completed").map((id, i) => {
        if (typeof id !== "string" || id === "") throw new ShapeError(`missions.completed[${i}] : identifiant attendu`);
        return id;
      }),
    };
  });
}

// ---------------------------------------------------------------- phase 4 : le prestige

/** Ce qui vient après un prestige (`PrestigePreview.next`) : l'époque suivante, ou une étoile après la dernière. */
export const PRESTIGE_NEXT = ["EPOQUE_2", "EPOQUE_3", "EPOQUE_4", "ETOILE"] as const;

export type PrestigeNext = (typeof PRESTIGE_NEXT)[number];

/** Les Lingots qu'un membre recevrait (`PrestigePreview.shares[]`). */
export interface PrestigeShare {
  xuid: Xuid;
  lingots: number;
}

/**
 * Réponse 200 de `prestigePreview` : ce que rapporterait un prestige maintenant, calculé par le service. L'add-on
 * affiche ces nombres tels quels. Quand `allowed` est faux, le service rend zéro Lingot et zéro part.
 */
export interface PrestigePreview {
  allowed: boolean;
  lingotsTotal: number;
  /** Lingots si le joueur avait gagné deux fois plus de Donar : le rendement décroissant. Le contrat le laisse facultatif. */
  lingotsIfDoubled?: number;
  shares: PrestigeShare[];
  next: PrestigeNext;
}

function readPrestigeShare(value: unknown, path: string): PrestigeShare {
  const rec = record(value, path);
  const xuid = str(rec, "xuid", path);
  if (!XUID_PATTERN.test(xuid)) throw new ShapeError(`${path}.xuid : XUID attendu`);
  // Le contrat ne les marque pas obligatoires, mais une part sans membre ou sans montant ne veut rien dire.
  return { xuid, lingots: whole(rec, "lingots", path, 0) };
}

/** Lit le corps 200 de prestigePreview. Les Lingots sont des entiers positifs ou nuls ; `next` une des quatre valeurs du contrat. */
export function parsePrestigePreview(body: unknown): Parsed<PrestigePreview> {
  return attempt(() => {
    const rec = record(body, "prestige");
    const preview: PrestigePreview = {
      allowed: bool(rec, "allowed", "prestige"),
      lingotsTotal: whole(rec, "lingotsTotal", "prestige", 0),
      shares: list(rec["shares"], "prestige.shares").map((item, i) => readPrestigeShare(item, `prestige.shares[${i}]`)),
      next: oneOf(rec, "next", "prestige", PRESTIGE_NEXT),
    };
    if (rec["lingotsIfDoubled"] !== undefined) preview.lingotsIfDoubled = whole(rec, "lingotsIfDoubled", "prestige", 0);
    return preview;
  });
}

// ---------------------------------------------------------------- phase 4 : le personnel

/** Bornes de `slot` dans `hire` : les cinq places de la liste du jour. */
export const SLOT_MIN = 1;
export const SLOT_MAX = 5;

/** Gabarits des chemins du personnel. `{companyId}`, `{slot}` et `{staffId}` sont remplacés par les fonctions ci-dessous. */
export const STAFF_PATHS = {
  agency: "/v1/companies/{companyId}/agency",
  hire: "/v1/companies/{companyId}/agency/{slot}/hire",
  staff: "/v1/companies/{companyId}/staff/{staffId}",
} as const;

export function agencyPath(companyId: number): string {
  return STAFF_PATHS.agency.replace("{companyId}", companySegment(companyId));
}

export function hirePath(companyId: number, slot: number): string {
  if (!Number.isInteger(slot) || slot < SLOT_MIN || slot > SLOT_MAX) throw new RangeError(`slot invalide : ${slot}`);
  return STAFF_PATHS.hire.replace("{companyId}", companySegment(companyId)).replace("{slot}", String(slot));
}

function staffSegment(staffId: number): string {
  if (!Number.isSafeInteger(staffId) || staffId < 0) throw new RangeError(`staffId invalide : ${staffId}`);
  return String(staffId);
}

/** Le chemin d'un membre du personnel : celui d'`assignStaff` (PATCH) et, sans corps, celui de `fireStaff` (DELETE). */
export function staffPath(companyId: number, staffId: number): string {
  return STAFF_PATHS.staff.replace("{companyId}", companySegment(companyId)).replace("{staffId}", staffSegment(staffId));
}

/**
 * Le chemin de `fireStaff` : un DELETE n'a pas de corps, `actor` et `requestId` voyagent dans la requête
 * (paramètres `ActorXuid` et `RequestId` du contrat).
 */
export function fireStaffPath(companyId: number, staffId: number, action: ActionBase): string {
  if (!XUID_PATTERN.test(action.actor)) throw new RangeError(`actor invalide : ${action.actor}`);
  return `${staffPath(companyId, staffId)}?requestId=${encodeURIComponent(action.requestId)}&actor=${action.actor}`;
}

/** Corps de `hire`. Sans `factoryId`, le candidat reste sans usine. */
export interface HireBody extends ActionBase {
  factoryId?: string;
}

export function hireBody(action: ActionBase, factoryId?: string): HireBody {
  return factoryId === undefined
    ? { requestId: action.requestId, actor: action.actor }
    : { requestId: action.requestId, actor: action.actor, factoryId };
}

/** Corps d'`assignStaff` : l'usine est obligatoire. */
export interface AssignStaffBody extends ActionBase {
  factoryId: string;
}

export function assignStaffBody(action: ActionBase, factoryId: string): AssignStaffBody {
  return { requestId: action.requestId, actor: action.actor, factoryId };
}

function readStaff(value: unknown, path: string): Staff {
  const rec = record(value, path);
  const staff: Staff = {
    name: str(rec, "name", path),
    skinId: str(rec, "skinId", path),
    role: oneOf(rec, "role", path, STAFF_ROLES),
    rarity: oneOf(rec, "rarity", path, STAFF_RARITIES),
    bonusPercent: num(rec, "bonusPercent", path),
  };
  if (rec["staffId"] !== undefined) staff.staffId = whole(rec, "staffId", path, 0);
  if (rec["slot"] !== undefined) staff.slot = whole(rec, "slot", path, SLOT_MIN);
  if (rec["salaryPercent"] !== undefined) staff.salaryPercent = num(rec, "salaryPercent", path);
  if (rec["salaryPerHour"] !== undefined) staff.salaryPerHour = num(rec, "salaryPerHour", path);
  if (rec["hireCost"] !== undefined) staff.hireCost = num(rec, "hireCost", path);
  if (rec["factoryId"] !== undefined) staff.factoryId = readNullableString(rec, "factoryId", path) ?? null;
  return staff;
}

/** Un membre du personnel embauché : le service lui donne toujours un `staffId`. */
function readMember(value: unknown, path: string): StaffMember {
  const staff = readStaff(value, path);
  if (staff.staffId === undefined) throw new ShapeError(`${path}.staffId : entier attendu`);
  return { ...staff, staffId: staff.staffId };
}

/** Un candidat du jour : le service lui donne toujours une place, et `hire` en a besoin. */
function readCandidate(value: unknown, path: string): Candidate {
  const staff = readStaff(value, path);
  if (staff.slot === undefined) throw new ShapeError(`${path}.slot : entier attendu`);
  if (staff.slot > SLOT_MAX) throw new ShapeError(`${path}.slot : ${SLOT_MAX} au plus attendu`);
  return { ...staff, slot: staff.slot };
}

/**
 * Lit le corps 200 de getCandidates : un tableau de `Staff` qui ont chacun une place. Le contrat ne fixe pas le nombre
 * de candidats ; l'add-on montre ceux que le service donne, dans l'ordre où il les donne.
 */
export function parseCandidates(body: unknown): Parsed<Candidate[]> {
  return attempt(() => list(body, "agence").map((item, i) => readCandidate(item, `agence[${i}]`)));
}

// ---------------------------------------------------------------- phase 4 : l'entreprise partagée

/** Gabarits des chemins de l'entreprise partagée. `invite` (POST) et `setShares` (PUT) partagent le premier. */
export const MEMBER_PATHS = {
  members: "/v1/companies/{companyId}/members",
  accept: "/v1/companies/{companyId}/members/accept",
} as const;

/** Le chemin d'`invite` (POST) et de `setShares` (PUT). */
export function membersPath(companyId: number): string {
  return MEMBER_PATHS.members.replace("{companyId}", companySegment(companyId));
}

/** Le chemin d'`acceptInvite` : `companyId` est l'entreprise qui a invité, pas celle du joueur. */
export function acceptInvitePath(companyId: number): string {
  return MEMBER_PATHS.accept.replace("{companyId}", companySegment(companyId));
}

/** Vrai pour un XUID du contrat (schéma `Xuid`) : un entier décimal de 1 à 20 chiffres, écrit en chaîne. */
export function isXuid(value: string): boolean {
  return XUID_PATTERN.test(value);
}

/** Corps d'`invite` : l'action, et le joueur invité. */
export interface InviteBody extends ActionBase {
  targetXuid: Xuid;
}

export function inviteBody(action: ActionBase, targetXuid: Xuid): InviteBody {
  return { requestId: action.requestId, actor: action.actor, targetXuid };
}

/** La part d'un membre dans `setShares` : un pourcentage entier de 0 à 100. */
export interface ShareInput {
  xuid: Xuid;
  percent: number;
}

/** Bornes de `percent` dans `setShares`. */
export const SHARE_MIN = 0;
export const SHARE_MAX = 100;

/** Vrai pour un pourcentage que le contrat accepte : entier, de 0 à 100. La somme n'est pas vérifiée ici : c'est au service. */
export function isSharePercent(value: number): boolean {
  return Number.isInteger(value) && value >= SHARE_MIN && value <= SHARE_MAX;
}

/** Corps de `setShares` : une part par membre, dans l'ordre donné. */
export interface SetSharesBody extends ActionBase {
  shares: ShareInput[];
}

export function setSharesBody(action: ActionBase, shares: readonly ShareInput[]): SetSharesBody {
  return {
    requestId: action.requestId,
    actor: action.actor,
    shares: shares.map((share) => ({ xuid: share.xuid, percent: share.percent })),
  };
}

/** Un membre de `CompanyState.members`. Le XUID et la part suivent le schéma `Member` du contrat. */
function readCompanyMember(value: unknown, path: string): Member {
  const rec = record(value, path);
  const xuid = str(rec, "xuid", path);
  if (!XUID_PATTERN.test(xuid)) throw new ShapeError(`${path}.xuid : XUID attendu`);
  const sharePercent = whole(rec, "sharePercent", path, SHARE_MIN);
  if (sharePercent > SHARE_MAX) throw new ShapeError(`${path}.sharePercent : ${SHARE_MAX} au plus attendu`);
  return { xuid, name: str(rec, "name", path), role: oneOf(rec, "role", path, MEMBER_ROLES), sharePercent };
}

// ---------------------------------------------------------------- phase 4 : la ville

/** Ce qu'un bâtiment donne (`CityBuilding.bonusType`) : des places de logement, des pourcents de production, des pourcents de vitesse de livraison. */
export const CITY_BONUS_TYPES = ["LOGEMENTS", "PRODUCTION_DONAR", "LIVRAISON_VITESSE"] as const;

export type CityBonusType = (typeof CITY_BONUS_TYPES)[number];

/** Les quatre rotations d'un bâtiment, en degrés. */
export const BUILDING_ROTATIONS = [0, 90, 180, 270] as const;

export type BuildingRotation = (typeof BUILDING_ROTATIONS)[number];

/** Les zones de la ville : de 1 à 16. */
export const ZONE_MIN = 1;
export const ZONE_MAX = 16;

/** La prochaine zone à acheter (`CityState.nextZone`) : le prix et ce qu'elle demande, tels que le service les donne. */
export interface CityNextZone {
  zoneIndex: number;
  priceLingots: number;
  mairieLevelRequired: number;
  /** Usines actives qu'il faut avoir ; zéro sauf pour la première zone. */
  activeFactoriesRequired: number;
}

/** Ce que coûte le niveau suivant d'un bâtiment (`CityBuilding.nextLevel`). */
export interface CityNextLevel {
  priceLingots: number;
  mairieLevelRequired: number;
}

/** Un bâtiment de la ville (`CityBuilding`). `x` et `z` sont le coin de sa parcelle, en blocs depuis le coin de l'île principale. */
export interface CityBuilding {
  buildingId: number;
  buildingType: string;
  level: number;
  maxLevel: number;
  zoneIndex: number;
  x: number;
  z: number;
  rotation: BuildingRotation;
  /** Nuls pour la mairie et les routes. */
  bonusType?: CityBonusType | null;
  bonus?: number | null;
  /** Nul au dernier niveau. */
  nextLevel?: CityNextLevel | null;
}

/** Ce que les bâtiments donnent ensemble (`CityBonuses`), plafonds appliqués par le service. */
export interface CityBonuses {
  housingPlaces: number;
  housedStaff: number;
  donarProductionPercent: number;
  deliverySpeedPercent: number;
}

/** La ville de l'entreprise (`CityState`). Le service la calcule : l'add-on l'affiche. */
export interface CityState {
  mairieLevel: number;
  /** Nulle quand toutes les zones sont à l'entreprise. Le contrat ne la marque pas obligatoire : absente, elle est lue comme nulle. */
  nextZone: CityNextZone | null;
  buildings: CityBuilding[];
  bonuses: CityBonuses;
}

function readNextZone(value: unknown, path: string): CityNextZone {
  const rec = record(value, path);
  const zoneIndex = whole(rec, "zoneIndex", path, ZONE_MIN);
  if (zoneIndex > ZONE_MAX) throw new ShapeError(`${path}.zoneIndex : ${ZONE_MAX} au plus attendu`);
  return {
    zoneIndex,
    priceLingots: whole(rec, "priceLingots", path, 0),
    mairieLevelRequired: whole(rec, "mairieLevelRequired", path, 1),
    activeFactoriesRequired: whole(rec, "activeFactoriesRequired", path, 0),
  };
}

function readCityBuilding(value: unknown, path: string): CityBuilding {
  const rec = record(value, path);
  const zoneIndex = whole(rec, "zoneIndex", path, ZONE_MIN);
  if (zoneIndex > ZONE_MAX) throw new ShapeError(`${path}.zoneIndex : ${ZONE_MAX} au plus attendu`);
  const rotation = num(rec, "rotation", path);
  const found = BUILDING_ROTATIONS.find((candidate) => candidate === rotation);
  if (found === undefined) throw new ShapeError(`${path}.rotation : une de ${BUILDING_ROTATIONS.join(", ")} attendue`);
  const building: CityBuilding = {
    buildingId: whole(rec, "buildingId", path, 0),
    buildingType: str(rec, "buildingType", path),
    level: whole(rec, "level", path, 1),
    maxLevel: whole(rec, "maxLevel", path, 1),
    zoneIndex,
    x: whole(rec, "x", path, 0),
    z: whole(rec, "z", path, 0),
    rotation: found,
  };
  if (building.buildingType === "") throw new ShapeError(`${path}.buildingType : identifiant vide`);
  if (rec["bonusType"] !== undefined) building.bonusType = rec["bonusType"] === null ? null : oneOf(rec, "bonusType", path, CITY_BONUS_TYPES);
  if (rec["bonus"] !== undefined) building.bonus = rec["bonus"] === null ? null : num(rec, "bonus", path);
  if (rec["nextLevel"] !== undefined) {
    if (rec["nextLevel"] === null) building.nextLevel = null;
    else {
      const next = record(rec["nextLevel"], `${path}.nextLevel`);
      building.nextLevel = {
        priceLingots: whole(next, "priceLingots", `${path}.nextLevel`, 0),
        mairieLevelRequired: whole(next, "mairieLevelRequired", `${path}.nextLevel`, 1),
      };
    }
  }
  return building;
}

function readCityBonuses(value: unknown, path: string): CityBonuses {
  const rec = record(value, path);
  return {
    housingPlaces: whole(rec, "housingPlaces", path, 0),
    housedStaff: whole(rec, "housedStaff", path, 0),
    donarProductionPercent: num(rec, "donarProductionPercent", path),
    deliverySpeedPercent: num(rec, "deliverySpeedPercent", path),
  };
}

function readCity(value: unknown, path: string): CityState {
  const rec = record(value, path);
  const nextZone = rec["nextZone"];
  return {
    mairieLevel: whole(rec, "mairieLevel", path, 1),
    nextZone: nextZone === undefined || nextZone === null ? null : readNextZone(nextZone, `${path}.nextZone`),
    buildings: list(rec["buildings"], `${path}.buildings`).map((item, i) => readCityBuilding(item, `${path}.buildings[${i}]`)),
    bonuses: readCityBonuses(rec["bonuses"], `${path}.bonuses`),
  };
}

/** Gabarits des chemins de la ville. `{zoneIndex}` et `{buildingId}` sont remplacés par les fonctions ci-dessous. */
export const CITY_PATHS = {
  zone: "/v1/companies/{companyId}/city/zones/{zoneIndex}",
  buildings: "/v1/companies/{companyId}/city/buildings",
  building: "/v1/companies/{companyId}/city/buildings/{buildingId}",
} as const;

/** Le chemin de `buyZone`. `zoneIndex` va de 1 à 16 ; hors de ces bornes la fonction lève une erreur. */
export function buyZonePath(companyId: number, zoneIndex: number): string {
  if (!Number.isInteger(zoneIndex) || zoneIndex < ZONE_MIN || zoneIndex > ZONE_MAX) throw new RangeError(`zoneIndex invalide : ${zoneIndex}`);
  return CITY_PATHS.zone.replace("{companyId}", companySegment(companyId)).replace("{zoneIndex}", String(zoneIndex));
}

/** Le chemin de `placeBuilding`. */
export function placeBuildingPath(companyId: number): string {
  return CITY_PATHS.buildings.replace("{companyId}", companySegment(companyId));
}

/** Le chemin de `upgradeBuilding`. `buildingId` est un entier du service (int64 dans le contrat). */
export function upgradeBuildingPath(companyId: number, buildingId: number): string {
  if (!Number.isSafeInteger(buildingId) || buildingId < 0) throw new RangeError(`buildingId invalide : ${buildingId}`);
  return CITY_PATHS.building.replace("{companyId}", companySegment(companyId)).replace("{buildingId}", String(buildingId));
}

/** Longueur maximale de `buildingType` dans `placeBuilding` : 32 caractères. */
export const BUILDING_TYPE_MAX = 32;

/** Corps de `placeBuilding` : l'action, le type, la parcelle (son coin, en blocs depuis le coin de l'île principale) et la rotation. */
export interface PlaceBuildingBody extends ActionBase {
  buildingType: string;
  x: number;
  z: number;
  rotation: BuildingRotation;
}

export function placeBuildingBody(action: ActionBase, buildingType: string, x: number, z: number, rotation: BuildingRotation): PlaceBuildingBody {
  if (buildingType === "" || buildingType.length > BUILDING_TYPE_MAX) throw new RangeError(`buildingType invalide : ${buildingType}`);
  if (!Number.isSafeInteger(x) || x < 0) throw new RangeError(`x invalide : ${x}`);
  if (!Number.isSafeInteger(z) || z < 0) throw new RangeError(`z invalide : ${z}`);
  if (!BUILDING_ROTATIONS.includes(rotation)) throw new RangeError(`rotation invalide : ${rotation}`);
  return { requestId: action.requestId, actor: action.actor, buildingType, x, z, rotation };
}
