// Le compteur de Donar qui défile. C'est la seule valeur que l'add-on projette lui-même : entre deux réponses
// du service, il ajoute `ratePerHour` multiplié par le temps écoulé au `donar` de la dernière réponse
// (api/silooo-core.openapi.yaml, CompanyState.ratePerHour : « l'add-on fait défiler le compteur à partir de ce
// revenu, sans rappeler le service », docs/socle.md règle 2). À chaque réponse du service, on repart de son
// `donar` : le service a toujours raison, le compteur n'est qu'un affichage.
//
// Fonctions pures : l'instant est un argument, donc les tests font avancer le temps sans horloge.

import type { CompanyState } from "../protocol";

/** Millisecondes dans une heure : le revenu du service est par heure, l'horloge est en millisecondes. */
export const MS_PER_HOUR = 3_600_000;

/** Le point de départ du défilement : ce que le service a dit, et à quel instant on l'a su. */
export interface CounterAnchor {
  readonly donar: number;
  readonly ratePerHour: number;
  /** L'instant de la réponse du service, en millisecondes. */
  readonly atMs: number;
}

export function anchorOf(state: Pick<CompanyState, "donar" | "ratePerHour">, atMs: number): CounterAnchor {
  return { donar: state.donar, ratePerHour: state.ratePerHour, atMs };
}

/**
 * La valeur du compteur à l'instant `nowMs`. Elle ne descend jamais sous le `donar` du service : une horloge
 * qui recule, un revenu négatif ou illisible donnent simplement la valeur de départ.
 */
export function counterValue(anchor: CounterAnchor, nowMs: number): number {
  const elapsedMs = nowMs - anchor.atMs;
  if (!Number.isFinite(elapsedMs) || elapsedMs <= 0) return anchor.donar;
  if (!Number.isFinite(anchor.ratePerHour) || anchor.ratePerHour <= 0) return anchor.donar;
  return anchor.donar + (anchor.ratePerHour * elapsedMs) / MS_PER_HOUR;
}
