// Le vocabulaire commun des écrans du menu : où l'on est (`Route`), ce que fait un bouton (`Target`), un écran
// montré (`Screen`), et les petites pièces qui les fabriquent. Aucun écran ici : menu.ts et personnel.ts les
// dessinent, createMenu les enchaîne. Ce fichier ne calcule rien et n'importe rien de Bedrock.

import { RESTORE_STEP_MAX, type BuyAmount, type Factory } from "../protocol";
import { COLOR, colored, ICON } from "./apparence";
import type { ActionFormSpec } from "./formulaires";
import { formatCount, formatRate } from "./nombres";
import { renderBubble, USINES_TEXTES, type Bubble } from "./textes";
import type { FactoryStatus } from "./usines";

// ---------------------------------------------------------------- vocabulaire des écrans

/** Où le joueur se trouve. Chaque écran sait quel est son parent : pas de pile à tenir. */
export type Route =
  | { readonly screen: "main" }
  | { readonly screen: "silo" }
  | { readonly screen: "objectifs" }
  | { readonly screen: "prestige" }
  /** L'écran d'après le prestige : on n'y arrive que par la réponse du service au prestige. */
  | { readonly screen: "apres" }
  | { readonly screen: "carte"; readonly page: number }
  | { readonly screen: "usines"; readonly page: number }
  | { readonly screen: "fiche"; readonly factoryId: string; readonly page: number }
  /** Les équipes, une par usine puis les membres sans usine. */
  | { readonly screen: "personnel"; readonly page: number }
  /** L'équipe d'une usine (`null` : les membres sans usine), à la page `page` de sa liste. */
  | { readonly screen: "equipe"; readonly factoryId: string | null; readonly page: number }
  | { readonly screen: "membre"; readonly staffId: number }
  /** Le choix de l'usine d'un membre. */
  | { readonly screen: "affecter"; readonly staffId: number; readonly page: number }
  /** La confirmation d'un renvoi : définitif, sans remboursement. */
  | { readonly screen: "renvoi"; readonly staffId: number }
  /** Les candidats du jour, relus à chaque ouverture. */
  | { readonly screen: "agence" }
  | { readonly screen: "candidat"; readonly slot: number }
  /** Le choix de l'usine d'un candidat qu'on embauche. */
  | { readonly screen: "embauche"; readonly slot: number; readonly page: number };

/** Ce que fait un bouton. */
export type Target =
  | { readonly kind: "go"; readonly route: Route }
  /** Une tuile verrouillée : l'écran reste, Oooscar dit que ce n'est pas encore là (ou la bulle donnée). */
  | { readonly kind: "soon"; readonly bubble?: Bubble }
  | { readonly kind: "collect" }
  /** Le bouton « Prestige ! » : demande le prestige au service. */
  | { readonly kind: "prestige" }
  /** Une île de l'entreprise sur la carte : y téléporte le joueur. */
  | { readonly kind: "travel"; readonly factoryId: string }
  | { readonly kind: "buy"; readonly factoryId: string; readonly amount: BuyAmount }
  | { readonly kind: "restore"; readonly factoryId: string; readonly step: number }
  /** Embauche le candidat de la place `slot`, dans une usine ou, sans `factoryId`, sans usine. */
  | { readonly kind: "hire"; readonly slot: number; readonly factoryId?: string }
  /** Change l'usine d'un membre. */
  | { readonly kind: "assign"; readonly staffId: number; readonly factoryId: string }
  /** Renvoie un membre. Le bouton n'existe que sur l'écran de confirmation. */
  | { readonly kind: "fire"; readonly staffId: number }
  /** Ferme le menu. */
  | { readonly kind: "end" };

/** Un écran montré : la fenêtre, et la cible de chaque bouton (même ordre que `spec.buttons`). */
export interface Screen {
  readonly spec: ActionFormSpec;
  readonly targets: readonly Target[];
}

/** Ce qu'Oooscar dit en haut d'un écran après une action, avec les lignes de chiffres qui vont avec. */
export interface Notice {
  readonly bubble: Bubble;
  readonly lines?: readonly string[];
}

export const MAIN: Route = { screen: "main" };

export function go(route: Route): Target {
  return { kind: "go", route };
}

/** Un bouton à deux lignes : le nom, puis ce qu'il faut savoir avant d'appuyer. */
export function button(label: string, detail?: string): string {
  return detail === undefined ? label : `${label}\n${colored(COLOR.muted, detail)}`;
}

/**
 * Le corps d'un écran : la bulle d'Oooscar et ses chiffres, une ligne vide, puis les lignes de l'écran (une
 * chaîne vide y fait une ligne vide). Aucune ligne vide ne reste au bout.
 */
export function bodyOf(notice: Notice | undefined, lines: readonly string[]): string {
  const top = notice === undefined ? [] : [renderBubble(notice.bubble), ...(notice.lines ?? [])];
  const all = top.length > 0 && lines.length > 0 ? [...top, "", ...lines] : [...top, ...lines];
  while (all.length > 0 && all[all.length - 1] === "") all.pop();
  return all.join("\n");
}

export interface Entry {
  readonly text: string;
  readonly target: Target;
}

export function screenOf(title: string, body: string, entries: readonly Entry[]): Screen {
  return {
    spec: { title, body, buttons: entries.map((entry) => entry.text) },
    targets: entries.map((entry) => entry.target),
  };
}

/**
 * Découpe des lignes en pages de `perScreen` au plus, avec « Précédent » et « Suite » quand il y a une page avant ou
 * après. `routeOf` dit où mène chaque page. Rend les entrées de la page (boutons de navigation compris), son rang
 * corrigé (de 0 à pages - 1) et le nombre de pages.
 */
export function pageOf(
  rows: readonly Entry[],
  page: number,
  perScreen: number,
  routeOf: (page: number) => Route,
): { readonly entries: Entry[]; readonly current: number; readonly pages: number } {
  const perPage = Math.max(1, Math.floor(perScreen));
  const pages = Math.max(1, Math.ceil(rows.length / perPage));
  const current = Math.min(Math.max(0, Math.floor(page)), pages - 1);
  const entries = rows.slice(current * perPage, (current + 1) * perPage);
  if (current > 0) entries.push({ text: USINES_TEXTES.precedent, target: go(routeOf(current - 1)) });
  if (current < pages - 1) entries.push({ text: USINES_TEXTES.suite, target: go(routeOf(current + 1)) });
  return { entries, current, pages };
}

/** Le repère d'une usine selon sa situation : une icône et une couleur, jamais la couleur seule. */
export const STATUS_MARK: Record<FactoryStatus, string> = {
  ACTIVE: colored(COLOR.success, ICON.usine),
  EN_RUINE: colored(COLOR.blocked, ICON.cadenas),
  REMISE_EN_ETAT: colored(COLOR.paper, ICON.engrenage),
  INCIDENT: colored(COLOR.incident, ICON.flamme),
  SANS_COURANT: colored(COLOR.blocked, ICON.eclair),
};

/** La ligne d'info d'une tuile d'usine : tout vient de la fiche du service. */
export function tileDetail(factory: Factory, status: FactoryStatus): string {
  const tuile = USINES_TEXTES.tuile;
  switch (status) {
    case "ACTIVE":
      return tuile.active(formatCount(factory.level), formatRate(factory.ratePerHour));
    case "EN_RUINE":
      return tuile.enRuine;
    case "REMISE_EN_ETAT":
      return tuile.remise(factory.restoreStep ?? 0, RESTORE_STEP_MAX);
    case "INCIDENT":
      return tuile.incident;
    case "SANS_COURANT":
      return tuile.sansCourant;
  }
}
