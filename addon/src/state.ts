// L'état gardé en mémoire par joueur, pour la phase 3 (écrans, compteur du Donar). Rien n'est
// persisté : au redémarrage du serveur la mémoire est vide, et chaque join la remplit de nouveau
// (silooo-core garde l'état du jeu, docs/bedrock.md, propriétés dynamiques non utilisées).

import type { JoinResponse, Xuid } from "./protocol";

export interface PlayerState {
  readonly xuid: Xuid;
  readonly gamertag: string;
  /** La dernière réponse de join : soldes, entreprise, revenu par heure. À afficher, jamais à recalculer. */
  readonly join: JoinResponse;
}

export interface PlayerStore {
  set(state: PlayerState): void;
  get(xuid: Xuid): PlayerState | undefined;
  remove(xuid: Xuid): boolean;
  xuids(): Xuid[];
  readonly size: number;
}

export function createPlayerStore(): PlayerStore {
  const players = new Map<Xuid, PlayerState>();
  return {
    set: (state) => {
      players.set(state.xuid, state);
    },
    get: (xuid) => players.get(xuid),
    remove: (xuid) => players.delete(xuid),
    xuids: () => [...players.keys()],
    get size() {
      return players.size;
    },
  };
}
