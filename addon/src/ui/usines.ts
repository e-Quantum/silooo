// Lire l'état d'une usine tel que le service le donne : quelle situation afficher, quelle étape de remise en
// état proposer. Ce fichier classe et ne calcule rien : pas de coût, pas de gain, pas de niveau.

import { RESTORE_STEP_MAX, RESTORE_STEP_MIN, type Factory } from "../protocol";

/**
 * Ce que le joueur doit comprendre d'une usine, du plus grave au plus calme. Une usine en ruine ou en remise en
 * état n'a ni incident ni arrêt faute de courant à montrer ; une usine active peut avoir l'un ou l'autre.
 */
export type FactoryStatus = "EN_RUINE" | "REMISE_EN_ETAT" | "INCIDENT" | "SANS_COURANT" | "ACTIVE";

export function factoryStatus(factory: Factory): FactoryStatus {
  if (factory.state === "EN_RUINE") return "EN_RUINE";
  if (factory.state === "REMISE_EN_ETAT") return "REMISE_EN_ETAT";
  if (factory.incidentId !== undefined && factory.incidentId !== null) return "INCIDENT";
  if (factory.stoppedNoPower === true) return "SANS_COURANT";
  return "ACTIVE";
}

/** Une usine dont on peut monter les niveaux : tout sauf la ruine et la remise en état. */
export function canBuyLevels(factory: Factory): boolean {
  return factory.state === "ACTIVE";
}

/**
 * L'étape de remise en état à demander au service : la première pour une ruine, la suivante pour une remise en
 * état en cours. `restoreStep` est lu comme la dernière étape faite (contrat à confirmer, voir addon/README.md).
 * Rend undefined quand il n'y a rien à demander, par exemple si les quatre étapes sont déjà faites.
 */
export function nextRestoreStep(factory: Factory): number | undefined {
  if (factory.state === "EN_RUINE") return RESTORE_STEP_MIN;
  if (factory.state !== "REMISE_EN_ETAT") return undefined;
  const done = Number.isInteger(factory.restoreStep) ? (factory.restoreStep ?? 0) : 0;
  const next = Math.max(RESTORE_STEP_MIN, done + 1);
  return next <= RESTORE_STEP_MAX ? next : undefined;
}
