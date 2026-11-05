// Les annonces de la boutique : les Gemmes qui arrivent, le boost collectif, le titre de pionnier, l'alerte « sans courant ».
// Le service les publie en événements (api/silooo-core.openapi.yaml, `GameEvent` ; core/README.md pour `GEMMES_RECUES`) ;
// ce fichier les lit et les écrit en phrases. Rien n'est calculé ici :
//   - `GEMMES_RECUES` : une bulle d'Oooscar par livraison, puis le solde affiché est celui de l'événement (`gemmesTotal`) ;
//   - `BOOST_COLLECTIF` : une seule ligne de tchat pour tous les joueurs présents ; la durée dite est celle qui reste à courir,
//     lue dans `endsAt` (et `startsAt` quand le boost attend le précédent), arrondie à la minute ;
//   - `ANNONCE` `pionnier` : une bulle de bienvenue, une fois, et le titre est retenu pour l'en-tête du menu ;
//   - `ANNONCE` `sansCourant` : une bulle d'Oooscar au joueur qui a remis l'usine en état, avec le nom de l'usine.
// Le service garde les événements et l'add-on les relit depuis le début quand le serveur redémarre : les annonces d'un autre
// temps sont donc reconnues quand elles portent une date (un boost fini ne se dit pas) ; une livraison de Gemmes rejouée se
// redit, et son solde cède la place au suivant, qui arrive dans la même relecture.

import type { Balances, GameEvent, Xuid } from "../protocol";
import { PIONEER_COSMETIC_ID } from "../protocol";
import { describeError, type Clock, type Logger } from "../runtime";
import { COLOR, colored } from "./apparence";
import type { ViewStore } from "./etat";
import { formatAmount, formatCount } from "./nombres";
import { ANNONCES_TEXTES, dureeEnMots, factoryName, renderBubble } from "./textes";

/** Un entier positif ou nul du payload, ou undefined. */
function wholeOf(value: unknown): number | undefined {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : undefined;
}

/** Un nombre positif ou nul du payload (un pourcentage peut être décimal), ou undefined. */
function amountOf(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : undefined;
}

/** Un instant ISO 8601 du payload, en millisecondes, ou undefined. */
function instantOf(value: unknown): number | undefined {
  if (typeof value !== "string") return undefined;
  const ms = Date.parse(value);
  return Number.isFinite(ms) ? ms : undefined;
}

/** Les Gemmes d'une livraison : la bulle d'Oooscar, puis ce qui est arrivé et le solde, tels que l'événement les dit. */
export function gemmesText(payload: GameEvent["payload"]): string {
  const lines = [renderBubble(ANNONCES_TEXTES.gemmes.bulle)];
  const gemmes = wholeOf(payload["gemmes"]);
  const total = wholeOf(payload["gemmesTotal"]);
  if (gemmes !== undefined && total !== undefined) {
    lines.push(colored(COLOR.gemmes, ANNONCES_TEXTES.gemmes.ligne(formatCount(gemmes), formatCount(total))));
  }
  return lines.join("\n");
}

/**
 * La durée du boost à dire, en minutes entières : de `max(startsAt, maintenant)` à `endsAt`. Un boost qui attend le précédent
 * garde ainsi sa durée propre ; un boost fini, ou une relecture d'un vieil événement, rend 0 ou moins. Sans `endsAt` lisible,
 * la durée `minutes` de l'événement.
 */
export function boostMinutes(payload: GameEvent["payload"], nowMs: number): number | undefined {
  const ends = instantOf(payload["endsAt"]);
  if (ends === undefined) return wholeOf(payload["minutes"]);
  const starts = instantOf(payload["startsAt"]);
  const from = starts === undefined ? nowMs : Math.max(starts, nowMs);
  return Math.round((ends - from) / 60_000);
}

/**
 * La ligne de tchat du boost collectif, ou undefined quand le boost n'est plus à dire (fini, ou payload illisible).
 * Avec le pseudo de l'acheteur quand le service le donne.
 */
export function boostLine(payload: GameEvent["payload"], nowMs: number): string | undefined {
  const percent = amountOf(payload["percent"]);
  const minutes = boostMinutes(payload, nowMs);
  if (percent === undefined || percent <= 0 || minutes === undefined || minutes <= 0) return undefined;
  const duree = dureeEnMots(minutes);
  const buyer = payload["buyerName"];
  const text =
    typeof buyer === "string" && buyer !== ""
      ? ANNONCES_TEXTES.boost.avecNom(buyer, formatAmount(percent), duree)
      : ANNONCES_TEXTES.boost.sansNom(formatAmount(percent), duree);
  return colored(COLOR.gemmes, text);
}

/** L'alerte « sans courant » : la bulle d'Oooscar, puis l'usine arrêtée (son nom vient de l'add-on, comme partout). */
export function sansCourantText(payload: GameEvent["payload"]): string {
  const lines = [renderBubble(ANNONCES_TEXTES.sansCourant.bulle)];
  const id = payload["factoryId"];
  if (typeof id === "string" && id !== "") lines.push(ANNONCES_TEXTES.sansCourant.ligne(factoryName(id)));
  return lines.join("\n");
}

export interface Announcements {
  /** `GEMMES_RECUES` : une bulle par livraison, et le solde de la vue devient celui du service. Ne lève pas. */
  onGemmes(event: GameEvent): void;
  /** `BOOST_COLLECTIF` : une ligne de tchat pour tous les joueurs présents. Ne lève pas. */
  onBoost(event: GameEvent): void;
  /** `ANNONCE` : `pionnier` et `sansCourant`. Les autres actions sont lues par les autres modules. Ne lève pas. */
  onAnnonce(event: GameEvent): void;
  /** Les cosmétiques du joueur : ceux de `join`, plus le titre de pionnier appris par l'annonce depuis. */
  cosmeticsOf(xuid: Xuid, fromJoin: readonly string[] | undefined): readonly string[];
}

export interface AnnouncementsDeps {
  readonly views: Pick<ViewStore, "get" | "setBalances">;
  /** Les joueurs présents que le service connaît : ceux à qui le boost s'adresse. */
  readonly online: () => readonly Xuid[];
  /** Envoie un texte au joueur (le tchat). */
  readonly say: (xuid: Xuid, text: string) => void;
  readonly clock: Clock;
  readonly log: Logger;
}

export function createAnnouncements(deps: AnnouncementsDeps): Announcements {
  const { views, online, say, clock, log } = deps;
  /** Les joueurs à qui le titre de pionnier a été annoncé : la bienvenue ne se dit qu'une fois. */
  const pioneers = new Set<Xuid>();

  function tell(xuid: Xuid, text: string, what: string): void {
    try {
      say(xuid, text);
    } catch (error) {
      log.warn(`${what}, message à ${xuid} : ${describeError(error)}`);
    }
  }

  return {
    onGemmes(event) {
      if (event.kind !== "GEMMES_RECUES" || event.xuid === null) return;
      const xuid = event.xuid;
      // Un joueur absent n'a personne à qui parler ; à son retour, `join` rend le solde du service.
      if (views.get(xuid) === undefined) return;
      const total = wholeOf(event.payload["gemmesTotal"]);
      if (total !== undefined) {
        const change: Partial<Balances> = { gemmes: total };
        views.setBalances(xuid, change);
      }
      tell(xuid, gemmesText(event.payload), "GEMMES_RECUES");
    },
    onBoost(event) {
      if (event.kind !== "BOOST_COLLECTIF") return;
      const line = boostLine(event.payload, clock.now());
      if (line === undefined) return;
      for (const xuid of online()) tell(xuid, line, "BOOST_COLLECTIF");
    },
    onAnnonce(event) {
      if (event.kind !== "ANNONCE" || event.xuid === null) return;
      const xuid = event.xuid;
      const { action } = event.payload;
      if (action === "pionnier") {
        if (pioneers.has(xuid)) return;
        pioneers.add(xuid);
        if (online().includes(xuid)) tell(xuid, renderBubble(ANNONCES_TEXTES.pionnier), "annonce-pionnier");
        return;
      }
      if (action === "sansCourant") {
        if (online().includes(xuid)) tell(xuid, sansCourantText(event.payload), "annonce-sansCourant");
      }
    },
    cosmeticsOf(xuid, fromJoin) {
      const owned = fromJoin ?? [];
      if (!pioneers.has(xuid) || owned.includes(PIONEER_COSMETIC_ID)) return owned;
      return [...owned, PIONEER_COSMETIC_ID];
    },
  };
}
