// Les annonces de la ville : le service publie un événement `ANNONCE` quand une zone est achetée, quand un bâtiment est
// posé ou amélioré (api/silooo-core.openapi.yaml, `buyZone`, `placeBuilding`, `upgradeBuilding`). Il l'adresse à celui qui
// agit, pas à l'entreprise entière.
//
// Ce fichier lit l'événement, relit l'état de l'entreprise (pour que la ville de la vue soit celle du service, même si
// l'achat a été fait depuis un autre écran ou une autre session), puis dit une bulle courte au joueur. Le relevé du monde
// (monde/synchro.ts) voit la nouvelle ville au tour suivant et pose ce qui a changé. Rien n'est calculé ici.

import type { CoreApi } from "../api";
import { describeFailure } from "../http";
import type { GameEvent, Xuid } from "../protocol";
import { describeError, type Logger } from "../runtime";
import type { ViewStore } from "./etat";
import { VILLE_TEXTES, renderBubble, type Bubble } from "./textes";

/** Les `payload.action` des ANNONCE de la ville. Les autres annonces (missions, parts) ont leurs propres modules. */
export const CITY_ACTIONS = ["buyZone", "placeBuilding", "upgradeBuilding"] as const;

export type CityAction = (typeof CITY_ACTIONS)[number];

/** Lit un événement `ANNONCE` de la ville. Rend undefined pour tout autre événement ou une autre action. */
export function readCityAnnouncement(event: GameEvent): CityAction | undefined {
  if (event.kind !== "ANNONCE") return undefined;
  const { action } = event.payload;
  return CITY_ACTIONS.find((candidate) => candidate === action);
}

/** La bulle d'Oooscar de chaque annonce. */
export function cityBubble(action: CityAction): Bubble {
  switch (action) {
    case "buyZone":
      return VILLE_TEXTES.bulles.zoneAchetee;
    case "placeBuilding":
      return VILLE_TEXTES.bulles.batimentPose;
    case "upgradeBuilding":
      return VILLE_TEXTES.bulles.batimentAmeliore;
  }
}

export interface CityEvents {
  /**
   * Un événement `ANNONCE` : si c'est une annonce de la ville pour un joueur présent, relit l'état de son entreprise, puis
   * Oooscar le dit. Ne lève pas.
   */
  onAnnonce(event: GameEvent): Promise<void>;
}

export interface CityEventsDeps {
  readonly api: Pick<CoreApi, "getCompany">;
  readonly views: ViewStore;
  /** Envoie un texte au joueur (le tchat). Une exception est journalisée, jamais relancée. */
  readonly say: (xuid: Xuid, text: string) => void;
  readonly log: Logger;
}

export function createCityEvents(deps: CityEventsDeps): CityEvents {
  const { api, views, say, log } = deps;

  return {
    async onAnnonce(event) {
      const action = readCityAnnouncement(event);
      const xuid = event.xuid;
      if (action === undefined || xuid === null) return;
      // Un joueur absent n'a personne à qui parler : à son retour, `join` rend l'état du service.
      const view = views.get(xuid);
      if (view === undefined) return;
      try {
        const result = await api.getCompany(view.state.companyId);
        if (result.kind === "ok") views.apply(xuid, { state: result.body });
        else log.warn(`annonce de la ville, état de ${xuid} : ${describeFailure(result)}`);
      } catch (error) {
        log.warn(`annonce de la ville, état de ${xuid} : ${describeError(error)}`);
      }
      try {
        say(xuid, renderBubble(cityBubble(action)));
      } catch (error) {
        log.warn(`annonce de la ville à ${xuid} : ${describeError(error)}`);
      }
    },
  };
}
