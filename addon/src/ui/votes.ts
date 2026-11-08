// Les votes vus par le joueur : le rappel du vote du jour, et les deux annonces du service (un vote reçu, la Vote party).
//
// Les votes arrivent de Hubyy par Votifier, côté service, qui verse les Lingots et publie l'événement `ANNONCE`
// (api/silooo-core.openapi.yaml, `todayVotes`). Ce fichier ne fait que dire ce que le service a décidé :
//   - à l'arrivée du joueur et dans l'écran de la boutique, un rappel sobre quand `todayVotes` dit qu'il n'a pas voté
//     aujourd'hui, avec le nom des sites de definitions/vote.json (le nom seul, pas de lien) ;
//   - `ANNONCE` `vote` : la récompense versée et la série, en une bulle d'Oooscar dans le tchat du joueur ;
//   - `ANNONCE` `voteParty` : une bulle pour tous les joueurs présents.
// Les Lingots montrés sont ceux de l'événement : rien n'est calculé, et aucun solde n'est ajouté ici.

import type { CoreApi } from "../api";
import { describeFailure } from "../http";
import { VOTE } from "../monde/definitions";
import type { GameEvent, Xuid } from "../protocol";
import { describeError, type Clock, type Logger } from "../runtime";
import type { VotesOutcome } from "./actions";
import { COLOR, colored } from "./apparence";
import { formatCount } from "./nombres";
import { VOTE_TEXTES, renderBubble } from "./textes";

/** Les noms des sites de vote, tels que definitions/vote.json les donne, séparés par une virgule. Vide s'il n'y en a aucun. */
export function voteSites(sites: readonly { readonly nom: string }[] = VOTE.sites): string {
  return sites.map((site) => site.nom).join(", ");
}

/** Le service dit-il que le joueur n'a pas voté aujourd'hui ? Faux tant que la lecture manque ou a échoué : pas de rappel sans preuve. */
export function hasNotVoted(votes: VotesOutcome | undefined): boolean {
  return votes?.kind === "votes" && votes.votes.mine.votes === 0;
}

/** La ligne de rappel de l'écran de la boutique, ou undefined quand le joueur a voté, qu'on ne sait pas, ou qu'aucun site n'est connu. */
export function reminderLine(votes: VotesOutcome | undefined, sites: string = voteSites()): string | undefined {
  if (!hasNotVoted(votes) || sites === "") return undefined;
  return colored(COLOR.muted, VOTE_TEXTES.rappel.boutique(sites));
}

/** Le texte du rappel d'arrivée : la bulle d'Oooscar, puis les sites où voter. */
export function reminderText(sites: string = voteSites()): string {
  const lines = [renderBubble(VOTE_TEXTES.rappel.bulle)];
  if (sites !== "") lines.push(VOTE_TEXTES.rappel.ligne(sites));
  return lines.join("\n");
}

/** Un nombre entier positif ou nul du payload, ou undefined. */
function wholeOf(value: unknown): number | undefined {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : undefined;
}

/**
 * Le texte d'un vote reçu : la bulle d'Oooscar, les Lingots versés (ceux de l'événement, bonus de série compris), la série en
 * jours et, quand la série a donné un bonus, la part du bonus. Une valeur absente du payload n'est pas dite.
 */
export function voteText(payload: GameEvent["payload"]): string {
  const lines = [renderBubble(VOTE_TEXTES.vote.bulle)];
  const reward = wholeOf(payload["rewardLingots"]);
  if (reward !== undefined && reward > 0) lines.push(colored(COLOR.lingots, VOTE_TEXTES.vote.recompense(formatCount(reward))));
  const streak = wholeOf(payload["streak"]);
  if (streak !== undefined && streak > 0) lines.push(VOTE_TEXTES.vote.serie(formatCount(streak)));
  const bonus = wholeOf(payload["streakBonusLingots"]);
  if (bonus !== undefined && bonus > 0) lines.push(VOTE_TEXTES.vote.bonus(formatCount(bonus)));
  return lines.join("\n");
}

/** Le texte de la Vote party : la bulle d'Oooscar, puis ce que chaque joueur présent a reçu (le chiffre de l'événement). */
export function votePartyText(payload: GameEvent["payload"]): string {
  const lines = [renderBubble(VOTE_TEXTES.voteParty.bulle)];
  const reward = wholeOf(payload["rewardLingots"]);
  if (reward !== undefined && reward > 0) lines.push(colored(COLOR.lingots, VOTE_TEXTES.voteParty.ligne(formatCount(reward))));
  return lines.join("\n");
}

/** Le jour UTC d'un instant, « AAAA-MM-JJ » : le format des jours du service. */
function utcDay(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

export interface Votes {
  /** À l'arrivée du joueur : si le service dit qu'il n'a pas voté aujourd'hui, Oooscar le lui rappelle. Ne lève pas. */
  arrive(xuid: Xuid): Promise<void>;
  /** Un événement `ANNONCE` : un vote reçu (dit au joueur) ou la Vote party (dite à tous les joueurs présents). Ne lève pas. */
  onAnnonce(event: GameEvent): Promise<void>;
}

export interface VotesDeps {
  readonly api: Pick<CoreApi, "todayVotes">;
  /** Les joueurs présents que le service connaît : ceux à qui la Vote party s'adresse. */
  readonly online: () => readonly Xuid[];
  /** Envoie un texte au joueur (le tchat). */
  readonly say: (xuid: Xuid, text: string) => void;
  readonly clock: Clock;
  readonly log: Logger;
}

export function createVotes(deps: VotesDeps): Votes {
  const { api, online, say, clock, log } = deps;

  function tell(xuid: Xuid, text: string): void {
    try {
      say(xuid, text);
    } catch (error) {
      log.warn(`vote, message à ${xuid} : ${describeError(error)}`);
    }
  }

  // Le jour UTC du dernier rappel de chaque joueur : un joueur qui se reconnecte dans la journée n'est pas relancé.
  const reminded = new Map<Xuid, string>();

  return {
    async arrive(xuid) {
      try {
        const today = utcDay(clock.now());
        if (reminded.get(xuid) === today) return;
        const result = await api.todayVotes(xuid);
        if (result.kind !== "ok") {
          log.warn(`rappel du vote de ${xuid} : ${describeFailure(result)}`);
          return;
        }
        if (result.body.mine.votes === 0) {
          reminded.set(xuid, today);
          tell(xuid, reminderText());
        }
      } catch (error) {
        log.warn(`rappel du vote de ${xuid} : ${describeError(error)}`);
      }
    },
    async onAnnonce(event) {
      if (event.kind !== "ANNONCE") return;
      const { action } = event.payload;
      if (action === "vote") {
        // Un joueur absent n'a personne à qui parler ; ses Lingots l'attendent chez le service.
        if (event.xuid !== null && online().includes(event.xuid)) tell(event.xuid, voteText(event.payload));
        return;
      }
      if (action === "voteParty") {
        // Le service garde les événements et l'add-on les relit depuis le début si son curseur gardé est perdu : une Vote party d'un autre jour n'est pas une nouvelle.
        if (event.payload["day"] !== utcDay(clock.now())) return;
        const text = votePartyText(event.payload);
        for (const xuid of online()) tell(xuid, text);
      }
    },
  };
}
