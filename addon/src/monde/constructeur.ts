// Le constructeur d'îles : pose, un par un, les mini-îles et l'île principale que les entreprises présentes demandent,
// dans l'état que le service dit. Il ne décide de rien : il lit l'état donné par le service (via `sync` et `syncCity`), le
// compare à ce qu'il a déjà posé (une marque par île, par zone et par bâtiment, gardée dans le monde) et ne refait que
// ce qui a changé.
//
// - Une marque `état@x,z` par usine et par entreprise : la même île n'est jamais reposée dans le même état ; un
//   changement d'état refait le bâtiment seulement ; une autre origine d'archipel refait aussi la plateforme.
// - La ville a ses marques à elle (clés « ville:zone:N » et « ville:b:ID », monde/ville.ts) : une plateforme par zone
//   possédée, un préfabriqué par bâtiment, refait quand son niveau, sa parcelle ou sa rotation changent. Un bâtiment
//   attend la plateforme de sa zone.
// - Un seul chantier à la fois, et des lots d'opérations séparés par des pauses : le serveur ne se fige pas.
// - Un chantier est loin du joueur (plusieurs centaines de blocs) : il charge d'abord sa zone (ticking area), pose,
//   puis la relâche. Si le chargement échoue, nouvel essai plus tard, attente doublée à chaque échec.
// - Rien d'ici n'importe @minecraft/* : le monde est un `BlockWriter`, les marques un `MarkStore`.

import type { IlesDefinitions, VilleDefinitions } from "../generated/definitions";
import type { CityState, Factory, FactoryState } from "../protocol";
import { createStatusLog, describeError, pause, type Clock, type Logger, type Scheduler, type StatusLog } from "../runtime";
import { config } from "../config";
import { VILLE } from "./definitions";
import type { Box, Pos, Xz } from "./geometrie";
import { usineIle } from "./geometrie";
import { planIsland, type BlockStates, type Op } from "./plan";
import { cityWorks, isKnownBuilding, type Segment } from "./ville";

/** Ce que le constructeur demande au monde. L'implémentation réelle est dans monde/bedrock.ts. */
export interface BlockWriter {
  /** Charge la zone, exécute `run`, relâche la zone. Rejette si la zone ne peut pas être chargée. */
  withLoadedArea<T>(box: Box, run: () => Promise<T>): Promise<T>;
  fill(from: Pos, to: Pos, block: string): void;
  set(at: Pos, block: string, states?: BlockStates): void;
  setText(at: Pos, text: string): void;
  /** Disponible dans Bedrock ; les autres mondes peuvent rejouer le plan bloc par bloc. */
  structure?(id: string, at: Pos): void;
}

/** Les marques des travaux posés, par entreprise, gardées là où le monde les garde (propriétés dynamiques). */
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
  /** La ville de l'île principale (definitions/ville.json). Absente : celle du fichier de données. */
  readonly ville?: VilleDefinitions;
  readonly writer: BlockWriter;
  readonly marks: MarkStore;
  readonly scheduler: Scheduler;
  readonly clock: Clock;
  readonly log: Logger;
  readonly settings?: BuilderSettings;
}

export interface IslandBuilder {
  /**
   * Demande que la mini-île de chaque usine soit posée dans son état. Rend le nombre de chantiers qui restent à poser
   * (en attente, en cours ou à réessayer). Sans effet pour une île déjà posée dans cet état.
   */
  sync(companyId: number, origin: Xz, factories: readonly Factory[]): number;
  /**
   * Demande que la plateforme de chaque zone possédée et chaque bâtiment de la ville soient posés. Rend le nombre de
   * chantiers qui restent à poser. Sans effet pour ce qui est déjà posé tel quel.
   */
  syncCity(companyId: number, origin: Xz, city: CityState): number;
  /**
   * Résout vrai dès que le chantier de la clé de marque `markKey` est posé comme demandé, faux au bout de
   * `timeoutSeconds`. La clé d'une mini-île est l'identifiant de son usine ; celle d'un bâtiment, `buildingKey`.
   */
  whenReady(companyId: number, markKey: string, timeoutSeconds: number): Promise<boolean>;
  /** Les chantiers qui attendent ou sont en cours. */
  pending(): number;
}

/** Ce qu'un chantier va exécuter, calculé au moment de le lancer (les marques connues peuvent avoir changé depuis la demande). */
interface JobPlan {
  readonly segments: readonly Segment[];
  /** Un mot de plus pour le journal. */
  readonly detail: string;
}

interface Job {
  /** « entreprise/clé de marque » : un chantier par clé. */
  readonly key: string;
  readonly companyId: number;
  /** La clé de sa marque dans le `MarkStore` : l'identifiant d'une usine, ou une clé de la ville. */
  readonly markKey: string;
  /** La marque qu'on obtiendra une fois posé. */
  readonly mark: string;
  /** « île 7/carriere », « zone 7/1 » : le début de la ligne de journal. */
  readonly label: string;
  /** « posée » ou « posé » : la fin de la ligne de journal. */
  readonly done: string;
  /** La clé d'un autre chantier de la même entreprise à finir avant celui-ci. */
  readonly after: string | undefined;
  readonly plan: (known: Readonly<Record<string, string>>) => JobPlan;
  attempts: number;
  notBeforeMs: number;
}

interface Waiter {
  readonly companyId: number;
  readonly markKey: string;
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
    case "structure":
      if (writer.structure === undefined) throw new Error("monde sans placement de structure");
      writer.structure(op.id, op.at);
      return;
  }
}

export function createIslandBuilder(deps: BuilderDeps): IslandBuilder {
  const { defs, writer, marks, scheduler, clock, log } = deps;
  const ville = deps.ville ?? VILLE;
  const settings = deps.settings ?? config.world;
  const jobs = new Map<string, Job>();
  const cache = new Map<number, Record<string, string>>();
  const waiters: Waiter[] = [];
  const statuses = new Map<string, StatusLog>();
  const unknown = new Set<string>();
  let inFlight: Job | undefined;
  let running = false;
  let timerArmed = false;

  const keyOf = (companyId: number, markKey: string): string => `${companyId}/${markKey}`;

  function marksOf(companyId: number): Record<string, string> {
    let known = cache.get(companyId);
    if (known === undefined) {
      known = { ...marks.read(companyId) };
      cache.set(companyId, known);
    }
    return known;
  }

  function statusOf(job: Job): StatusLog {
    let status = statuses.get(job.key);
    if (status === undefined) {
      status = createStatusLog(log, job.label);
      statuses.set(job.key, status);
    }
    return status;
  }

  function isReady(companyId: number, markKey: string): boolean {
    const key = keyOf(companyId, markKey);
    return marksOf(companyId)[markKey] !== undefined && !jobs.has(key) && inFlight?.key !== key;
  }

  function notify(): void {
    for (let index = waiters.length - 1; index >= 0; index -= 1) {
      const waiter = waiters[index];
      if (waiter !== undefined && isReady(waiter.companyId, waiter.markKey)) {
        waiters.splice(index, 1);
        waiter.resolve(true);
      }
    }
  }

  /** Un bâtiment attend la plateforme de sa zone : tant qu'elle est à poser, en cours ou à réessayer, il reste en file. */
  function blocked(job: Job): boolean {
    if (job.after === undefined) return false;
    const key = keyOf(job.companyId, job.after);
    return jobs.has(key) || inFlight?.key === key;
  }

  async function execute(job: Job, plan: JobPlan): Promise<void> {
    let total = 0;
    for (const [index, segment] of plan.segments.entries()) {
      if (index > 0) await pause(scheduler, settings.batchPauseSeconds);
      await writer.withLoadedArea(segment.box, async () => {
        for (let start = 0; start < segment.ops.length; start += settings.opsPerBatch) {
          for (const op of segment.ops.slice(start, start + settings.opsPerBatch)) applyOp(writer, op);
          if (start + settings.opsPerBatch < segment.ops.length) await pause(scheduler, settings.batchPauseSeconds);
        }
      });
      total += segment.ops.length;
    }
    log.info(`${job.label} ${job.done} (${job.mark}, ${total} opération(s)${plan.detail})`);
  }

  function nextDue(): Job | undefined {
    const now = clock.now();
    for (const job of jobs.values()) if (job.notBeforeMs <= now && !blocked(job)) return job;
    return undefined;
  }

  function armTimer(): void {
    if (timerArmed || jobs.size === 0) return;
    const now = clock.now();
    let soonest = Number.POSITIVE_INFINITY;
    for (const job of jobs.values()) if (!blocked(job)) soonest = Math.min(soonest, job.notBeforeMs);
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
        try {
          const known = marksOf(job.companyId);
          await execute(job, job.plan(known));
          known[job.markKey] = job.mark;
          marks.write(job.companyId, known);
          statusOf(job).ok();
        } catch (error) {
          job.attempts += 1;
          const wait = Math.min(settings.buildRetryMaxSeconds, settings.buildRetrySeconds * 2 ** (job.attempts - 1));
          job.notBeforeMs = clock.now() + wait * 1000;
          // Une demande plus récente pour le même chantier prime ; sinon on réessaie celui-ci plus tard.
          if (!jobs.has(job.key)) jobs.set(job.key, job);
          statusOf(job).failed(describeError(error));
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

  /** Met un chantier en file, sauf s'il est déjà posé tel quel ou déjà demandé tel quel. */
  function request(job: Job): void {
    const marked = marksOf(job.companyId)[job.markKey];
    if (marked === job.mark) {
      jobs.delete(job.key);
      return;
    }
    if (jobs.get(job.key)?.mark === job.mark || (inFlight?.key === job.key && inFlight.mark === job.mark)) return;
    jobs.set(job.key, job);
  }

  const pendingCount = (): number => jobs.size + (inFlight === undefined ? 0 : 1);

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
        const state = factory.state;
        request({
          key: keyOf(companyId, factoryId),
          companyId,
          markKey: factoryId,
          mark: markOf(state, origin),
          label: `île ${companyId}/${factoryId}`,
          done: "posée",
          after: undefined,
          plan: (known) => {
            const usine = usineIle(defs, factoryId);
            if (usine === undefined) throw new Error(`usine ${factoryId} absente de iles.json`);
            const plan = planIsland(defs, usine, origin, state);
            const withBase = markOrigin(known[factoryId]) !== `${origin.x},${origin.z}`;
            const ops: Op[] = plan.structure !== undefined && writer.structure !== undefined
              ? [...(plan.clear ?? []), { kind: "structure", ...plan.structure }, ...plan.state.filter((op) => op.kind === "text")]
              : withBase ? [...plan.base, ...plan.state] : [...plan.state];
            return { segments: [{ box: plan.box, ops }], detail: withBase ? ", plateforme comprise" : "" };
          },
          attempts: 0,
          notBeforeMs: 0,
        });
      }
      void pump();
      notify();
      return pendingCount();
    },

    syncCity(companyId, origin, city) {
      for (const building of city.buildings) {
        if (!isKnownBuilding(building.buildingType) && !unknown.has(building.buildingType)) {
          unknown.add(building.buildingType);
          log.warn(`bâtiment ${building.buildingType} : aspect inconnu, une boîte de planches posée à la place`);
        }
      }
      for (const work of cityWorks(defs, ville, origin, city)) {
        request({
          key: keyOf(companyId, work.markKey),
          companyId,
          markKey: work.markKey,
          mark: work.mark,
          label: `${work.kind} ${companyId}/${work.ref}`,
          done: work.kind === "zone" ? "posée" : "posé",
          after: work.after,
          plan: () => ({ segments: work.segments(), detail: "" }),
          attempts: 0,
          notBeforeMs: 0,
        });
      }
      void pump();
      notify();
      return pendingCount();
    },

    whenReady(companyId, markKey, timeoutSeconds) {
      if (isReady(companyId, markKey)) return Promise.resolve(true);
      return new Promise<boolean>((resolve) => {
        const waiter: Waiter = { companyId, markKey, resolve };
        waiters.push(waiter);
        scheduler.after(timeoutSeconds, () => {
          const index = waiters.indexOf(waiter);
          if (index < 0) return;
          waiters.splice(index, 1);
          resolve(false);
        });
      });
    },

    pending: pendingCount,
  };
}
