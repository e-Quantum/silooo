// La cinématique d'arrivée (design/composants/Cinematique.md) : 4 plans, 20 secondes, sous-titres d'Oooscar, toujours
// passable en restant accroupi une seconde. Deux pièces :
//
// - `buildTimeline` : une liste d'étapes datées (fondu, coupe, glissé de caméra, sous-titre), construite d'après les
//   plans de definitions/iles.json et les phrases de ui/textes.ts. Pure.
// - `createCinematicRunner` : joue une chronologie pour un joueur, un pas à la fois (`tick`, toutes les 50 ms), d'après
//   une horloge injectée. Il masque le HUD et fige les déplacements au début, et rend TOUJOURS la main à la fin :
//   quoi qu'il arrive (fin, passage, départ du joueur, erreur), chaque remise en état est tentée à part.
//
// Rien ici n'importe @minecraft/* : la scène est une `CinematicStage`, branchée dans monde/bedrock.ts.

import type { IlesDefinitions } from "../generated/definitions";
import type { CinematicSettings } from "../config";
import { config } from "../config";
import { describeError, type Clock, type Logger } from "../runtime";
import { viewPoint, type Arrival, type Pos } from "./geometrie";

// ---------------------------------------------------------------- la chronologie

export type Step =
  | { readonly at: number; readonly kind: "fade"; readonly inSeconds: number; readonly holdSeconds: number; readonly outSeconds: number }
  | { readonly at: number; readonly kind: "cut"; readonly camera: Pos; readonly facing: Pos }
  | { readonly at: number; readonly kind: "move"; readonly camera: Pos; readonly facing: Pos; readonly seconds: number }
  | { readonly at: number; readonly kind: "subtitle"; readonly speaker: string; readonly text: string; readonly seconds: number };

export interface Timeline {
  /** Les étapes dans l'ordre du temps. */
  readonly steps: readonly Step[];
  /** Quand la cinématique se termine, en secondes depuis son début. */
  readonly totalSeconds: number;
}

/** Les phrases de la cinématique : qui parle, et une phrase par plan. */
export interface CinematicTexts {
  readonly speaker: string;
  readonly plans: readonly string[];
}

/**
 * Chaque plan : un fondu au noir qui cache la coupe, la caméra posée au point de départ, puis un glissé jusqu'au point
 * d'arrivée qui finit juste avant le fondu suivant. Le premier plan s'ouvre sur un fondu, le dernier se ferme sur un fondu.
 */
export function buildTimeline(
  defs: IlesDefinitions,
  arrival: Pick<Arrival, "frame">,
  texts: CinematicTexts,
  settings: CinematicSettings = config.cinematic,
): Timeline {
  const { frame } = arrival;
  const plans = defs.cinematique.plans;
  const steps: Step[] = [];
  const fadeIn = settings.fadeInSeconds;
  const starts: number[] = [];
  let total = 0;
  for (const plan of plans) {
    starts.push(total);
    total += plan.duree;
  }

  plans.forEach((plan, index) => {
    const start = starts[index] ?? 0;
    const end = start + plan.duree;
    const fadeAt = index === 0 ? 0 : start - fadeIn;
    const cutAt = index === 0 ? fadeIn : start;
    const camera = viewPoint(frame, plan.depart, defs);
    const target = viewPoint(frame, plan.arrivee, defs);
    const facing = viewPoint(frame, plan.regard, defs);
    steps.push({ at: fadeAt, kind: "fade", inSeconds: fadeIn, holdSeconds: settings.fadeHoldSeconds, outSeconds: settings.fadeOutSeconds });
    steps.push({ at: cutAt, kind: "cut", camera, facing });
    const moveAt = cutAt + settings.moveLeadSeconds;
    steps.push({ at: moveAt, kind: "move", camera: target, facing, seconds: Math.max(0.5, end - fadeIn - moveAt) });
    const phrase = texts.plans[index];
    if (phrase !== undefined && phrase !== "") {
      const subtitleAt = start + settings.subtitleLeadSeconds;
      steps.push({
        at: subtitleAt,
        kind: "subtitle",
        speaker: texts.speaker,
        text: phrase,
        seconds: Math.max(settings.subtitleMinSeconds, end - fadeIn - subtitleAt),
      });
    }
  });
  // Le fondu qui ferme le dernier plan : à son terme, la caméra du joueur revient sous le noir.
  steps.push({ at: total - fadeIn, kind: "fade", inSeconds: fadeIn, holdSeconds: settings.fadeHoldSeconds, outSeconds: settings.fadeOutSeconds });

  return { steps: steps.map((step, order) => ({ step, order })).sort((a, b) => a.step.at - b.step.at || a.order - b.order).map((item) => item.step), totalSeconds: total };
}

// ---------------------------------------------------------------- la scène et le lecteur

/** Ce que la cinématique fait à un joueur. L'implémentation réelle est dans monde/bedrock.ts. */
export interface CinematicStage<P> {
  /** Faux quand le joueur est parti ou n'existe plus. */
  isValid(player: P): boolean;
  isSneaking(player: P): boolean;
  hideHud(player: P): void;
  showHud(player: P): void;
  /** Fige les déplacements du joueur (pas l'accroupissement, qu'on lit pour passer). */
  lockInput(player: P): void;
  unlockInput(player: P): void;
  /** Pose la caméra libre d'un coup en `camera`, tournée vers `facing`. */
  setCamera(player: P, camera: Pos, facing: Pos): void;
  /** Fait glisser la caméra jusqu'en `camera` en `seconds` secondes, en regardant `facing`. */
  moveCamera(player: P, camera: Pos, facing: Pos, seconds: number): void;
  /** Rend la caméra au joueur. */
  clearCamera(player: P): void;
  fade(player: P, inSeconds: number, holdSeconds: number, outSeconds: number): void;
  subtitle(player: P, speaker: string, text: string, seconds: number): void;
  clearSubtitle(player: P): void;
  /** La consigne pour passer. */
  hint(player: P, text: string): void;
  clearHint(player: P): void;
}

export type CinematicOutcome = "finished" | "skipped" | "aborted";

export interface CinematicRunner<P> {
  /** Joue `timeline` pour ce joueur. La promesse se résout quand tout est remis en état. */
  start(key: string, player: P, timeline: Timeline): Promise<CinematicOutcome>;
  /** Un pas : à appeler toutes les `tickSeconds`. N'importe quelle exception est attrapée. */
  tick(): void;
  isPlaying(key: string): boolean;
  /** Arrête la cinématique de ce joueur (il part). Rend vrai s'il y en avait une. */
  cancel(key: string): boolean;
}

interface Playing<P> {
  readonly key: string;
  readonly player: P;
  readonly timeline: Timeline;
  readonly startedMs: number;
  readonly resolve: (outcome: CinematicOutcome) => void;
  next: number;
  sneakSinceMs: number | undefined;
  lastHintMs: number;
}

export function createCinematicRunner<P>(deps: {
  readonly stage: CinematicStage<P>;
  readonly clock: Clock;
  readonly log: Logger;
  readonly hint: string;
  readonly settings?: CinematicSettings;
}): CinematicRunner<P> {
  const { stage, clock, log, hint } = deps;
  const settings = deps.settings ?? config.cinematic;
  const playing = new Map<string, Playing<P>>();

  /** Une remise en état à la fois, chacune dans son try : l'échec de l'une n'empêche pas les autres. */
  function attempt(label: string, run: () => void): void {
    try {
      run();
    } catch (error) {
      log.warn(`cinématique : ${label} impossible (${describeError(error)})`);
    }
  }

  function finish(run: Playing<P>, outcome: CinematicOutcome): void {
    if (playing.get(run.key) !== run) return;
    playing.delete(run.key);
    const { player } = run;
    if (outcome === "skipped") attempt("fondu", () => stage.fade(player, 0.2, 0.2, 0.3));
    attempt("sous-titre", () => stage.clearSubtitle(player));
    attempt("consigne", () => stage.clearHint(player));
    attempt("caméra", () => stage.clearCamera(player));
    attempt("déplacements", () => stage.unlockInput(player));
    attempt("HUD", () => stage.showHud(player));
    run.resolve(outcome);
  }

  function apply(run: Playing<P>, step: Step): void {
    const { player } = run;
    switch (step.kind) {
      case "fade":
        stage.fade(player, step.inSeconds, step.holdSeconds, step.outSeconds);
        return;
      case "cut":
        stage.setCamera(player, step.camera, step.facing);
        return;
      case "move":
        stage.moveCamera(player, step.camera, step.facing, step.seconds);
        return;
      case "subtitle":
        stage.subtitle(player, step.speaker, step.text, step.seconds);
        return;
    }
  }

  function advance(run: Playing<P>): void {
    if (!stage.isValid(run.player)) {
      finish(run, "aborted");
      return;
    }
    const now = clock.now();
    if (stage.isSneaking(run.player)) {
      run.sneakSinceMs ??= now;
      if (now - run.sneakSinceMs >= settings.skipHoldSeconds * 1000) {
        finish(run, "skipped");
        return;
      }
    } else {
      run.sneakSinceMs = undefined;
    }
    const elapsed = (now - run.startedMs) / 1000;
    for (let step = run.timeline.steps[run.next]; step !== undefined && step.at <= elapsed; step = run.timeline.steps[run.next]) {
      run.next += 1;
      apply(run, step);
    }
    if (now - run.lastHintMs >= settings.hintRepeatSeconds * 1000) {
      run.lastHintMs = now;
      stage.hint(run.player, hint);
    }
    if (elapsed >= run.timeline.totalSeconds) finish(run, "finished");
  }

  return {
    start(key, player, timeline) {
      const before = playing.get(key);
      if (before !== undefined) finish(before, "aborted");
      return new Promise<CinematicOutcome>((resolve) => {
        const startedMs = clock.now();
        const run: Playing<P> = { key, player, timeline, startedMs, resolve, next: 0, sneakSinceMs: undefined, lastHintMs: Number.NEGATIVE_INFINITY };
        playing.set(key, run);
        attempt("HUD masqué", () => stage.hideHud(player));
        attempt("déplacements figés", () => stage.lockInput(player));
        // Premier pas tout de suite : le fondu d'ouverture part sans attendre le prochain tick.
        try {
          advance(run);
        } catch (error) {
          log.error(`cinématique : ${describeError(error)}`);
          finish(run, "aborted");
        }
      });
    },

    tick() {
      for (const run of [...playing.values()]) {
        try {
          advance(run);
        } catch (error) {
          log.error(`cinématique : ${describeError(error)}`);
          finish(run, "aborted");
        }
      }
    },

    isPlaying: (key) => playing.has(key),

    cancel(key) {
      const run = playing.get(key);
      if (run === undefined) return false;
      finish(run, "aborted");
      return true;
    },
  };
}
