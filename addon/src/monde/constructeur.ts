// Le constructeur d'îles : pose, une par une, les mini-îles que les entreprises présentes demandent, dans l'état de
// leur usine. Il ne décide de rien : il lit l'état donné par le service (via `sync`), le compare à ce qu'il a déjà posé
// (une marque par île, gardée dans le monde) et ne refait que ce qui a changé.
//
// - Une marque `état@x,z` par usine et par entreprise : la même île n'est jamais reposée dans le même état ; un
//   changement d'état refait le bâtiment seulement ; une autre origine d'archipel refait aussi la plateforme.
// - Un seul chantier à la fois, et des lots d'opérations séparés par des pauses : le serveur ne se fige pas.
// - Une île est loin du joueur (plusieurs centaines de blocs) : le chantier charge d'abord sa zone (ticking area),
//   pose, puis la relâche. Si le chargement échoue, nouvel essai plus tard, attente doublée à chaque échec.
// - Rien d'ici n'importe @minecraft/* : le monde est un `BlockWriter`, les marques un `MarkStore`.

import type { IlesDefinitions } from "../generated/definitions";
import type { Factory, FactoryState } from "../protocol";
import { createStatusLog, describeError, pause, type Clock, type Logger, type Scheduler, type StatusLog } from "../runtime";
import { config } from "../config";
import type { Box, Pos, Xz } from "./geometrie";
import { usineIle } from "./geometrie";
import { planIsland, type BlockStates, type IslandPlan, type Op } from "./plan";

/** Ce que le constructeur demande au monde. L'implémentation réelle est dans monde/bedrock.ts. */
export interface BlockWriter {
  /** Charge la zone, exécute `run`, relâche la zone. Rejette si la zone ne peut pas être chargée. */
  withLoadedArea<T>(box: Box, run: () => Promise<T>): Promise<T>;
  fill(from: Pos, to: Pos, block: string): void;
  set(at: Pos, block: string, states?: BlockStates): void;
  setText(at: Pos, text: string): void;
}

/** Les marques des îles posées, par entreprise, gardées là où le monde les garde (propriétés dynamiques). */
export interface MarkStore {
  read(companyId: number): Readonly<Record<string, string>>;
  write(companyId: number, marks: Readonly<Record<string, string>>): void;
}

export interface BuilderSettings {
  readonly opsPerBatch: number;
  readonly batchPauseSeconds: number;
  readonly buildRetrySeconds: number;
  readonly buildRetryMaxSeconds: number;
}

export interface BuilderDeps {
  readonly defs: IlesDefinitions;
  readonly writer: BlockWriter;
  readonly marks: MarkStore;
  readonly scheduler: Scheduler;
  readonly clock: Clock;
  readonly log: Logger;
  readonly settings?: BuilderSettings;
}

export interface IslandBuilder {
  /**
   * Demande que la mini-île de chaque usine soit posée dans son état. Rend le nombre d'îles qui restent à poser
   * (en attente, en cours ou à réessayer). Sans effet pour une île déjà posée dans cet état.
   */
  sync(companyId: number, origin: Xz, factories: readonly Factory[]): number;
  /** Résout vrai dès que la mini-île de l'usine est posée comme demandé, faux au bout de `timeoutSeconds`. */
  whenReady(companyId: number, factoryId: string, timeoutSeconds: number): Promise<boolean>;
  /** Les îles qui attendent ou sont en cours de pose. */
  pending(): number;
}

interface Job {
  readonly key: string;
  readonly companyId: number;
  readonly factoryId: string;
  readonly origin: Xz;
  readonly state: FactoryState;
  /** La marque qu'on obtiendra une fois l'île posée. */
  readonly mark: string;
  attempts: number;
  notBeforeMs: number;
}

interface Waiter {
  readonly companyId: number;
  readonly factoryId: string;
  readonly resolve: (ready: boolean) => void;
}

export function markOf(state: FactoryState, origin: Xz): string {
  return `${state}@${origin.x},${origin.z}`;
}

/** L'origine d'une marque (« état@x,z »), pour savoir si la plateforme est déjà posée à cet endroit. */
function markOrigin(mark: string | undefined): string | undefined {
  const at = mark?.indexOf("@") ?? -1;
  return mark === undefined || at < 0 ? undefined : mark.slice(at + 1);
}

export function applyOp(writer: BlockWriter, op: Op): void {
  switch (op.kind) {
    case "fill":
      writer.fill(op.from, op.to, op.block);
      return;
    case "set":
      if (op.states === undefined) writer.set(op.at, op.block);
      else writer.set(op.at, op.block, op.states);
      return;
    case "text":
      writer.setText(op.at, op.text);
      return;
  }
}

export function createIslandBuilder(deps: BuilderDeps): IslandBuilder {
  const { defs, writer, marks, scheduler, clock, log } = deps;
  const settings = deps.settings ?? config.world;
  const jobs = new Map<string, Job>();
  const cache = new Map<number, Record<string, string>>();
  const waiters: Waiter[] = [];
  const statuses = new Map<string, StatusLog>();
  const unknown = new Set<string>();
  let inFlight: Job | undefined;
  let running = false;
  let timerArmed = false;

  const keyOf = (companyId: number, factoryId: string): string => `${companyId}/${factoryId}`;

  function marksOf(companyId: number): Record<string, string> {
    let known = cache.get(companyId);
    if (known === undefined) {
      known = { ...marks.read(companyId) };
      cache.set(companyId, known);
    }
    return known;
  }

  function statusOf(key: string): StatusLog {
    let status = statuses.get(key);
    if (status === undefined) {
      status = createStatusLog(log, `île ${key}`);
      statuses.set(key, status);
    }
    return status;
  }

  function isReady(companyId: number, factoryId: string): boolean {
    const key = keyOf(companyId, factoryId);
    return marksOf(companyId)[factoryId] !== undefined && !jobs.has(key) && inFlight?.key !== key;
  }

  function notify(): void {
    for (let index = waiters.length - 1; index >= 0; index -= 1) {
      const waiter = waiters[index];
      if (waiter !== undefined && isReady(waiter.companyId, waiter.factoryId)) {
        waiters.splice(index, 1);
        waiter.resolve(true);
      }
    }
  }

  async function execute(job: Job, plan: IslandPlan, withBase: boolean): Promise<void> {
    const ops = withBase ? [...plan.base, ...plan.state] : [...plan.state];
    await writer.withLoadedArea(plan.box, async () => {
      for (let start = 0; start < ops.length; start += settings.opsPerBatch) {
        for (const op of ops.slice(start, start + settings.opsPerBatch)) applyOp(writer, op);
        if (start + settings.opsPerBatch < ops.length) await pause(scheduler, settings.batchPauseSeconds);
      }
    });
    log.info(`île ${job.key} posée (${job.mark}, ${ops.length} opération(s)${withBase ? ", plateforme comprise" : ""})`);
  }

  function nextDue(): Job | undefined {
    const now = clock.now();
    for (const job of jobs.values()) if (job.notBeforeMs <= now) return job;
    return undefined;
  }

  function armTimer(): void {
    if (timerArmed || jobs.size === 0) return;
    const now = clock.now();
    let soonest = Number.POSITIVE_INFINITY;
    for (const job of jobs.values()) soonest = Math.min(soonest, job.notBeforeMs);
    if (!Number.isFinite(soonest)) return;
    timerArmed = true;
    scheduler.after(Math.max(0.05, (soonest - now) / 1000), () => {
      timerArmed = false;
      void pump();
    });
  }

  async function pump(): Promise<void> {
    if (running) return;
    running = true;
    try {
      for (let job = nextDue(); job !== undefined; job = nextDue()) {
        jobs.delete(job.key);
        inFlight = job;
        const usine = usineIle(defs, job.factoryId);
        try {
          if (usine === undefined) throw new Error(`usine ${job.factoryId} absente de iles.json`);
          const known = marksOf(job.companyId);
          const plan = planIsland(defs, usine, job.origin, job.state);
          const withBase = markOrigin(known[job.factoryId]) !== `${job.origin.x},${job.origin.z}`;
          await execute(job, plan, withBase);
          known[job.factoryId] = job.mark;
          marks.write(job.companyId, known);
          statusOf(job.key).ok();
        } catch (error) {
          job.attempts += 1;
          const wait = Math.min(settings.buildRetryMaxSeconds, settings.buildRetrySeconds * 2 ** (job.attempts - 1));
          job.notBeforeMs = clock.now() + wait * 1000;
          // Une demande plus récente pour la même île prime ; sinon on réessaie celle-ci plus tard.
          if (!jobs.has(job.key)) jobs.set(job.key, job);
          statusOf(job.key).failed(describeError(error));
        } finally {
          inFlight = undefined;
        }
        notify();
      }
    } finally {
      running = false;
      armTimer();
    }
  }

  return {
    sync(companyId, origin, factories) {
      for (const factory of factories) {
        const factoryId = factory.factoryId;
        if (usineIle(defs, factoryId) === undefined) {
          if (!unknown.has(factoryId)) {
            unknown.add(factoryId);
            log.warn(`usine ${factoryId} : pas d'île dans iles.json, rien posé`);
          }
          continue;
        }
        const key = keyOf(companyId, factoryId);
        const mark = markOf(factory.state, origin);
        if (marksOf(companyId)[factoryId] === mark) {
          jobs.delete(key);
          continue;
        }
        if (jobs.get(key)?.mark === mark || (inFlight?.key === key && inFlight.mark === mark)) continue;
        jobs.set(key, { key, companyId, factoryId, origin, state: factory.state, mark, attempts: 0, notBeforeMs: 0 });
      }
      void pump();
      notify();
      return jobs.size + (inFlight === undefined ? 0 : 1);
    },

    whenReady(companyId, factoryId, timeoutSeconds) {
      if (isReady(companyId, factoryId)) return Promise.resolve(true);
      return new Promise<boolean>((resolve) => {
        const waiter: Waiter = { companyId, factoryId, resolve };
        waiters.push(waiter);
        scheduler.after(timeoutSeconds, () => {
          const index = waiters.indexOf(waiter);
          if (index < 0) return;
          waiters.splice(index, 1);
          resolve(false);
        });
      });
    },

    pending: () => jobs.size + (inFlight === undefined ? 0 : 1),
  };
}
