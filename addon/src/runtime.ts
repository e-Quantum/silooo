// Les points d'appui de l'exécution : journal, horloge à ticks, rapport d'état, garde contre les
// exceptions. Rien ici n'importe @minecraft/* : main.ts branche les vrais objets, les tests des faux.

import { config } from "./config";

export interface Logger {
  info(message: string): void;
  warn(message: string): void;
  error(message: string): void;
}

/** Ce que le code demande à l'horloge du serveur, en secondes. */
export interface Scheduler {
  /** Appelle `run` toutes les `seconds` secondes. */
  every(seconds: number, run: () => void): void;
  /** Appelle `run` une fois, dans `seconds` secondes. */
  after(seconds: number, run: () => void): void;
}

/** La partie de `system` (@minecraft/server) dont le Scheduler a besoin. */
export interface SystemLike {
  runInterval(callback: () => void, tickInterval?: number): number;
  runTimeout(callback: () => void, tickDelay?: number): number;
}

/** Secondes en ticks, au moins un tick : l'API refuse un délai nul ou négatif. */
export function secondsToTicks(seconds: number, ticksPerSecond: number = config.ticksPerSecond): number {
  if (!Number.isFinite(seconds)) return 1;
  return Math.max(1, Math.round(seconds * ticksPerSecond));
}

export function createSystemScheduler(system: SystemLike): Scheduler {
  return {
    every: (seconds, run) => {
      system.runInterval(run, secondsToTicks(seconds));
    },
    after: (seconds, run) => {
      system.runTimeout(run, secondsToTicks(seconds));
    },
  };
}

/** Une promesse qui se résout dans `seconds` secondes, à l'horloge du serveur : pour attendre dans une fonction async. */
export function pause(scheduler: Pick<Scheduler, "after">, seconds: number): Promise<void> {
  return new Promise((resolve) => scheduler.after(seconds, resolve));
}

/** Le texte d'une erreur attrapée, sans pile : une ligne de journal. */
export function describeError(error: unknown): string {
  if (error instanceof Error) return error.message === "" ? error.name : `${error.name}: ${error.message}`;
  return String(error);
}

export function prefixLogger(sink: Logger, prefix: string): Logger {
  return {
    info: (message) => sink.info(`${prefix} ${message}`),
    warn: (message) => sink.warn(`${prefix} ${message}`),
    error: (message) => sink.error(`${prefix} ${message}`),
  };
}

interface ConsoleLike {
  log(message: string): void;
  warn(message: string): void;
  error(message: string): void;
}

/**
 * Le journal du serveur BDS, par `console`. Lu sur globalThis : le code de l'add-on n'a pas de types
 * globaux de Node ni du navigateur, et `console` existe dans le moteur de scripts de Bedrock.
 */
export function consoleLogger(prefix: string = config.logPrefix): Logger {
  const sink = (globalThis as unknown as { console?: ConsoleLike }).console;
  if (sink === undefined) {
    const silent = (): void => undefined;
    return { info: silent, warn: silent, error: silent };
  }
  return prefixLogger(
    { info: (m) => sink.log(m), warn: (m) => sink.warn(m), error: (m) => sink.error(m) },
    prefix,
  );
}

/**
 * Rapport d'état d'une tâche qui se répète (heartbeat, lecture des événements). Journalise le premier
 * échec, un changement de cause et le retour à la normale, jamais chaque échec : un service arrêté
 * ne doit pas remplir le journal toutes les trois secondes.
 */
export interface StatusLog {
  ok(): void;
  failed(reason: string): void;
}

export function createStatusLog(log: Logger, label: string): StatusLog {
  let failures = 0;
  let lastReason = "";
  return {
    ok() {
      if (failures > 0) log.info(`${label} : rétabli après ${failures} échec(s)`);
      failures = 0;
      lastReason = "";
    },
    failed(reason) {
      failures += 1;
      if (reason !== lastReason) {
        log.warn(`${label} : échec (${reason})`);
        lastReason = reason;
      }
    },
  };
}

/**
 * Enveloppe un rappel pour qu'aucune exception, synchrone ou d'une promesse, ne sorte vers le moteur
 * de scripts : un script qui lève mal peut être arrêté par le serveur.
 */
export function guard<Args extends unknown[]>(
  log: Logger,
  label: string,
  run: (...args: Args) => void | Promise<void>,
): (...args: Args) => void {
  return (...args) => {
    try {
      const pending = run(...args);
      if (pending instanceof Promise) {
        pending.catch((error: unknown) => log.error(`${label} : ${describeError(error)}`));
      }
    } catch (error) {
      log.error(`${label} : ${describeError(error)}`);
    }
  };
}

// ---------------------------------------------------------------- horloge et identifiants de requête

/** L'heure, en millisecondes depuis 1970. Injectée pour que les tests fassent avancer le temps eux-mêmes. */
export interface Clock {
  now(): number;
}

export const systemClock: Clock = { now: () => Date.now() };

/** Ce dont le générateur d'identifiants a besoin : l'heure en ms, et un nombre au hasard dans [0, 1[. */
export interface RequestIdSources {
  readonly now: () => number;
  readonly random: () => number;
}

const systemRequestIdSources: RequestIdSources = { now: () => Date.now(), random: () => Math.random() };

function randomHex(random: () => number, digits: number): string {
  let out = "";
  for (let index = 0; index < digits; index += 1) {
    const value = Math.floor(random() * 16);
    out += (Number.isFinite(value) ? Math.min(15, Math.max(0, value)) : 0).toString(16);
  }
  return out;
}

/**
 * Un générateur d'UUID version 7 (RFC 9562) pour le `requestId` des actions : le service s'en sert comme
 * clé d'idempotence, il lui faut donc un UUID bien formé et jamais deux fois le même. Bedrock n'a pas de
 * `crypto.randomUUID`, d'où ce générateur maison. Il n'a pas besoin d'être imprévisible : ce n'est pas un secret.
 *
 * Les 48 premiers bits sont l'heure en ms. Dans la même milliseconde (ou si l'horloge recule), un compteur
 * de 12 bits prend la place de `rand_a` : deux appels de suite ne rendent jamais le même identifiant, même
 * avec un `random` constant. Le reste est tiré au hasard.
 */
export function createRequestIdGenerator(sources: RequestIdSources = systemRequestIdSources): () => string {
  let lastMs = -1;
  let sequence = 0;
  return () => {
    const reading = Math.floor(sources.now());
    let ms = Number.isFinite(reading) && reading >= 0 ? reading : 0;
    if (ms > lastMs) {
      sequence = 0;
    } else {
      ms = lastMs;
      sequence += 1;
      if (sequence > 0xfff) {
        ms += 1;
        sequence = 0;
      }
    }
    lastMs = ms;
    const time = ms.toString(16).padStart(12, "0").slice(-12);
    const variant = (8 + Math.min(3, Math.max(0, Math.floor(sources.random() * 4) || 0))).toString(16);
    return [
      time.slice(0, 8),
      time.slice(8, 12),
      `7${sequence.toString(16).padStart(3, "0")}`,
      `${variant}${randomHex(sources.random, 3)}`,
      randomHex(sources.random, 12),
    ].join("-");
  };
}
