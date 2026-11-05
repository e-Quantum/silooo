// Faux partagés par les tests de la partie « monde » : un temps virtuel (horloge et planificateur qui avancent
// ensemble), un monde de blocs qui tient dans une Map, des marques en mémoire. Rien ici n'importe @minecraft/*.

import type { BlockWriter, MarkStore } from "../src/monde/constructeur";
import type { Box, Pos } from "../src/monde/geometrie";
import type { BlockStates } from "../src/monde/plan";
import type { Clock, Scheduler } from "../src/runtime";

// ---------------------------------------------------------------- le temps

export interface VirtualTime {
  readonly scheduler: Scheduler;
  readonly clock: Clock;
  /** Fait avancer le temps de `seconds`, en exécutant dans l'ordre chaque délai échu et en laissant les promesses se résoudre. */
  advance(seconds: number): Promise<void>;
  /** Laisse les promesses en attente se résoudre, sans faire avancer le temps. */
  flush(): Promise<void>;
  /** Les délais et intervalles qui attendent. */
  readonly timers: number;
  /** Combien de secondes se sont écoulées depuis le début. */
  elapsed(): number;
}

interface Timer {
  at: number;
  readonly order: number;
  readonly run: () => void;
  readonly period: number | undefined;
}

export function virtualTime(startMs = 1_000_000): VirtualTime {
  let now = startMs;
  let sequence = 0;
  const timers: Timer[] = [];

  const flush = async (): Promise<void> => {
    // Un tour de la boucle d'événements vide toutes les microtâches en attente.
    await new Promise<void>((resolve) => setImmediate(resolve));
  };

  function next(limit: number): Timer | undefined {
    let best: Timer | undefined;
    for (const timer of timers) {
      if (timer.at > limit) continue;
      if (best === undefined || timer.at < best.at || (timer.at === best.at && timer.order < best.order)) best = timer;
    }
    return best;
  }

  return {
    clock: { now: () => now },
    scheduler: {
      every(seconds, run) {
        sequence += 1;
        timers.push({ at: now + seconds * 1000, order: sequence, run, period: seconds * 1000 });
      },
      after(seconds, run) {
        sequence += 1;
        timers.push({ at: now + seconds * 1000, order: sequence, run, period: undefined });
      },
    },
    flush,
    async advance(seconds) {
      const target = now + seconds * 1000;
      await flush();
      for (let due = next(target); due !== undefined; due = next(target)) {
        now = Math.max(now, due.at);
        if (due.period === undefined) timers.splice(timers.indexOf(due), 1);
        else due.at += due.period;
        due.run();
        await flush();
      }
      now = target;
      await flush();
    },
    get timers() {
      return timers.length;
    },
    elapsed: () => (now - startMs) / 1000,
  };
}

// ---------------------------------------------------------------- un monde de blocs

const AIR = "minecraft:air";

export interface PlacedBlock {
  readonly block: string;
  readonly states?: BlockStates;
}

export interface FakeWorld extends BlockWriter {
  /** Les blocs posés, par « x,y,z ». L'air n'y figure pas. */
  readonly blocks: Map<string, PlacedBlock>;
  /** Le texte des enseignes, par « x,y,z ». */
  readonly texts: Map<string, string>;
  /** Les zones chargées, dans l'ordre. */
  readonly areas: Box[];
  /** Les instants (ms de l'horloge) où une zone a été demandée. */
  readonly attemptTimes: number[];
  /** Combien d'opérations (fill, set, setText) ont été exécutées. */
  ops: number;
  /** Les prochains chargements de zone échouent, tant que ce nombre est positif. */
  failLoads: number;
  /** Le plus de zones ouvertes en même temps. */
  maxOpenAreas: number;
  blockAt(x: number, y: number, z: number): string | undefined;
  count(block: string): number;
  /** Une copie de la photographie du monde : pour comparer deux mondes. */
  snapshot(): Array<[string, string]>;
}

const key = (x: number, y: number, z: number): string => `${x},${y},${z}`;

export function fakeWorld(clock: Clock = { now: () => 0 }): FakeWorld {
  const blocks = new Map<string, PlacedBlock>();
  const texts = new Map<string, string>();
  const areas: Box[] = [];
  const attemptTimes: number[] = [];
  let open: Box | undefined;
  let openCount = 0;

  const world: FakeWorld = {
    blocks,
    texts,
    areas,
    attemptTimes,
    ops: 0,
    failLoads: 0,
    maxOpenAreas: 0,

    async withLoadedArea(box, run) {
      attemptTimes.push(clock.now());
      if (world.failLoads > 0) {
        world.failLoads -= 1;
        throw new Error("zone impossible à charger");
      }
      areas.push(box);
      open = box;
      openCount += 1;
      world.maxOpenAreas = Math.max(world.maxOpenAreas, openCount);
      try {
        return await run();
      } finally {
        openCount -= 1;
        if (openCount === 0) open = undefined;
      }
    },

    fill(from, to, block) {
      world.ops += 1;
      for (let x = Math.min(from.x, to.x); x <= Math.max(from.x, to.x); x += 1) {
        for (let y = Math.min(from.y, to.y); y <= Math.max(from.y, to.y); y += 1) {
          for (let z = Math.min(from.z, to.z); z <= Math.max(from.z, to.z); z += 1) place({ x, y, z }, block);
        }
      }
    },

    set(at, block, states) {
      world.ops += 1;
      place(at, block, states);
    },

    setText(at, text) {
      world.ops += 1;
      inside(at);
      const found = blocks.get(key(at.x, at.y, at.z));
      if (found === undefined || !found.block.includes("sign")) throw new Error(`pas d'enseigne en ${key(at.x, at.y, at.z)}`);
      texts.set(key(at.x, at.y, at.z), text);
    },

    blockAt: (x, y, z) => blocks.get(key(x, y, z))?.block,

    count(block) {
      let total = 0;
      for (const placed of blocks.values()) if (placed.block === block) total += 1;
      return total;
    },

    snapshot: () => [...blocks.entries()].map(([k, v]) => [k, `${v.block}${JSON.stringify(v.states ?? {})}`] as [string, string]).sort(([a], [b]) => a.localeCompare(b)),
  };

  /** Le monde refuse d'écrire hors d'une zone chargée : le constructeur doit toujours charger avant de poser. */
  function inside(at: Pos): void {
    if (open === undefined) throw new Error(`écriture en ${key(at.x, at.y, at.z)} hors d'une zone chargée`);
    const { from, to } = open;
    if (at.x < from.x || at.x > to.x || at.y < from.y || at.y > to.y || at.z < from.z || at.z > to.z) {
      throw new Error(`écriture en ${key(at.x, at.y, at.z)} hors de la zone chargée`);
    }
  }

  function place(at: Pos, block: string, states?: BlockStates): void {
    inside(at);
    const k = key(at.x, at.y, at.z);
    if (block === AIR) {
      blocks.delete(k);
      texts.delete(k);
    } else if (states === undefined) blocks.set(k, { block });
    else blocks.set(k, { block, states });
  }

  return world;
}

// ---------------------------------------------------------------- les marques

export interface FakeMarks extends MarkStore {
  readonly stored: Map<number, Record<string, string>>;
  reads: number;
  writes: number;
}

export function fakeMarks(initial: Record<number, Record<string, string>> = {}): FakeMarks {
  const stored = new Map<number, Record<string, string>>(Object.entries(initial).map(([id, marks]) => [Number(id), { ...marks }]));
  const marks: FakeMarks = {
    stored,
    reads: 0,
    writes: 0,
    read(companyId) {
      marks.reads += 1;
      return { ...(stored.get(companyId) ?? {}) };
    },
    write(companyId, value) {
      marks.writes += 1;
      stored.set(companyId, { ...value });
    },
  };
  return marks;
}
