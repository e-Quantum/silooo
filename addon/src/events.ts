// La lecture des événements : le service ne peut pas appeler l'add-on, donc l'add-on vient chercher
// ce que le service a à dire (GET /v1/events?after=<curseur>), pour tous les joueurs à la fois
// (règle 8 du socle). Le service garde les événements ; l'add-on ne garde que le curseur, dans un magasin que
// main.ts branche (la propriété dynamique du monde), pour qu'un redémarrage du serveur ne rejoue pas ce qui a déjà
// été dit. Sans magasin, ou si le magasin ne rend rien d'exploitable, le curseur part de 0.

import type { CoreApi } from "./api";
import { config, type EventsSettings } from "./config";
import { describeFailure } from "./http";
import type { EventsPage, GameEvent, GameEventKind } from "./protocol";
import { describeError, type Logger, type Scheduler, type StatusLog } from "./runtime";

// ---------------------------------------------------------------- le registre d'écouteurs

export type EventListener = (event: GameEvent) => void | Promise<void>;

export interface EventListeners {
  /** Appelle `listener` pour chaque événement de ce type. Vide en phase 2 : les écrans arrivent en phase 3. */
  on(kind: GameEventKind, listener: EventListener): void;
  /** Répartit un événement vers les écouteurs de son type. Une exception d'un écouteur est journalisée, jamais relancée. */
  dispatch(event: GameEvent): Promise<void>;
}

export function createEventListeners(log: Logger): EventListeners {
  const byKind = new Map<string, EventListener[]>();
  return {
    on(kind, listener) {
      const listeners = byKind.get(kind) ?? [];
      listeners.push(listener);
      byKind.set(kind, listeners);
    },
    async dispatch(event) {
      for (const listener of byKind.get(event.kind) ?? []) {
        try {
          await listener(event);
        } catch (error) {
          log.error(`écouteur de ${event.kind} (événement ${event.id}) : ${describeError(error)}`);
        }
      }
    },
  };
}

// ---------------------------------------------------------------- curseur et cadence

/** Le curseur après une page : il ne recule jamais, et dépasse les identifiants reçus. */
export function advanceCursor(current: number, page: EventsPage): number {
  let next = Math.max(current, page.cursor);
  for (const event of page.events) next = Math.max(next, event.id);
  return next;
}

export interface PollState {
  /** Échecs de suite ; 0 après une lecture réussie. */
  readonly failures: number;
  /** Événements de la dernière page lue. */
  readonly lastPageCount: number;
  readonly pageSize: number;
}

/** Secondes avant la prochaine lecture : retour exponentiel après un échec, relecture rapide après une page pleine. */
export function nextPollDelaySeconds(state: PollState, settings: EventsSettings = config.events): number {
  if (state.failures > 0) {
    return Math.min(settings.maxBackoffSeconds, settings.pollIntervalSeconds * 2 ** state.failures);
  }
  if (state.lastPageCount >= state.pageSize) return settings.drainDelaySeconds;
  return settings.pollIntervalSeconds;
}

/** « GEMMES_RECUES x2, ANNONCE x1 » : le journal d'une page tient en une ligne. */
export function summarizeKinds(events: readonly GameEvent[]): string {
  const counts = new Map<string, number>();
  for (const event of events) counts.set(event.kind, (counts.get(event.kind) ?? 0) + 1);
  return [...counts].map(([kind, count]) => `${kind} x${count}`).join(", ");
}

// ---------------------------------------------------------------- la lecture

/** Où le curseur survit au redémarrage du serveur. Les deux appels peuvent lever : le poller s'en protège. */
export interface CursorStore {
  /** Le dernier curseur gardé ; 0 quand rien n'a encore été gardé. */
  load(): number;
  save(cursor: number): void;
}

export interface EventPollerDeps {
  readonly api: Pick<CoreApi, "pollEvents">;
  readonly listeners: EventListeners;
  readonly status: StatusLog;
  readonly log: Logger;
  readonly pageSize?: number;
  /** Absent : le curseur reste en mémoire et repart de 0 au démarrage. */
  readonly cursorStore?: CursorStore;
}

export interface EventPoller {
  /** Une lecture, la répartition de ses événements, puis le délai avant la suivante, en secondes. */
  pollOnce(): Promise<number>;
  readonly cursor: number;
}

/** Le curseur gardé : un entier positif, sinon 0. Un magasin qui lève ou rend autre chose ne fait pas tomber le poller. */
function loadCursor(store: CursorStore | undefined, log: Logger): number {
  if (store === undefined) return 0;
  try {
    const stored = store.load();
    return Number.isSafeInteger(stored) && stored > 0 ? stored : 0;
  } catch (error) {
    log.warn(`curseur des événements illisible, reprise à 0 : ${describeError(error)}`);
    return 0;
  }
}

export function createEventPoller(deps: EventPollerDeps): EventPoller {
  const pageSize = deps.pageSize ?? config.events.pageSize;
  // Lu au premier besoin (la première lecture), pas à la création : le script se charge avant que le monde soit lisible.
  let loaded: number | undefined;
  let failures = 0;
  let lastPageCount = 0;
  let saveFailed = false;

  const current = (): number => (loaded ??= loadCursor(deps.cursorStore, deps.log));

  /** Garde le curseur ; l'échec est journalisé à sa première occurrence, jusqu'à la prochaine réussite. */
  function remember(cursor: number): void {
    if (deps.cursorStore === undefined) return;
    try {
      deps.cursorStore.save(cursor);
      saveFailed = false;
    } catch (error) {
      if (!saveFailed) deps.log.warn(`curseur des événements non gardé (un redémarrage rejouera des annonces) : ${describeError(error)}`);
      saveFailed = true;
    }
  }

  return {
    get cursor() {
      return current();
    },
    async pollOnce() {
      const cursor = current();
      const result = await deps.api.pollEvents(cursor, pageSize);
      if (result.kind !== "ok") {
        failures += 1;
        lastPageCount = 0;
        deps.status.failed(describeFailure(result));
        return nextPollDelaySeconds({ failures, lastPageCount, pageSize });
      }

      failures = 0;
      deps.status.ok();
      const page = result.body;
      // Le service ne rend que des identifiants au-dessus du curseur ; ce filtre garde un doublon hors des écouteurs.
      const fresh = page.events.filter((event) => event.id > cursor);
      if (page.skipped > 0) deps.log.warn(`${page.skipped} événement(s) illisible(s) ignoré(s)`);
      if (fresh.length > 0) deps.log.info(`${fresh.length} événement(s) : ${summarizeKinds(fresh)}`);
      for (const event of fresh) await deps.listeners.dispatch(event);
      const next = advanceCursor(cursor, page);
      if (next > cursor) {
        loaded = next;
        remember(next);
      }
      lastPageCount = page.events.length + page.skipped;
      return nextPollDelaySeconds({ failures, lastPageCount, pageSize });
    },
  };
}

/** Lance la boucle : chaque lecture planifie la suivante, donc deux lectures ne se chevauchent jamais. */
export function startEventPolling(
  scheduler: Scheduler,
  poller: Pick<EventPoller, "pollOnce">,
  log: Logger,
  startupDelaySeconds: number,
): void {
  const tick = (): void => {
    void (async () => {
      let delay: number;
      try {
        delay = await poller.pollOnce();
      } catch (error) {
        log.error(`lecture des événements : ${describeError(error)}`);
        delay = config.events.maxBackoffSeconds;
      }
      scheduler.after(delay, tick);
    })();
  };
  scheduler.after(startupDelaySeconds, tick);
}
