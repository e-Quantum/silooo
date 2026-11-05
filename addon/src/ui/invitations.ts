// Les invitations reçues, et les annonces de l'entreprise partagée. Deux événements du service, dits au joueur :
//
// - `INVITATION` (adressé à l'invité) : une entreprise l'invite. On garde UNE invitation par entreprise qui invite (une
//   nouvelle remplace l'ancienne), jusqu'à ce qu'elle expire (`expiresAt`, comparée à l'horloge) ou que le joueur y
//   réponde. Le joueur est prévenu dans le tchat ; l'invitation attend dans l'écran des associés (ui/associes.ts).
//   Elle est gardée même si l'invité est hors ligne : le curseur des événements repart de 0 au redémarrage du serveur,
//   et l'invité peut se connecter après. Le service reste seul juge : il refuse une invitation périmée (400), pleine,
//   ou qu'une autre acceptation a rendue caduque.
// - `ANNONCE` de `setShares` (adressée à l'acteur, le PDG) : « Les parts ont changé ». Un drapeau par joueur évite de
//   répéter la phrase tant qu'il n'a pas rouvert l'écran des associés (qui relit la liste) : un redémarrage du serveur
//   rejoue d'anciens événements, et un joueur ne doit pas en recevoir dix.
//
// Rien n'est calculé ici : un nom, un nom d'entreprise, une date d'expiration, tels que le service les a donnés.

import type { GameEvent, Xuid } from "../protocol";
import { describeError, type Clock, type Logger } from "../runtime";
import type { PlayerStore } from "../state";
import { COLOR, colored } from "./apparence";
import { ASSOCIES_TEXTES, renderBubble } from "./textes";

/** Le `payload.action` de l'ANNONCE publiée par `setShares`. */
const SET_SHARES_ACTION = "setShares";

/** Une invitation en attente, telle que le service l'a envoyée. */
export interface Invitation {
  /** L'entreprise qui invite. */
  readonly companyId: number;
  readonly companyName: string;
  readonly invitedBy: string;
  readonly invitedByName: string;
  /** L'instant d'expiration, en millisecondes sur l'horloge du service. */
  readonly expiresAtMs: number;
}

export interface Invitations {
  /** Les invitations en cours du joueur, de la plus ancienne à la plus récente. Celles qui ont expiré sont retirées. */
  list(xuid: Xuid): readonly Invitation[];
  find(xuid: Xuid, companyId: number): Invitation | undefined;
  /** Le joueur a répondu, ou le service dit qu'elle n'existe plus. */
  remove(xuid: Xuid, companyId: number): void;
  /** Le joueur est entré dans une entreprise : le service périme ses autres invitations. */
  clear(xuid: Xuid): void;
  /** Un événement `INVITATION`. Ne lève pas. */
  onInvitation(event: GameEvent): void;
  /** Un événement `ANNONCE` : seul `setShares` est repris. Ne lève pas. */
  onAnnonce(event: GameEvent): void;
  /** Le joueur vient d'arriver : ses invitations en attente, qu'il n'a pas encore lues, lui sont dites. Ne lève pas. */
  arrive(xuid: Xuid): void;
  /** Le joueur a ouvert l'écran des associés : l'annonce des parts est lue. */
  opened(xuid: Xuid): void;
}

export interface InvitationsDeps {
  readonly clock: Clock;
  readonly players: Pick<PlayerStore, "get">;
  /** Envoie un texte au joueur (le tchat). Une exception est journalisée, jamais relancée. */
  readonly say: (xuid: Xuid, text: string) => void;
  readonly log: Logger;
}

interface Entry extends Invitation {
  /** Le joueur en a été informé dans le tchat. */
  told: boolean;
}

function readInvitation(event: GameEvent): Invitation | undefined {
  const payload = event.payload;
  const companyId = payload["companyId"];
  const companyName = payload["companyName"];
  const invitedBy = payload["invitedBy"];
  const invitedByName = payload["invitedByName"];
  const expiresAt = payload["expiresAt"];
  if (typeof companyId !== "number" || !Number.isSafeInteger(companyId) || companyId <= 0) return undefined;
  if (typeof companyName !== "string" || companyName === "") return undefined;
  if (typeof invitedBy !== "string" || typeof invitedByName !== "string" || invitedByName === "") return undefined;
  if (typeof expiresAt !== "string") return undefined;
  const expiresAtMs = Date.parse(expiresAt);
  if (!Number.isFinite(expiresAtMs)) return undefined;
  return { companyId, companyName, invitedBy, invitedByName, expiresAtMs };
}

/** Le texte du tchat : la bulle d'Oooscar, puis qui invite dans quelle entreprise. */
export function invitationText(invitation: Invitation): string {
  const line = colored(COLOR.muted, ASSOCIES_TEXTES.invitation.texte(invitation.invitedByName, invitation.companyName));
  return `${renderBubble(ASSOCIES_TEXTES.bulles.invitationRecue)}\n${line}`;
}

export function createInvitations(deps: InvitationsDeps): Invitations {
  const { clock, players, log } = deps;
  /** Par invité, une entrée par entreprise qui invite. */
  const byPlayer = new Map<Xuid, Map<number, Entry>>();
  /** Les joueurs à qui l'on a dit « Les parts ont changé » et qui n'ont pas rouvert l'écran des associés depuis. */
  const sharesTold = new Set<Xuid>();

  function tell(xuid: Xuid, text: string): void {
    try {
      deps.say(xuid, text);
    } catch (error) {
      log.warn(`annonce d'associés à ${xuid} : ${describeError(error)}`);
    }
  }

  /** Les invitations du joueur, sans celles qui ont expiré. */
  function live(xuid: Xuid): Map<number, Entry> | undefined {
    const entries = byPlayer.get(xuid);
    if (entries === undefined) return undefined;
    const now = clock.now();
    for (const [companyId, entry] of entries) if (entry.expiresAtMs <= now) entries.delete(companyId);
    if (entries.size === 0) {
      byPlayer.delete(xuid);
      return undefined;
    }
    return entries;
  }

  return {
    list: (xuid) => [...(live(xuid)?.values() ?? [])],

    find: (xuid, companyId) => live(xuid)?.get(companyId),

    remove(xuid, companyId) {
      live(xuid)?.delete(companyId);
      if (byPlayer.get(xuid)?.size === 0) byPlayer.delete(xuid);
    },

    clear(xuid) {
      byPlayer.delete(xuid);
    },

    onInvitation(event) {
      const xuid = event.xuid;
      if (event.kind !== "INVITATION" || xuid === null) return;
      const invitation = readInvitation(event);
      if (invitation === undefined) {
        log.warn(`invitation ${event.id} illisible, ignorée`);
        return;
      }
      if (invitation.expiresAtMs <= clock.now()) return;
      const entries = live(xuid) ?? new Map<number, Entry>();
      // Une nouvelle invitation de la même entreprise remplace l'ancienne, et passe en dernier.
      entries.delete(invitation.companyId);
      const entry: Entry = { ...invitation, told: false };
      entries.set(invitation.companyId, entry);
      byPlayer.set(xuid, entries);
      if (players.get(xuid) !== undefined) {
        entry.told = true;
        tell(xuid, invitationText(invitation));
      }
    },

    onAnnonce(event) {
      const xuid = event.xuid;
      if (event.kind !== "ANNONCE" || xuid === null) return;
      if (event.payload["action"] !== SET_SHARES_ACTION) return;
      // Un joueur absent n'a personne à qui parler : la liste est relue à l'ouverture de l'écran.
      if (players.get(xuid) === undefined || sharesTold.has(xuid)) return;
      sharesTold.add(xuid);
      tell(xuid, renderBubble(ASSOCIES_TEXTES.bulles.partsChangees));
    },

    arrive(xuid) {
      for (const entry of live(xuid)?.values() ?? []) {
        if (entry.told) continue;
        entry.told = true;
        tell(xuid, invitationText(entry));
      }
    },

    opened(xuid) {
      sharesTold.delete(xuid);
    },
  };
}
