// Faux partagés par les tests : un journal qui enregistre, une horloge que le test fait avancer, un
// transport qui rend des réponses prévues. Rien ici n'importe @minecraft/*.

import type { ApiResult, Header, HttpMethod, Transport, TransportResponse } from "../src/http";
import type { Logger, Scheduler } from "../src/runtime";
import type { Balances, CompanyState, EventsPage, Factory, GameEvent, JoinResponse, Mission, MissionState } from "../src/protocol";

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

/** L'événement `ANNONCE` d'une mission terminée, tel que le service le publie (api/silooo-core.openapi.yaml, getMissions). */
export function missionAnnouncement(id: number, xuid: string | null, missionId = "bienvenue-mairie", rewardLingots: number | null = 10): GameEvent {
  const payload: Record<string, unknown> = { action: "mission", missionId };
  if (rewardLingots !== null) payload["rewardLingots"] = rewardLingots;
  return { id, xuid, kind: "ANNONCE", payload };
}
