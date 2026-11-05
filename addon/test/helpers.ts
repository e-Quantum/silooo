// Faux partagés par les tests : un journal qui enregistre, une horloge que le test fait avancer, un
// transport qui rend des réponses prévues. Rien ici n'importe @minecraft/*.

import type { ApiResult, Header, HttpMethod, Transport, TransportResponse } from "../src/http";
import { BOUTIQUE } from "../src/monde/definitions";
import type { Logger, Scheduler } from "../src/runtime";
import type {
  Balances,
  Candidate,
  CatalogItem,
  CompanyState,
  EventsPage,
  Factory,
  GameEvent,
  JoinResponse,
  Mission,
  MissionState,
  PassClaimed,
  PassState,
  PrestigePreview,
  StaffMember,
  VotesToday,
} from "../src/protocol";

export interface RecordingLogger extends Logger {
  readonly infos: string[];
  readonly warns: string[];
  readonly errors: string[];
  all(): string[];
}

export function recordingLogger(): RecordingLogger {
  const infos: string[] = [];
  const warns: string[] = [];
  const errors: string[] = [];
  return {
    infos,
    warns,
    errors,
    info: (m) => void infos.push(m),
    warn: (m) => void warns.push(m),
    error: (m) => void errors.push(m),
    all: () => [...infos, ...warns, ...errors],
  };
}

export interface FakeScheduler extends Scheduler {
  readonly intervals: Array<{ seconds: number; run: () => void }>;
  readonly timeouts: Array<{ seconds: number; run: () => void }>;
  /** Exécute le plus ancien délai en attente et rend sa durée ; undefined s'il n'y en a pas. */
  runNextTimeout(): number | undefined;
}

export function fakeScheduler(): FakeScheduler {
  const intervals: FakeScheduler["intervals"] = [];
  const timeouts: FakeScheduler["timeouts"] = [];
  return {
    intervals,
    timeouts,
    every: (seconds, run) => void intervals.push({ seconds, run }),
    after: (seconds, run) => void timeouts.push({ seconds, run }),
    runNextTimeout() {
      const next = timeouts.shift();
      if (next === undefined) return undefined;
      next.run();
      return next.seconds;
    },
  };
}

export interface SentRequest {
  method: HttpMethod;
  url: string;
  headers: readonly Header[];
  body: string | undefined;
  timeoutSeconds: number;
}

export interface ScriptedTransport extends Transport {
  readonly requests: SentRequest[];
}

/** Rend les réponses dans l'ordre ; une Error est un rejet du transport ; la dernière sert pour les appels en trop. */
export function scriptedTransport(script: ReadonlyArray<TransportResponse | Error>): ScriptedTransport {
  const requests: SentRequest[] = [];
  let index = 0;
  return {
    requests,
    async send(method, url, headers, body, timeoutSeconds) {
      requests.push({ method, url, headers, body, timeoutSeconds });
      const step = script[Math.min(index, script.length - 1)];
      index += 1;
      if (step === undefined) throw new Error("script de transport vide");
      if (step instanceof Error) throw step;
      return step;
    },
  };
}

export function json(status: number, body: unknown): TransportResponse {
  return { status, body: JSON.stringify(body) };
}

export function joinResponse(overrides: Partial<JoinResponse> = {}): JoinResponse {
  return {
    xuid: "2535400000000001",
    firstJoin: true,
    companyId: 7,
    balances: { lingots: 0, gemmes: 0, jetons: 0, cles: 0 },
    state: {
      companyId: 7,
      name: "Entreprise de test",
      era: 1,
      stars: 0,
      courseNumber: 1,
      donar: 0,
      donarEarned: 0,
      ratePerHour: 0,
      multiplier: 1,
      threshold: 1,
      factories: [],
      version: 1,
    },
    ...overrides,
  };
}

export function gameEvent(id: number, kind = "ANNONCE", xuid: string | null = null): GameEvent {
  return { id, xuid, kind, payload: {} };
}

export function eventsPage(events: GameEvent[], cursor?: number): EventsPage {
  return { cursor: cursor ?? events.at(-1)?.id ?? 0, events, skipped: 0 };
}

export function ok<T>(body: T, status = 200): ApiResult<T> {
  return { kind: "ok", status, body };
}

export function factory(overrides: Partial<Factory> = {}): Factory {
  return {
    factoryId: "carriere",
    state: "ACTIVE",
    level: 3,
    ratePerHour: 7200,
    nextCost: 150,
    nextMilestone: 25,
    ...overrides,
  };
}

export function companyState(overrides: Partial<CompanyState> = {}): CompanyState {
  return { ...joinResponse().state, ...overrides };
}

export function balances(overrides: Partial<Balances> = {}): Balances {
  return { lingots: 0, gemmes: 0, jetons: 0, cles: 0, ...overrides };
}

/** La mission de bienvenue de la mairie, au début : rien encaissé, une action à faire, dix Lingots à la clé. */
export function mission(overrides: Partial<Mission> = {}): Mission {
  return { id: "bienvenue-mairie", progress: 0, target: 1, rewardLingots: 10, ...overrides };
}

export function missionState(overrides: Partial<MissionState> = {}): MissionState {
  return { current: mission(), completed: [], ...overrides };
}

/** Un aperçu de prestige du service : permis, 1 200 Lingots (1 700 si le joueur doublait), une seule part, l'époque 2 ensuite. */
export function prestigePreview(overrides: Partial<PrestigePreview> = {}): PrestigePreview {
  return { allowed: true, lingotsTotal: 1_200, lingotsIfDoubled: 1_700, shares: [{ xuid: "1", lingots: 1_200 }], next: "EPOQUE_2", ...overrides };
}

/** Un membre du personnel du service : Adèle, salariée commune de la Carrière, 10 % de bonus. */
export function staffMember(overrides: Partial<StaffMember> = {}): StaffMember {
  return {
    staffId: 11,
    name: "Adèle",
    skinId: "ouvriere-gilet",
    role: "SALARIE",
    rarity: "COMMUN",
    bonusPercent: 10,
    salaryPercent: 2.5,
    salaryPerHour: 180,
    factoryId: "carriere",
    ...overrides,
  };
}

/** Un candidat du jour du service : Basile, salarié commun à la place 1, 4 800 Donar, pas encore embauché. */
export function candidate(overrides: Partial<Candidate> = {}): Candidate {
  return {
    slot: 1,
    name: "Basile",
    skinId: "ouvrier-casque",
    role: "SALARIE",
    rarity: "COMMUN",
    bonusPercent: 10,
    salaryPercent: 2.5,
    hireCost: 4_800,
    factoryId: null,
    ...overrides,
  };
}

/** L'événement `ANNONCE` d'une mission terminée, tel que le service le publie (api/silooo-core.openapi.yaml, getMissions). */
export function missionAnnouncement(id: number, xuid: string | null, missionId = "bienvenue-mairie", rewardLingots: number | null = 10): GameEvent {
  const payload: Record<string, unknown> = { action: "mission", missionId };
  if (rewardLingots !== null) payload["rewardLingots"] = rewardLingots;
  return { id, xuid, kind: "ANNONCE", payload };
}

// ---------------------------------------------------------------- la boutique, le pass, les votes

/** Un article du catalogue du service : possédé non, disponible oui, sauf ce que le test change. */
export function catalogItem(itemId: string, overrides: Partial<CatalogItem> = {}): CatalogItem {
  return { itemId, gemmes: 100, owned: false, available: true, ...overrides };
}

/**
 * Le catalogue tel que le service le rend à un joueur qui ne possède rien : les six grades (le premier seul disponible), les
 * abonnements (Pilier, puis le préfixe perso, que l'add-on ne montre pas), les cosmétiques en vente, le boost collectif et le
 * pass premium. Les identifiants et les prix sont ceux de definitions/ ; `changes` ajuste un article par son identifiant.
 */
export function shopCatalog(changes: Readonly<Record<string, Partial<CatalogItem>>> = {}): CatalogItem[] {
  const items: CatalogItem[] = [
    ...BOUTIQUE.grades.map((grade, i) => catalogItem(grade.id, { gemmes: grade.gemmes, available: i === 0 })),
    ...BOUTIQUE.abonnements.map((abonnement) => catalogItem(abonnement.id, { gemmes: abonnement.gemmes, available: abonnement.id !== "perso" })),
    ...BOUTIQUE.cosmetiques.filter((cosmetic) => cosmetic.achetable).map((cosmetic) => catalogItem(cosmetic.id, { gemmes: cosmetic.prixGemmes })),
    catalogItem("boost-collectif", { gemmes: BOUTIQUE.boostCollectif.prixGemmes }),
    catalogItem("pass-premium", { gemmes: 250 }),
  ];
  return items.map((item) => ({ ...item, ...changes[item.itemId] }));
}

/** Un pass en cours : 120 points, trois paliers, le premier réclamé sur la piste gratuite. */
export function passState(overrides: Partial<PassState> = {}): PassState {
  return {
    seasonId: "saison-1",
    name: "Saison 1",
    startsOn: "2026-11-05",
    endsOn: "2027-01-31",
    active: true,
    points: 120,
    premium: false,
    claimed: ["1-GRATUIT"],
    tiers: [
      { tier: 1, points: 40, free: { lingots: 1 }, premium: { cosmeticId: "s1-fanion" } },
      { tier: 2, points: 100, free: { lingots: 1 }, premium: null },
      { tier: 3, points: 180, free: { lingots: 2 }, premium: { cosmeticId: "s1-lanterne" } },
    ],
    ...overrides,
  };
}

/** La réponse de `claimPass` : des Lingots sur la piste gratuite, un cosmétique sur la piste premium. */
export function passClaimed(overrides: Partial<PassClaimed> = {}): PassClaimed {
  return {
    seasonId: "saison-1",
    tier: 2,
    track: "GRATUIT",
    rewardLingots: 1,
    cosmeticId: null,
    balances: { lingots: 9, gemmes: 0, jetons: 0, cles: 0 },
    ...overrides,
  };
}

/** Les votes du jour : le joueur n'a pas encore voté. */
export function votesToday(overrides: Partial<VotesToday> = {}): VotesToday {
  return {
    day: "2026-11-05",
    votes: 3,
    goal: 10,
    partyReached: false,
    rewardLingots: 1,
    partyRewardLingots: 1,
    streakGoalDays: 7,
    streakBonusLingots: 2,
    mine: { votes: 0, streak: 0 },
    ...overrides,
  };
}
