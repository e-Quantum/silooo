// Le vocabulaire commun des écrans du menu : où l'on est (`Route`), ce que fait un bouton (`Target`), un écran
// montré (`Screen`), et les petites pièces qui les fabriquent. Aucun écran ici : menu.ts et personnel.ts les
// dessinent, createMenu les enchaîne. Ce fichier ne calcule rien et n'importe rien de Bedrock.

import { RESTORE_STEP_MAX, type BuildingRotation, type BuyAmount, type Factory, type PassTrack, type Xuid } from "../protocol";
import { COLOR, colored, ICON } from "./apparence";
import type { ActionFormSpec } from "./formulaires";
import { formatCount, formatRate } from "./nombres";
import type { CosmeticCategory } from "../monde/definitions";
import { incidentTexts, MENU_TEXTES, renderBubble, USINES_TEXTES, type Bubble } from "./textes";
import type { FactoryStatus } from "./usines";

// ---------------------------------------------------------------- vocabulaire des écrans

/** Un rayon de la boutique : les grades, la liste des catégories de cosmétiques, ou une catégorie. */
export type ShopShelf = "grades" | "cosmetiques" | CosmeticCategory;

/** D'où l'on vient pour ouvrir un article : un rayon, ou la boutique elle-même (le Pilier et le boost collectif n'ont pas de rayon). */
export type ArticleShelf = "direct" | "grades" | CosmeticCategory;

/** Où le joueur se trouve. Chaque écran sait quel est son parent : pas de pile à tenir. */
export type Route =
  | { readonly screen: "main" }
  | { readonly screen: "compte" }
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
  | { readonly screen: "embauche"; readonly slot: number; readonly page: number }
  /** Les associés de l'entreprise, les invitations reçues, et ce que le PDG peut faire (associes.ts). */
  | { readonly screen: "associes" }
  /** Le choix du joueur à inviter, parmi les joueurs connectés. */
  | { readonly screen: "inviter"; readonly page: number }
  /** Une invitation reçue, avec Accepter et Plus tard. */
  | { readonly screen: "invitation"; readonly companyId: number }
  /** La ville de l'île principale (ville.ts) : mairie, bonus, prochaine zone. */
  | { readonly screen: "ville" }
  /** Les bâtiments de la ville, une page à la fois. */
  | { readonly screen: "batiments"; readonly page: number }
  /** La fiche d'un bâtiment ; `page` est celle de la liste d'où l'on vient. */
  | { readonly screen: "batiment"; readonly buildingId: number; readonly page: number }
  /** Le choix du bâtiment à poser. */
  | { readonly screen: "construire" }
  /** La pose d'un bâtiment sur la parcelle où se tient le joueur, avec la rotation choisie. */
  | { readonly screen: "poser"; readonly buildingType: string; readonly rotation: BuildingRotation }
  /** La boutique en Gemmes (boutique.ts) : les grades, le Pilier, les cosmétiques, le boost collectif, le pass. */
  | { readonly screen: "boutique" }
  /** Un rayon de la boutique, à la page `page` de sa liste. */
  | { readonly screen: "rayon"; readonly rayon: ShopShelf; readonly page: number }
  /** La fiche d'un article ; `shelf` et `page` disent d'où l'on vient. */
  | { readonly screen: "article"; readonly itemId: string; readonly shelf: ArticleShelf; readonly page: number }
  /** Le choix du joueur à qui l'on offre l'article ; `shelf` et `page` sont ceux de l'article, `choix` la page des joueurs. */
  | { readonly screen: "offrir"; readonly itemId: string; readonly shelf: ArticleShelf; readonly page: number; readonly choix: number }
  /** Le pass de saison (pass.ts) : la saison, les points, la piste premium. */
  | { readonly screen: "pass" }
  /** Les paliers du pass, une page à la fois. */
  | { readonly screen: "paliers"; readonly page: number }
  /** Un palier, avec ses deux pistes ; `page` est celle de la liste d'où l'on vient. */
  | { readonly screen: "palier"; readonly tier: number; readonly page: number };

/** Ce que fait un bouton. */
export type Target =
  | { readonly kind: "discordLink" }
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
  /**
   * Le bouton « Réparer » d'une usine en incident : joue le mini-jeu de la sorte (ui/reparation.ts), puis, s'il est réussi,
   * demande au service de clore l'incident `incidentId`. `incidentKind` est celui de la fiche du service (nul s'il manque).
   */
  | { readonly kind: "repair"; readonly factoryId: string; readonly incidentId: number; readonly incidentKind: string | null }
  /** Embauche le candidat de la place `slot`, dans une usine ou, sans `factoryId`, sans usine. */
  | { readonly kind: "hire"; readonly slot: number; readonly factoryId?: string }
  /** Change l'usine d'un membre. */
  | { readonly kind: "assign"; readonly staffId: number; readonly factoryId: string }
  /** Renvoie un membre. Le bouton n'existe que sur l'écran de confirmation. */
  | { readonly kind: "fire"; readonly staffId: number }
  /** Invite un joueur dans l'entreprise du joueur. */
  | { readonly kind: "invite"; readonly targetXuid: Xuid }
  /** Ouvre le formulaire des parts : un champ par membre. */
  | { readonly kind: "parts" }
  /** Accepte l'invitation de l'entreprise `companyId`. */
  | { readonly kind: "accept"; readonly companyId: number }
  /** L'île principale sur la carte : y téléporte le joueur, devant la mairie. */
  | { readonly kind: "travelCity" }
  /** Achète la zone `zoneIndex` de la ville. */
  | { readonly kind: "buyZone"; readonly zoneIndex: number }
  /** Passe le bâtiment `buildingId` au niveau suivant. */
  | { readonly kind: "upgrade"; readonly buildingId: number }
  /** Pose un bâtiment sur la parcelle où se tient le joueur : la parcelle est lue au moment du clic. */
  | { readonly kind: "place"; readonly buildingType: string; readonly rotation: BuildingRotation }
  /** Achète l'article `itemId` de la boutique avec les Gemmes du joueur. */
  | { readonly kind: "buyItem"; readonly itemId: string }
  /** Offre l'article `itemId` au joueur `forXuid` : c'est encore le joueur qui paie. */
  | { readonly kind: "giftItem"; readonly itemId: string; readonly forXuid: Xuid }
  /** Réclame la récompense du palier `tier` sur la piste `track` du pass. */
  | { readonly kind: "claim"; readonly tier: number; readonly track: PassTrack }
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

/** Une tuile verrouillée : un cadenas, du gris, et un mot (« Bientôt ») ou ce qu'il faut pour l'ouvrir. Elle ne mène nulle part. */
export function lockedTile(label: string, detail: string = MENU_TEXTES.bientot): Entry {
  return {
    text: `${COLOR.locked}${ICON.cadenas} ${label}\n${detail}${COLOR.reset}`,
    target: { kind: "soon" },
  };
}

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
      return incidentTexts(factory.incidentKind).nom;
    case "SANS_COURANT":
      return tuile.sansCourant;
  }
}
