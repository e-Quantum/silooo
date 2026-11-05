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
